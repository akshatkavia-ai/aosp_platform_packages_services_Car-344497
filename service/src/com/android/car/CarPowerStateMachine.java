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

import android.util.Log;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.internal.annotations.GuardedBy;

import java.util.LinkedList;

/**
 * Power state machine extracted from CarPowerManagementService.
 *
 * <p>This keeps the original behavior and ordering:
 * <ul>
 *   <li>Power events are queued and processed on the handler thread.</li>
 *   <li>SHUTDOWN_PREPARE triggers preprocessing, then shutdown vs deep sleep.</li>
 *   <li>Deep sleep ordering: onSleepEntry -> VHAL sleep entry -> deep sleep -> VHAL exit
 *       -> onSleepExit -> app callbacks.</li>
 * </ul>
 */
final class CarPowerStateMachine {

    /**
     * Handler-thread actions required by the state machine.
     *
     * <p>This explicitly extends {@link CarPowerPreShutdownProcessor.HandlerActions} so the
     * state-machine can pass the same handler actions instance into the pre-shutdown processor
     * without relying on fragile casts.
     */
    interface HandlerActions extends CarPowerPreShutdownProcessor.HandlerActions {
        void handlePowerStateChange();
    }

    interface ShutdownOnNextSuspendSupplier {
        boolean getShutdownOnNextSuspend();
    }

    private final PowerHalService mHal;
    private final CarPowerSystemActions mSystemActions;
    private final PowerListenerRegistry mListenerRegistry;
    private final CarPowerPreShutdownProcessor mPreShutdownProcessor;
    private final ShutdownOnNextSuspendSupplier mShutdownOnNextSuspendSupplier;

    @GuardedBy("this")
    private PowerState mCurrentState;
    @GuardedBy("this")
    private final LinkedList<PowerState> mPendingPowerStates = new LinkedList<>();

    CarPowerStateMachine(
            PowerHalService hal,
            CarPowerSystemActions systemActions,
            PowerListenerRegistry listenerRegistry,
            CarPowerPreShutdownProcessor preShutdownProcessor,
            ShutdownOnNextSuspendSupplier shutdownOnNextSuspendSupplier) {
        mHal = hal;
        mSystemActions = systemActions;
        mListenerRegistry = listenerRegistry;
        mPreShutdownProcessor = preShutdownProcessor;
        mShutdownOnNextSuspendSupplier = shutdownOnNextSuspendSupplier;
    }

    void release() {
        synchronized (this) {
            mCurrentState = null;
            mPendingPowerStates.clear();
        }
    }

    PowerState getCurrentState() {
        synchronized (this) {
            return mCurrentState;
        }
    }

    int getCurrentStateInt() {
        synchronized (this) {
            return (mCurrentState == null) ? -1 : mCurrentState.mState;
        }
    }

    void onApPowerStateChange(PowerState state, HandlerActions handlerActions) {
        synchronized (this) {
            mPendingPowerStates.addFirst(state);
        }
        handlerActions.handlePowerStateChange();
    }

    void doHandlePowerStateChange(HandlerActions handlerActions) {
        PowerState state;
        synchronized (this) {
            state = mPendingPowerStates.peekFirst();
            mPendingPowerStates.clear();
            if (state == null) {
                return;
            }
            if (!needPowerStateChangeLocked(state)) {
                return;
            }
        }
        handlerActions.cancelProcessingComplete();

        Log.i(CarLog.TAG_POWER, "Power state change:" + state);
        switch (state.mState) {
            case PowerHalService.STATE_ON_DISP_OFF:
                handleDisplayOff(state);
                mPreShutdownProcessor.notifyPowerOn(false);
                break;
            case PowerHalService.STATE_ON_FULL:
                handleFullOn(state);
                mPreShutdownProcessor.notifyPowerOn(true);
                break;
            case PowerHalService.STATE_SHUTDOWN_PREPARE:
                handleShutdownPrepare(state, handlerActions);
                break;
        }
    }

    private void handleDisplayOff(PowerState newState) {
        setCurrentState(newState);
        mSystemActions.setDisplayState(false);
    }

    private void handleFullOn(PowerState newState) {
        setCurrentState(newState);
        mSystemActions.setDisplayState(true);
    }

    private void handleShutdownPrepare(PowerState newState, HandlerActions handlerActions) {
        setCurrentState(newState);
        mSystemActions.setDisplayState(false);
        boolean shouldShutdown = true;

        if (mHal.isDeepSleepAllowed()
                && mSystemActions.isSystemSupportingDeepSleep()
                && newState.canEnterDeepSleep()
                && !mShutdownOnNextSuspendSupplier.getShutdownOnNextSuspend()) {
            Log.i(CarLog.TAG_POWER, "starting sleep");
            shouldShutdown = false;
            mPreShutdownProcessor.doHandlePreprocessing(shouldShutdown, handlerActions);
            return;
        } else if (newState.canPostponeShutdown()) {
            Log.i(CarLog.TAG_POWER, "starting shutdown with processing");
            mPreShutdownProcessor.doHandlePreprocessing(shouldShutdown, handlerActions);
        } else {
            Log.i(CarLog.TAG_POWER, "starting shutdown immediately");
            doHandleShutdown();
        }
    }

    void doHandleProcessingComplete(boolean shutdownWhenCompleted, HandlerActions handlerActions) {
        if (!mPreShutdownProcessor.prepareForProcessingComplete(shutdownWhenCompleted)) {
            return;
        }
        if (shutdownWhenCompleted) {
            doHandleShutdown();
        } else {
            doHandleDeepSleep(handlerActions);
        }
    }

    private void doHandleDeepSleep(HandlerActions handlerActions) {
        // Keep holding partial wakelock to prevent entering sleep before enterDeepSleep call.
        // enterDeepSleep should force sleep entry even if wake lock is kept.
        mSystemActions.switchToPartialWakeLock();
        handlerActions.cancelProcessingComplete();

        mListenerRegistry.dispatchSleepEntry();

        int wakeupTimeSec = mPreShutdownProcessor.getWakeupTimeSec();
        mHal.sendSleepEntry();
        mPreShutdownProcessor.noteSleepEntryNow();

        if (mSystemActions.enterDeepSleep(wakeupTimeSec) == false) {
            // System did not suspend. Need to shutdown
            // TODO: Shutdown gracefully
            Log.e(CarLog.TAG_POWER, "Sleep did not succeed. Need to shutdown");
        }

        mHal.sendSleepExit();
        mListenerRegistry.dispatchSleepExit();

        // Notify applications
        mListenerRegistry.sendSuspendExitToApps();

        if (mSystemActions.isWakeupCausedByTimer()) {
            mPreShutdownProcessor.doHandlePreprocessing(false /*shuttingDown*/, handlerActions);
        } else {
            PowerState currentState = mHal.getCurrentPowerState();
            if (currentState != null && needPowerStateChange(currentState)) {
                onApPowerStateChange(currentState, handlerActions);
            } else { // Power controller woke-up but no power state change. Just shutdown.
                Log.w(CarLog.TAG_POWER, "external sleep wake up, but no power state change:"
                        + currentState);
                doHandleShutdown();
            }
        }
    }

    private void doHandleShutdown() {
        // Now shutdown
        mListenerRegistry.dispatchShutdown();
        int wakeupTimeSec = 0;
        if (mHal.isTimedWakeupAllowed()) {
            wakeupTimeSec = mPreShutdownProcessor.getWakeupTimeSec();
        }
        mHal.sendShutdownStart(wakeupTimeSec);
        mSystemActions.shutdown();
    }

    private boolean needPowerStateChange(PowerState newState) {
        synchronized (this) {
            return needPowerStateChangeLocked(newState);
        }
    }

    @GuardedBy("this")
    private boolean needPowerStateChangeLocked(PowerState newState) {
        return !(mCurrentState != null && mCurrentState.equals(newState));
    }

    private void setCurrentState(PowerState state) {
        synchronized (this) {
            mCurrentState = state;
        }
    }
}
