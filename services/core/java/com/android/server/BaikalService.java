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

package com.android.server;

import android.app.Service;
import android.app.AlarmManager;
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
import android.net.NetworkInfo;
import android.net.Uri;
import com.android.internal.os.BackgroundThread;
import com.android.server.power.PowerManagerService;
import com.android.server.power.PowerManagerService.WakeLock;
import com.android.server.am.ServiceRecord;
import com.android.server.am.ActivityManagerService;
import com.android.server.DeviceIdleController;
import com.android.server.AlarmManagerService;

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


public class BaikalService extends SystemService {

    private static final String TAG = "BaikalService";

    private static final boolean DEBUG = true;


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

    private boolean mThrottleAlarms;
    private boolean mIdleAggressive;

    Thread mTorchThread = null;

    private final Context mContext;
    private Constants mConstants;
    final MyHandler mHandler;

    private CameraManager mCameraManager;
    private String mRearFlashCameraId;


    AlarmManagerService mAlarmManagerService;
    DeviceIdleController mDeviceIdleController;
    PowerManagerService mPowerManagerService;
    ActivityManagerService mActivityManagerService;

    private boolean mNetworkAllowedWhileIdle;

    // Set of app ids that we will always respect the wake locks for.
    int[] mDeviceIdleWhitelist = new int[0];

    // Set of app ids that are temporarily allowed to acquire wakelocks due to high-pri message
    int[] mDeviceIdleTempWhitelist = new int[0];


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

    public void setActivityManagerService(ActivityManagerService service) {
        synchronized (this) {
            mActivityManagerService = service;
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

    public boolean setWakeLockDisabledState(WakeLock wakeLock) {
        return false;
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

        if( a.packageName.startsWith("com.google.android.gms") ) {
            block = true;
        } else if( a.packageName.startsWith("com.google.android.wearable.app") ) {
            if( a.statsTag.contains("com.google.android.clockwork.TIME_SYNC") || 
                a.statsTag.contains("com.google.android.clockwork.TIME_ZONE_SYNC") ||
                a.statsTag.contains("com.google.android.clockwork.calendar.action.REFRESH")) {
                block = true;
            }
        }


        if( (a.flags&(AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | AlarmManager.FLAG_WAKE_FROM_IDLE)) == 0 ) {
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
            a.when += 55*60*1000;
            long whenElapsed = AlarmManagerService.convertToElapsed(a.when, a.type);
            a.whenElapsed = whenElapsed;
            a.maxWhenElapsed = whenElapsed;
            a.origWhen = a.when;
            Slog.i(TAG,"AppAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
            return true;
        }
        if( a.statsTag.contains("WifiConnectivityManager Schedule Periodic Scan Timer") ) {
            final long now = SystemClock.elapsedRealtime();
            if( (a.when - now)  < 15*60*1000 ) {
                a.when = a.whenElapsed = a.maxWhenElapsed = a.origWhen = now + 15*60*1000;
            } 
            Slog.i(TAG,"AppAlarm: unrestricted:" + a.statsTag + ":" + a.toStringLong());
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

    public boolean isIntentWhitelisted(Intent intent, int callingUid, int callingPid, String callingPackageName) {
        return false;
    }

    public boolean isIntentBlacklisted(Intent intent, int callingUid, int callingPid, String callingPackageName) {
        return false;
    }

    public boolean isServiceWhitelisted(ServiceRecord service, int callingUid, int callingPid, String callingPackageName, boolean isStarting) {
        return false;
    }
    
    public boolean isServiceBlacklisted(ServiceRecord service, int callingUid, int callingPid, String callingPackageName, boolean isStarting) {
        return false;
    }


    public boolean isDeviceIdleModeLocked() {
        return mDeviceIdleMode;
    }

    public boolean isLightDeviceIdleModeLocked() {
        return mLightDeviceIdleMode;
    }

    public void setDeviceIdleModeLocked(boolean mode) {
        mDeviceIdleMode = mode;
    }

    public void setLightDeviceIdleModeLocked(boolean mode) {
        mLightDeviceIdleMode = mode;
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


}
