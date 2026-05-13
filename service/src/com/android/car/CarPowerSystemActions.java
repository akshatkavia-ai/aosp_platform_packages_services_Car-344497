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

import com.android.car.systeminterface.SystemInterface;

/**
 * Small adapter around {@link SystemInterface} used by CarPowerManagementService / state machine.
 *
 * <p>Package-private to avoid expanding public surface area. This is intentionally thin and
 * behavior-preserving.
 */
final class CarPowerSystemActions {
    private final SystemInterface mSystemInterface;

    CarPowerSystemActions(SystemInterface systemInterface) {
        mSystemInterface = systemInterface;
    }

    void setDisplayState(boolean on) {
        mSystemInterface.setDisplayState(on);
    }

    void setDisplayBrightness(int brightness) {
        mSystemInterface.setDisplayBrightness(brightness);
    }

    void switchToFullWakeLock() {
        mSystemInterface.switchToFullWakeLock();
    }

    void switchToPartialWakeLock() {
        mSystemInterface.switchToPartialWakeLock();
    }

    boolean isSystemSupportingDeepSleep() {
        return mSystemInterface.isSystemSupportingDeepSleep();
    }

    boolean enterDeepSleep(int wakeupTimeSec) {
        return mSystemInterface.enterDeepSleep(wakeupTimeSec);
    }

    boolean isWakeupCausedByTimer() {
        return mSystemInterface.isWakeupCausedByTimer();
    }

    void shutdown() {
        mSystemInterface.shutdown();
    }

    void releaseAllWakeLocks() {
        mSystemInterface.releaseAllWakeLocks();
    }
}
