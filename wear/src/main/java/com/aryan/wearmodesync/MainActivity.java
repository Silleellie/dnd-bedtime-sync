package com.aryan.wearmodesync;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.wear.widget.WearableLinearLayoutManager;
import androidx.wear.widget.WearableRecyclerView;

import com.google.android.gms.wearable.Wearable;

import com.aryan.wearmodesync.shared.WearSignal;

public class MainActivity extends AppCompatActivity {
    private WearableRecyclerView recyclerView;
    private MenuAdapter adapter;

    /**
     * Samsung One UI / Google Wear OS accelerated edge layout callback:
     * - Plateau safe zone across central ~55% of the viewport (scale 1.0x, alpha 1.0)
     * - Accelerated non-linear drop-off at upper and lower horizons (fast shrink to 0.70x, fade to 0.45)
     */
    public static class OneUILayoutCallback extends WearableLinearLayoutManager.LayoutCallback {
        private static final float EDGE_THRESHOLD = 0.42f;
        private static final float MIN_SCALE = 0.70f;
        private static final float MIN_ALPHA = 0.62f;

        @Override
        public void onLayoutFinished(View child, RecyclerView parent) {
            int parentHeight = parent.getHeight();
            if (parentHeight == 0) return;

            float midY = parentHeight / 2.0f;
            float childMidY = (child.getTop() + child.getBottom()) / 2.0f;
            float distanceFromCenter = Math.abs(midY - childMidY) / midY;
            distanceFromCenter = Math.min(1.0f, distanceFromCenter);

            if (distanceFromCenter <= EDGE_THRESHOLD) {
                // Center reading safe zone: full 1.0x size, crisp and stable
                child.setScaleX(1.0f);
                child.setScaleY(1.0f);
                child.setAlpha(1.0f);
            } else {
                // Accelerated edge drop-off matching Google Messages & Samsung One UI
                float p = (distanceFromCenter - EDGE_THRESHOLD) / (1.0f - EDGE_THRESHOLD);
                float pEased = p * p; // Fast quadratic drop-off
                float scale = 1.0f - pEased * (1.0f - MIN_SCALE);
                float alpha = 1.0f - p * (1.0f - MIN_ALPHA);

                child.setScaleX(scale);
                child.setScaleY(scale);
                child.setAlpha(alpha);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.wearable_recycler_view);
        WearableLinearLayoutManager layoutManager = new WearableLinearLayoutManager(this, new OneUILayoutCallback());
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setEdgeItemsCenteringEnabled(false);

        // Smooth continuous rotary bezel scrolling: intercept discrete notch jumps and
        // smoothly glide pixel-by-pixel with physics instead of teleporting card-by-card
        recyclerView.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL
                    && (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                    || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0)) {
                float axis = -event.getAxisValue(MotionEvent.AXIS_SCROLL);
                if (axis != 0) {
                    float density = getResources().getDisplayMetrics().density;
                    int deltaY = Math.round(axis * 38f * density);
                    recyclerView.smoothScrollBy(0, deltaY, new DecelerateInterpolator(1.2f), 100);
                    return true;
                }
            }
            return false;
        });

        recyclerView.requestFocus();

        adapter = new MenuAdapter();
        recyclerView.setAdapter(adapter);

        requestNotificationPermissionIfNeeded();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0)) {
            float axis = -event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (axis != 0 && recyclerView != null) {
                float density = getResources().getDisplayMetrics().density;
                int deltaY = Math.round(axis * 38f * density);
                recyclerView.smoothScrollBy(0, deltaY, new DecelerateInterpolator(1.2f), 100);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.refreshState();
        }
    }

    private void performHapticClick() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                VibrationAttributes attrs = new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build();
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE), attrs);
            }
        } catch (Exception ignored) {}
    }

    private class MenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER     = 0;
        private static final int TYPE_SYNC_BTN   = 1;
        private static final int TYPE_STATUS     = 2;
        private static final int TYPE_PERM_DND   = 3;
        private static final int TYPE_PERM_SEC   = 4;

        private boolean isPhoneConnected = false;
        private boolean isDndGranted = false;
        private boolean isSecureSettingsGranted = false;
        private String modeSummary = "Checking modes...";
        private boolean isSyncing = false;

        public void refreshState() {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            isDndGranted = nm != null && nm.isNotificationPolicyAccessGranted();
            isSecureSettingsGranted = ContextCompat.checkSelfPermission(
                    MainActivity.this, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;

            // Read local mode info
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int ringer = am != null ? am.getRingerMode() : -1;
            String ringerStr = ringer == AudioManager.RINGER_MODE_SILENT ? "Silent" :
                    ringer == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Ring";
            int dnd = nm != null ? nm.getCurrentInterruptionFilter() : -1;
            String dndStr = dnd == NotificationManager.INTERRUPTION_FILTER_ALL ? "DND Off" : "DND On";
            modeSummary = dndStr + " • " + ringerStr;

            // Check node connectivity
            Wearable.getNodeClient(MainActivity.this).getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        isPhoneConnected = nodes != null && !nodes.isEmpty();
                        notifyDataSetChanged();
                    })
                    .addOnFailureListener(e -> {
                        isPhoneConnected = false;
                        notifyDataSetChanged();
                    });

            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return 5;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case TYPE_HEADER:
                    return new HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false));
                case TYPE_SYNC_BTN:
                    return new SyncBtnViewHolder(inflater.inflate(R.layout.item_sync_button, parent, false));
                case TYPE_STATUS:
                    return new StatusViewHolder(inflater.inflate(R.layout.item_status_card, parent, false));
                case TYPE_PERM_DND:
                case TYPE_PERM_SEC:
                default:
                    return new PermViewHolder(inflater.inflate(R.layout.item_permission_card, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof SyncBtnViewHolder) {
                SyncBtnViewHolder vh = (SyncBtnViewHolder) holder;
                vh.tvLabel.setText(isSyncing ? "Syncing..." : "Sync Now");
                vh.tvSub.setText(isSyncing ? "Sync signal dispatched" : "Tap to sync modes");
                vh.btnSync.setEnabled(!isSyncing);
                vh.btnSync.setOnClickListener(v -> {
                    performHapticClick();
                    isSyncing = true;
                    vh.tvLabel.setText("Syncing...");
                    vh.tvSub.setText("Sync signal dispatched");
                    vh.btnSync.setEnabled(false);

                    DNDNotificationService.sendWearSync(MainActivity.this, WearSignal.createSyncRequest());
                    Toast.makeText(MainActivity.this, "Sync sent to phone", Toast.LENGTH_SHORT).show();

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        isSyncing = false;
                        vh.tvLabel.setText("Sync Now");
                        vh.tvSub.setText("Tap to sync modes");
                        vh.btnSync.setEnabled(true);
                        refreshState();
                    }, 1200);
                });
            } else if (holder instanceof StatusViewHolder) {
                StatusViewHolder vh = (StatusViewHolder) holder;
                vh.tvStatus.setText(isPhoneConnected ? "Phone Connected" : "Searching Phone...");
                vh.badgeCircle.setBackgroundResource(isPhoneConnected ? R.drawable.bg_circle_green : R.drawable.bg_circle_orange);
                vh.tvSummary.setText(modeSummary);
            } else if (holder instanceof PermViewHolder) {
                PermViewHolder vh = (PermViewHolder) holder;
                if (position == TYPE_PERM_DND) {
                    vh.tvTitle.setText("DND Access");
                    vh.ivIcon.setImageResource(R.drawable.ic_do_not_disturb);
                    vh.flIconBg.setBackgroundResource(R.drawable.bg_circle_slate);
                    vh.tvBadge.setText(isDndGranted ? "Granted" : "Denied");
                    vh.tvBadge.setTextColor(isDndGranted ? Color.parseColor("#81C784") : Color.parseColor("#E57373"));
                    vh.tvBadge.setBackgroundResource(isDndGranted ? R.drawable.bg_chip_granted : R.drawable.bg_chip_denied);
                    vh.container.setOnClickListener(v -> {
                        if (!isDndGranted) {
                            Toast.makeText(MainActivity.this, "DND permission not granted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "DND access active", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    vh.tvTitle.setText("Bedtime / Saver");
                    vh.ivIcon.setImageResource(R.drawable.ic_bedtime);
                    vh.flIconBg.setBackgroundResource(R.drawable.bg_circle_purple);
                    vh.tvBadge.setText(isSecureSettingsGranted ? "Granted" : "Denied");
                    vh.tvBadge.setTextColor(isSecureSettingsGranted ? Color.parseColor("#81C784") : Color.parseColor("#E57373"));
                    vh.tvBadge.setBackgroundResource(isSecureSettingsGranted ? R.drawable.bg_chip_granted : R.drawable.bg_chip_denied);
                    vh.container.setOnClickListener(v -> {
                        if (!isSecureSettingsGranted) {
                            Toast.makeText(MainActivity.this, "Bedtime / Saver sync not granted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Bedtime & Saver sync active", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }

        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle;
            HeaderViewHolder(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tv_header_title);
            }
        }

        class SyncBtnViewHolder extends RecyclerView.ViewHolder {
            View btnSync;
            TextView tvLabel;
            TextView tvSub;
            ImageView ivIcon;
            SyncBtnViewHolder(View v) {
                super(v);
                btnSync = v.findViewById(R.id.btn_sync_now);
                tvLabel = v.findViewById(R.id.tv_sync_label);
                tvSub = v.findViewById(R.id.tv_sync_sub);
                ivIcon = v.findViewById(R.id.iv_sync_icon);
            }
        }

        class StatusViewHolder extends RecyclerView.ViewHolder {
            FrameLayout badgeCircle;
            ImageView ivIcon;
            TextView tvStatus;
            TextView tvSummary;
            StatusViewHolder(View v) {
                super(v);
                badgeCircle = v.findViewById(R.id.badge_status_circle);
                ivIcon = v.findViewById(R.id.iv_status_icon);
                tvStatus = v.findViewById(R.id.tv_connection_status);
                tvSummary = v.findViewById(R.id.tv_mode_summary);
            }
        }

        class PermViewHolder extends RecyclerView.ViewHolder {
            View container;
            FrameLayout flIconBg;
            ImageView ivIcon;
            TextView tvTitle;
            TextView tvBadge;
            PermViewHolder(View v) {
                super(v);
                container = v.findViewById(R.id.card_permission);
                flIconBg = v.findViewById(R.id.fl_perm_icon_bg);
                ivIcon = v.findViewById(R.id.iv_perm_icon);
                tvTitle = v.findViewById(R.id.tv_perm_title);
                tvBadge = v.findViewById(R.id.tv_perm_badge);
            }
        }
    }
}
