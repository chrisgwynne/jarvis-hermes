package com.jarvis.hermes

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registers geofences with Play Services for each persisted [Reminder].
 *
 * On enter/exit the GeofenceReceiver fires a notification with the
 * reminder text and removes it from the store.
 */
object GeofenceManager {

    fun hasPermissions(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return fine && background
    }

    @SuppressLint("MissingPermission")
    fun register(context: Context, reminder: Reminder): Boolean {
        if (!hasPermissions(context)) return false
        val client: GeofencingClient = LocationServices.getGeofencingClient(context.applicationContext)
        val fence = Geofence.Builder()
            .setRequestId(reminder.id)
            .setCircularRegion(reminder.latitude, reminder.longitude, reminder.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                when (reminder.trigger) {
                    Reminder.Trigger.ENTER -> Geofence.GEOFENCE_TRANSITION_ENTER
                    Reminder.Trigger.EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
                }
            )
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // Don't fire just because we're already inside.
            .addGeofence(fence)
            .build()
        return try {
            client.addGeofences(request, pendingIntent(context))
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            android.util.Log.w("GeofenceManager", "addGeofences failed: ${e.message}")
            false
        }
    }

    fun unregister(context: Context, reminderId: String) {
        try {
            LocationServices.getGeofencingClient(context.applicationContext)
                .removeGeofences(listOf(reminderId))
        } catch (_: Exception) {}
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).apply {
            action = GeofenceReceiver.ACTION_GEOFENCE_EVENT
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
