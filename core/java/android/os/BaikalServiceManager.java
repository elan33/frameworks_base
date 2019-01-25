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
@SystemService(Context.BAIKAL_SERVICE_CONTROLLER)
public class BaikalServiceManager {
    private final Context mContext;
    private final IBaikalServiceController mService;

    public BaikalServiceManager(@NonNull Context context, @NonNull IBaikalServiceController service) {
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
}


