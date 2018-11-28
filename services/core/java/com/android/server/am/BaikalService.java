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

package com.android.server.am;

import android.app.Service;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Activity;
import android.app.ActivityManager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.BroadcastReceiver;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Slog;
import android.util.ArrayMap;
import android.net.NetworkInfo;
import android.net.Uri;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.server.power.PowerManagerService;
import com.android.server.power.PowerManagerService.WakeLock;
import com.android.server.am.ProcessRecord;
import com.android.server.am.ServiceRecord;
import com.android.server.am.BroadcastRecord;
import com.android.server.am.BroadcastFilter;
import com.android.server.am.ReceiverList;
import com.android.server.am.ActivityManagerService;
import com.android.server.DeviceIdleController;
import com.android.server.AlarmManagerService;
import com.android.server.AppOpsService;
import com.android.server.SystemService;

import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.CellLocation;
import android.telephony.CellInfo;
import android.telephony.SignalStrength;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.VoLteServiceState;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import android.provider.Settings;

import java.util.List;
import java.util.Arrays;


public class BaikalService extends SystemService {

    private static final String TAG = "BaikalService";

    private static final boolean DEBUG = true;

    private static final int MESSAGE_DEVICE_IDLE_CHANGED = 100;
    private static final int MESSAGE_LIGHT_DEVICE_IDLE_CHANGED = 101;

    private final String [] mGoogleServicesIdleBlackListed = {
        "com.google.android.location.geocode.GeocodeService",
        "com.google.android.location.geofencer.service.GeofenceProviderService",
        "com.google.android.location.network.NetworkLocationService",
        "com.google.android.location.internal.GoogleLocationManagerService",
        "com.google.android.location.reporting.service.ReportingAndroidService",
        "com.google.android.location.internal.server.GoogleLocationService",
        "com.google.android.location.fused.FusedLocationService",
        "com.google.android.location.internal.server.HardwareArProviderService",
        "com.google.android.location.places.service.PlaceDetectionAsyncService",
        "com.google.android.gms.tron.CollectionService",
        "com.google.location.nearby.direct.service.NearbyDirectService",
        ".lockbox.service.LockboxBrokerService",
        ".usagereporting.service.UsageReportingService"
    };


    private boolean mSystemReady = false;

    private boolean mDeviceIdleMode;
    private boolean mLightDeviceIdleMode;
    private String mLastWakeupReason;
    private String mLastSleepReason;
    private boolean mTorchLongPressPowerEnabled;
    private boolean mTorchIncomingCall;
    private boolean mTorchNotification;
    private boolean mActiveIncomingCall;

    private boolean mTorchEnabled;

    private boolean mIdleAggressive;
    private boolean mLimitServices = true;
    private boolean mLimitBroadCasts = true;

    private boolean mThrottleAlarms;
    private boolean mQtiBiometricsInitialized;

    private boolean mWlBlockEnabled;

    Thread mTorchThread = null;

    private final Context mContext;
    private Constants mConstants;
    final MyHandler mHandler;

    private CameraManager mCameraManager;
    private String mRearFlashCameraId;


    AppOpsService mAppOpsService;


    AlarmManagerService mAlarmManagerService;
    DeviceIdleController mDeviceIdleController;
    PowerManagerService mPowerManagerService;
    ActivityManagerService mActivityManagerService;

    private boolean mNetworkAllowedWhileIdle;

    // Set of app ids that we will always respect the wake locks for.
    int[] mDeviceIdleWhitelist = new int[0];

    // Set of app ids that are temporarily allowed to acquire wakelocks due to high-pri message
    int[] mDeviceIdleTempWhitelist = new int[0];

    final ArrayMap<String, RestrictionStatistics> mRestrictionStatistics = new ArrayMap<String, RestrictionStatistics>();

    public BaikalService(Context context) {
        super(context);
        mContext = context;
        if( DEBUG ) {
            Slog.i(TAG,"BaikalService()");
        }

        mHandler = new MyHandler(BackgroundThread.getHandler().getLooper());
    }

    @Override
    public void onStart() {
        if( DEBUG ) {
            Slog.i(TAG,"onStart()");
        }
        publishLocalService(BaikalService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if( DEBUG ) {
            Slog.i(TAG,"onBootPhase(" + phase + ")");
        }

        if (phase == PHASE_BOOT_COMPLETED) {

            mConstants = new Constants(mHandler, getContext().getContentResolver());

            // get notified of phone state changes
            TelephonyManager telephonyManager =
                    (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            telephonyManager.listen(mPhoneStateListener, 0xFFFFFFF);

            mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
            mCameraManager.registerTorchCallback(new TorchModeCallback(), mHandler);


            final PackageManager pm = getContext().getPackageManager();

            try {
                ApplicationInfo ai = pm.getApplicationInfo("com.google.android.gms",
                       PackageManager.MATCH_ALL);
                if( ai != null ) {
                    setGmsUid(ai.uid);
                    Slog.i(TAG,"onBootPhase(" + phase + "): Google Play Services uid=" + ai.uid);
                }
            } catch(Exception e) {
                Slog.i(TAG,"onBootPhase(" + phase + "): Google Play Services not found on this device.");
            }
        }
    }

    public void setAlarmManagerService(AlarmManagerService service) {
        synchronized (this) {
            mAlarmManagerService = service;
        }
    }

    public void setPowerManagerService(PowerManagerService service) {
        synchronized (this) {
            mPowerManagerService = service;
        }
    }

    public void setActivityManagerService(ActivityManagerService service, AppOpsService appOps) {
        synchronized (this) {
            mActivityManagerService = service;
            mAppOpsService = appOps;
        }
    }

    public void setDeviceIdleController(DeviceIdleController service) {
        synchronized (this) {
            mDeviceIdleController = service;
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        if( DEBUG ) {
            Slog.i(TAG,"onSwitchUser()");
        }
    }

    @Override
    public void onUnlockUser(int userHandle) {
        if( DEBUG ) {
            Slog.i(TAG,"onUnlockUser()");
        }
    }

    final class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_DEVICE_IDLE_CHANGED:
                onDeviceIdleModeChanged();
                break;
                case MESSAGE_LIGHT_DEVICE_IDLE_CHANGED:
                onLightDeviceIdleModeChanged();
                break;
            }
        }
    }

    private final class Constants extends ContentObserver {

        private final ContentResolver mResolver;

        public Constants(Handler handler, ContentResolver resolver) {
            super(handler);
            mResolver = resolver;

            try {
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.TORCH_LONG_PRESS_POWER_GESTURE), false, this,
                    UserHandle.USER_ALL);

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.TORCH_ON_INCOMING_CALL), false, this,
                    UserHandle.USER_ALL);

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.TORCH_ON_NOTIFICATION), false, this,
                    UserHandle.USER_ALL);

            resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.POWERSAVE_THROTTLE_ALARMS_ENABLED),
                    false, this);

            resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.DEVICE_IDLE_AGGRESSIVE_ENABLED),
                    false, this);

            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWERSAVE_WL_BLOCK_ENABLED), false, this);

            } catch( Exception e ) {
            }

            //mResolver.registerContentObserver(
            //        Settings.Global.getUriFor(Settings.Global.DEVICE_IDLE_AGGRESSIVE),
            //        false, this);

            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (BaikalService.this) {
                updateConstantsLocked();
            }
        }

        public void updateConstantsLocked() {
            try {
                mTorchLongPressPowerEnabled = Settings.System.getIntForUser(
                        mResolver, Settings.System.TORCH_LONG_PRESS_POWER_GESTURE, 0,
                        UserHandle.USER_CURRENT) == 1;


                mTorchIncomingCall = Settings.System.getIntForUser(
                        mResolver, Settings.System.TORCH_ON_INCOMING_CALL, 0,
                        UserHandle.USER_CURRENT) == 1;


                mTorchNotification = Settings.System.getIntForUser(
                        mResolver, Settings.System.TORCH_ON_NOTIFICATION, 0,
                        UserHandle.USER_CURRENT) == 1;


                mIdleAggressive = Settings.Global.getInt(mResolver,
                        Settings.Global.DEVICE_IDLE_AGGRESSIVE_ENABLED) == 1;

                mThrottleAlarms = Settings.Global.getInt(
                        mResolver, Settings.Global.POWERSAVE_THROTTLE_ALARMS_ENABLED, 
                        0) == 1;


                mWlBlockEnabled = Settings.System.getInt(
                        mResolver, Settings.Global.POWERSAVE_WL_BLOCK_ENABLED, 0) == 1;

                    //mParser.setString(Settings.Global.getString(mResolver,
                    //        Settings.Global.DEVICE_IDLE_CONSTANTS));

            } catch (Exception e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                Slog.e(TAG, "Bad BaikalService settings", e);
            }

            Slog.d(TAG, "updateConstantsLocked: mTorchLongPressPowerEnabled=" + mTorchLongPressPowerEnabled +
                        "mTorchIncomingCall=" + mTorchIncomingCall +
                        "mTorchNotification=" + mTorchNotification +
                        "mIdleAggressive=" + mIdleAggressive +
                        "mThrottleAlarms=" + mThrottleAlarms);

        }

    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            //if (!mHasTelephony) return;
            //final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            //mAirplaneState = inAirplaneMode ? ToggleAction.State.On : ToggleAction.State.Off;
            //mAirplaneModeOn.updateState(mAirplaneState);
            //mAdapter.notifyDataSetChanged();
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onServiceStateChanged(" + serviceState + ")");
            }

        }

        /**
         * Callback invoked when network signal strength changes.
         *
         * @see ServiceState#STATE_EMERGENCY_ONLY
         * @see ServiceState#STATE_IN_SERVICE
         * @see ServiceState#STATE_OUT_OF_SERVICE
         * @see ServiceState#STATE_POWER_OFF
         * @deprecated Use {@link #onSignalStrengthsChanged(SignalStrength)}
         */
        @Override
        public void onSignalStrengthChanged(int asu) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onSignalStrengthChanged(" + asu + ")");
            }

            // default implementation empty
        }

        /**
         * Callback invoked when the message-waiting indicator changes.
         */
        @Override
        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onMessageWaitingIndicatorChanged(" + mwi + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when the call-forwarding indicator changes.
         */
        @Override
        public void onCallForwardingIndicatorChanged(boolean cfi) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onCallForwardingIndicatorChanged(" + cfi + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when device cell location changes.
         */
        @Override
        public void onCellLocationChanged(CellLocation location) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onCellLocationChanged(" + location + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when device call state changes.
         * @param state call state
         * @param incomingNumber incoming call phone number. If application does not have
         * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} permission, an empty
         * string will be passed as an argument.
         *
         * @see TelephonyManager#CALL_STATE_IDLE
         * @see TelephonyManager#CALL_STATE_RINGING
         * @see TelephonyManager#CALL_STATE_OFFHOOK
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onCallStateChanged(" + state + "," + incomingNumber + ")");
            }

            synchronized (this) {
                onCallStateChangedLocked(state,incomingNumber);
            }

        // default implementation empty
        }

        /**
         * Callback invoked when connection state changes.
         *
         * @see TelephonyManager#DATA_DISCONNECTED
         * @see TelephonyManager#DATA_CONNECTING
         * @see TelephonyManager#DATA_CONNECTED
         * @see TelephonyManager#DATA_SUSPENDED
         */
        @Override
        public void onDataConnectionStateChanged(int state) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onDataConnectionStateChanged(" + state + ")");
            }
            // default implementation empty
        }

        /**
         * same as above, but with the network type.  Both called.
         */
        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onDataConnectionStateChanged(" + state + "," + networkType + ")");
            }
        }

        /**
         * Callback invoked when data activity state changes.
         *
         * @see TelephonyManager#DATA_ACTIVITY_NONE
         * @see TelephonyManager#DATA_ACTIVITY_IN
         * @see TelephonyManager#DATA_ACTIVITY_OUT
         * @see TelephonyManager#DATA_ACTIVITY_INOUT
         * @see TelephonyManager#DATA_ACTIVITY_DORMANT
         */
        @Override
        public void onDataActivity(int direction) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onDataActivity(" + direction + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when network signal strengths changes.
         *
         * @see ServiceState#STATE_EMERGENCY_ONLY
         * @see ServiceState#STATE_IN_SERVICE
         * @see ServiceState#STATE_OUT_OF_SERVICE
         * @see ServiceState#STATE_POWER_OFF
         */
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onSignalStrengthsChanged(" + signalStrength + ")");
            }
            // default implementation empty
        }


        /**
         * The Over The Air Service Provisioning (OTASP) has changed. Requires
         * the READ_PHONE_STATE permission.
         * @param otaspMode is integer <code>OTASP_UNKNOWN=1<code>
         *   means the value is currently unknown and the system should wait until
         *   <code>OTASP_NEEDED=2<code> or <code>OTASP_NOT_NEEDED=3<code> is received before
         *   making the decision to perform OTASP or not.
         *
         * @hide
         */
        @Override
        public void onOtaspChanged(int otaspMode) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onOtaspChanged(" + otaspMode + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when a observed cell info has changed,
         * or new cells have been added or removed.
         * @param cellInfo is the list of currently visible cells.
         */
        @Override
        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onCellInfoChanged(" + cellInfo + ")");
            }
        }

        /**
         * Callback invoked when precise device call state changes.
         *
         * @hide
         */
        @Override
        public void onPreciseCallStateChanged(PreciseCallState callState) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onPreciseCallStateChanged(" + callState + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when data connection state changes with precise information.
         *
         * @hide
         */
        @Override
        public void onPreciseDataConnectionStateChanged(
                PreciseDataConnectionState dataConnectionState) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onPreciseDataConnectionStateChanged(" + dataConnectionState + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when data connection state changes with precise information.
         *
         * @hide
         */
        @Override
        public void onDataConnectionRealTimeInfoChanged(
                DataConnectionRealTimeInfo dcRtInfo) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onDataConnectionRealTimeInfoChanged(" + dcRtInfo + ")");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when the service state of LTE network
         * related to the VoLTE service has changed.
         * @param stateInfo is the current LTE network information
         * @hide
         */
        @Override
        public void onVoLteServiceStateChanged(VoLteServiceState stateInfo) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onVoLteServiceStateChanged(" + stateInfo + ")");
            }
        }

        /**
         * Callback invoked when the SIM voice activation state has changed
         * @param state is the current SIM voice activation state
         * @hide
         */
        @Override
        public void onVoiceActivationStateChanged(int state) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onVoiceActivationStateChanged(" + state + ")");
            }
   
        }

        /**
         * Callback invoked when the SIM data activation state has changed
         * @param state is the current SIM data activation state
         * @hide
         */
        @Override
        public void onDataActivationStateChanged(int state) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onDataActivationStateChanged(" + state + ")");
            }
    
        }

        /**
         * Callback invoked when OEM hook raw event is received. Requires
         * the READ_PRIVILEGED_PHONE_STATE permission.
         * @param rawData is the byte array of the OEM hook raw data.
         * @hide
         */
        @Override
        public void onOemHookRawEvent(byte[] rawData) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onOemHookRawEvent( raw )");
            }
            // default implementation empty
        }

        /**
         * Callback invoked when telephony has received notice from a carrier
         * app that a network action that could result in connectivity loss
         * has been requested by an app using
         * {@link android.telephony.TelephonyManager#notifyCarrierNetworkChange(boolean)}
         *
         * @param active Whether the carrier network change is or shortly
         *               will be active. This value is true to indicate
         *               showing alternative UI and false to stop.
         *
         * @hide
         */
        @Override
        public void onCarrierNetworkChange(boolean active) {
            if( DEBUG ) {
                Slog.i(TAG,"PhoneStateListener: onCarrierNetworkChange(" + active + ")");
            }
            // default implementation empty
        }

    };



    private void onCallStateChangedLocked(int state, String phoneNumber) {
        if( state == 1 ) {
            mActiveIncomingCall = true;
        } else {
            mActiveIncomingCall = false;
        }
        if( mActiveIncomingCall && mTorchIncomingCall && mTorchThread == null ) {
            mTorchThread = new Thread(() -> {
                while( true ) {
                    try {
                        toggleTorch(true);
                        Thread.sleep(30);
                        toggleTorch(false);
                        Thread.sleep(50);

                        toggleTorch(true);
                        Thread.sleep(30);
                        toggleTorch(false);
                        Thread.sleep(50);
                    
                        toggleTorch(true);
                        Thread.sleep(30);
                        toggleTorch(false);
                        Thread.sleep(1500);
                    } catch( Exception e ) {
                    }

                    synchronized(BaikalService.this) {
                        if( !mActiveIncomingCall ) {
                            mTorchThread = null;
                            return;
                        }
                    }
                }
            }); 
            mTorchThread.start();
        }
    }


    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                //mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            //}

            if( DEBUG ) {
                Slog.i(TAG,"mRingerModeReceiver: onReceive(" + intent + ")");
            }

        }
    };


    public boolean isDeviceIdleMode() {
        synchronized(this) {
            return isDeviceIdleModeLocked();
        }
    }

    public boolean isAggressiveDeviceIdleMode() {
        synchronized(this) {
            return isAggressiveDeviceIdleModeLocked();
        }
    }

    public boolean isLightDeviceIdleMode() {
        synchronized(this) {
            return isLightDeviceIdleModeLocked();
        }
    }

    public void setDeviceIdleMode(boolean mode) {
        synchronized(this) {
            setDeviceIdleModeLocked(mode);
        }
    }

    public void setLightDeviceIdleMode(boolean mode) {
        synchronized(this) {
            setLightDeviceIdleModeLocked(mode);
        }
    }

    public boolean isNetworkAllowedWhileIdle() {
        return false;
    }

    public String lastWakeupReason() {
        synchronized(this) {
            return lastWakeupReasonLocked();
        }
    }

    public void setLastWakeupReason(String reason) {
        synchronized(this) {
            setLastWakeupReasonLocked(reason);
        }
    }

    public void setDeviceIdleWhitelist(int[] appids) {
        synchronized (this) {
            mDeviceIdleWhitelist = appids;
        }
    }

    public void setDeviceIdleTempWhitelist(int[] appids) {
        synchronized (this) {
            mDeviceIdleTempWhitelist = appids;
        }
    }

    public boolean [] setWakeLockDisabledState(WakeLock wakeLock) {
        boolean [] retval = new boolean[2];
        retval[0] = false;
        retval[1] = false;

        synchronized (this) {
            if( !mWlBlockEnabled ) {
                retval[0] = true;
                if( wakeLock.mDisabled ) {
                    wakeLock.mDisabled = false;
                    retval[1] = true;
                }
                return retval;
            }
        }
        return retval;
    }

    public boolean throttleAlarms() {
        synchronized (this) {
            return mThrottleAlarms;
        }
    }


    public boolean isAggressiveIdle() {
        synchronized (this) {
            return mIdleAggressive;
        }
    }

    public boolean processAlarm(AlarmManagerService.Alarm a, AlarmManagerService.Alarm pendingUntil) {

        if ( a == pendingUntil ) {
            if( DEBUG ) {
                Slog.i(TAG,"DeviceIdleAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
            }
            return false;
        }

        a.wakeup = a.type == AlarmManager.ELAPSED_REALTIME_WAKEUP
                || a.type == AlarmManager.RTC_WAKEUP;

        if ( a.uid < Process.FIRST_APPLICATION_UID && 
            ((a.flags&(AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | AlarmManager.FLAG_WAKE_FROM_IDLE)) != 0 
            || a.wakeup)) {

            if( restrictSystemIdleAlarm(a) ) {
                if( DEBUG ) {
                    Slog.i(TAG,"SystemAlarm: throttle IDLE alarm:" + a.statsTag + ":" + a.toStringLong());
                }
                return true;
            }
            
            if( DEBUG ) {
                Slog.i(TAG,"SystemAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
            }
            return false;    
        }

        if( ((a.flags&(AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | AlarmManager.FLAG_WAKE_FROM_IDLE)) != 0
            || a.wakeup) ) {

            if( restrictAppIdleAlarm(a) ) {
                if( DEBUG ) {
                    Slog.i(TAG,"AppAlarm: throttle IDLE alarm:" + a.statsTag + ":" + a.toStringLong());
                }
                return true;
            }

            if( DEBUG ) {
                Slog.i(TAG,"AppAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
            }
            return false;
        }

        if( ((a.flags&(AlarmManager.FLAG_ALLOW_WHILE_IDLE)) != 0 || a.wakeup) && a.alarmClock == null ) {
            synchronized (this) {
                if( mThrottleAlarms ) {
                    if( DEBUG ) {
                        Slog.i(TAG,"AppAlarm: throttle IDLE alarm:" + a.statsTag + ":" + a.toStringLong());
                    }
                    a.flags &= ~(AlarmManager.FLAG_WAKE_FROM_IDLE 
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED);
                    a.wakeup = false;
                    return true;
                }
            }
        }

        return false;
    }

    private boolean restrictAppIdleAlarm(AlarmManagerService.Alarm a) {
        synchronized (this) {
            if( !mThrottleAlarms ) {
                return false;
            }
        }

        boolean block = false;

        if( a.alarmClock != null ) {

        } else if( a.packageName.startsWith("com.google.android.gms") ) {
            block = true;
        } else if( a.packageName.startsWith("com.google.android.wearable.app") ) {
            if( a.statsTag.contains("com.google.android.clockwork.TIME_SYNC") || 
                a.statsTag.contains("com.google.android.clockwork.TIME_ZONE_SYNC") ||
                a.statsTag.contains("com.google.android.clockwork.calendar.action.REFRESH")) {
                block = true;
            }
        } else if( ((a.flags&(AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | AlarmManager.FLAG_WAKE_FROM_IDLE)) == 0) &&
            (Arrays.binarySearch(mDeviceIdleWhitelist, a.uid) < 0) ) {
            block = true;
        }

        if( block ) {
            a.flags &= ~(AlarmManager.FLAG_WAKE_FROM_IDLE 
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED);
            a.wakeup = false;
            return true;
        }
        return false;
    }


    private boolean restrictSystemIdleAlarm(AlarmManagerService.Alarm a) {
        synchronized (this) {
            if( !mThrottleAlarms ) {
                return false;
            }
        }

        boolean block = false;

        if( a.statsTag.contains("NETWORK_LINGER_COMPLETE") ||
            a.statsTag.contains("*sync") ||
            a.statsTag.contains("*job") || 
            a.statsTag.contains("APPWIDGET_UPDATE") ||
            a.statsTag.contains("com.android.server.NetworkTimeUpdateService.action.POLL") ||
            a.statsTag.contains("WifiConnectivityManager Restart Scan") ) {

            block = true;
        } 

        if( block ) {
            a.flags &= ~(AlarmManager.FLAG_WAKE_FROM_IDLE 
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED);
            a.wakeup = false;
            return true;
        }

        return false;
    }

    public boolean adjustAlarm(AlarmManagerService.Alarm a) {
        if( !a.wakeup ) return false;
        synchronized (this) {
            if( !mThrottleAlarms ) {
                return false;
            }
        }

        if( a.statsTag.contains("com.qualcomm.qti.biometrics.fingerprint.service") ) {
            if( mQtiBiometricsInitialized ) {
                a.when += 55*60*1000;
                long whenElapsed = AlarmManagerService.convertToElapsed(a.when, a.type);
                a.whenElapsed = whenElapsed;
                a.maxWhenElapsed = whenElapsed;
                a.origWhen = a.when;
                Slog.i(TAG,"AdjustAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
                return true;
            } else {
                mQtiBiometricsInitialized = true;
            }
        }
        if( a.statsTag.contains("WifiConnectivityManager Schedule Periodic Scan Timer") ) {
            final long now = SystemClock.elapsedRealtime();
            if( (a.when - now)  < 15*60*1000 ) {
                a.when = a.whenElapsed = a.maxWhenElapsed = a.origWhen = now + 15*60*1000;
            } 
            Slog.i(TAG,"AdjustAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
            return true;
        }
        return false;
    }

    public boolean isWakelockWhitelisted(PowerManagerService.WakeLock wakelock, int callingUid, String callingPackageName) {
        return false;
    }

    public boolean isWakelockBlacklisted(PowerManagerService.WakeLock wakelock, int callingUid, String callingPackageName) {
        return false;
    }

    public boolean isAlarmWhitelisted(String tag, int callingUid, String callingPackageName) {
        return false;
    }

    public boolean isAlarmBlacklisted(String tag, int callingUid, String callingPackageName) {
        return false;
    }

    
    public boolean isBroadcastFilterWhitelisted(BroadcastRecord r, BroadcastFilter filter) {
        //Slog.i(TAG,"isBroadcastFilterWhitelisted: from " + r.callerPackage + "/" + r.callingUid + "/" + r.callingPid + 
        //" to " + filter.receiverList.app + "/" + filter.receiverList.uid + "/" + filter.receiverList.pid + ">" + r.intent);
        return false;
    }

    public boolean isBroadcastFilterBlacklisted(BroadcastRecord r,  BroadcastFilter filter) {
        //Slog.i(TAG,"isBroadcastFilterBlacklisted: from " + r.callerPackage + "/" + r.callingUid + "/" + r.callingPid + 
        //" to " + filter.receiverList.app + "/" + filter.receiverList.uid + "/" + filter.receiverList.pid + ">" + r.intent);
        return false;
    }

    public boolean isBroadcastWhitelisted(BroadcastRecord r, ResolveInfo info) {
        //Slog.i(TAG,"isBroadcastWhitelisted: from " + r.callerPackage + "/" + r.callingUid + "/" + r.callingPid + " to " + r.intent);
        return false;
    }

    public boolean isBroadcastBlacklisted(BroadcastRecord r, ResolveInfo info) {
        //Slog.i(TAG,"isBroadcastBlacklisted: from " + r.callerPackage + "/" + r.callingUid + "/" + r.callingPid + " to " + r.intent);
        if( !mDeviceIdleMode ) {
            return false; 
        }
        if( info == null || info.activityInfo == null ) {
             Slog.i(TAG,"isBroadcastBlacklisted: ResolveInfo info NULL for " + r.callerPackage + "/" + r.callingUid + "/" + r.callingPid + " to " + r.intent);
             return false;
        }
        if( isAppRestricted(info.activityInfo.applicationInfo.uid, info.activityInfo.applicationInfo.packageName) ) return true;

        return false;
    }

    public boolean isServiceWhitelisted(ServiceRecord service, int callingUid, int callingPid, String callingPackageName, boolean isStarting) {
        //Slog.i(TAG,"isServiceWhitelisted: from " + callingPackageName + "/" + callingUid + "/" + callingPid + " to " + service.name.getClassName());
        if( !isGmsUid(service.appInfo.uid) ) return false;
        if( !mDeviceIdleMode ) {
            //Slog.i(TAG,"GmsService: unrestricted (not idle):" + service.name.getClassName());
            return true;
        }

        for( String srv:mGoogleServicesIdleBlackListed ) {
            if( service.name.getClassName().equals(srv) ) {
                return false;
            } 
        }
        //Slog.i(TAG,"GmsService: unrestricted (wl):" + service.name.getClassName());
        return true;
    }
    
    public boolean isServiceBlacklisted(ServiceRecord service, int callingUid, int callingPid, String callingPackageName, boolean isStarting) {
        //Slog.i(TAG,"isServiceBlacklisted: from " + callingPackageName + "/" + callingUid + "/" + callingPid + " to " + service.name.getClassName());
        if( !mDeviceIdleMode ) {
            //Slog.i(TAG,"GmsService: unblocked (not idle):" + service.name.getClassName());
            return false; 
        }

        if( isAppRestricted(service.appInfo.uid, service.appInfo.packageName) ) return true;
        if( !isGmsUid(service.appInfo.uid) ) return false;
        for( String srv:mGoogleServicesIdleBlackListed ) {
            if( service.name.getClassName().equals(srv) ) {
                //Slog.i(TAG,"GmsService: restricted:" + service.name.getClassName());
                return true;
            } 
        }
        return false;
    }


    public boolean killByOOM(ProcessRecord app, ProcessRecord top_app, ProcessRecord home_app, ProcessRecord prev_app ) {
        if( app.info.uid < Process.FIRST_APPLICATION_UID  ) return false;
        if( app == top_app ) return false;
        if( app == home_app ) return false;
        if( isGmsUid(app.info.uid) ) return false;
        if( !mIdleAggressive ) return false;
        if( !mDeviceIdleMode ) return false;

        if( !isAppRestricted(app.info.uid, app.info.packageName) ) return false;

        switch (app.curProcState) {
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
            case ActivityManager.PROCESS_STATE_BACKUP:
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
            case ActivityManager.PROCESS_STATE_RECEIVER:
            case ActivityManager.PROCESS_STATE_SERVICE:
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                try {
                    for (int is = app.services.size()-1;is >= 0; is--) {
                        ServiceRecord s = app.services.valueAt(is);
                        s.stopIfKilled = true;
                    } 
                } catch (Exception e) {
                }
                return true;
        }

        return true;
    }

    public void noteRestrictionStatistics(boolean allowed, String type, String callerName, int callerUid, int callerPid, 
                            String calledName, int calledUid, int calledPid, String Tag) {
        final String recordName =  mDeviceIdleMode + "/" + type + "/" + callerName  + "->" + calledName + "/" + Tag;

        RestrictionStatistics stat = mRestrictionStatistics.get(recordName);
        if( stat == null ) {
            stat = new RestrictionStatistics(type, callerName, callerUid, callerPid,
                                        calledName, calledUid, calledPid, Tag);
            mRestrictionStatistics.put(recordName, stat);
        }
        if( allowed ) stat.allowed++;
        else stat.blocked++;
        stat.deviceIdleMode = mDeviceIdleMode;

        Slog.i(TAG,"noteRestrictionStatistics:" + allowed + ":" + recordName);
    }

    public void logRestrictionStatistics() {
        for(int i=0;i<mRestrictionStatistics.size();i++) {
            RestrictionStatistics stat = mRestrictionStatistics.valueAt(i);
            if( stat.allowed > 0 ) {
                Slog.i(TAG,"RestrictionStatistics: allowed :" + stat.getLog() + "; allowed=" + stat.allowed);
            }
        }

        for(int i=0;i<mRestrictionStatistics.size();i++) {
            RestrictionStatistics stat = mRestrictionStatistics.valueAt(i);
            if( stat.blocked > 0 ) {
                Slog.i(TAG,"RestrictionStatistics: blocked :" + stat.getLog() + "; blocked=" + stat.blocked);
            }
        }
    }

    private boolean isAppRestricted(int uid, String packageName) {
        if( mAppOpsService == null ) return false;
        final int mode = mAppOpsService.checkOperation(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                uid, packageName);
        return mode != AppOpsManager.MODE_ALLOWED;
    }


    public boolean isDeviceIdleModeLocked() {
        return mDeviceIdleMode;
    }

    public boolean isAggressiveDeviceIdleModeLocked() {
        return mDeviceIdleMode && mIdleAggressive;
    }

    public boolean isLightDeviceIdleModeLocked() {
        return mLightDeviceIdleMode;
    }

    public void setDeviceIdleModeLocked(boolean mode) {
        if( mDeviceIdleMode != mode ) {
            Slog.i(TAG,"setDeviceIdleModeLocked: " + mode);
            mDeviceIdleMode = mode;
            Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_IDLE_CHANGED);
            mHandler.sendMessage(msg);
            logRestrictionStatistics();
        }
    }

    public void setLightDeviceIdleModeLocked(boolean mode) {
        if( mLightDeviceIdleMode != mode ) {
            Slog.i(TAG,"setLightDeviceIdleModeLocked: " + mode);
            mLightDeviceIdleMode = mode;
            Message msg = mHandler.obtainMessage(MESSAGE_LIGHT_DEVICE_IDLE_CHANGED);
            mHandler.sendMessage(msg);
        }
    }

    private void onDeviceIdleModeChanged() {
        mActivityManagerService.setDeviceIdleMode(mDeviceIdleMode);
    }

    private void onLightDeviceIdleModeChanged() {
    }

    public String lastWakeupReasonLocked() {
        return mLastWakeupReason;
    }

    public void setLastWakeupReasonLocked(String reason) {
        mLastWakeupReason = reason;
    }

    private void toggleTorch(boolean on) {
        //cancelTorchOff();
        final boolean origEnabled = mTorchEnabled;
        try {
            final String rearFlashCameraId = getRearFlashCameraId();
            if (rearFlashCameraId != null) {
                mCameraManager.setTorchMode(rearFlashCameraId, on);
                mTorchEnabled = on;
            }
        } catch (CameraAccessException e) {
            // Ignore
        }
    }


    private String getRearFlashCameraId() throws CameraAccessException {
        if (mRearFlashCameraId != null) return mRearFlashCameraId;
        for (final String id : mCameraManager.getCameraIdList()) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            int lensDirection = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable && lensDirection == CameraCharacteristics.LENS_FACING_BACK) {
                mRearFlashCameraId = id;
            }
        }
        return mRearFlashCameraId;
    }
    private class TorchModeCallback extends CameraManager.TorchCallback {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            //if (!cameraId.equals(mRearFlashCameraId)) return;
            //mTorchEnabled = enabled;
            //if (!mTorchEnabled) {
            //    cancelTorchOff();
            //}
        }
        @Override
        public void onTorchModeUnavailable(String cameraId) {
            //if (!cameraId.equals(mRearFlashCameraId)) return;
            //mTorchEnabled = false;
            //cancelTorchOff();
        }
    }

    private static Object mStaticMembersLock = new Object();

    private static int mGmsUid = -1;
    static void setGmsUid(int uid) {
        synchronized(mStaticMembersLock) {
            mGmsUid = uid;
        }
    }


    public static boolean isGmsUid(int uid) {
        synchronized(mStaticMembersLock) {
            return mGmsUid == uid;
        }
    }

    public static boolean isGmsAppid(int appid) {
        synchronized(mStaticMembersLock) {
            return UserHandle.getAppId(mGmsUid) == appid;
        }
    }

    public static int gmsAppid() {
        synchronized(mStaticMembersLock) {
            return UserHandle.getAppId(mGmsUid);
        }
    }

    public static int gmsUid() {
        synchronized(mStaticMembersLock) {
            return mGmsUid;
        }
    }

    final class RestrictionStatistics {
        public String type;
        public String callerName;
        public int callerUid;
        public int callerPid;
        public String calledName;
        public int calledUid;
        public int calledPid;
        public String RecordName;
        public String Tag;
        public int allowed;
        public int blocked;
        public boolean deviceIdleMode;

        public RestrictionStatistics(String _type, String _callerName, int _callerUid, int _callerPid, 
            String _calledName, int _calledUid, int _calledPid, String _Tag) {
            type = _type;
            callerName = _callerName;
            callerUid = _callerUid;
            callerPid = _callerPid;
            calledName = _calledName;
            calledUid = _calledUid;
            calledPid = _calledPid;
            Tag = _Tag;
        }

        public String getLog() {
            String log = deviceIdleMode + "/" + type + "/" + callerName + "->" + calledName + "/" + Tag; 
            return log;
        }
    }
}
