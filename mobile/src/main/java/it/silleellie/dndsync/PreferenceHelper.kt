package it.silleellie.dndsync

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.first

enum class PreferenceKeys(key: String){
    WatchDndSync("watch_dnd_sync_key"),
    DndAsBedtime("dnd_as_bedtime_key"),
    BedtimeSync("bedtime_sync_key"),
    PowerSave("power_save_key"),
    DndSync("dnd_sync_key"),
    WatchVibrate("watch_vibrate_key"),
}

class PreferencesHelper(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context);

    fun getValue(key: PreferenceKeys): Boolean {
        return prefs.getBoolean(key.name, false);
    }

    fun setValue(key: PreferenceKeys, value: Boolean) {
        prefs.edit().putBoolean(key.name, value).apply();
    }
}
