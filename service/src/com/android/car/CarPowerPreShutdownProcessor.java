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

import android.os.SystemClock;
import android.util.Log;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns preprocessing behavior for shutdown/suspend entry:
 * <ul>
 *   <li>Processing handler wrapper state (time + done)</li>
 *   <li>Polling timer behavior (postpone / expiration)</li>
 *   <li>Early completion via notifyPowerEventProcessingCompletion()</li>
 * </ul>
 *
 * <p>Package-private and behavior-preserving.
 */
final class CarPowerPreShutdownProcessor {

    interface HandlerActions {
        void handleProcessingComplete(boolean shutdownWhenCompleted);

        void cancelProcessingComplete();

        void handlePowerOn();
    }

    interface CurrentPowerStateSupplier {
        PowerState getCurrentPowerState();
    }

    interface ShutdownOnNextSuspendSupplier {
        boolean getShutdownOnNextSuspend();
    }

    private static final int SHUTDOWN_POLLING_INTERVAL_MS = 2000;
    private static final int SHUTDOWN_EXTEND_MAX_MS = 5000;

    private final PowerHalService mHal;
    private final PowerListenerRegistry mListenerRegistry;
    private final CurrentPowerStateSupplier mCurrentPowerStateSupplier;
    private final ShutdownOnNextSuspendSupplier mShutdownOnNextSuspendSupplier;

    private final CopyOnWriteArrayList<PowerEventProcessingHandlerWrapper> mProcessingHandlers =
            new CopyOnWriteArrayList<>();

    @GuardedBy("this")
    private Timer mTimer;
    @GuardedBy("this")
    private long mProcessingStartTime;
    @GuardedBy("this")
    private long mLastSleepEntryTime;

    CarPowerPreShutdownProcessor(
            PowerHalService hal,
            PowerListenerRegistry listenerRegistry,
            CurrentPowerStateSupplier currentPowerStateSupplier,
            ShutdownOnNextSuspendSupplier shutdownOnNextSuspendSupplier) {
        mHal = hal;
        mListenerRegistry = listenerRegistry;
        mCurrentPowerStateSupplier = currentPowerStateSupplier;
        mShutdownOnNextSuspendSupplier = shutdownOnNextSuspendSupplier;
    }

    void release() {
        synchronized (this) {
            releaseTimerLocked();
        }
        mProcessingHandlers.clear();
    }

    void addProcessingHandler(CarPowerManagementService.PowerEventProcessingHandler handler,
            HandlerActions handlerActions) {
        mProcessingHandlers.add(new PowerEventProcessingHandlerWrapper(handler));
        // Behavior-preserving: request onPowerOn to be delivered (wrapper gatekeeps duplicates).
        handlerActions.handlePowerOn();
    }

    @VisibleForTesting
    void notifyPowerOn(boolean displayOn) {
        for (PowerEventProcessingHandlerWrapper wrapper : mProcessingHandlers) {
            wrapper.callOnPowerOn(displayOn);
        }
    }

    @VisibleForTesting
    long notifyPrepareShutdown(boolean shuttingDown) {
        long processingTimeMs = 0;
        for (PowerEventProcessingHandlerWrapper wrapper : mProcessingHandlers) {
            long handlerProcessingTime = wrapper.handler.onPrepareShutdown(shuttingDown);
            if (handlerProcessingTime > processingTimeMs) {
                processingTimeMs = handlerProcessingTime;
            }
        }
        // Add time for powerManager events
        processingTimeMs += mListenerRegistry.sendPowerManagerEvent(shuttingDown);
        return processingTimeMs;
    }

    void doHandlePreprocessing(boolean shuttingDown, HandlerActions handlerActions) {
        long processingTimeMs = 0;
        for (PowerEventProcessingHandlerWrapper wrapper : mProcessingHandlers) {
            long handlerProcessingTime = wrapper.handler.onPrepareShutdown(shuttingDown);
            if (handlerProcessingTime > 0) {
                wrapper.setProcessingTimeAndResetProcessingDone(handlerProcessingTime);
            }
            if (handlerProcessingTime > processingTimeMs) {
                processingTimeMs = handlerProcessingTime;
            }
        }
        // Add time for powerManager events
        processingTimeMs += mListenerRegistry.sendPowerManagerEvent(shuttingDown);

        if (processingTimeMs > 0) {
            // VisibleForTesting / dummy construction can pass a null HAL. In that case, we cannot
            // send postpone messages; complete immediately to avoid NPE while keeping real
            // (non-null HAL) behavior unchanged.
            if (mHal == null) {
                Log.w(CarLog.TAG_POWER, "HAL is null; skipping shutdown postpone and completing "
                        + "processing immediately (test-only path)");
                handlerActions.handleProcessingComplete(shuttingDown);
                return;
            }
            int pollingCount = (int) (processingTimeMs / SHUTDOWN_POLLING_INTERVAL_MS) + 1;
            Log.i(CarLog.TAG_POWER, "processing before shutdown expected for :" + processingTimeMs
                    + " ms, adding polling:" + pollingCount);
            synchronized (this) {
                mProcessingStartTime = SystemClock.elapsedRealtime();
                releaseTimerLocked();
                mTimer = new Timer();
                mTimer.scheduleAtFixedRate(new ShutdownProcessingTimerTask(shuttingDown,
                        pollingCount, handlerActions),
                        0 /*delay*/,
                        SHUTDOWN_POLLING_INTERVAL_MS);
            }
        } else {
            handlerActions.handleProcessingComplete(shuttingDown);
        }
    }

    void noteSleepEntryNow() {
        synchronized (this) {
            mLastSleepEntryTime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Mirrors legacy doHandleProcessingComplete() pre-checks: release timer and avoid duplicate
     * sleep-entry completion.
     *
     * @return true if processing completion should proceed; false if it should be ignored.
     */
    boolean prepareForProcessingComplete(boolean shutdownWhenCompleted) {
        synchronized (this) {
            releaseTimerLocked();
            if (!shutdownWhenCompleted && mLastSleepEntryTime > mProcessingStartTime) {
                // Entered sleep after processing start. So this could be duplicate request.
                Log.w(CarLog.TAG_POWER, "Duplicate sleep entry request, ignore");
                return false;
            }
            return true;
        }
    }

    /**
     * Notifies early completion of processing. Matches original notifyPowerEventProcessingCompletion().
     */
    void notifyPowerEventProcessingCompletion(
            CarPowerManagementService.PowerEventProcessingHandler handler,
            HandlerActions handlerActions) {
        long processingTime = 0;
        for (PowerEventProcessingHandlerWrapper wrapper : mProcessingHandlers) {
            if (wrapper.handler == handler) {
                wrapper.markProcessingDone();
            } else if (!wrapper.isProcessingDone()) {
                processingTime = Math.max(processingTime, wrapper.getProcessingTime());
            }
        }
        // Add app extend time if tokens are outstanding (legacy behavior).
        processingTime += mListenerRegistry.getAppExtendTimeMsIfTokensOutstanding();

        long now = SystemClock.elapsedRealtime();
        long startTime;
        boolean shouldShutdown = true;

        PowerState currentState = mCurrentPowerStateSupplier.getCurrentPowerState();
        if (currentState == null) {
            return;
        }
        if (currentState.mState != PowerHalService.STATE_SHUTDOWN_PREPARE) {
            return;
        }

        if (currentState.canEnterDeepSleep() && !mShutdownOnNextSuspendSupplier.getShutdownOnNextSuspend()) {
            shouldShutdown = false;
            synchronized (this) {
                startTime = mProcessingStartTime;
                if (mLastSleepEntryTime > mProcessingStartTime && mLastSleepEntryTime < now) {
                    // Already slept
                    return;
                }
            }
        } else {
            synchronized (this) {
                startTime = mProcessingStartTime;
            }
        }

        if ((startTime + processingTime) <= now) {
            Log.i(CarLog.TAG_POWER, "Processing all done");
            handlerActions.handleProcessingComplete(shouldShutdown);
        }
    }

    int getWakeupTimeSec() {
        int wakeupTimeSec = 0;
        for (PowerEventProcessingHandlerWrapper wrapper : mProcessingHandlers) {
            int t = wrapper.handler.getWakeupTime();
            if (t > wakeupTimeSec) {
                wakeupTimeSec = t;
            }
        }
        return wakeupTimeSec;
    }

    void dump(PrintWriter writer) {
        writer.print(",mProcessingStartTime:" + mProcessingStartTime);
        writer.println(",mLastSleepEntryTime:" + mLastSleepEntryTime);
        writer.println("**PowerEventProcessingHandlers");
        for (PowerEventProcessingHandlerWrapper wrapper : mProcessingHandlers) {
            writer.println(wrapper.toString());
        }
    }

    @GuardedBy("this")
    private void releaseTimerLocked() {
        if (mTimer != null) {
            mTimer.cancel();
        }
        mTimer = null;
    }

    private final class ShutdownProcessingTimerTask extends TimerTask {
        private final boolean mShutdownWhenCompleted;
        private final int mExpirationCount;
        private final HandlerActions mHandlerActions;

        private int mCurrentCount;

        private ShutdownProcessingTimerTask(boolean shutdownWhenCompleted, int expirationCount,
                HandlerActions handlerActions) {
            mShutdownWhenCompleted = shutdownWhenCompleted;
            mExpirationCount = expirationCount;
            mHandlerActions = handlerActions;
            mCurrentCount = 0;
        }

        @Override
        public void run() {
            mCurrentCount++;
            if (mCurrentCount > mExpirationCount) {
                synchronized (CarPowerPreShutdownProcessor.this) {
                    releaseTimerLocked();
                }
                mHandlerActions.handleProcessingComplete(mShutdownWhenCompleted);
            } else {
                if (mHal != null) {
                    mHal.sendShutdownPostpone(SHUTDOWN_EXTEND_MAX_MS);
                } else {
                    Log.w(CarLog.TAG_POWER,
                            "HAL is null; cannot send shutdown postpone (test-only path)");
                }
            }
        }
    }

    static final class PowerEventProcessingHandlerWrapper {
        public final CarPowerManagementService.PowerEventProcessingHandler handler;
        private long mProcessingTime = 0;
        private boolean mProcessingDone = true;
        private boolean mPowerOnSent = false;
        private int mLastDisplayState = -1;

        PowerEventProcessingHandlerWrapper(
                CarPowerManagementService.PowerEventProcessingHandler handler) {
            this.handler = handler;
        }

        synchronized void setProcessingTimeAndResetProcessingDone(long processingTime) {
            mProcessingTime = processingTime;
            mProcessingDone = false;
        }

        synchronized long getProcessingTime() {
            return mProcessingTime;
        }

        synchronized void markProcessingDone() {
            mProcessingDone = true;
        }

        synchronized boolean isProcessingDone() {
            return mProcessingDone;
        }

        void callOnPowerOn(boolean displayOn) {
            int newDisplayState = displayOn ? 1 : 0;
            boolean shouldCall = false;
            synchronized (this) {
                if (!mPowerOnSent || (mLastDisplayState != newDisplayState)) {
                    shouldCall = true;
                    mPowerOnSent = true;
                    mLastDisplayState = newDisplayState;
                }
            }
            if (shouldCall) {
                handler.onPowerOn(displayOn);
            }
        }

        @Override
        public String toString() {
            return "PowerEventProcessingHandlerWrapper [handler=" + handler + ", mProcessingTime="
                    + mProcessingTime + ", mProcessingDone=" + mProcessingDone + "]";
        }
    }
}
