/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;
import static com.android.server.connectivity.mdns.util.MdnsUtils.equalsIgnoreDnsCase;
import static com.android.server.connectivity.mdns.util.MdnsUtils.toDnsLowerCase;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * The {@link MdnsServiceCache} manages the service which discovers from each socket and cache these
 * services to reduce duplicated queries.
 *
 * <p>This class is not thread safe, it is intended to be used only from the looper thread.
 *  However, the constructor is an exception, as it is called on another thread;
 *  therefore for thread safety all members of this class MUST either be final or initialized
 *  to their default value (0, false or null).
 */
public class MdnsServiceCache {
    private static class CacheKey {
        @NonNull final String mLowercaseServiceType;
        @NonNull final SocketKey mSocketKey;

        CacheKey(@NonNull String serviceType, @NonNull SocketKey socketKey) {
            mLowercaseServiceType = toDnsLowerCase(serviceType);
            mSocketKey = socketKey;
        }

        @Override public int hashCode() {
            return Objects.hash(mLowercaseServiceType, mSocketKey);
        }

        @Override public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            return Objects.equals(mLowercaseServiceType, ((CacheKey) other).mLowercaseServiceType)
                    && Objects.equals(mSocketKey, ((CacheKey) other).mSocketKey);
        }
    }
    /**
     * A map of cached services. Key is composed of service name, type and socket. Value is the
     * service which use the service type to discover from each socket.
     */
    @NonNull
    private final ArrayMap<CacheKey, List<MdnsResponse>> mCachedServices = new ArrayMap<>();
    @NonNull
    private final Handler mHandler;

    public MdnsServiceCache(@NonNull Looper looper) {
        mHandler = new Handler(looper);
    }

    /**
     * Get the cache services which are queried from given service type and socket.
     *
     * @param serviceType the target service type.
     * @param socketKey the target socket
     * @return the set of services which matches the given service type.
     */
    @NonNull
    public List<MdnsResponse> getCachedServices(@NonNull String serviceType,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(mHandler);
        final CacheKey key = new CacheKey(serviceType, socketKey);
        return mCachedServices.containsKey(key)
                ? Collections.unmodifiableList(new ArrayList<>(mCachedServices.get(key)))
                : Collections.emptyList();
    }

    /**
     * Find a matched response for given service name
     *
     * @param responses the responses to be searched.
     * @param serviceName the target service name
     * @return the response which matches the given service name or null if not found.
     */
    public static MdnsResponse findMatchedResponse(@NonNull List<MdnsResponse> responses,
            @NonNull String serviceName) {
        for (MdnsResponse response : responses) {
            if (equalsIgnoreDnsCase(serviceName, response.getServiceInstanceName())) {
                return response;
            }
        }
        return null;
    }

    /**
     * Get the cache service.
     *
     * @param serviceName the target service name.
     * @param serviceType the target service type.
     * @param socketKey the target socket
     * @return the service which matches given conditions.
     */
    @Nullable
    public MdnsResponse getCachedService(@NonNull String serviceName,
            @NonNull String serviceType, @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(mHandler);
        final List<MdnsResponse> responses =
                mCachedServices.get(new CacheKey(serviceType, socketKey));
        if (responses == null) {
            return null;
        }
        final MdnsResponse response = findMatchedResponse(responses, serviceName);
        return response != null ? new MdnsResponse(response) : null;
    }

    /**
     * Add or update a service.
     *
     * @param serviceType the service type.
     * @param socketKey the target socket
     * @param response the response of the discovered service.
     */
    public void addOrUpdateService(@NonNull String serviceType, @NonNull SocketKey socketKey,
            @NonNull MdnsResponse response) {
        ensureRunningOnHandlerThread(mHandler);
        final List<MdnsResponse> responses = mCachedServices.computeIfAbsent(
                new CacheKey(serviceType, socketKey), key -> new ArrayList<>());
        // Remove existing service if present.
        final MdnsResponse existing =
                findMatchedResponse(responses, response.getServiceInstanceName());
        responses.remove(existing);
        responses.add(response);
    }

    /**
     * Remove a service which matches the given service name, type and socket.
     *
     * @param serviceName the target service name.
     * @param serviceType the target service type.
     * @param socketKey the target socket.
     */
    @Nullable
    public MdnsResponse removeService(@NonNull String serviceName, @NonNull String serviceType,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(mHandler);
        final List<MdnsResponse> responses =
                mCachedServices.get(new CacheKey(serviceType, socketKey));
        if (responses == null) {
            return null;
        }
        final Iterator<MdnsResponse> iterator = responses.iterator();
        while (iterator.hasNext()) {
            final MdnsResponse response = iterator.next();
            if (equalsIgnoreDnsCase(serviceName, response.getServiceInstanceName())) {
                iterator.remove();
                return response;
            }
        }
        return null;
    }

    // TODO: check ttl expiration for each service and notify to the clients.
}
