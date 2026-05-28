package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.jarvis.hermes.GeofenceManager
import com.jarvis.hermes.LocalResponse
import com.jarvis.hermes.Reminder
import com.jarvis.hermes.RemindersStore
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reminder voice commands.
 *
 * Supported forms:
 *  - "remind me to <text> when I get home" → enter at saved "home"
 *  - "remind me to <text> when I leave home" → exit at saved "home"
 *  - "remind me to <text> at <address>" → enter at <address>
 *  - "list reminders" → list pending
 *  - "clear reminders" → clear all
 *
 * "home" / "work" addresses come from SharedPreferences (set via Settings
 * or by saying "this is home"). Without them, "when I get home" falls
 * back to "wherever I am right now" — useful as a debug path.
 */
object ReminderAction {

    private const val ACTION_ADD = "add"
    private const val ACTION_LIST = "list"
    private const val ACTION_CLEAR = "clear"
    private const val ACTION_SET_HOME = "set_home"
    private const val ACTION_SET_WORK = "set_work"

    fun requiredPermissions() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^remind\s+me\s+to\s+(.+?)\s+when\s+i\s+(get|arrive|reach)\s+(home|work|at\s+(.+))$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^remind\s+me\s+to\s+(.+?)\s+when\s+i\s+(get|arrive|reach)\s+(home|work|at\s+(.+))$""", RegexOption.IGNORE_CASE).find(text)!!
                val task = m.groupValues[1]
                val raw = m.groupValues[3].lowercase()
                val place = if (raw.startsWith("at ")) m.groupValues[4] else raw
                mapOf("action" to ACTION_ADD, "text" to task, "place" to place, "trigger" to "ENTER")
            }
            Regex("""^remind\s+me\s+to\s+(.+?)\s+when\s+i\s+leave\s+(home|work|(.+))$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^remind\s+me\s+to\s+(.+?)\s+when\s+i\s+leave\s+(home|work|(.+))$""", RegexOption.IGNORE_CASE).find(text)!!
                val task = m.groupValues[1]
                val place = m.groupValues[2].lowercase()
                mapOf("action" to ACTION_ADD, "text" to task, "place" to place, "trigger" to "EXIT")
            }
            Regex("""^(list|show)\s+reminders$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_LIST)
            Regex("""^(clear|delete)\s+(all\s+)?reminders$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_CLEAR)
            Regex("""^this\s+is\s+(home|my\s+home)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SET_HOME)
            Regex("""^this\s+is\s+(work|my\s+work|the\s+office)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SET_WORK)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Reminder action unclear.", "reminders_error")

        return when (action) {
            ACTION_LIST -> listReminders(context)
            ACTION_CLEAR -> clearAll(context)
            ACTION_SET_HOME -> setAnchor(context, "home")
            ACTION_SET_WORK -> setAnchor(context, "work")
            ACTION_ADD -> addReminder(
                context,
                params["text"] ?: "",
                params["place"] ?: "home",
                Reminder.Trigger.valueOf(params["trigger"] ?: "ENTER")
            )
            else -> LocalResponse("Unknown reminder action.", "reminders_error")
        }
    }

    private fun listReminders(context: Context): LocalResponse {
        val list = RemindersStore.all(context)
        if (list.isEmpty()) return LocalResponse("No reminders.", "reminders_list")
        val text = list.joinToString(". ") {
            "${it.text} when you ${if (it.trigger == Reminder.Trigger.ENTER) "arrive" else "leave"}"
        }
        return LocalResponse(text, "reminders_list")
    }

    private fun clearAll(context: Context): LocalResponse {
        val list = RemindersStore.all(context)
        list.forEach {
            RemindersStore.remove(context, it.id)
            GeofenceManager.unregister(context, it.id)
        }
        return LocalResponse("Cleared ${list.size} reminders.", "reminders_cleared")
    }

    private fun setAnchor(context: Context, key: String): LocalResponse {
        val loc = fetchLocation(context) ?: return LocalResponse(
            "Couldn't get current location.", "reminders_error"
        )
        context.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE).edit()
            .putString("anchor_${key}_lat", loc.latitude.toString())
            .putString("anchor_${key}_lng", loc.longitude.toString())
            .apply()
        return LocalResponse("Saved $key location.", "reminders_anchor")
    }

    private fun addReminder(
        context: Context,
        text: String,
        place: String,
        trigger: Reminder.Trigger
    ): LocalResponse {
        if (text.isBlank()) return LocalResponse("What should I remind you about?", "reminders_add")

        if (!GeofenceManager.hasPermissions(context)) {
            return LocalResponse(
                "Background location permission needed for reminders.",
                "reminders_permission"
            )
        }

        val (lat, lng) = resolvePlace(context, place)
            ?: return LocalResponse(
                "Couldn't find $place. Say 'this is home' there to anchor it.",
                "reminders_error"
            )

        val reminder = RemindersStore.add(
            context, text, lat, lng,
            radius = 100f, trigger = trigger
        )
        val ok = GeofenceManager.register(context, reminder)
        if (!ok) {
            RemindersStore.remove(context, reminder.id)
            return LocalResponse("Couldn't set up the geofence.", "reminders_error")
        }
        val verb = if (trigger == Reminder.Trigger.ENTER) "arrive at" else "leave"
        return LocalResponse("I'll remind you to $text when you $verb $place.", "reminders_added")
    }

    /**
     * Resolve a place name to lat/lng.
     *  - "home" / "work" look up anchors in SharedPreferences.
     *  - Other strings go through Geocoder (network or local).
     *  - As a last resort, return the user's current location.
     */
    private fun resolvePlace(context: Context, place: String): Pair<Double, Double>? {
        val prefs = context.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
        if (place == "home" || place == "work") {
            val lat = prefs.getString("anchor_${place}_lat", null)?.toDoubleOrNull()
            val lng = prefs.getString("anchor_${place}_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) return lat to lng
        }
        // Geocode.
        if (Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocationName(place, 1)
                val first = results?.firstOrNull()
                if (first != null) return first.latitude to first.longitude
            } catch (_: Exception) { /* fall through */ }
        }
        // Fallback: current location.
        val loc = fetchLocation(context) ?: return null
        return loc.latitude to loc.longitude
    }

    @Suppress("MissingPermission")
    private fun fetchLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) return null

        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = try {
                Tasks.await(client.lastLocation, 4, TimeUnit.SECONDS)
            } catch (_: Exception) { null }
            if (loc != null) return loc
        } catch (_: Throwable) { /* no play services */ }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try { lm.getProviders(true) } catch (_: Exception) { emptyList<String>() }
        var best: Location? = null
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } ?: continue
            if (best == null || l.accuracy < best.accuracy) best = l
        }
        return best
    }
}
