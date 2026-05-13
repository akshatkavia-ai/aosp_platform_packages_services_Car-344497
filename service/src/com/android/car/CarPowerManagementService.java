/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.car.Car;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.ICarPower;
import android.car.hardware.power.ICarPowerStateListener;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

public class CarPowerManagementService extends ICarPower.Stub implements CarServiceBase,
        PowerHalService.PowerEventListener,
        CarPowerListenerTokenRegistry.AllAppsFinishedCallback,
        CarPowerPreShutdownProcessor.CurrentPowerStateSupplier,
        CarPowerPreShutdownProcessor.ShutdownOnNextSuspendSupplier,
        CarPowerStateMachine.ShutdownOnNextSuspendSupplier {

    /**
     * Listener for other services to monitor power events.
     */
    public interface PowerServiceEventListener {
        /**
         * Shutdown is happening
         */
        void onShutdown();

        /**
         * Entering deep sleep.
         */
        void onSleepEntry();

        /**
         * Got out of deep sleep.
         */
        void onSleepExit();
    }

    /**
     * Interface for components requiring processing time before shutting-down or
     * entering sleep, and wake-up after shut-down.
     */
    public interface PowerEventProcessingHandler {
        /**
         * Called before shutdown or sleep entry to allow running some processing. This call
         * should only queue such task in different thread and should return quickly.
         * Blocking inside this call can trigger watchdog timer which can terminate the
         * whole system.
         * @param shuttingDown whether system is shutting down or not (= sleep entry).
         * @return time necessary to run processing in ms. should return 0 if there is no
         *         processing necessary.
         */
        long onPrepareShutdown(boolean shuttingDown);

        /**
         * Called when power state is changed to ON state. Display can be either on or off.
         * @param displayOn
         */
        void onPowerOn(boolean displayOn);

        /**
         * Returns wake up time after system is fully shutdown. Power controller will power on
         * the system after this time. This power on is meant for regular maintenance kind of
         * operation.
         * @return 0 of wake up is not necessary.
         */
        int getWakeupTime();
    }

    private final Context mContext;
    private final PowerHalService mHal;
    private final SystemInterface mSystemInterface;

    private final CarPowerSystemActions mSystemActions;

    private final PowerListenerRegistry mListenerRegistry;
    private final CarPowerPreShutdownProcessor mPreShutdownProcessor;
    private final CarPowerStateMachine mStateMachine;

    @GuardedBy("this")
    private HandlerThread mHandlerThread;
    @GuardedBy("this")
    private PowerHandler mHandler;

    private int mBootReason;
    private boolean mShutdownOnNextSuspend = false;

    public CarPowerManagementService(Context context, PowerHalService powerHal,
            SystemInterface systemInterface) {
        mContext = context;
        mHal = powerHal;
        mSystemInterface = systemInterface;

        mSystemActions = new CarPowerSystemActions(mSystemInterface);

        mListenerRegistry = new PowerListenerRegistry(
                this /*allAppsFinishedCallback*/,
                new CarPowerListenerTokenRegistry.CurrentHalPowerStateSupplier() {
                    @Override
                    public int getCurrentHalPowerState() {
                        return getCurrentHalPowerStateInt();
                    }
                });
        mPreShutdownProcessor = new CarPowerPreShutdownProcessor(
                mHal,
                mListenerRegistry,
                this /*currentPowerStateSupplier*/,
                this /*shutdownOnNextSuspendSupplier*/);
        mStateMachine = new CarPowerStateMachine(
                mHal,
                mSystemActions,
                mListenerRegistry,
                mPreShutdownProcessor,
                this /*shutdownOnNextSuspendSupplier*/);
    }

    /**
     * Create a dummy instance for unit testing purpose only. Instance constructed in this way
     * is not safe as members expected to be non-null are null.
     */
    @VisibleForTesting
    protected CarPowerManagementService() {
        mContext = null;
        mHal = null;
        mSystemInterface = null;
        mSystemActions = null;

        mListenerRegistry = new PowerListenerRegistry(
                this /*allAppsFinishedCallback*/,
                new CarPowerListenerTokenRegistry.CurrentHalPowerStateSupplier() {
                    @Override
                    public int getCurrentHalPowerState() {
                        return getCurrentHalPowerStateInt();
                    }
                });
        mPreShutdownProcessor = new CarPowerPreShutdownProcessor(
                null /*hal*/,
                mListenerRegistry,
                this /*currentPowerStateSupplier*/,
                this /*shutdownOnNextSuspendSupplier*/);
        mStateMachine = new CarPowerStateMachine(
                null /*hal*/,
                null /*systemActions*/,
                mListenerRegistry,
                mPreShutdownProcessor,
                this /*shutdownOnNextSuspendSupplier*/);

        mHandlerThread = null;
        mHandler = new PowerHandler(Looper.getMainLooper());
    }

    @Override
    public void init() {
        synchronized (this) {
            mHandlerThread = new HandlerThread(CarLog.TAG_POWER);
            mHandlerThread.start();
            mHandler = new PowerHandler(mHandlerThread.getLooper());
        }

        // Preserve init ordering: set listener -> boot complete / initial injection -> fallback
        // -> display monitoring start.
        mHal.setListener(this);
        if (mHal.isPowerStateSupported()) {
            mHal.sendBootComplete();
            PowerState currentState = mHal.getCurrentPowerState();
            if (currentState != null) {
                onApPowerStateChange(currentState);
            } else {
                Log.w(CarLog.TAG_POWER, "Unable to get get current power state during "
                        + "initialization");
            }
        } else {
            Log.w(CarLog.TAG_POWER, "Vehicle hal does not support power state yet.");
            onApPowerStateChange(new PowerState(PowerHalService.STATE_ON_FULL, 0));
            mSystemActions.switchToFullWakeLock();
        }
        mSystemInterface.startDisplayStateMonitoring(this);
    }

    @Override
    public void release() {
        HandlerThread handlerThread;
        synchronized (this) {
            mPreShutdownProcessor.release();
            mStateMachine.release();
            mHandler.cancelAll();
            handlerThread = mHandlerThread;
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
            try {
                handlerThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(CarLog.TAG_POWER, "Timeout while joining for handler thread to join.");
            }
        }

        mSystemInterface.stopDisplayStateMonitoring();
        mListenerRegistry.release();
        mSystemActions.releaseAllWakeLocks();
    }

    /**
     * Register listener to monitor power event. There is no unregister counter-part and the list
     * will be cleared when the service is released.
     * @param listener
     */
    public synchronized void registerPowerEventListener(PowerServiceEventListener listener) {
        mListenerRegistry.addServiceListener(listener);
    }

    /**
     * Register PowerEventPreprocessingHandler to run pre-processing before shutdown or
     * sleep entry. There is no unregister counter-part and the list
     * will be cleared when the service is released.
     * @param handler
     */
    public synchronized void registerPowerEventProcessingHandler(PowerEventProcessingHandler handler) {
        mPreShutdownProcessor.addProcessingHandler(handler, getHandlerLocked());
    }

    /**
     * Notifies earlier completion of power event processing. PowerEventProcessingHandler quotes
     * time necessary from onPrePowerEvent() call, but actual processing can finish earlier than
     * that, and this call can be called in such case to trigger shutdown without waiting further.
     *
     * @param handler PowerEventProcessingHandler that was already registered with
     *        {@link #registerPowerEventListener(PowerServiceEventListener)} call. If it was not
     *        registered before, this call will be ignored.
     */
    public void notifyPowerEventProcessingCompletion(PowerEventProcessingHandler handler) {
        mPreShutdownProcessor.notifyPowerEventProcessingCompletion(handler, getHandlerLocked());
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*PowerManagementService*");
        writer.print("mCurrentState:" + mStateMachine.getCurrentState());
        mPreShutdownProcessor.dump(writer);
    }

    @Override
    public void onBootReasonReceived(int bootReason) {
        mBootReason = bootReason;
    }

    @Override
    public void onApPowerStateChange(PowerState state) {
        mStateMachine.onApPowerStateChange(state, getHandlerLocked());
    }

    @Override
    public void onDisplayBrightnessChange(int brightness) {
        getHandlerLocked().handleDisplayBrightnessChange(brightness);
    }

    private void doHandleDisplayBrightnessChange(int brightness) {
        mSystemActions.setDisplayBrightness(brightness);
    }

    private void doHandleMainDisplayStateChange(boolean on) {
        Log.w(CarLog.TAG_POWER, "Unimplemented:  doHandleMainDisplayStateChange() - on = " + on);
    }

    public void handleMainDisplayChanged(boolean on) {
        getHandlerLocked().handleMainDisplayStateChange(on);
    }

    /**
     * Send display brightness to VHAL.
     * @param brightness value 0-100%
     */
    public void sendDisplayBrightness(int brightness) {
        mHal.sendDisplayBrightness(brightness);
    }

    public synchronized Handler getHandler() {
        return mHandler;
    }

    // Binder interface for CarPowerManager
    @Override
    public void registerListener(ICarPowerStateListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        mListenerRegistry.registerAppListener(listener);
    }

    @Override
    public void unregisterListener(ICarPowerStateListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        mListenerRegistry.unregisterAppListener(listener);
        // Mirror legacy behavior: if listener had a token, finishing it may trigger completion.
        mListenerRegistry.finishOutstandingTokenIfAny(listener, mStateMachine.getCurrentStateInt());
    }

    @Override
    public void requestShutdownOnNextSuspend() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        mShutdownOnNextSuspend = true;
    }

    @Override
    public int getBootReason() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        // Return the most recent bootReason value
        return mBootReason;
    }

    @Override
    public void finished(ICarPowerStateListener listener, int token) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        mListenerRegistry.finished(listener, token, mStateMachine.getCurrentStateInt());
    }

    @Override
    public void onAllAppsFinished() {
        // Legacy behavior: apps finished triggers notifyPowerEventProcessingCompletion(null).
        notifyPowerEventProcessingCompletion(null);
    }

    @Override
    public PowerState getCurrentPowerState() {
        return mStateMachine.getCurrentState();
    }

    @Override
    public boolean getShutdownOnNextSuspend() {
        return mShutdownOnNextSuspend;
    }

    @VisibleForTesting
    protected void notifyPowerOn(boolean displayOn) {
        mPreShutdownProcessor.notifyPowerOn(displayOn);
    }

    @VisibleForTesting
    protected long notifyPrepareShutdown(boolean shuttingDown) {
        return mPreShutdownProcessor.notifyPrepareShutdown(shuttingDown);
    }

    private PowerHandler getHandlerLocked() {
        synchronized (this) {
            return mHandler;
        }
    }

    private int getCurrentHalPowerStateInt() {
        if (mHal == null) {
            return -1;
        }
        PowerState state = mHal.getCurrentPowerState();
        return (state == null) ? -1 : state.mState;
    }

    private final class PowerHandler extends Handler implements
            CarPowerStateMachine.HandlerActions,
            CarPowerPreShutdownProcessor.HandlerActions {

        private static final int MSG_POWER_STATE_CHANGE = 0;
        private static final int MSG_DISPLAY_BRIGHTNESS_CHANGE = 1;
        private static final int MSG_MAIN_DISPLAY_STATE_CHANGE = 2;
        private static final int MSG_PROCESSING_COMPLETE = 3;
        private static final int MSG_NOTIFY_POWER_ON = 4;

        // Do not handle this immediately but with some delay as there can be a race between
        // display off due to rear view camera and delivery to here.
        private static final long MAIN_DISPLAY_EVENT_DELAY_MS = 500;

        private PowerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handlePowerStateChange() {
            Message msg = obtainMessage(MSG_POWER_STATE_CHANGE);
            sendMessage(msg);
        }

        void handleDisplayBrightnessChange(int brightness) {
            Message msg = obtainMessage(MSG_DISPLAY_BRIGHTNESS_CHANGE, brightness, 0);
            sendMessage(msg);
        }

        void handleMainDisplayStateChange(boolean on) {
            removeMessages(MSG_MAIN_DISPLAY_STATE_CHANGE);
            Message msg = obtainMessage(MSG_MAIN_DISPLAY_STATE_CHANGE, Boolean.valueOf(on));
            sendMessageDelayed(msg, MAIN_DISPLAY_EVENT_DELAY_MS);
        }

        @Override
        public void handleProcessingComplete(boolean shutdownWhenCompleted) {
            removeMessages(MSG_PROCESSING_COMPLETE);
            Message msg = obtainMessage(MSG_PROCESSING_COMPLETE, shutdownWhenCompleted ? 1 : 0, 0);
            sendMessage(msg);
        }

        @Override
        public void handlePowerOn() {
            Message msg = obtainMessage(MSG_NOTIFY_POWER_ON);
            sendMessage(msg);
        }

        @Override
        public void cancelProcessingComplete() {
            removeMessages(MSG_PROCESSING_COMPLETE);
        }

        void cancelAll() {
            removeMessages(MSG_POWER_STATE_CHANGE);
            removeMessages(MSG_DISPLAY_BRIGHTNESS_CHANGE);
            removeMessages(MSG_MAIN_DISPLAY_STATE_CHANGE);
            removeMessages(MSG_PROCESSING_COMPLETE);
            removeMessages(MSG_NOTIFY_POWER_ON);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POWER_STATE_CHANGE:
                    mStateMachine.doHandlePowerStateChange(this);
                    break;
                case MSG_DISPLAY_BRIGHTNESS_CHANGE:
                    doHandleDisplayBrightnessChange(msg.arg1);
                    break;
                case MSG_MAIN_DISPLAY_STATE_CHANGE:
                    doHandleMainDisplayStateChange((Boolean) msg.obj);
                    break;
                case MSG_PROCESSING_COMPLETE:
                    mStateMachine.doHandleProcessingComplete(msg.arg1 == 1, this);
                    break;
                case MSG_NOTIFY_POWER_ON:
                    // Behavior-preserving: re-send power-on notification to newly registered
                    // processing handlers.
                    boolean displayOn = false;
                    PowerState current = mStateMachine.getCurrentState();
                    if (current != null && current.mState == PowerHalService.STATE_ON_FULL) {
                        displayOn = true;
                    }
                    mPreShutdownProcessor.notifyPowerOn(displayOn);
                    break;
            }
        }
    }
}
