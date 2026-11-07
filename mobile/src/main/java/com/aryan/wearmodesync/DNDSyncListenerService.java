package com.aryan.wearmodesync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.apache.commons.lang3.SerializationUtils;

import com.aryan.wearmodesync.shared.WearSignal;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            byte[] data = messageEvent.getData();
            WearSignal wearSignal = SerializationUtils.deserialize(data);
            if (wearSignal == null) return;

            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

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
}

