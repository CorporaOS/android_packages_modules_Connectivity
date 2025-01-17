/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cts.netpolicy.hostside;

import android.net.NetworkInfo;

import com.android.cts.netpolicy.hostside.NetworkCheckResult;

interface INetworkStateObserver {
    void onNetworkStateChecked(int resultCode, in NetworkCheckResult networkCheckResult);

    const int RESULT_SUCCESS_NETWORK_STATE_CHECKED = 0;
    const int RESULT_ERROR_UNEXPECTED_PROC_STATE = 1;
    const int RESULT_ERROR_UNEXPECTED_CAPABILITIES = 2;
    const int RESULT_ERROR_OTHER = 3;
}