package com.jarvis.hermes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transitions and surfaces a notification with the
 * reminder text. One-shot: removes the reminder + unregisters the
 * geofence after firing.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GEOFENCE_EVENT) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            android.util.Log.w("GeofenceReceiver", "Error code ${event.errorCode}")
            return
        }
        val triggering = event.triggeringGeofences.orEmpty()
        if (triggering.isEmpty()) return

        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        for (geofence in triggering) {
            val reminder = RemindersStore.byId(context, geofence.requestId) ?: continue
            val tapIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, reminder.id.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Reminder")
                .setContentText(reminder.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(reminder.id.hashCode(), n)

            // One-shot — remove from store and unregister.
            RemindersStore.remove(context, reminder.id)
            GeofenceManager.unregister(context, reminder.id)
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val ACTION_GEOFENCE_EVENT = "com.jarvis.hermes.GEOFENCE_EVENT"
        private const val CHANNEL_ID = "jarvis_hermes_reminders"
    }
}
