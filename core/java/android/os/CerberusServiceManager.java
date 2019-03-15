/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;

/**
 * Access to the service that keeps track of device idleness and drives low power mode based on
 * that.
 *
 * @hide
 */
@TestApi
@SystemService(Context.CERBERUS_SERVICE_CONTROLLER)
public class CerberusServiceManager {

    public static final String ACTION_READER_ON = "ACTION_READER_ON";
    public static final String ACTION_READER_OFF = "ACTION_READER_OFF";

    public static final String ACTION_BRIGHTNESS_CHANGED = "ACTION_BRIGHTNESS_CHANGED";


    public static final int PRIO_CRITICAL = 0;
    public static final int PRIO_SYSTEM = 1;
    public static final int PRIO_UNRESTRICTED = 2;
    public static final int PRIO_ALARM_CLOCK = 3;
    public static final int PRIO_BACKGROUND_NOTIFICATIONS = 3;
    public static final int PRIO_BACKGROUND_NAVIGATION = 4;
    public static final int PRIO_MESSENGER = 3;
    public static final int PRIO_REGULAR = 5;
    public static final int PRIO_GAME = 5;
    public static final int PRIO_BOOKREADER = 3;
    public static final int PRIO_RESTRICTED = 5;
    public static final int PRIO_TOP_ONLY = 5;

    public static final int SCREEN_BRIGHTNESS_DEFAULT = 0;
    public static final int SCREEN_BRIGHTNESS_10 = 1;
    public static final int SCREEN_BRIGHTNESS_20 = 2;
    public static final int SCREEN_BRIGHTNESS_30 = 3;
    public static final int SCREEN_BRIGHTNESS_40 = 4;
    public static final int SCREEN_BRIGHTNESS_50 = 5;
    public static final int SCREEN_BRIGHTNESS_60 = 6;
    public static final int SCREEN_BRIGHTNESS_70 = 7;
    public static final int SCREEN_BRIGHTNESS_80 = 8;
    public static final int SCREEN_BRIGHTNESS_90 = 9;
    public static final int SCREEN_BRIGHTNESS_AUTO_LOW = 10;
    public static final int SCREEN_BRIGHTNESS_FULL = 11;
    public static final int SCREEN_BRIGHTNESS_AUTO_QUARTER = 12;

    public static final int OP_CAMERA_HAL1 = 1;

    private final Context mContext;
    private final ICerberusServiceController mService;

    public CerberusServiceManager(@NonNull Context context, @NonNull ICerberusServiceController service) {
        mContext = context;
        mService = service;
    }

    public String getAppPerfProfile(String packageName) {
        try {
            return mService.getAppPerfProfile(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return "default";
        }
    }

    public String getAppThermProfile(String packageName) {
        try {
            return mService.getAppPerfProfile(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return "defualt";
        }
    }

    public void setAppPerfProfile(String packageName, String profile) {
        try {
            mService.setAppPerfProfile(packageName,profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public void setAppThermProfile(String packageName, String profile) {
        try {
            mService.setAppThermProfile(packageName,profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
    
    public boolean isAppRestrictedProfile(String packageName) {
        try {
            return mService.isAppRestrictedProfile(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    public void setAppRestrictedProfile(String packageName, boolean restricted) {
        try {
            mService.setAppRestrictedProfile(packageName,restricted);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public int getAppPriority(String packageName) {
        try {
            return mService.getAppPriority(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return 0;
        }
    }

    public void setAppPriority(String packageName, int priority) {
        try {
            mService.setAppPriority(packageName,priority);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public int getAppBrightness(String packageName) {
        try {
            return mService.getAppBrightness(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return 0;
        }
    }

    public void setAppBrightness(String packageName, int brightness) {
        try {
            mService.setAppBrightness(packageName,brightness);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public String getDefaultPerfProfile() {
        try {
            return mService.getDefaultPerfProfile();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return "default";
        }
    }

    public void setDefaultPerfProfile(String profile) {
        try {
            mService.setDefaultPerfProfile(profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public String getDefaultThermProfile() {
        try {
            return mService.getDefaultThermProfile();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return "default";
        }
    }

    public void setDefaultThermProfile(String profile) {
        try {
            mService.setDefaultThermProfile(profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public int getAppOption(String packageName,int option) {
        try {
            return mService.getAppOption(packageName,option);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return 0;
        }
    }

    public void setAppOption(String packageName,int option,int value) {
        try {
            mService.setAppOption(packageName,option,value);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }




    public int getBrightnessOverride() {
        try {
            return mService.getBrightnessOverride();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return 0;
        }
    }

    boolean isReaderMode() {
        try {
            return mService.isReaderMode();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    boolean isDeviceIdleMode() {
        try {
            return mService.isDeviceIdleMode();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    boolean isLightDeviceIdleMode() {
        try {
            return mService.isLightDeviceIdleMode();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    void setDeviceIdleMode(boolean enabled) {
        try {
            mService.setDeviceIdleMode(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setLightDeviceIdleMode(boolean enabled) {
        try {
            mService.setLightDeviceIdleMode(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    boolean isAggressiveDeviceIdleMode() {
        try {
            return mService.isLightDeviceIdleMode();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    void setWakefulness(int wakefulness,int reason) {
        try {
            mService.setWakefulness(wakefulness,reason);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

}


