package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.jarvis.hermes.LocalResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Location action handler: where am I, share location, track.
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
            // Where am I
            Regex("""^(where\s+am\s+I|my\s+location|current\s+location)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_CURRENT)
            }
            // Share location
            Regex("""^share\s+(my\s+)?location$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SHARE)
            }
            // Get lat/long
            Regex("""^(what('?s| is)\s+)?lat(itude)?(\s+and\s+)?long(itude)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LATLONG)
            }
            // Open maps
            Regex("""^open\s+(google\s+)?maps$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_MAPS)
            }
            // Navigate to
            Regex("""^navigate\s+(to\s+)?(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^navigate\s+(to\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_NAVIGATE, "destination" to (match?.groupValues?.get(2) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Location action unclear.", "location_error")

        val missingPerms = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            return LocalResponse("Location permission not granted.", "location_permission")
        }

        return when (action) {
            ACTION_CURRENT -> getCurrentLocation(context)
            ACTION_SHARE -> shareLocation(context)
            ACTION_LATLONG -> getLatLong(context)
            ACTION_OPEN_MAPS -> openMaps(context)
            ACTION_NAVIGATE -> navigateTo(context, params["destination"] ?: "")
            else -> LocalResponse("Unknown location action.", "location_error")
        }
    }

    @Suppress("MissingPermission")
    private fun getCurrentLocation(context: Context): LocalResponse {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        var result: LocalResponse = LocalResponse("Couldn't get location.", "location_error")
        val latch = CountDownLatch(1)

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    result = LocalResponse("Your location is latitude ${location.latitude}, longitude ${location.longitude}.", "location_current")
                }
                latch.countDown()
            }.addOnFailureListener {
                result = LocalResponse("Location unavailable.", "location_error")
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            result = LocalResponse("Location error.", "location_error")
        }

        return result
    }

    @Suppress("MissingPermission")
    private fun getLatLong(context: Context): LocalResponse {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        var result: LocalResponse = LocalResponse("Couldn't get coordinates.", "location_error")
        val latch = CountDownLatch(1)

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    result = LocalResponse(
                        "Latitude ${location.latitude}, longitude ${location.longitude}",
                        "location_coordinates"
                    )
                }
                latch.countDown()
            }.addOnFailureListener {
                result = LocalResponse("Location unavailable.", "location_error")
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            result = LocalResponse("Location error.", "location_error")
        }

        return result
    }

    @Suppress("MissingPermission")
    private fun shareLocation(context: Context): LocalResponse {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        var result: LocalResponse = LocalResponse("Opening location sharing.", "location_share")
        val latch = CountDownLatch(1)

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "My location: https://maps.google.com/?q=${location.latitude},${location.longitude}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share location"))
                    result = LocalResponse("Sharing location.", "location_share")
                }
                latch.countDown()
            }.addOnFailureListener {
                result = LocalResponse("Location unavailable.", "location_error")
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            result = LocalResponse("Location error.", "location_error")
        }

        return result
    }

    private fun openMaps(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("geo:0,0")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening maps.", "location_maps")
        } catch (e: Exception) {
            // Try Google Maps specifically
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("com.google.android.apps.maps")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening Google Maps.", "location_maps")
            } catch (e2: Exception) {
                LocalResponse("Couldn't open maps.", "location_error")
            }
        }
    }

    private fun navigateTo(context: Context, destination: String): LocalResponse {
        if (destination.isBlank()) {
            return LocalResponse("Where would you like to go?", "location_navigate")
        }

        return try {
            val encodedDest = Uri.encode(destination)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("google.navigation:q=$encodedDest")
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Navigating to $destination.", "location_navigate")
            } else {
                // Fallback to web maps
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encodedDest")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                LocalResponse("Opening Google Maps for $destination.", "location_navigate")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't start navigation.", "location_error")
        }
    }
}