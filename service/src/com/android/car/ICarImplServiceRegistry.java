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

import android.content.Context;
import android.hardware.automotive.vehicle.V2_0.IVehicle;
import android.car.user.CarUserManagerHelper;

import com.android.car.cluster.InstrumentClusterService;
import com.android.car.hal.VehicleHal;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Package-private service registry and lifecycle controller for {@link ICarImpl}.
 *
 * <p>This class exists to centralize:
 * <ul>
 *   <li>Service instance construction and dependency wiring</li>
 *   <li>Stable service init ordering and reverse release ordering</li>
 *   <li>Vehicle HAL reconnect fanout ordering</li>
 * </ul>
 *
 * <p>Behavioral constraints:
 * <ul>
 *   <li>VehicleHal.init() must happen before services init</li>
 *   <li>Service init must follow the explicit ordering that previously lived in ICarImpl</li>
 *   <li>Service release must be reverse init order</li>
 *   <li>VehicleHal.vehicleHalReconnected() must happen before services.vehicleHalReconnected()</li>
 * </ul>
 */
final class ICarImplServiceRegistry {

    private final Context mContext;
    private final VehicleHal mHal;
    private final SystemInterface mSystemInterface;
    private final ICarImpl mCarImpl;

    private final SystemActivityMonitoringService mSystemActivityMonitoringService;
    private final CarPowerManagementService mCarPowerManagementService;
    private final CarPackageManagerService mCarPackageManagerService;
    private final CarInputService mCarInputService;
    private final CarDrivingStateService mCarDrivingStateService;
    private final CarUxRestrictionsManagerService mCarUXRestrictionsService;
    private final CarAudioService mCarAudioService;
    private final CarProjectionService mCarProjectionService;
    private final CarPropertyService mCarPropertyService;
    private final CarNightService mCarNightService;
    private final AppFocusService mAppFocusService;
    private final GarageModeService mGarageModeService;
    private final InstrumentClusterService mInstrumentClusterService;
    private final CarLocationService mCarLocationService;
    private final SystemStateControllerService mSystemStateControllerService;
    private final CarBluetoothService mCarBluetoothService;
    private final PerUserCarServiceHelper mPerUserCarServiceHelper;
    private final CarDiagnosticService mCarDiagnosticService;
    private final CarStorageMonitoringService mCarStorageMonitoringService;
    private final CarConfigurationService mCarConfigurationService;

    private final CarUserManagerHelper mUserManagerHelper;
    private final CarUserService mCarUserService; // nullable
    private final VmsSubscriberService mVmsSubscriberService;
    private final VmsPublisherService mVmsPublisherService;

    private final CarServiceBase[] mAllServices;

    ICarImplServiceRegistry(Context serviceContext, VehicleHal hal, SystemInterface systemInterface,
            ICarImpl carImpl) {
        mContext = serviceContext;
        mHal = hal;
        mSystemInterface = systemInterface;
        mCarImpl = carImpl;

        // NOTE: Service construction below is intentionally kept identical to the previous
        // ICarImpl constructor ordering and wiring to avoid behavior changes.
        mSystemActivityMonitoringService = new SystemActivityMonitoringService(serviceContext);
        mCarPowerManagementService = new CarPowerManagementService(mContext, mHal.getPowerHal(),
                systemInterface);
        mCarPropertyService = new CarPropertyService(serviceContext, mHal.getPropertyHal());
        mCarDrivingStateService = new CarDrivingStateService(serviceContext, mCarPropertyService);
        mCarUXRestrictionsService = new CarUxRestrictionsManagerService(serviceContext,
                mCarDrivingStateService, mCarPropertyService);
        mCarPackageManagerService = new CarPackageManagerService(serviceContext,
                mCarUXRestrictionsService,
                mSystemActivityMonitoringService);
        mCarInputService = new CarInputService(serviceContext, mHal.getInputHal());
        mCarProjectionService = new CarProjectionService(serviceContext, mCarInputService);
        mGarageModeService = new GarageModeService(mContext, mCarPowerManagementService);
        mCarLocationService = new CarLocationService(mContext, mCarPowerManagementService,
                mCarPropertyService);
        mAppFocusService = new AppFocusService(serviceContext, mSystemActivityMonitoringService);
        mCarAudioService = new CarAudioService(serviceContext);
        mCarNightService = new CarNightService(serviceContext, mCarPropertyService);
        mInstrumentClusterService = new InstrumentClusterService(serviceContext,
                mAppFocusService, mCarInputService);
        mSystemStateControllerService = new SystemStateControllerService(serviceContext,
                mCarPowerManagementService, mCarAudioService, mCarImpl);
        mPerUserCarServiceHelper = new PerUserCarServiceHelper(serviceContext);
        mCarBluetoothService = new CarBluetoothService(serviceContext, mCarPropertyService,
                mPerUserCarServiceHelper, mCarUXRestrictionsService);
        mVmsSubscriberService = new VmsSubscriberService(serviceContext, mHal.getVmsHal());
        mVmsPublisherService = new VmsPublisherService(serviceContext, mHal.getVmsHal());
        mCarDiagnosticService = new CarDiagnosticService(serviceContext, mHal.getDiagnosticHal());
        mCarStorageMonitoringService = new CarStorageMonitoringService(serviceContext,
                systemInterface);
        mCarConfigurationService =
                new CarConfigurationService(serviceContext, new JsonReaderImpl());
        mUserManagerHelper = new CarUserManagerHelper(serviceContext);

        // Be careful with order. Service depending on other service should be inited later.
        List<CarServiceBase> allServices = new ArrayList<>();
        allServices.add(mSystemActivityMonitoringService);
        allServices.add(mCarPowerManagementService);
        allServices.add(mCarPropertyService);
        allServices.add(mCarDrivingStateService);
        allServices.add(mCarUXRestrictionsService);
        allServices.add(mCarPackageManagerService);
        allServices.add(mCarInputService);
        allServices.add(mCarLocationService);
        allServices.add(mGarageModeService);
        allServices.add(mAppFocusService);
        allServices.add(mCarAudioService);
        allServices.add(mCarNightService);
        allServices.add(mInstrumentClusterService);
        allServices.add(mCarProjectionService);
        allServices.add(mSystemStateControllerService);
        allServices.add(mCarBluetoothService);
        allServices.add(mCarDiagnosticService);
        allServices.add(mPerUserCarServiceHelper);
        allServices.add(mCarStorageMonitoringService);
        allServices.add(mCarConfigurationService);
        allServices.add(mVmsSubscriberService);
        allServices.add(mVmsPublisherService);

        CarUserService carUserService = null;
        if (mUserManagerHelper.isHeadlessSystemUser()) {
            carUserService = new CarUserService(serviceContext, mUserManagerHelper);
            allServices.add(carUserService);
        }
        mCarUserService = carUserService;

        mAllServices = allServices.toArray(new CarServiceBase[allServices.size()]);
    }

    SystemActivityMonitoringService getSystemActivityMonitoringService() {
        return mSystemActivityMonitoringService;
    }

    CarPowerManagementService getCarPowerManagementService() {
        return mCarPowerManagementService;
    }

    CarPackageManagerService getCarPackageManagerService() {
        return mCarPackageManagerService;
    }

    CarInputService getCarInputService() {
        return mCarInputService;
    }

    CarDrivingStateService getCarDrivingStateService() {
        return mCarDrivingStateService;
    }

    CarUxRestrictionsManagerService getCarUXRestrictionsService() {
        return mCarUXRestrictionsService;
    }

    CarAudioService getCarAudioService() {
        return mCarAudioService;
    }

    CarProjectionService getCarProjectionService() {
        return mCarProjectionService;
    }

    CarPropertyService getCarPropertyService() {
        return mCarPropertyService;
    }

    CarNightService getCarNightService() {
        return mCarNightService;
    }

    AppFocusService getAppFocusService() {
        return mAppFocusService;
    }

    GarageModeService getGarageModeService() {
        return mGarageModeService;
    }

    InstrumentClusterService getInstrumentClusterService() {
        return mInstrumentClusterService;
    }

    CarLocationService getCarLocationService() {
        return mCarLocationService;
    }

    SystemStateControllerService getSystemStateControllerService() {
        return mSystemStateControllerService;
    }

    CarBluetoothService getCarBluetoothService() {
        return mCarBluetoothService;
    }

    PerUserCarServiceHelper getPerUserCarServiceHelper() {
        return mPerUserCarServiceHelper;
    }

    CarDiagnosticService getCarDiagnosticService() {
        return mCarDiagnosticService;
    }

    CarStorageMonitoringService getCarStorageMonitoringService() {
        return mCarStorageMonitoringService;
    }

    CarConfigurationService getCarConfigurationService() {
        return mCarConfigurationService;
    }

    VmsSubscriberService getVmsSubscriberService() {
        return mVmsSubscriberService;
    }

    VmsPublisherService getVmsPublisherService() {
        return mVmsPublisherService;
    }

    CarUserService getCarUserService() {
        return mCarUserService;
    }

    CarUserManagerHelper getUserManagerHelper() {
        return mUserManagerHelper;
    }

    CarServiceBase[] getAllServices() {
        // Defensive copy: callers must not be able to mutate internal ordering/contents.
        return Arrays.copyOf(mAllServices, mAllServices.length);
    }

    void initVehicleHal() {
        mHal.init();
    }

    void initAllServices() {
        for (CarServiceBase service : mAllServices) {
            service.init();
        }
    }

    void releaseAllServicesReverseOrder() {
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
    }

    void releaseVehicleHal() {
        mHal.release();
    }

    void vehicleHalReconnected(IVehicle vehicle) {
        mHal.vehicleHalReconnected(vehicle);
        for (CarServiceBase service : mAllServices) {
            service.vehicleHalReconnected();
        }
    }
}
