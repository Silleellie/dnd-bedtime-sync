package com.aryan.wearmodesync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import com.aryan.wearmodesync.shared.PhoneSignal;
import com.aryan.wearmodesync.shared.WearSignal;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    private static final String ALERT_CHANNEL_ID = "wearmodesync_battery_alerts_v2";
    private static final int NOTIF_WATCH_BATTERY_ID = 2002;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            byte[] data = messageEvent.getData();
            WearSignal wearSignal = WearSignal.fromByteArray(data);
            if (wearSignal == null) return;

            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // 1. Handle manual sync request from watch
            if (wearSignal.syncRequest) {
                Log.d(TAG, "Watch requested manual sync — replying with current phone modes");
                int currentDnd = mNotificationManager.getCurrentInterruptionFilter();
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int currentRinger = am != null ? am.getRingerMode() : -1;
                PhoneSignal response = new PhoneSignal(currentDnd, currentRinger, prefs);
                DNDNotificationService.sendDNDSync(this, response);
                return;
            }

            // 2. Handle low battery alert from watch (<= 15%)
            if (wearSignal.batteryLowAlert) {
                boolean shouldAlert = prefs.getBoolean("watch_low_battery_key", true);
                if (shouldAlert) {
                    int pct = wearSignal.batteryPct != null ? wearSignal.batteryPct : 15;
                    Log.d(TAG, "Watch battery low: " + pct + "%, posting notification");
                    postWatchLowBatteryNotification(pct);
                }
                return;
            }

            // 1. Handle DND state from watch
            if (wearSignal.dndState != null) {
                int dndStateWear = wearSignal.dndState;
                Log.d(TAG, "dndStateWear: " + dndStateWear);
                int currentDndState = mNotificationManager.getCurrentInterruptionFilter();
                boolean shouldSyncDnd = prefs.getBoolean("watch_dnd_sync_key", true);

                if (currentDndState != dndStateWear && shouldSyncDnd) {
                    if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                        DNDNotificationService.lastReceivedDndFilter = dndStateWear;
                        mNotificationManager.setInterruptionFilter(dndStateWear);
                        Log.d(TAG, "Phone DND set to " + dndStateWear);
                        MainActivity.notifyModeChanged(this);
                    } else {
                        Log.w(TAG, "Attempting to set DND but access not granted");
                    }
                }
            }

            // 2. Handle Sound mode (Ringer mode) from watch
            if (wearSignal.ringerMode != null) {
                int ringerModeWear = wearSignal.ringerMode;
                Log.d(TAG, "ringerModeWear: " + ringerModeWear);
                boolean shouldSyncRinger = prefs.getBoolean("watch_ringer_sync_key", true);

                if (shouldSyncRinger) {
                    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (audioManager != null) {
                        int currentRinger = audioManager.getRingerMode();
                        if (currentRinger != ringerModeWear) {
                            DNDNotificationService.lastReceivedRingerMode = ringerModeWear;
                            int initialFilter = mNotificationManager.getCurrentInterruptionFilter();

                            try {
                                audioManager.setRingerMode(ringerModeWear);
                                Log.d(TAG, "Phone ringer mode set to " + ringerModeWear);
                                MainActivity.notifyModeChanged(this);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to set phone ringer mode via AudioManager", e);
                            }

                            // Keep DND exactly as it was before ringer change
                            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                int postFilter = mNotificationManager.getCurrentInterruptionFilter();
                                if (postFilter != initialFilter) {
                                    DNDNotificationService.lastReceivedDndFilter = initialFilter;
                                    mNotificationManager.setInterruptionFilter(initialFilter);
                                    Log.d(TAG, "Restored DND filter to: " + initialFilter);
                                }
                            }
                        }
                    }
                }
            }

        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    private void postWatchLowBatteryNotification(int pct) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Battery Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alerts for watch and phone battery states");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 150, 300});
            nm.createNotificationChannel(channel);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_battery_alert)
                    .setContentTitle("Watch Battery Low")
                    .setContentText("Galaxy Watch battery is at " + pct + "%")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true);

            nm.notify(NOTIF_WATCH_BATTERY_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Error posting watch low battery notification", e);
        }
    }
}

