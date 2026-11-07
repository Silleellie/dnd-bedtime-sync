package com.aryan.wearmodesync;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.apache.commons.lang3.SerializationUtils;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.aryan.wearmodesync.shared.WearSignal;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "DNDNotificationService";
    private static final String DND_SYNC_CAPABILITY_NAME = "dnd_sync";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    public static boolean running = false;

    // Loop prevention state updated when receiving events from phone
    public static volatile int lastReceivedFilter = -1;
    public static volatile int lastReceivedRingerMode = -1;

    private ContentObserver ringerObserver;
    private BroadcastReceiver ringerReceiver;
    private int lastSentRinger = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        registerRingerListeners();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterRingerListeners();
    }

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "listener connected");
        running = true;
        registerRingerListeners();
    }

    @Override
    public void onListenerDisconnected() {
        Log.d(TAG, "listener disconnected");
        running = false;
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "Watch interruption filter changed to " + interruptionFilter);

        if (interruptionFilter == lastReceivedFilter) {
            Log.d(TAG, "Skipping echo DND filter broadcast from phone sync: " + interruptionFilter);
            lastReceivedFilter = -1;
            return;
        }

        sendWearSync(this, new WearSignal(interruptionFilter, null, null));
    }

    private synchronized void handleWatchRingerChange(int currentRinger) {
        if (currentRinger == lastReceivedRingerMode) {
            Log.d(TAG, "Skipping echo watch ringerMode from phone sync: " + currentRinger);
            lastReceivedRingerMode = -1;
            lastSentRinger = currentRinger;
            return;
        }

        if (currentRinger != -1 && currentRinger != lastSentRinger) {
            lastSentRinger = currentRinger;
            Log.d(TAG, "Watch ringer mode changed locally to: " + currentRinger);
            sendWearSync(this, new WearSignal(null, currentRinger, null));
        }
    }

    private void registerRingerListeners() {
        if (ringerObserver == null) {
            lastSentRinger = Settings.Global.getInt(getContentResolver(), "mode_ringer", -1);
            if (lastSentRinger == -1) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    lastSentRinger = am.getRingerMode();
                }
            }
            ringerObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    int currentRinger = Settings.Global.getInt(getContentResolver(), "mode_ringer", -1);
                    if (currentRinger == -1) {
                        AudioManager audioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (audioMgr != null) {
                            currentRinger = audioMgr.getRingerMode();
                        }
                    }
                    Log.d(TAG, "Watch ringer observer onChange: " + currentRinger);
                    handleWatchRingerChange(currentRinger);
                }
            };

            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("mode_ringer"),
                    false,
                    ringerObserver
            );
            Log.d(TAG, "Registered watch ringer observer, initial: " + lastSentRinger);
        }

        if (ringerReceiver == null) {
            ringerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) {
                        int currentRinger = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
                        if (currentRinger == -1) {
                            AudioManager audioMgr = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                            if (audioMgr != null) {
                                currentRinger = audioMgr.getRingerMode();
                            }
                        }
                        Log.d(TAG, "Watch ringer broadcast onReceive: " + currentRinger);
                        handleWatchRingerChange(currentRinger);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ringerReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(ringerReceiver, filter);
            }
        }
    }

    private void unregisterRingerListeners() {
        if (ringerObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(ringerObserver);
            } catch (Exception ignored) {}
            ringerObserver = null;
        }
        if (ringerReceiver != null) {
            try {
                unregisterReceiver(ringerReceiver);
            } catch (Exception ignored) {}
            ringerReceiver = null;
        }
    }

    public static void sendWearSync(Context context, WearSignal wearSignal) {
        new Thread(() -> {
            try {
                CapabilityInfo capabilityInfo = Tasks.await(
                        Wearable.getCapabilityClient(context).getCapability(
                                DND_SYNC_CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE));
                Set<Node> connectedNodes = capabilityInfo.getNodes();
                if (connectedNodes.isEmpty()) {
                    Log.d(TAG, "Unable to retrieve node with sync capability!");
                    return;
                }
                byte[] data = SerializationUtils.serialize(wearSignal);
                for (Node node : connectedNodes) {
                    if (node.isNearby()) {
                        Task<Integer> sendTask = Wearable.getMessageClient(context).sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data);
                        sendTask.addOnSuccessListener(res -> Log.d(TAG, "send successful to node: " + node.getId()));
                        sendTask.addOnFailureListener(e -> Log.e(TAG, "send failed to node: " + node.getId(), e));
                    }
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error sending wear sync", e);
            }
        }).start();
    }
}

