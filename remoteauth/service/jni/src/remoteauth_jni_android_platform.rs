// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use crate::jnames::{SEND_REQUEST_MNAME, SEND_REQUEST_MSIG};
use crate::unique_jvm;
use anyhow::anyhow;
use async_trait::async_trait;
use jni::errors::Error as JNIError;
use jni::objects::{GlobalRef, JMethodID, JObject, JValue};
use jni::signature::TypeSignature;
use jni::sys::{jbyteArray, jint, jlong, jvalue};
use jni::{JNIEnv, JavaVM};
use lazy_static::lazy_static;
use log::{debug, error, info};
use std::collections::HashMap;
use std::sync::{
    atomic::{AtomicI64, Ordering},
    Arc,
};
use tokio::{
    runtime::Runtime,
    sync::{mpsc, Mutex},
};

/// Macro capturing the name of the function calling this macro.
///
/// function_name()! -> &'static str
/// Returns the function name as 'static reference.
macro_rules! function_name {
    () => {{
        // Declares function f inside current function.
        fn f() {}
        fn type_name_of<T>(_: T) -> &'static str {
            std::any::type_name::<T>()
        }
        // type name of f is struct_or_crate_name::calling_function_name::f
        let name = type_name_of(f);
        // Find and cut the rest of the path:
        // Third to last character, up to the first semicolon: is calling_function_name
        match &name[..name.len() - 3].rfind(':') {
            Some(pos) => &name[pos + 1..name.len() - 3],
            None => &name[..name.len() - 3],
        }
    }};
}

lazy_static! {
    static ref HANDLE_MAPPING: Mutex<HashMap<i64, Arc<Mutex<JavaPlatform>>>> =
        Mutex::new(HashMap::new());
    static ref HANDLE_RN: AtomicI64 = AtomicI64::new(0);
}

fn generate_platform_handle() -> i64 {
    HANDLE_RN.fetch_add(1, Ordering::SeqCst)
}

async fn insert_platform_handle(handle: i64, item: Arc<Mutex<JavaPlatform>>) {
    if 0 == handle {
        // Init once
        logger::init(
            logger::Config::default()
                .with_tag_on_device("remoteauth")
                .with_min_level(log::Level::Trace)
                .with_filter("trace,jni=info"),
        );
    }
    HANDLE_MAPPING.lock().await.insert(handle, Arc::clone(&item));
}

#[async_trait]
pub trait Platform {
    /// Send a binary message to the remote with the given connection id and return the response.
    async fn send_request(&mut self, connection_id: i32, request: &[u8])
        -> anyhow::Result<Vec<u8>>;
}
//////////////////////////////////

pub struct JavaPlatform {
    platform_handle: i64,
    vm: &'static Arc<JavaVM>,
    platform_native_obj: GlobalRef,
    send_request_method_id: JMethodID,
    map_futures: Mutex<HashMap<i64, mpsc::Sender<Vec<u8>>>>,
    atomic_handle: AtomicI64,
}

impl JavaPlatform {
    // Method to create JavaPlatform
    pub async fn create<'a>(
        env: JNIEnv<'a>,
        java_platform_native: JObject<'a>,
    ) -> Result<Arc<Mutex<impl Platform>>, JNIError> {
        let jvm = env.get_java_vm()?;
        let _ = unique_jvm::set_once(jvm);
        let platform_handle = generate_platform_handle();
        let platform = Arc::new(Mutex::new(JavaPlatform::new(
            platform_handle,
            unique_jvm::get_static_ref().ok_or(JNIError::InvalidCtorReturn)?,
            java_platform_native,
        )?));
        insert_platform_handle(platform_handle, Arc::clone(&platform)).await;
        Ok(Arc::clone(&platform))
    }

    fn new(
        platform_handle: i64,
        vm: &'static Arc<JavaVM>,
        java_platform_native: JObject,
    ) -> Result<JavaPlatform, JNIError> {
        vm.attach_current_thread().and_then(|env| {
            let platform_class = env.get_object_class(java_platform_native)?;
            let platform_native_obj = env.new_global_ref(java_platform_native)?;
            let send_request_method: JMethodID =
                env.get_method_id(platform_class, SEND_REQUEST_MNAME, SEND_REQUEST_MSIG)?;

            Ok(Self {
                platform_handle,
                vm,
                platform_native_obj,
                send_request_method_id: send_request_method,
                map_futures: Mutex::new(HashMap::new()),
                atomic_handle: AtomicI64::new(0),
            })
        })
    }
}

#[async_trait]
impl Platform for JavaPlatform {
    async fn send_request(
        &mut self,
        connection_id: i32,
        request: &[u8],
    ) -> anyhow::Result<Vec<u8>> {
        let type_signature = TypeSignature::from_str(SEND_REQUEST_MSIG)
            .map_err(|e| anyhow!("JNI: Invalid type signature: {:?}", e))?;

        let (tx, mut rx) = mpsc::channel(1);
        let response_handle = self.atomic_handle.fetch_add(1, Ordering::SeqCst);
        self.map_futures.lock().await.insert(response_handle, tx);
        self.vm
            .attach_current_thread()
            .and_then(|env| {
                let request_jbytearray = env.byte_array_from_slice(request)?;
                // Safety: request_jbytearray is safely instantiated above.
                let request_jobject = unsafe { JObject::from_raw(request_jbytearray) };

                let _ = env.call_method_unchecked(
                    self.platform_native_obj.as_obj(),
                    self.send_request_method_id,
                    type_signature.ret,
                    &[
                        jvalue::from(JValue::Int(connection_id)),
                        jvalue::from(JValue::Object(request_jobject)),
                        jvalue::from(JValue::Long(response_handle)),
                        jvalue::from(JValue::Long(self.platform_handle)),
                    ],
                );
                Ok(info!(
                    "{} successfully sent-message, waiting for response {}:{}",
                    function_name!(),
                    self.platform_handle,
                    response_handle
                ))
            })
            .map_err(|e| anyhow!("JNI: Failed to attach current thread: {:?}", e))?;

        rx.recv().await.ok_or(anyhow!("{} failed in awaiting for a result", function_name!()))
    }
}

impl JavaPlatform {
    async fn on_send_request_success(&mut self, response: &[u8], response_handle: i64) {
        info!(
            "{} completed successfully {}:{}",
            function_name!(),
            self.platform_handle,
            response_handle
        );
        if let Some(tx) = self.map_futures.lock().await.remove(&response_handle) {
            let _ = tx.send(response.to_vec()).await;
        } else {
            error!(
                "Failed to find TX for {} and {}:{}",
                function_name!(),
                self.platform_handle,
                response_handle
            );
        }
    }

    async fn on_send_request_error(&self, error_code: i32, response_handle: i64) {
        error!(
            "{} completed with error {} {}:{}",
            function_name!(),
            error_code,
            self.platform_handle,
            response_handle
        );
        if let Some(tx) = self.map_futures.lock().await.remove(&response_handle) {
            // `rx.recv()` ends with `Err`
            drop(tx);
        } else {
            error!(
                "Failed to find TX for {} and {}:{}",
                function_name!(),
                self.platform_handle,
                response_handle
            );
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_android_server_remoteauth_jni_NativeRemoteAuthJavaPlatform_native_on_send_request_success(
    env: JNIEnv,
    _: JObject,
    app_response: jbyteArray,
    platform_handle: jlong,
    response_handle: jlong,
) {
    debug!("{}: enter", function_name!());
    Runtime::new().unwrap().block_on(native_on_send_request_success(
        env,
        app_response,
        platform_handle,
        response_handle,
    ));
}

async fn native_on_send_request_success(
    env: JNIEnv<'_>,
    app_response: jbyteArray,
    platform_handle: jlong,
    response_handle: jlong,
) {
    if let Some(platform) = HANDLE_MAPPING.lock().await.get(&platform_handle) {
        let response =
            env.convert_byte_array(app_response).map_err(|_| JNIError::InvalidCtorReturn).unwrap();
        let mut platform = (*platform).lock().await;
        platform.on_send_request_success(&response, response_handle).await;
    } else {
        let _ = env.throw_new(
            "com/android/server/remoteauth/jni/BadHandleException",
            format!("Failed to find Platform with ID {} in {}", platform_handle, function_name!()),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_com_android_server_remoteauth_jni_NativeRemoteAuthJavaPlatform_native_on_send_request_error(
    env: JNIEnv,
    _: JObject,
    error_code: jint,
    platform_handle: jlong,
    response_handle: jlong,
) {
    debug!("{}: enter", function_name!());
    Runtime::new().unwrap().block_on(native_on_send_request_error(
        env,
        error_code,
        platform_handle,
        response_handle,
    ));
}

async fn native_on_send_request_error(
    env: JNIEnv<'_>,
    error_code: jint,
    platform_handle: jlong,
    response_handle: jlong,
) {
    if let Some(platform) = HANDLE_MAPPING.lock().await.get(&platform_handle) {
        let platform = (*platform).lock().await;
        platform.on_send_request_error(error_code, response_handle).await;
    } else {
        let _ = env.throw_new(
            "com/android/server/remoteauth/jni/BadHandleException",
            format!("Failed to find Platform with ID {} in {}", platform_handle, function_name!()),
        );
    }
}

#[cfg(test)]
mod tests {
    //use super::*;

    //use tokio::runtime::Builder;

    /// Checks validity of the function_name! macro.
    #[test]
    fn test_function_name() {
        assert_eq!(function_name!(), "test_function_name");
    }
}
