package com.aryan.wearmodesync;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.apache.commons.lang3.SerializationUtils;

import com.aryan.wearmodesync.shared.PhoneSignal;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH);

            byte[] data = messageEvent.getData();
            PhoneSignal phoneSignal = SerializationUtils.deserialize(data);
            if (phoneSignal == null) return;

            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int currentDndState = mNotificationManager.getCurrentInterruptionFilter();

            // 1. DND sync from phone
            if (phoneSignal.dndState != null && phoneSignal.dndState != currentDndState) {
                Log.d(TAG, "dndStatePhone != currentDndState: " + phoneSignal.dndState + " != " + currentDndState);
                DNDNotificationService.lastReceivedFilter = phoneSignal.dndState;

                changeDndSetting(mNotificationManager, phoneSignal.dndState);

                if (phoneSignal.vibratePref) {
                    vibrate();
                }
            }

            // 2. Sound mode sync from phone
            if (phoneSignal.ringerMode != null) {
                DNDNotificationService.lastReceivedRingerMode = phoneSignal.ringerMode;
                changeRingerSetting(phoneSignal.ringerMode, phoneSignal.vibratePref);
            }

            // 3. Bedtime sync from phone
            String settingBedtimeStr = "setting_bedtime_mode_running_state";
            int currentBedtimeState = Settings.Global.getInt(
                    getApplicationContext().getContentResolver(), settingBedtimeStr, -1);

            if (currentBedtimeState != -1) {
                Log.d(TAG, "watch is the galaxy watch");
            } else {
                Log.d(TAG, "watch is not the galaxy watch");
                settingBedtimeStr = "bedtime_mode";
                currentBedtimeState = Settings.Global.getInt(
                        getApplicationContext().getContentResolver(), settingBedtimeStr, -1);
            }

            Log.d(TAG, "currentBedtimeState: " + currentBedtimeState);

            if (phoneSignal.bedtimeState != null && phoneSignal.bedtimeState != currentBedtimeState) {
                Log.d(TAG, "bedtimeStatePhone != currentBedtimeState: " + phoneSignal.bedtimeState + " != " + currentBedtimeState);

                int dndState = phoneSignal.bedtimeState == 1 ? 2 : 1;
                DNDNotificationService.lastReceivedFilter = dndState;
                changeDndSetting(mNotificationManager, dndState);

                boolean bedtimeModeSuccess = changeBedtimeSetting(settingBedtimeStr, phoneSignal.bedtimeState);
                if (bedtimeModeSuccess) {
                    Log.d(TAG, "Bedtime mode value toggled");
                } else {
                    Log.d(TAG, "Bedtime mode toggle failed");
                }

                if (phoneSignal.powersavePref) {
                    boolean powerModeSuccess = changePowerModeSetting(phoneSignal.bedtimeState);
                    if (powerModeSuccess) {
                        Log.d(TAG, "Power Saver mode toggled");
                    } else {
                        Log.d(TAG, "Power Saver mode toggle failed");
                    }
                }

                if (phoneSignal.vibratePref) {
                    vibrate();
                }
            }

        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    private void changeDndSetting(NotificationManager mNotificationManager, int newSetting) {
        if (mNotificationManager.isNotificationPolicyAccessGranted()) {
            mNotificationManager.setInterruptionFilter(newSetting);
            Log.d(TAG, "DND set to " + newSetting);
        } else {
            Log.d(TAG, "attempting to set DND but access not granted");
        }
    }

    private void changeRingerSetting(int newSetting, boolean vibratePref) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        int currentRinger = audioManager.getRingerMode();
        Log.d(TAG, "current watch ringerMode: " + currentRinger + ", target: " + newSetting);

        if (currentRinger != newSetting) {
            DNDNotificationService.lastReceivedRingerMode = newSetting;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int initialFilter = nm != null ? nm.getCurrentInterruptionFilter() : -1;

            try {
                audioManager.setRingerMode(newSetting);
                Log.d(TAG, "Watch ringer mode set to " + newSetting);
            } catch (Exception e) {
                Log.e(TAG, "Error setting ringer mode on watch", e);
            }

            // Keep DND exactly as it was before ringer change
            if (nm != null && nm.isNotificationPolicyAccessGranted() && initialFilter != -1) {
                int postFilter = nm.getCurrentInterruptionFilter();
                if (postFilter != initialFilter) {
                    DNDNotificationService.lastReceivedFilter = initialFilter;
                    nm.setInterruptionFilter(initialFilter);
                    Log.d(TAG, "Restored watch DND filter to " + initialFilter);
                }
            }

            vibrateForRingerMode(newSetting);
        }
    }

    /**
     * Mode-Distinct Haptic Feedback:
     * - Silent: 1 solid, distinct pulse (140ms)
     * - Vibrate: 2 punchy buzzes (180ms - 100ms gap - 180ms)
     * - Ring: 3 energetic escalating pulses (100ms - 100ms - 220ms)
     * Uses USAGE_ALARM to ensure ringer mode / silent state does not drop the haptic feedback.
     */
    private void vibrateForRingerMode(int ringerMode) {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "wearmodesync:ringer_vibrate");
            wakeLock.acquire(1500); // Automatically released after 1.5s
        }

        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;

            VibrationAttributes attrs = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build();

            VibrationEffect effect;
            boolean hasAmp = v.hasAmplitudeControl();

            switch (ringerMode) {
                case AudioManager.RINGER_MODE_SILENT: // 0: Silent -> 1 solid pulse
                    if (hasAmp) {
                        effect = VibrationEffect.createOneShot(140, 220);
                    } else {
                        effect = VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE);
                    }
                    break;

                case AudioManager.RINGER_MODE_VIBRATE: // 1: Vibrate -> 2 punchy buzzes
                    long[] timingsVibrate = new long[]{0, 180, 100, 180};
                    if (hasAmp) {
                        int[] ampsVibrate = new int[]{0, 255, 0, 255};
                        effect = VibrationEffect.createWaveform(timingsVibrate, ampsVibrate, -1);
                    } else {
                        effect = VibrationEffect.createWaveform(timingsVibrate, -1);
                    }
                    break;

                case AudioManager.RINGER_MODE_NORMAL: // 2: Ring -> 3 energetic escalating pulses
                default:
                    long[] timingsNormal = new long[]{0, 100, 70, 100, 70, 220};
                    if (hasAmp) {
                        int[] ampsNormal = new int[]{0, 200, 0, 230, 0, 255};
                        effect = VibrationEffect.createWaveform(timingsNormal, ampsNormal, -1);
                    } else {
                        effect = VibrationEffect.createWaveform(timingsNormal, -1);
                    }
                    break;
            }
            v.vibrate(effect, attrs);
            Log.d(TAG, "Mode-distinct haptic played with USAGE_ALARM for mode: " + ringerMode);
        } catch (Exception e) {
            Log.e(TAG, "Error playing mode haptic", e);
        }
    }

    private boolean changeBedtimeSetting(String settingBedtimeStr, int newSetting) {
        boolean bedtimeModeSuccess = Settings.Global.putInt(
                getApplicationContext().getContentResolver(), settingBedtimeStr, newSetting);
        boolean zenModeSuccess = Settings.Global.putInt(
                getApplicationContext().getContentResolver(), "zen_mode", newSetting);

        return bedtimeModeSuccess && zenModeSuccess;
    }

    private boolean changePowerModeSetting(int newSetting) {
        boolean lowPower = Settings.Global.putInt(
                getApplicationContext().getContentResolver(), "low_power", newSetting);
        boolean restrictedDevicePerformance = Settings.Global.putInt(
                getApplicationContext().getContentResolver(), "restricted_device_performance", newSetting);

        boolean lowPowerBackDataOff = Settings.Global.putInt(
                getApplicationContext().getContentResolver(), "low_power_back_data_off", newSetting);
        boolean smConnectivityDisable = Settings.Secure.putInt(
                getApplicationContext().getContentResolver(), "sm_connectivity_disable", newSetting);

        return lowPower && restrictedDevicePerformance
                && lowPowerBackDataOff && smConnectivityDisable;
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                VibrationAttributes attrs = new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build();
                v.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE), attrs);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in vibrate()", e);
        }
    }
}

