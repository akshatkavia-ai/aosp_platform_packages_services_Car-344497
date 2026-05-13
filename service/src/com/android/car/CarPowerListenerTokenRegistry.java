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

import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.ICarPowerStateListener;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.hal.PowerHalService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for {@link ICarPowerStateListener} instances and their suspend/shutdown tokens.
 *
 * <p>This preserves the original CarPowerManagementService behavior:
 * <ul>
 *   <li>Tokens are generated per broadcast during SHUTDOWN_PREPARE preprocessing.</li>
 *   <li>Binder death triggers unregister and finishes outstanding tokens.</li>
 *   <li>App extend time is added when there are pending tokens.</li>
 * </ul>
 *
 * <p>Package-private and intentionally behavior-preserving.
 */
final class CarPowerListenerTokenRegistry {

    /**
     * Callback invoked when the registry detects that all app tokens are finished while
     * the power HAL state is {@link PowerHalService#STATE_SHUTDOWN_PREPARE}.
     */
    interface AllAppsFinishedCallback {
        void onAllAppsFinished();
    }

    /**
     * Supplies the current HAL power state for state-aware completion gating.
     *
     * <p>This is used to ensure binder-death cleanup does not trigger completion callbacks
     * when the system is not currently in {@link PowerHalService#STATE_SHUTDOWN_PREPARE}.
     */
    interface CurrentHalPowerStateSupplier {
        int getCurrentHalPowerState();
    }

    private static final int APP_EXTEND_MAX_MS = 10000;

    private final PowerManagerCallbackList mPowerManagerListeners = new PowerManagerCallbackList();
    private final Map<IBinder, Integer> mPowerManagerListenerTokens = new ConcurrentHashMap<>();

    private final AllAppsFinishedCallback mAllAppsFinishedCallback;
    private final CurrentHalPowerStateSupplier mCurrentHalPowerStateSupplier;

    private int mTokenValue = 1;

    CarPowerListenerTokenRegistry(AllAppsFinishedCallback allAppsFinishedCallback,
            CurrentHalPowerStateSupplier currentHalPowerStateSupplier) {
        mAllAppsFinishedCallback = allAppsFinishedCallback;
        mCurrentHalPowerStateSupplier = currentHalPowerStateSupplier;
    }

    void release() {
        mPowerManagerListeners.kill();
        mPowerManagerListenerTokens.clear();
    }

    void registerListener(ICarPowerStateListener listener) {
        mPowerManagerListeners.register(listener);
    }

    void unregisterListener(ICarPowerStateListener listener) {
        doUnregisterListener(listener);
    }

    /**
     * Sends power manager "enter" event and returns the extra processing time needed for apps.
     * Mirrors the original sendPowerManagerEvent() behavior.
     */
    long sendPowerManagerEvent(boolean shuttingDown) {
        long processingTimeMs = 0;
        int newState = shuttingDown ? CarPowerStateListener.SHUTDOWN_ENTER
                : CarPowerStateListener.SUSPEND_ENTER;

        synchronized (mPowerManagerListenerTokens) {
            mPowerManagerListenerTokens.clear();
            int i = mPowerManagerListeners.beginBroadcast();
            while (i-- > 0) {
                try {
                    ICarPowerStateListener listener = mPowerManagerListeners.getBroadcastItem(i);
                    listener.onStateChanged(newState, mTokenValue);
                    mPowerManagerListenerTokens.put(listener.asBinder(), mTokenValue);
                    mTokenValue++;
                } catch (RemoteException e) {
                    // It's likely the connection snapped. Let binder death handle the situation.
                    Log.e(CarLog.TAG_POWER, "onStateChanged calling failed: " + e);
                }
            }
            mPowerManagerListeners.finishBroadcast();
            if (!mPowerManagerListenerTokens.isEmpty()) {
                Log.i(CarLog.TAG_POWER, "mPowerMangerListenerTokens not empty, add APP_EXTEND_MAX_MS");
                processingTimeMs += APP_EXTEND_MAX_MS;
            }
        }
        return processingTimeMs;
    }

    /**
     * Extra time to add when there are outstanding tokens, matching legacy behavior.
     */
    long getAppExtendTimeMsIfTokensOutstanding() {
        synchronized (mPowerManagerListenerTokens) {
            return mPowerManagerListenerTokens.isEmpty() ? 0 : APP_EXTEND_MAX_MS;
        }
    }

    /**
     * Sends "exit" event after deep sleep. Matches original behavior: no token bookkeeping.
     */
    void sendSuspendExitToApps() {
        int i = mPowerManagerListeners.beginBroadcast();
        while (i-- > 0) {
            try {
                ICarPowerStateListener listener = mPowerManagerListeners.getBroadcastItem(i);
                listener.onStateChanged(CarPowerStateListener.SUSPEND_EXIT, 0);
            } catch (RemoteException e) {
                // It's likely the connection snapped. Let binder death handle the situation.
                Log.e(CarLog.TAG_POWER, "onStateChanged calling failed: " + e);
            }
        }
        mPowerManagerListeners.finishBroadcast();
    }

    /**
     * Called by binder API finished(). This matches the original token removal semantics.
     */
    void finished(ICarPowerStateListener listener, int token, int currentHalPowerState) {
        synchronized (mPowerManagerListenerTokens) {
            finishedLocked(listener.asBinder(), token, currentHalPowerState);
        }
    }

    /**
     * Called when a listener unregisters (or dies) and might have outstanding token.
     */
    void finishOutstandingTokenIfAny(ICarPowerStateListener listener, int currentHalPowerState) {
        IBinder binder = listener.asBinder();
        synchronized (mPowerManagerListenerTokens) {
            Integer token = mPowerManagerListenerTokens.get(binder);
            if (token != null) {
                finishedLocked(binder, token.intValue(), currentHalPowerState);
            }
        }
    }

    private void finishedLocked(IBinder binder, int token, int currentHalPowerState) {
        Integer currentToken = mPowerManagerListenerTokens.get(binder);
        if (currentToken == null) {
            return;
        }
        if (currentToken.intValue() == token) {
            mPowerManagerListenerTokens.remove(binder);
            if (mPowerManagerListenerTokens.isEmpty()
                    && currentHalPowerState == PowerHalService.STATE_SHUTDOWN_PREPARE) {
                // All apps are ready to shutdown/suspend.
                Log.i(CarLog.TAG_POWER, "Apps are finished, notify completion");
                mAllAppsFinishedCallback.onAllAppsFinished();
            }
        }
    }

    private void doUnregisterListener(ICarPowerStateListener listener) {
        boolean found = mPowerManagerListeners.unregister(listener);
        if (!found) {
            return;
        }
        // Outstanding token (if any) is handled by caller (service) because it needs the current
        // HAL state to decide whether to trigger processing completion.
    }

    private final class PowerManagerCallbackList extends RemoteCallbackList<ICarPowerStateListener> {
        /**
         * Old version of {@link #onCallbackDied(E, Object)} that does not provide a cookie.
         */
        @Override
        public void onCallbackDied(ICarPowerStateListener listener) {
            Log.i(CarLog.TAG_POWER, "binderDied " + listener.asBinder());
            // Behavior-preserving: unregister to stop further callbacks.
            doUnregisterListener(listener);

            // Follow-up fix: ensure binder death does not leave an outstanding token behind,
            // which would otherwise delay/prevent shutdown/suspend completion.
            IBinder binder = listener.asBinder();
            synchronized (mPowerManagerListenerTokens) {
                Integer removed = mPowerManagerListenerTokens.remove(binder);
                if (removed != null && mPowerManagerListenerTokens.isEmpty()) {
                    // State-aware gating: only signal completion if we are still in
                    // SHUTDOWN_PREPARE. This avoids relying exclusively on downstream gating.
                    if (mCurrentHalPowerStateSupplier.getCurrentHalPowerState()
                            == PowerHalService.STATE_SHUTDOWN_PREPARE) {
                        mAllAppsFinishedCallback.onAllAppsFinished();
                    }
                }
            }
        }
    }
}
