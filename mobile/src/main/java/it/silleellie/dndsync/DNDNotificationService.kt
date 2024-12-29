package it.silleellie.dndsync

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import it.silleellie.dndsync.shared.PhoneSignal
import org.apache.commons.lang3.SerializationUtils
import java.util.concurrent.ExecutionException


class DNDNotificationService : NotificationListenerService() {
    private fun isWindDownNotification(sbn: StatusBarNotification): Boolean {
        return sbn.packageName == "com.google.android.apps.wellbeing" &&
                sbn.notification.channelId == "wind_down_notifications"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isWindDownNotification(sbn)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val syncBedTime = prefs.getBoolean("bedtime_sync_key", true)

            if (syncBedTime) {
                // depending on the number of actions that can be done, bedtime mode
                // could be in "pause mode" or "on mode":
                // * If it is in "pause" mode, there is only one action ("Restart bedtime")
                // * If it is in "on" mode, there are two actions possible ("Pause it" and "De-activate it")
                val is_on = sbn.getNotification().actions.size == 2
                val is_paused = sbn.getNotification().actions.size == 1

                if (is_on) {
                    // 5 means bedtime ON
                    Log.d(TAG, "bedtime mode is on")
                    val interruptionFilter = 5
                    Thread(Runnable { sendDNDSync(PhoneSignal(interruptionFilter, prefs)) }).start()
                } else if (is_paused) {
                    // 6 means bedtime OFF
                    Log.d(TAG, "bedtime mode is off")
                    val interruptionFilter = 6
                    Thread(Runnable { sendDNDSync(PhoneSignal(interruptionFilter, prefs)) }).start()
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // if notifications is removed, we want surely to disable bedtime mode
        if (isWindDownNotification(sbn)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val syncBedTime = prefs.getBoolean("bedtime_sync_key", true)

            if (syncBedTime) {
                // 6 means bedtime OFF
                Log.d(TAG, "bedtime mode is off")
                val interruptionFilter = 6
                Thread(Runnable { sendDNDSync(PhoneSignal(interruptionFilter, prefs)) }).start()
            }
        }
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        Log.d(TAG, "interruption filter changed to " + interruptionFilter)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val syncDnd = prefs.getBoolean("dnd_sync_key", true)

        if (syncDnd) {
            Thread(Runnable { sendDNDSync(PhoneSignal(interruptionFilter, prefs)) }).start()
        }
    }

    private fun sendDNDSync(phoneSignal: PhoneSignal?) {
        // https://developer.android.com/training/wearables/data/messages

        // search nodes for sync

        val capabilityInfo: CapabilityInfo
        try {
            capabilityInfo = Tasks.await<CapabilityInfo>(
                Wearable.getCapabilityClient(this).getCapability(
                    DND_SYNC_CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE
                )
            )
        } catch (e: ExecutionException) {
            e.printStackTrace()
            Log.e(TAG, "execution error while searching nodes", e)
            return
        } catch (e: InterruptedException) {
            e.printStackTrace()
            Log.e(TAG, "interruption error while searching nodes", e)
            return
        }

        // send request to all reachable nodes
        // capabilityInfo has the reachable nodes with the dnd sync capability
        val connectedNodes = capabilityInfo.getNodes()
        if (connectedNodes.isEmpty()) {
            // Unable to retrieve node with transcription capability
            Log.d(TAG, "Unable to retrieve node with sync capability!")
        } else {
            for (node in connectedNodes) {
                if (node.isNearby()) {
                    val data = SerializationUtils.serialize(phoneSignal)
                    val sendTask =
                        Wearable.getMessageClient(this)
                            .sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data)

                    sendTask.addOnSuccessListener(OnSuccessListener { integer: Int? ->
                        Log.d(
                            TAG,
                            "send successful! Receiver node id: " + node.getId()
                        )
                    })

                    sendTask.addOnFailureListener(OnFailureListener { e: Exception? ->
                        Log.d(
                            TAG,
                            "send failed! Receiver node id: " + node.getId()
                        )
                    })
                }
            }
        }
    }

    companion object {
        private const val TAG = "DNDNotificationService"
        private const val DND_SYNC_CAPABILITY_NAME = "dnd_sync"
        private const val DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync"
    }
}
