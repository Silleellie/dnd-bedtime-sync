package com.aryan.wearmodesync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.aryan.wearmodesync.shared.PhoneSignal;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "DNDNotificationService";

    // Dedicated single-thread worker to eliminate thread creation churn & GC spikes
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor();

    // Foreground service constants — keep service active on aggressive OEM devices
    private static final int FGS_NOTIFICATION_ID = 1001;
    private static final String FGS_CHANNEL_ID   = "wearmodesync_persistent";

    private static final String DND_SYNC_CAPABILITY_NAME = "dnd_sync";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    // Loop prevention state updated when receiving events from watch
    public static volatile int lastReceivedRingerMode = -1;
    public static volatile int lastReceivedDndFilter = -1;

    private ContentObserver ringerObserver;
    private BroadcastReceiver ringerReceiver;
    private int lastRingerMode = -1;

    private BroadcastReceiver batteryReceiver;
    private boolean hasAlertedCharged = false;
    private boolean hasAlertedLow = false;

    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    public void onCreate() {
        super.onCreate();
        registerRingerListeners();
        registerBatteryListener();
        setupPreferenceListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterRingerListeners();
        unregisterBatteryListener();
        if (prefListener != null) {
            PreferenceManager.getDefaultSharedPreferences(this)
                    .unregisterOnSharedPreferenceChangeListener(prefListener);
            prefListener = null;
        }
    }

    private void registerBatteryListener() {
        if (batteryReceiver == null) {
            batteryReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                        if (level < 0 || scale <= 0) return;
                        int pct = (int) ((level / (float) scale) * 100);
                        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                                || status == BatteryManager.BATTERY_STATUS_FULL;

                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

                        // 1. Phone 100% full charge alert
                        if (isCharging && pct >= 100) {
                            if (!hasAlertedCharged) {
                                hasAlertedCharged = true;
                                if (prefs.getBoolean("phone_charged_alert_key", true)) {
                                    Log.d(TAG, "Phone fully charged (100%), sending alert to watch");
                                    SYNC_EXECUTOR.execute(() -> sendDNDSync(PhoneSignal.createBatteryAlert(true, 100)));
                                }
                            }
                        } else if (!isCharging) {
                            hasAlertedCharged = false; // reset when unplugged
                        }

                        // 2. Phone low battery alert (<= 15%)
                        if (!isCharging && pct <= 15 && pct > 0) {
                            if (!hasAlertedLow) {
                                hasAlertedLow = true;
                                if (prefs.getBoolean("phone_low_battery_key", true)) {
                                    Log.d(TAG, "Phone battery low (" + pct + "%), sending alert to watch");
                                    SYNC_EXECUTOR.execute(() -> sendDNDSync(PhoneSignal.createBatteryAlert(false, pct)));
                                }
                            }
                        } else if (pct > 20) {
                            hasAlertedLow = false; // reset once recharged above 20%
                        }
                    }
                }
            };
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    private void unregisterBatteryListener() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {}
            batteryReceiver = null;
        }
    }

    private void setupPreferenceListener() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefListener = (sharedPreferences, key) -> {
            if ("keep_alive_key".equals(key)) {
                boolean keepAlive = sharedPreferences.getBoolean("keep_alive_key", true);
                Log.d(TAG, "keep_alive_key changed: " + keepAlive);
                if (keepAlive) {
                    startPersistentForeground();
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    private synchronized void handleRingerChange(int currentRinger) {
        if (currentRinger == lastReceivedRingerMode) {
            Log.d(TAG, "Skipping echo ringerMode from watch sync: " + currentRinger);
            lastReceivedRingerMode = -1;
            lastRingerMode = currentRinger;
            return;
        }

        if (currentRinger != -1 && currentRinger != lastRingerMode) {
            lastRingerMode = currentRinger;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean syncRinger = prefs.getBoolean("ringer_sync_key", true);
            Log.d(TAG, "syncRinger enabled: " + syncRinger + ", sending ringerMode immediately: " + currentRinger);

            if (syncRinger) {
                int finalRinger = currentRinger;
                SYNC_EXECUTOR.execute(() -> sendDNDSync(new PhoneSignal(-1, finalRinger, prefs)));
            }
        }
    }

    private void registerRingerListeners() {
        if (ringerObserver == null) {
            lastRingerMode = Settings.Global.getInt(getContentResolver(), "mode_ringer", -1);
            if (lastRingerMode == -1) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    lastRingerMode = am.getRingerMode();
                }
            }
            Log.d(TAG, "registering ringer listeners, initial lastRingerMode: " + lastRingerMode);

            ringerObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    int currentRinger = Settings.Global.getInt(getContentResolver(), "mode_ringer", -1);
                    if (currentRinger == -1) {
                        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            currentRinger = am.getRingerMode();
                        }
                    }
                    Log.d(TAG, "Ringer observer onChange: " + currentRinger + ", last: " + lastRingerMode);
                    handleRingerChange(currentRinger);
                }
            };

            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("mode_ringer"),
                    false,
                    ringerObserver
            );
        }

        if (ringerReceiver == null) {
            ringerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) {
                        int currentRinger = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
                        if (currentRinger == -1) {
                            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                            if (am != null) {
                                currentRinger = am.getRingerMode();
                            }
                        }
                        Log.d(TAG, "Ringer broadcast onReceive: " + currentRinger + ", last: " + lastRingerMode);
                        handleRingerChange(currentRinger);
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

    /**
     * Called when Android's NotificationListenerService binding is established.
     * We immediately promote this process to a connectedDevice foreground service so that
     * OEM background killers cannot evict it.
     * This runs no extra CPU work — the process stays parked in epoll_wait until a DND event fires.
     */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("keep_alive_key", true)) {
            startPersistentForeground();
        }
        registerRingerListeners();
        Log.d(TAG, "listener connected");
    }

    /**
     * Called when the listener binding is lost (e.g. permission revoked, user disabled app).
     * We stop the foreground notification so it does not linger in the status bar.
     */
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.d(TAG, "listener disconnected — foreground service stopped");
    }

    /**
     * Creates a silent, collapsed, low-priority notification channel (shown once, never again),
     * then calls startForeground() with FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE.
     */
    private void startPersistentForeground() {
        try {
            NotificationChannel channel = new NotificationChannel(
                    FGS_CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_MIN  // silent, no sound, no pop-up
            );
            channel.setDescription(getString(R.string.fgs_notif_text));
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, FGS_CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.fgs_notif_text))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .setShowWhen(false)
                    .build();

            startForeground(FGS_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            Log.d(TAG, "Foreground service successfully started");
        } catch (Exception e) {
            Log.e(TAG, "Could not start foreground service: " + e.getMessage(), e);
        }
    }

    private boolean isWindDownNotification(StatusBarNotification sbn) {
        return sbn.getPackageName().equals("com.google.android.apps.wellbeing") &&
                sbn.getNotification().getChannelId().equals("wind_down_notifications");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn){
        if (isWindDownNotification(sbn)) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean syncBedTime = prefs.getBoolean("bedtime_sync_key", false);

            if (syncBedTime) {
                boolean is_on = sbn.getNotification().actions != null && sbn.getNotification().actions.length == 2;
                boolean is_paused = sbn.getNotification().actions != null && sbn.getNotification().actions.length == 1;

                if (is_on) {
                    Log.d(TAG, "bedtime mode is on");
                    int interruptionFilter = 5;
                    SYNC_EXECUTOR.execute(() -> sendDNDSync(new PhoneSignal(interruptionFilter, prefs)));
                } else if (is_paused) {
                    Log.d(TAG, "bedtime mode is off");
                    int interruptionFilter = 6;
                    SYNC_EXECUTOR.execute(() -> sendDNDSync(new PhoneSignal(interruptionFilter, prefs)));
                }
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn){
        if (isWindDownNotification(sbn)) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean syncBedTime = prefs.getBoolean("bedtime_sync_key", false);

            if (syncBedTime) {
                Log.d(TAG, "bedtime mode is off (notification removed)");
                int interruptionFilter = 6;
                SYNC_EXECUTOR.execute(() -> sendDNDSync(new PhoneSignal(interruptionFilter, prefs)));
            }
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "interruption filter changed to " + interruptionFilter);

        if (interruptionFilter == lastReceivedDndFilter) {
            Log.d(TAG, "Skipping echo DND filter broadcast from watch sync: " + interruptionFilter);
            lastReceivedDndFilter = -1;
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean syncDnd = prefs.getBoolean("dnd_sync_key", true);

        if (syncDnd) {
            SYNC_EXECUTOR.execute(() -> sendDNDSync(new PhoneSignal(interruptionFilter, prefs)));
        }
    }

    public void sendDNDSync(PhoneSignal phoneSignal) {
        sendDNDSync(this, phoneSignal);
    }

    public static void sendDNDSync(Context context, PhoneSignal phoneSignal) {
        SYNC_EXECUTOR.execute(() -> {
            CapabilityInfo capabilityInfo;
            try {
                capabilityInfo = Tasks.await(
                        Wearable.getCapabilityClient(context).getCapability(
                                DND_SYNC_CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE));
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "execution error while searching nodes", e);
                return;
            }

            Set<Node> connectedNodes = capabilityInfo.getNodes();
            if (connectedNodes.isEmpty()) {
                Log.d(TAG, "Unable to retrieve node with sync capability!");
            } else {
                for (Node node : connectedNodes) {
                    if (node.isNearby()) {
                        byte[] data = phoneSignal.toByteArray();
                        Task<Integer> sendTask =
                                Wearable.getMessageClient(context).sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data);

                        sendTask.addOnSuccessListener(integer -> Log.d(TAG, "send successful! Receiver node id: " + node.getId()));
                        sendTask.addOnFailureListener(e -> Log.d(TAG, "send failed! Receiver node id: " + node.getId()));
                    }
                }
            }
        });
    }
}

