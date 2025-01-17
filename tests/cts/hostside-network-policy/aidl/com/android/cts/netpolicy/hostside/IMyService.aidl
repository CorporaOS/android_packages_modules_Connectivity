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

import android.app.job.JobInfo;

import com.android.cts.netpolicy.hostside.INetworkCallback;
import com.android.cts.netpolicy.hostside.NetworkCheckResult;

interface IMyService {
    void registerBroadcastReceiver();
    int getCounters(String receiverName, String action);
    NetworkCheckResult checkNetworkStatus(String customUrl);
    String getRestrictBackgroundStatus();
    void sendNotification(int notificationId, String notificationType);
    void registerNetworkCallback(in NetworkRequest request, in INetworkCallback cb);
    void unregisterNetworkCallback();
    int scheduleJob(in JobInfo jobInfo);
}
