package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.jarvis.hermes.LocalResponse
import java.util.concurrent.TimeUnit

/**
 * Location action handler.
 *
 *  - Fused provider used when available (Play Services).
 *  - Falls back to LocationManager.getLastKnownLocation across all providers
 *    on phones without Play Services.
 *  - We block synchronously up to 5s; on a fresh boot lastLocation may be
 *    null but caching usually wins.
 */
object LocationAction {

    private const val ACTION_CURRENT = "current"
    private const val ACTION_SHARE = "share"
    private const val ACTION_OPEN_MAPS = "open_maps"
    private const val ACTION_NAVIGATE = "navigate"
    private const val ACTION_LATLONG = "latlong"

    fun requiredPermissions() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(where\s+am\s+i|my\s+location|current\s+location)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_CURRENT)
            Regex("""^share\s+(my\s+)?location$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SHARE)
            Regex("""^(what('?s| is)\s+)?(my\s+)?lat(itude)?(\s+and\s+)?long(itude)?$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_LATLONG)
            Regex("""^open\s+(google\s+)?maps$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN_MAPS)
            Regex("""^navigate\s+(to\s+)?(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^navigate\s+(to\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_NAVIGATE, "destination" to (m?.groupValues?.get(2) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Location action unclear.", "location_error")

        if (action == ACTION_OPEN_MAPS || action == ACTION_NAVIGATE) {
            return when (action) {
                ACTION_OPEN_MAPS -> openMaps(context)
                else -> navigateTo(context, params["destination"] ?: "")
            }
        }

        val granted = requiredPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return LocalResponse("Location permission not granted.", "location_permission")
        if (!locationEnabled(context)) return LocalResponse("Location is off. Turn it on first.", "location_disabled")

        val loc = fetchLocation(context) ?: return LocalResponse("Couldn't get a location fix.", "location_error")

        return when (action) {
            ACTION_CURRENT -> LocalResponse(
                "Your location is latitude ${"%.4f".format(loc.latitude)}, longitude ${"%.4f".format(loc.longitude)}.",
                "location_current",
                mapOf("lat" to loc.latitude.toString(), "lng" to loc.longitude.toString())
            )
            ACTION_LATLONG -> LocalResponse(
                "${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}",
                "location_coordinates"
            )
            ACTION_SHARE -> shareLocation(context, loc)
            else -> LocalResponse("Unknown location action.", "location_error")
        }
    }

    @Suppress("MissingPermission")
    private fun fetchLocation(context: Context): Location? {
        // 1. Fused provider via Play Services.
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val task = client.lastLocation
            val loc = try {
                Tasks.await(task, 4, TimeUnit.SECONDS)
            } catch (_: Exception) { null }
            if (loc != null) return loc
        } catch (_: Throwable) { /* no Play Services */ }

        // 2. LocationManager fallback.
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try { lm.getProviders(true) } catch (_: Exception) { emptyList<String>() }
        var best: Location? = null
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } ?: continue
            if (best == null || l.accuracy < best.accuracy) best = l
        }
        return best
    }

    private fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun shareLocation(context: Context, loc: Location): LocalResponse {
        return try {
            val url = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "My location: $url")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Share location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            LocalResponse("Sharing location.", "location_share")
        } catch (_: Exception) {
            LocalResponse("Couldn't share location.", "location_error")
        }
    }

    private fun openMaps(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("geo:0,0?q=")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Opening maps.", "location_maps")
            } else {
                // Last resort: open the Maps web app.
                val web = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.google.com/maps")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(web)
                LocalResponse("Opening Maps.", "location_maps")
            }
        } catch (_: Exception) {
            LocalResponse("Couldn't open maps.", "location_error")
        }
    }

    private fun navigateTo(context: Context, destination: String): LocalResponse {
        if (destination.isBlank()) return LocalResponse("Where would you like to go?", "location_navigate")
        return try {
            val encoded = Uri.encode(destination)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("google.navigation:q=$encoded")
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Navigating to $destination.", "location_navigate")
            } else {
                val web = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(web)
                LocalResponse("Opening Maps for $destination.", "location_navigate")
            }
        } catch (_: Exception) {
            LocalResponse("Couldn't start navigation.", "location_error")
        }
    }
}
