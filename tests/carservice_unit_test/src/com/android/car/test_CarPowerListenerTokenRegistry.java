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
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.car.hal.PowerHalService;

import java.io.FileDescriptor;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link CarPowerListenerTokenRegistry} extracted from CarPowerManagementService.
 *
 * <p>These tests focus on edge cases introduced / clarified by refactor seams:
 * <ul>
 *   <li>Binder death must clear outstanding tokens so shutdown/suspend completion can't be blocked.</li>
 *   <li>Binder death completion must be gated on current HAL power state (SHUTDOWN_PREPARE only).</li>
 *   <li>Multi-listener broadcast ordering must remain stable (RemoteCallbackList iteration order).</li>
 * </ul>
 */
@SmallTest
public final class test_CarPowerListenerTokenRegistry extends AndroidTestCase {

    private static final long TIMEOUT_MS = 2000;

    private final AtomicInteger mAllAppsFinishedCount = new AtomicInteger(0);

    private volatile int mCurrentHalPowerState = PowerHalService.STATE_ON_FULL;

    private CarPowerListenerTokenRegistry newRegistry() {
        return new CarPowerListenerTokenRegistry(
                new CarPowerListenerTokenRegistry.AllAppsFinishedCallback() {
                    @Override
                    public void onAllAppsFinished() {
                        mAllAppsFinishedCount.incrementAndGet();
                    }
                },
                new CarPowerListenerTokenRegistry.CurrentHalPowerStateSupplier() {
                    @Override
                    public int getCurrentHalPowerState() {
                        return mCurrentHalPowerState;
                    }
                });
    }

    public void testSendPowerManagerEvent_invokesListenersInReverseRegistrationOrderAndAssignsTokens()
            throws Exception {
        CarPowerListenerTokenRegistry registry = newRegistry();
        mCurrentHalPowerState = PowerHalService.STATE_SHUTDOWN_PREPARE;

        BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        RecordingPowerListener l1 = new RecordingPowerListener("l1", calls);
        RecordingPowerListener l2 = new RecordingPowerListener("l2", calls);

        registry.registerListener(l1);
        registry.registerListener(l2);

        long extra = registry.sendPowerManagerEvent(true /*shuttingDown*/);
        // Behavior contract in CarPowerListenerTokenRegistry: non-empty token map adds extend time.
        assertEquals(10000, extra);

        // Implementation iterates RemoteCallbackList from end to start (while (i-- > 0)), so the
        // most-recently registered listener is invoked first.
        assertEquals("l2:" + CarPowerStateListener.SHUTDOWN_ENTER + ":1", poll(calls));
        assertEquals("l1:" + CarPowerStateListener.SHUTDOWN_ENTER + ":2", poll(calls));

        // Tokens are outstanding until finished() or binder death cleanup.
        assertEquals(10000, registry.getAppExtendTimeMsIfTokensOutstanding());
        assertEquals(0, mAllAppsFinishedCount.get());
    }

    public void testFinished_wrongTokenDoesNotClear_andCorrectTokenCompletes() throws Exception {
        CarPowerListenerTokenRegistry registry = newRegistry();
        mCurrentHalPowerState = PowerHalService.STATE_SHUTDOWN_PREPARE;

        BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        RecordingPowerListener l1 = new RecordingPowerListener("l1", calls);
        registry.registerListener(l1);

        registry.sendPowerManagerEvent(true /*shuttingDown*/);
        assertEquals("l1:" + CarPowerStateListener.SHUTDOWN_ENTER + ":1", poll(calls));
        assertEquals(10000, registry.getAppExtendTimeMsIfTokensOutstanding());
        assertEquals(0, mAllAppsFinishedCount.get());

        // Wrong token must not clear.
        registry.finished(l1, 2 /*wrong token*/, mCurrentHalPowerState);
        assertEquals(10000, registry.getAppExtendTimeMsIfTokensOutstanding());
        assertEquals(0, mAllAppsFinishedCount.get());

        // Correct token clears and should trigger completion callback (HAL state is SHUTDOWN_PREPARE).
        registry.finished(l1, 1 /*correct token*/, mCurrentHalPowerState);
        assertEquals(0, registry.getAppExtendTimeMsIfTokensOutstanding());
        assertEquals(1, mAllAppsFinishedCount.get());
    }

    public void testBinderDeath_clearsOutstandingToken_andSignalsCompletionOnlyDuringShutdownPrepare()
            throws Exception {
        CarPowerListenerTokenRegistry registry = newRegistry();

        // --- Case 1: SHUTDOWN_PREPARE => binder death may trigger all-apps-finished ---
        mCurrentHalPowerState = PowerHalService.STATE_SHUTDOWN_PREPARE;
        RecordingPowerListener listener = new RecordingPowerListener("l1", new LinkedBlockingQueue<>());
        registry.registerListener(listener);
        registry.sendPowerManagerEvent(true /*shuttingDown*/);

        assertEquals(10000, registry.getAppExtendTimeMsIfTokensOutstanding());
        listener.getBinder().die();

        // Token must be cleared so completion cannot be blocked.
        assertEquals(0, registry.getAppExtendTimeMsIfTokensOutstanding());
        assertEquals(1, mAllAppsFinishedCount.get());

        // Listener should be removed; further broadcasts should not add extend time.
        assertEquals(0, registry.sendPowerManagerEvent(true /*shuttingDown*/));

        // --- Case 2: not SHUTDOWN_PREPARE => binder death must NOT trigger completion ---
        mAllAppsFinishedCount.set(0);
        mCurrentHalPowerState = PowerHalService.STATE_ON_FULL;
        CarPowerListenerTokenRegistry registry2 = newRegistry();
        RecordingPowerListener listener2 = new RecordingPowerListener("l2", new LinkedBlockingQueue<>());
        registry2.registerListener(listener2);
        registry2.sendPowerManagerEvent(true /*shuttingDown*/);

        listener2.getBinder().die();
        assertEquals(0, mAllAppsFinishedCount.get());
    }

    private static String poll(BlockingQueue<String> calls) throws Exception {
        String v = calls.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("Timed out waiting for listener callback", v);
        return v;
    }

    /**
     * Minimal IBinder implementation that allows tests to simulate binder death by explicitly
     * invoking the registered DeathRecipient.
     */
    private static final class DeathNotifyingBinder implements IBinder {
        private volatile boolean mAlive = true;
        private volatile DeathRecipient mRecipient;

        void die() {
            mAlive = false;
            DeathRecipient r = mRecipient;
            if (r != null) {
                r.binderDied();
            }
        }

        @Override
        public String getInterfaceDescriptor() throws RemoteException {
            return "test-binder";
        }

        @Override
        public boolean pingBinder() {
            return mAlive;
        }

        @Override
        public boolean isBinderAlive() {
            return mAlive;
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) throws RemoteException {
            // No-op for tests.
        }

        @Override
        public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
            // No-op for tests.
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return false;
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            if (!mAlive) {
                throw new RemoteException("Binder already dead");
            }
            mRecipient = recipient;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            if (mRecipient == recipient) {
                mRecipient = null;
                return true;
            }
            return false;
        }
    }

    private static final class RecordingPowerListener implements ICarPowerStateListener {
        private final String mName;
        private final BlockingQueue<String> mCalls;
        private final DeathNotifyingBinder mBinder = new DeathNotifyingBinder();

        RecordingPowerListener(String name, BlockingQueue<String> calls) {
            mName = name;
            mCalls = calls;
        }

        DeathNotifyingBinder getBinder() {
            return mBinder;
        }

        @Override
        public void onStateChanged(int state, int token) throws RemoteException {
            mCalls.offer(mName + ":" + state + ":" + token);
        }

        @Override
        public IBinder asBinder() {
            return mBinder;
        }
    }
}
