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

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.IOInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.WakeLockInterface;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link CarPowerStateMachine}.
 *
 * <p>Focuses on refactor-sensitive edge cases:
 * <ul>
 *   <li>shutdown-on-next-suspend must force shutdown even when deep sleep is allowed.</li>
 *   <li>deep sleep exit when not woken by timer and no power-state change => shutdown path.</li>
 *   <li>ordering constraints around sleep-entry/exit and VHAL reporting.</li>
 * </ul>
 */
@SmallTest
public final class test_CarPowerStateMachine extends AndroidTestCase {

    public void testShutdownPrepare_canSleepButShutdownOnNextSuspend_forcesShutdown() throws Exception {
        List<String> calls = new ArrayList<>();

        RecordingPowerHalService hal = new RecordingPowerHalService(
                true /*isPowerStateSupported*/,
                true /*isDeepSleepAllowed*/,
                false /*isTimedWakeupAllowed*/,
                calls);

        RecordingSystemStateInterface systemState = new RecordingSystemStateInterface(calls);
        systemState.setSystemSupportingDeepSleep(true);

        SystemInterface systemInterface = SystemInterface.Builder.defaultSystemInterface(getContext())
                .withDisplayInterface(new RecordingDisplayInterface(calls))
                .withWakeLockInterface(new RecordingWakeLockInterface(calls))
                .withSystemStateInterface(systemState)
                .withIOInterface(new DefaultFilesDirIOInterface(getContext().getFilesDir()))
                .build();

        CarPowerSystemActions systemActions = new CarPowerSystemActions(systemInterface);

        final boolean[] shutdownOnNextSuspend = new boolean[] { true };

        PowerListenerRegistry listenerRegistry = new PowerListenerRegistry(
                new CarPowerListenerTokenRegistry.AllAppsFinishedCallback() {
                    @Override
                    public void onAllAppsFinished() {
                        // Not used in these tests.
                    }
                },
                new CarPowerListenerTokenRegistry.CurrentHalPowerStateSupplier() {
                    @Override
                    public int getCurrentHalPowerState() {
                        PowerState s = hal.getCurrentPowerState();
                        return (s == null) ? -1 : s.mState;
                    }
                });

        CarPowerPreShutdownProcessor preShutdownProcessor = new CarPowerPreShutdownProcessor(
                hal,
                listenerRegistry,
                new CarPowerPreShutdownProcessor.CurrentPowerStateSupplier() {
                    @Override
                    public PowerState getCurrentPowerState() {
                        return hal.getCurrentPowerState();
                    }
                },
                new CarPowerPreShutdownProcessor.ShutdownOnNextSuspendSupplier() {
                    @Override
                    public boolean getShutdownOnNextSuspend() {
                        return shutdownOnNextSuspend[0];
                    }
                });

        CarPowerStateMachine stateMachine = new CarPowerStateMachine(
                hal,
                systemActions,
                listenerRegistry,
                preShutdownProcessor,
                new CarPowerStateMachine.ShutdownOnNextSuspendSupplier() {
                    @Override
                    public boolean getShutdownOnNextSuspend() {
                        return shutdownOnNextSuspend[0];
                    }
                });

        SynchronousHandlerActions handlerActions = new SynchronousHandlerActions(stateMachine);

        // CAN_SLEEP is set, but shutdownOnNextSuspend forces the shutdown path.
        PowerState request = new PowerState(PowerHalService.STATE_SHUTDOWN_PREPARE,
                PowerHalService.SHUTDOWN_CAN_SLEEP);
        hal.setCurrentPowerState(request, false /*notify*/);

        stateMachine.onApPowerStateChange(request, handlerActions);

        assertTrue("Expected shutdown() to be called", calls.contains("system:shutdown"));
        assertFalse("Deep sleep must not be entered when shutdown-on-next-suspend is set",
                calls.contains("system:enterDeepSleep"));

        // Ensure VHAL shutdown start report is sent.
        assertTrue("Expected VHAL shutdown start report", calls.contains("hal:shutdownStart"));
    }

    public void testDeepSleepExit_noWakeupTimerAndNoPowerStateChange_shutsDown() throws Exception {
        List<String> calls = new ArrayList<>();

        RecordingPowerHalService hal = new RecordingPowerHalService(
                true /*isPowerStateSupported*/,
                true /*isDeepSleepAllowed*/,
                false /*isTimedWakeupAllowed*/,
                calls);

        RecordingSystemStateInterface systemState = new RecordingSystemStateInterface(calls);
        systemState.setSystemSupportingDeepSleep(true);
        systemState.setWakeupCausedByTimer(false);

        SystemInterface systemInterface = SystemInterface.Builder.defaultSystemInterface(getContext())
                .withDisplayInterface(new RecordingDisplayInterface(calls))
                .withWakeLockInterface(new RecordingWakeLockInterface(calls))
                .withSystemStateInterface(systemState)
                .withIOInterface(new DefaultFilesDirIOInterface(getContext().getFilesDir()))
                .build();

        CarPowerSystemActions systemActions = new CarPowerSystemActions(systemInterface);

        final boolean[] shutdownOnNextSuspend = new boolean[] { false };

        PowerListenerRegistry listenerRegistry = new PowerListenerRegistry(
                new CarPowerListenerTokenRegistry.AllAppsFinishedCallback() {
                    @Override
                    public void onAllAppsFinished() {
                        // Not used in these tests.
                    }
                },
                new CarPowerListenerTokenRegistry.CurrentHalPowerStateSupplier() {
                    @Override
                    public int getCurrentHalPowerState() {
                        PowerState s = hal.getCurrentPowerState();
                        return (s == null) ? -1 : s.mState;
                    }
                });

        final RecordingServiceListener serviceListener = new RecordingServiceListener(calls);
        listenerRegistry.addServiceListener(serviceListener);

        CarPowerPreShutdownProcessor preShutdownProcessor = new CarPowerPreShutdownProcessor(
                hal,
                listenerRegistry,
                new CarPowerPreShutdownProcessor.CurrentPowerStateSupplier() {
                    @Override
                    public PowerState getCurrentPowerState() {
                        return hal.getCurrentPowerState();
                    }
                },
                new CarPowerPreShutdownProcessor.ShutdownOnNextSuspendSupplier() {
                    @Override
                    public boolean getShutdownOnNextSuspend() {
                        return shutdownOnNextSuspend[0];
                    }
                });

        CarPowerStateMachine stateMachine = new CarPowerStateMachine(
                hal,
                systemActions,
                listenerRegistry,
                preShutdownProcessor,
                new CarPowerStateMachine.ShutdownOnNextSuspendSupplier() {
                    @Override
                    public boolean getShutdownOnNextSuspend() {
                        return shutdownOnNextSuspend[0];
                    }
                });

        SynchronousHandlerActions handlerActions = new SynchronousHandlerActions(stateMachine);

        PowerState shutdownPrepareCanSleep = new PowerState(PowerHalService.STATE_SHUTDOWN_PREPARE,
                PowerHalService.SHUTDOWN_CAN_SLEEP);
        // Ensure hal "current" remains the same after deep sleep exit: this should trigger shutdown.
        hal.setCurrentPowerState(shutdownPrepareCanSleep, false /*notify*/);

        stateMachine.onApPowerStateChange(shutdownPrepareCanSleep, handlerActions);

        // Validate key ordering points in the deep sleep flow (matches CarPowerStateMachine impl):
        // - onSleepEntry happens before VHAL sleep entry report
        // - VHAL sleep exit report happens before onSleepExit
        int idxSleepEntry = calls.indexOf("listener:sleepEntry");
        int idxHalSleepEntry = calls.indexOf("hal:sleepEntry");
        int idxHalSleepExit = calls.indexOf("hal:sleepExit");
        int idxSleepExit = calls.indexOf("listener:sleepExit");

        assertTrue("Expected sleep entry callback", idxSleepEntry >= 0);
        assertTrue("Expected VHAL sleep entry report", idxHalSleepEntry >= 0);
        assertTrue("Expected VHAL sleep exit report", idxHalSleepExit >= 0);
        assertTrue("Expected sleep exit callback", idxSleepExit >= 0);

        assertTrue("Sleep entry callback must happen before VHAL sleep entry report",
                idxSleepEntry < idxHalSleepEntry);
        assertTrue("VHAL sleep exit report must happen before sleep exit callback",
                idxHalSleepExit < idxSleepExit);

        // No wakeup timer and no power-state change => shutdown path after deep sleep.
        assertTrue("Expected shutdown() to be called", calls.contains("system:shutdown"));
        assertTrue("Expected VHAL shutdown start report", calls.contains("hal:shutdownStart"));
    }

    private static final class SynchronousHandlerActions implements CarPowerStateMachine.HandlerActions {
        private final CarPowerStateMachine mStateMachine;

        private SynchronousHandlerActions(CarPowerStateMachine stateMachine) {
            mStateMachine = stateMachine;
        }

        @Override
        public void handlePowerStateChange() {
            mStateMachine.doHandlePowerStateChange(this);
        }

        @Override
        public void handleProcessingComplete(boolean shutdownWhenCompleted) {
            mStateMachine.doHandleProcessingComplete(shutdownWhenCompleted, this);
        }

        @Override
        public void cancelProcessingComplete() {
            // No-op for synchronous tests.
        }

        @Override
        public void handlePowerOn() {
            // Not used in these tests.
        }
    }

    private static final class RecordingPowerHalService extends MockedPowerHalService {
        private final List<String> mCalls;

        RecordingPowerHalService(boolean isPowerStateSupported, boolean isDeepSleepAllowed,
                boolean isTimedWakeupAllowed, List<String> calls) {
            super(isPowerStateSupported, isDeepSleepAllowed, isTimedWakeupAllowed);
            mCalls = calls;
        }

        @Override
        public void sendSleepEntry() {
            mCalls.add("hal:sleepEntry");
            super.sendSleepEntry();
        }

        @Override
        public void sendSleepExit() {
            mCalls.add("hal:sleepExit");
            super.sendSleepExit();
        }

        @Override
        public void sendShutdownStart(int wakeupTimeSec) {
            mCalls.add("hal:shutdownStart");
            super.sendShutdownStart(wakeupTimeSec);
        }
    }

    private static final class RecordingServiceListener
            implements CarPowerManagementService.PowerServiceEventListener {
        private final List<String> mCalls;

        RecordingServiceListener(List<String> calls) {
            mCalls = calls;
        }

        @Override
        public void onShutdown() {
            mCalls.add("listener:shutdown");
        }

        @Override
        public void onSleepEntry() {
            mCalls.add("listener:sleepEntry");
        }

        @Override
        public void onSleepExit() {
            mCalls.add("listener:sleepExit");
        }
    }

    private static final class RecordingDisplayInterface implements DisplayInterface {
        private final List<String> mCalls;

        RecordingDisplayInterface(List<String> calls) {
            mCalls = calls;
        }

        @Override
        public void setDisplayBrightness(int brightness) {
            mCalls.add("display:brightness:" + brightness);
        }

        @Override
        public void setDisplayState(boolean on) {
            mCalls.add("display:state:" + on);
        }

        @Override
        public void startDisplayStateMonitoring(CarPowerManagementService service) {
            // No-op for tests.
        }

        @Override
        public void stopDisplayStateMonitoring() {
            // No-op for tests.
        }
    }

    private static final class RecordingWakeLockInterface implements WakeLockInterface {
        private final List<String> mCalls;

        RecordingWakeLockInterface(List<String> calls) {
            mCalls = calls;
        }

        @Override
        public void releaseAllWakeLocks() {
            mCalls.add("wakelock:releaseAll");
        }

        @Override
        public void switchToPartialWakeLock() {
            mCalls.add("wakelock:partial");
        }

        @Override
        public void switchToFullWakeLock() {
            mCalls.add("wakelock:full");
        }
    }

    private static final class RecordingSystemStateInterface implements SystemStateInterface {
        private final List<String> mCalls;

        private volatile boolean mWakeupCausedByTimer;
        private volatile boolean mSystemSupportingDeepSleep;

        RecordingSystemStateInterface(List<String> calls) {
            mCalls = calls;
        }

        void setWakeupCausedByTimer(boolean v) {
            mWakeupCausedByTimer = v;
        }

        void setSystemSupportingDeepSleep(boolean v) {
            mSystemSupportingDeepSleep = v;
        }

        @Override
        public void shutdown() {
            mCalls.add("system:shutdown");
        }

        @Override
        public boolean enterDeepSleep(int sleepDurationSec) {
            mCalls.add("system:enterDeepSleep");
            return true;
        }

        @Override
        public void scheduleActionForBootCompleted(Runnable action, Duration delay) {
            // No-op for tests.
        }

        @Override
        public boolean isWakeupCausedByTimer() {
            return mWakeupCausedByTimer;
        }

        @Override
        public boolean isSystemSupportingDeepSleep() {
            return mSystemSupportingDeepSleep;
        }
    }

    private static final class DefaultFilesDirIOInterface implements IOInterface {
        private final File mFilesDir;

        DefaultFilesDirIOInterface(File filesDir) {
            mFilesDir = filesDir;
        }

        @Override
        public File getFilesDir() {
            return mFilesDir;
        }
    }
}
