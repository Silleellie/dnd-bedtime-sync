package it.silleellie.dndsync

import android.content.Context
import androidx.preference.PreferenceManager
import it.silleellie.dndsync.shared.PreferenceKeys
import kotlinx.coroutines.flow.first

class PreferencesHelper(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context);

    fun getValue(key: PreferenceKeys): Boolean {
        return prefs.getBoolean(key.key, key.defaultValue)
    }

    fun setValue(key: PreferenceKeys, value: Boolean) {
        prefs.edit().putBoolean(key.key, value).apply()
    }
}
