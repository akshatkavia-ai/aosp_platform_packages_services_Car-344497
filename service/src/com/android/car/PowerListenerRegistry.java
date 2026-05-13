/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.car;

import android.car.hardware.power.ICarPowerStateListener;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates power listener responsibilities extracted from {@link CarPowerManagementService}.
 *
 * <p>This helper centralizes:
 * <ul>
 *   <li>Service-internal listeners ({@link CarPowerManagementService.PowerServiceEventListener})</li>
 *   <li>App/binder listeners ({@link ICarPowerStateListener}) + shutdown/suspend token tracking</li>
 * </ul>
 *
 * <p>Behavior/ordering is preserved:
 * <ul>
 *   <li>Service listeners are invoked in registration order.</li>
 *   <li>App listener token semantics and binder-death cleanup remain in
 *       {@link CarPowerListenerTokenRegistry}.</li>
 * </ul>
 *
 * <p>Package-private by design.
 */
final class PowerListenerRegistry {

    private final CopyOnWriteArrayList<CarPowerManagementService.PowerServiceEventListener>
            mServiceListeners = new CopyOnWriteArrayList<>();

    private final CarPowerListenerTokenRegistry mAppListenerTokenRegistry;

    PowerListenerRegistry(
            CarPowerListenerTokenRegistry.AllAppsFinishedCallback allAppsFinishedCallback,
            CarPowerListenerTokenRegistry.CurrentHalPowerStateSupplier currentHalPowerStateSupplier) {
        mAppListenerTokenRegistry = new CarPowerListenerTokenRegistry(
                allAppsFinishedCallback,
                currentHalPowerStateSupplier);
    }

    void release() {
        mServiceListeners.clear();
        mAppListenerTokenRegistry.release();
    }

    // ---- Service listeners (used by the state machine) ----

    void addServiceListener(CarPowerManagementService.PowerServiceEventListener listener) {
        mServiceListeners.add(listener);
    }

    void clearServiceListeners() {
        mServiceListeners.clear();
    }

    void dispatchShutdown() {
        for (CarPowerManagementService.PowerServiceEventListener listener : mServiceListeners) {
            listener.onShutdown();
        }
    }

    void dispatchSleepEntry() {
        for (CarPowerManagementService.PowerServiceEventListener listener : mServiceListeners) {
            listener.onSleepEntry();
        }
    }

    void dispatchSleepExit() {
        for (CarPowerManagementService.PowerServiceEventListener listener : mServiceListeners) {
            listener.onSleepExit();
        }
    }

    // ---- App/binder listeners (ICarPower) ----

    void registerAppListener(ICarPowerStateListener listener) {
        mAppListenerTokenRegistry.registerListener(listener);
    }

    void unregisterAppListener(ICarPowerStateListener listener) {
        mAppListenerTokenRegistry.unregisterListener(listener);
    }

    void finished(ICarPowerStateListener listener, int token, int currentHalPowerState) {
        mAppListenerTokenRegistry.finished(listener, token, currentHalPowerState);
    }

    void finishOutstandingTokenIfAny(ICarPowerStateListener listener, int currentHalPowerState) {
        mAppListenerTokenRegistry.finishOutstandingTokenIfAny(listener, currentHalPowerState);
    }

    long sendPowerManagerEvent(boolean shuttingDown) {
        return mAppListenerTokenRegistry.sendPowerManagerEvent(shuttingDown);
    }

    long getAppExtendTimeMsIfTokensOutstanding() {
        return mAppListenerTokenRegistry.getAppExtendTimeMsIfTokensOutstanding();
    }

    void sendSuspendExitToApps() {
        mAppListenerTokenRegistry.sendSuspendExitToApps();
    }
}
