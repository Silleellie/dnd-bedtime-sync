package com.aryan.wearmodesync;

import android.Manifest;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

import com.aryan.wearmodesync.shared.PhoneSignal;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public static final String ACTION_MODE_UPDATED = "com.aryan.wearmodesync.ACTION_MODE_UPDATED";
    private static final String DND_SYNC_CAPABILITY_NAME = "dnd_sync";

    public interface ModeUpdateListener {
        void onModeUpdated();
    }

    private static volatile ModeUpdateListener liveModeListener;

    public static void notifyModeChanged(Context context) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (liveModeListener != null) {
                liveModeListener.onModeUpdated();
            }
        });

        if (context != null) {
            try {
                Intent intent = new Intent(ACTION_MODE_UPDATED);
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            } catch (Exception ignored) {}
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshStateInternal;

    private BroadcastReceiver modeReceiver;
    private ContentObserver settingsContentObserver;
    private CapabilityClient.OnCapabilityChangedListener capabilityListener;

    private SharedPreferences prefs;

    // Status & Quick Action views
    private LinearLayout cardWatchStatus;
    private FrameLayout badgeStatusCircle;
    private TextView tvConnectionStatus;
    private TextView tvModeSummary;

    private LinearLayout btnSyncNow;
    private TextView tvSyncLabel;
    private TextView tvSyncSub;
    private boolean isSyncing = false;

    private LinearLayout cardPermission;
    private TextView tvPermBadge;

    // Phone to Watch Sync switches
    private LinearLayout rowDndSync;
    private SwitchCompat swDndSync;

    private LinearLayout rowDndAsBedtime;
    private TextView tvDndAsBedtimeTitle;
    private TextView tvDndAsBedtimeDesc;
    private SwitchCompat swDndAsBedtime;

    private LinearLayout rowBedtimeSync;
    private SwitchCompat swBedtimeSync;

    private LinearLayout rowPowerSave;
    private TextView tvPowerSaveTitle;
    private TextView tvPowerSaveDesc;
    private SwitchCompat swPowerSave;

    private LinearLayout rowRingerSync;
    private SwitchCompat swRingerSync;

    // Watch to Phone Sync switches
    private LinearLayout rowWatchDndSync;
    private SwitchCompat swWatchDndSync;

    private LinearLayout rowWatchRingerSync;
    private SwitchCompat swWatchRingerSync;

    private LinearLayout rowWatchVibrate;
    private SwitchCompat swWatchVibrate;

    // Battery Alerts switches
    private LinearLayout rowPhoneChargedAlert;
    private SwitchCompat swPhoneChargedAlert;

    private LinearLayout rowPhoneLowBattery;
    private SwitchCompat swPhoneLowBattery;

    private LinearLayout rowWatchLowBattery;
    private SwitchCompat swWatchLowBattery;

    // Background & OEM Protection switch
    private LinearLayout rowKeepAlive;
    private SwitchCompat swKeepAlive;

    // Android 13+ Notification permission launcher
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                refreshState();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Edge-to-edge window insets handling
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinator_layout), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            findViewById(R.id.app_bar).setPadding(0, insets.top, 0, 0);
            findViewById(R.id.scroll_view).setPadding(insets.left, 0, insets.right, insets.bottom);
            return windowInsets;
        });

        initViews();
        setupQuickActions();
        setupPreferenceToggles();

        requestNotificationPermissionIfNeeded();
    }

    private void initViews() {
        // Status card
        cardWatchStatus = findViewById(R.id.card_watch_status);
        badgeStatusCircle = findViewById(R.id.badge_status_circle);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        tvModeSummary = findViewById(R.id.tv_mode_summary);

        // Sync Now action
        btnSyncNow = findViewById(R.id.btn_sync_now);
        tvSyncLabel = findViewById(R.id.tv_sync_label);
        tvSyncSub = findViewById(R.id.tv_sync_sub);

        // Permission card
        cardPermission = findViewById(R.id.card_permission);
        tvPermBadge = findViewById(R.id.tv_perm_badge);

        // Phone to Watch Sync (Grouped logically)
        rowDndSync = findViewById(R.id.row_dnd_sync);
        swDndSync = findViewById(R.id.sw_dnd_sync);

        rowDndAsBedtime = findViewById(R.id.row_dnd_as_bedtime);
        tvDndAsBedtimeTitle = findViewById(R.id.tv_dnd_as_bedtime_title);
        tvDndAsBedtimeDesc = findViewById(R.id.tv_dnd_as_bedtime_desc);
        swDndAsBedtime = findViewById(R.id.sw_dnd_as_bedtime);

        rowBedtimeSync = findViewById(R.id.row_bedtime_sync);
        swBedtimeSync = findViewById(R.id.sw_bedtime_sync);

        rowPowerSave = findViewById(R.id.row_power_save);
        tvPowerSaveTitle = findViewById(R.id.tv_power_save_title);
        tvPowerSaveDesc = findViewById(R.id.tv_power_save_desc);
        swPowerSave = findViewById(R.id.sw_power_save);

        rowRingerSync = findViewById(R.id.row_ringer_sync);
        swRingerSync = findViewById(R.id.sw_ringer_sync);

        // Watch to Phone Sync
        rowWatchDndSync = findViewById(R.id.row_watch_dnd_sync);
        swWatchDndSync = findViewById(R.id.sw_watch_dnd_sync);

        rowWatchRingerSync = findViewById(R.id.row_watch_ringer_sync);
        swWatchRingerSync = findViewById(R.id.sw_watch_ringer_sync);

        rowWatchVibrate = findViewById(R.id.row_watch_vibrate);
        swWatchVibrate = findViewById(R.id.sw_watch_vibrate);

        // Battery Alerts
        rowPhoneChargedAlert = findViewById(R.id.row_phone_charged_alert);
        swPhoneChargedAlert = findViewById(R.id.sw_phone_charged_alert);

        rowPhoneLowBattery = findViewById(R.id.row_phone_low_battery);
        swPhoneLowBattery = findViewById(R.id.sw_phone_low_battery);

        rowWatchLowBattery = findViewById(R.id.row_watch_low_battery);
        swWatchLowBattery = findViewById(R.id.sw_watch_low_battery);

        // Background & OEM Protection
        rowKeepAlive = findViewById(R.id.row_keep_alive);
        swKeepAlive = findViewById(R.id.sw_keep_alive);
    }

    private void setupQuickActions() {
        // 1. Manual Sync Now action (matches Watch Sync button)
        btnSyncNow.setOnClickListener(v -> {
            if (isSyncing) return;
            performHapticClick();

            isSyncing = true;
            tvSyncLabel.setText(R.string.syncing);
            tvSyncSub.setText(R.string.sync_dispatched);
            btnSyncNow.setEnabled(false);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int currentDnd = nm != null ? nm.getCurrentInterruptionFilter() : NotificationManager.INTERRUPTION_FILTER_ALL;
            int currentRinger = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;

            PhoneSignal signal = new PhoneSignal(currentDnd, currentRinger, prefs);
            if (signal.bedtimeState == null && prefs.getBoolean("bedtime_sync_key", false)) {
                int samsungBedtime = Settings.Global.getInt(getContentResolver(), "setting_bedtime_mode_running_state", -1);
                int aospBedtime = Settings.Global.getInt(getContentResolver(), "bedtime_mode", -1);
                if (samsungBedtime == 1 || aospBedtime == 1 || DNDNotificationService.isBedtimeActive) {
                    signal.bedtimeState = 1;
                }
            }
            DNDNotificationService.sendDNDSync(MainActivity.this, signal);
            Toast.makeText(MainActivity.this, "Sync sent to watch", Toast.LENGTH_SHORT).show();

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                isSyncing = false;
                tvSyncLabel.setText(R.string.sync_now);
                tvSyncSub.setText(R.string.tap_to_sync);
                btnSyncNow.setEnabled(true);
                refreshState();
            }, 1200);
        });

        // 2. Notification & DND Permission Card click
        cardPermission.setOnClickListener(v -> {
            performHapticClick();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            boolean isDndGranted = nm != null && nm.isNotificationPolicyAccessGranted();

            if (!isDndGranted) {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            } else {
                Toast.makeText(MainActivity.this, "Notification & DND access active", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupPreferenceToggles() {
        // Phone to Watch Sync toggles (Grouped logically)
        bindSwitch(rowDndSync, swDndSync, "dnd_sync_key", true, (buttonView, isChecked) -> {
            updateDependencies();
        });

        bindSwitch(rowDndAsBedtime, swDndAsBedtime, "dnd_as_bedtime_key", false, (buttonView, isChecked) -> {
            updateDependencies();
        });

        bindSwitch(rowBedtimeSync, swBedtimeSync, "bedtime_sync_key", false, (buttonView, isChecked) -> {
            updateDependencies();
        });

        bindSwitch(rowPowerSave, swPowerSave, "power_save_key", false, null);

        bindSwitch(rowRingerSync, swRingerSync, "ringer_sync_key", true, null);

        // Watch to Phone Sync toggles
        bindSwitch(rowWatchDndSync, swWatchDndSync, "watch_dnd_sync_key", true, null);
        bindSwitch(rowWatchRingerSync, swWatchRingerSync, "watch_ringer_sync_key", true, null);
        bindSwitch(rowWatchVibrate, swWatchVibrate, "watch_vibrate_key", true, null);

        // Battery Alerts
        bindSwitch(rowPhoneChargedAlert, swPhoneChargedAlert, "phone_charged_alert_key", true, null);
        bindSwitch(rowPhoneLowBattery, swPhoneLowBattery, "phone_low_battery_key", true, null);
        bindSwitch(rowWatchLowBattery, swWatchLowBattery, "watch_low_battery_key", true, null);

        // Background & OEM Protection
        bindSwitch(rowKeepAlive, swKeepAlive, "keep_alive_key", true, null);
    }

    private void bindSwitch(View row, SwitchCompat sw, String key, boolean defaultValue,
                            CompoundButton.OnCheckedChangeListener extraListener) {
        boolean initialVal = prefs.getBoolean(key, defaultValue);
        sw.setChecked(initialVal);

        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            performHapticClick();
            prefs.edit().putBoolean(key, isChecked).apply();
            if (extraListener != null) {
                extraListener.onCheckedChanged(buttonView, isChecked);
            }
        });

        // Clicking the entire row triggers the switch
        row.setOnClickListener(v -> {
            if (sw.isEnabled()) {
                sw.setChecked(!sw.isChecked());
            }
        });
    }

    private void updateDependencies() {
        boolean dndSyncActive = swDndSync.isChecked();
        swDndAsBedtime.setEnabled(dndSyncActive);
        rowDndAsBedtime.setAlpha(dndSyncActive ? 1.0f : 0.45f);

        boolean dndAsBedtimeActive = dndSyncActive && swDndAsBedtime.isChecked();
        boolean bedtimeSyncActive = swBedtimeSync.isChecked();
        boolean powerSaveEnabled = dndAsBedtimeActive || bedtimeSyncActive;

        swPowerSave.setEnabled(powerSaveEnabled);
        rowPowerSave.setAlpha(powerSaveEnabled ? 1.0f : 0.45f);
    }

    public void refreshState() {
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.post(refreshRunnable);
    }

    private void refreshStateInternal() {
        if (isFinishing() || isDestroyed()) return;

        // 1. Check DND & Notification Policy Permission
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean isDndGranted = nm != null && nm.isNotificationPolicyAccessGranted();

        if (isDndGranted) {
            tvPermBadge.setText(R.string.permission_granted);
            tvPermBadge.setTextColor(Color.parseColor("#81C784"));
            tvPermBadge.setBackgroundResource(R.drawable.bg_chip_granted);
        } else {
            tvPermBadge.setText(R.string.permission_required);
            tvPermBadge.setTextColor(Color.parseColor("#E57373"));
            tvPermBadge.setBackgroundResource(R.drawable.bg_chip_denied);
        }

        // 2. Read local phone mode state
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int ringer = am != null ? am.getRingerMode() : -1;
        String ringerStr = ringer == AudioManager.RINGER_MODE_SILENT ? "Silent" :
                ringer == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Ring";
        int dnd = nm != null ? nm.getCurrentInterruptionFilter() : -1;
        boolean isDndOn = isDndGranted && (dnd > NotificationManager.INTERRUPTION_FILTER_ALL);
        String dndStr = isDndOn ? "DND On" : "DND Off";

        // Bedtime mode detection
        int samsungBedtime = Settings.Global.getInt(getContentResolver(), "setting_bedtime_mode_running_state", -1);
        int aospBedtime = Settings.Global.getInt(getContentResolver(), "bedtime_mode", -1);
        boolean isBedtime = (samsungBedtime == 1) || (aospBedtime == 1) || DNDNotificationService.isBedtimeActive;

        if (isBedtime) {
            tvModeSummary.setText("Phone: " + dndStr + " • " + ringerStr + " • Bedtime");
        } else {
            tvModeSummary.setText("Phone: " + dndStr + " • " + ringerStr);
        }

        // 3. Check Galaxy Watch connection status
        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (isFinishing() || isDestroyed()) return;
                    boolean isConnected = nodes != null && !nodes.isEmpty();
                    if (isConnected) {
                        String nodeName = nodes.get(0).getDisplayName();
                        tvConnectionStatus.setText(nodeName != null && !nodeName.isEmpty() ? nodeName : getString(R.string.watch_connected));
                        badgeStatusCircle.setBackgroundResource(R.drawable.bg_circle_green);
                    } else {
                        tvConnectionStatus.setText(R.string.searching_watch);
                        badgeStatusCircle.setBackgroundResource(R.drawable.bg_circle_orange);
                    }
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvConnectionStatus.setText(R.string.searching_watch);
                    badgeStatusCircle.setBackgroundResource(R.drawable.bg_circle_orange);
                });

        updateDependencies();
    }

    private void registerModeListeners() {
        if (modeReceiver == null) {
            modeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent != null ? intent.getAction() : null;
                    Log.d(TAG, "modeReceiver received: " + action);
                    refreshState();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED);
            filter.addAction(ACTION_MODE_UPDATED);
            registerReceiver(modeReceiver, filter, Context.RECEIVER_EXPORTED);
        }

        if (settingsContentObserver == null) {
            settingsContentObserver = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "settingsContentObserver onChange: " + uri);
                    refreshState();
                }
            };

            String[] settingsToObserve = {
                    "mode_ringer",
                    "zen_mode",
                    "setting_bedtime_mode_running_state",
                    "bedtime_mode"
            };

            for (String setting : settingsToObserve) {
                try {
                    getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(setting),
                            false,
                            settingsContentObserver
                    );
                } catch (Exception ignored) {}
            }

            try {
                getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(Settings.System.MODE_RINGER_STREAMS_AFFECTED),
                        false,
                        settingsContentObserver
                );
            } catch (Exception ignored) {}
        }

        if (capabilityListener == null) {
            capabilityListener = capabilityInfo -> {
                Log.d(TAG, "Capability changed for dnd_sync");
                refreshState();
            };
            try {
                Wearable.getCapabilityClient(this).addListener(
                        capabilityListener,
                        DND_SYNC_CAPABILITY_NAME
                );
            } catch (Exception ignored) {}
        }
    }

    private void unregisterModeListeners() {
        if (modeReceiver != null) {
            try {
                unregisterReceiver(modeReceiver);
            } catch (Exception ignored) {}
            modeReceiver = null;
        }

        if (settingsContentObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(settingsContentObserver);
            } catch (Exception ignored) {}
            settingsContentObserver = null;
        }

        if (capabilityListener != null) {
            try {
                Wearable.getCapabilityClient(this).removeListener(capabilityListener);
            } catch (Exception ignored) {}
            capabilityListener = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        liveModeListener = this::refreshState;
        registerModeListeners();
    }

    @Override
    protected void onStop() {
        super.onStop();
        liveModeListener = null;
        unregisterModeListeners();
    }

    private void performHapticClick() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                } else {
                    v.vibrate(25);
                }
            }
        } catch (Exception ignored) {}
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }
}
