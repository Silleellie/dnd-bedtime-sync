package com.aryan.wearmodesync;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference secureSettingsPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        dndPref = findPreference("dnd_permission_key");
        secureSettingsPref = findPreference("secure_settings_permission_key");

        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                if (!checkDNDPermission()) {
                    Toast.makeText(getContext(), "Follow the instructions to grant the permission via ADB!", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        if (secureSettingsPref != null) {
            secureSettingsPref.setOnPreferenceClickListener(preference -> {
                if (!checkSecureSettingsPermission(getContext())) {
                    Toast.makeText(getContext(), "Follow the instructions to grant the permission via ADB!", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        checkDNDPermission();
        checkSecureSettingsPermission(getContext());
    }

    private boolean checkDNDPermission() {
        if (getContext() == null) return false;
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        boolean allowed = mNotificationManager != null && mNotificationManager.isNotificationPolicyAccessGranted();
        if (dndPref != null) {
            if (allowed) {
                dndPref.setSummary(R.string.granted);
            } else {
                dndPref.setSummary(R.string.denied);
            }
        }
        return allowed;
    }

    private boolean checkSecureSettingsPermission(Context context) {
        if (context == null) return false;
        boolean allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        if (secureSettingsPref != null) {
            if (allowed) {
                secureSettingsPref.setSummary(R.string.granted);
            } else {
                secureSettingsPref.setSummary(R.string.denied);
            }
        }
        return allowed;
    }
}

