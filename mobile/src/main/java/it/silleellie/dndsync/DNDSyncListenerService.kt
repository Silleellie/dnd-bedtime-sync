package it.silleellie.dndsync

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.WearSignal
import org.apache.commons.lang3.SerializationUtils

class DNDSyncListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: $messageEvent")

        if (messageEvent.path.equals(URL_OPEN_PATH, ignoreCase = true)) {
            val url = "https://github.com/Silleellie/dnd-bedtime-sync#watch"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } else if (messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, ignoreCase = true)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)

            val data = messageEvent.getData()
            val wearSignal = SerializationUtils.deserialize<WearSignal>(data)
            val dndStateWear = wearSignal.dndState

            Log.d(TAG, "dndStateWear: $dndStateWear")

            // get dnd state
            val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val currentDndState = mNotificationManager.getCurrentInterruptionFilter()

            Log.d(TAG, "currentDndState: $currentDndState")
            if (currentDndState < 0 || currentDndState > 4) {
                Log.d(TAG, "Current DND state it's weird, should be in range [0,4]")
            }

            val shouldSync = prefs.getBoolean("watch_dnd_sync_key", false)

            if (currentDndState != dndStateWear && shouldSync) {
                Log.d(
                    TAG,
                    "currentDndState != dndStateWear: $currentDndState != $dndStateWear"
                )
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    mNotificationManager.setInterruptionFilter(dndStateWear)
                    Log.d(TAG, "DND set to $dndStateWear")
                } else {
                    Log.d(TAG, "attempting to set DND but access not granted")
                }
            }
        } else {
            super.onMessageReceived(messageEvent)
        }
    }

    companion object {
        private const val TAG = "DNDSyncListenerService"
        private const val DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync"
        private const val URL_OPEN_PATH = "/open_link"
    }
}
