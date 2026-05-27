package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse

/**
 * System action handler: brightness, wifi, bluetooth, screenshot, settings.
 */
object SystemAction {

    private const val ACTION_BRIGHTNESS_UP = "brightness_up"
    private const val ACTION_BRIGHTNESS_DOWN = "brightness_down"
    private const val ACTION_BRIGHTNESS_AUTO = "brightness_auto"
    private const val ACTION_WIFI_ON = "wifi_on"
    private const val ACTION_WIFI_OFF = "wifi_off"
    private const val ACTION_WIFI_TOGGLE = "wifi_toggle"
    private const val ACTION_BLUETOOTH_ON = "bluetooth_on"
    private const val ACTION_BLUETOOTH_OFF = "bluetooth_off"
    private const val ACTION_BLUETOOTH_TOGGLE = "bluetooth_toggle"
    private const val ACTION_LOCATION_ON = "location_on"
    private const val ACTION_LOCATION_OFF = "location_off"
    private const val ACTION_AIRPLANE_ON = "airplane_on"
    private const val ACTION_AIRPLANE_OFF = "airplane_off"
    private const val ACTION_SCREENSHOT = "screenshot"
    private const val ACTION_OPEN_SETTINGS = "open_settings"
    private const val ACTION_OPEN_WIFI = "open_wifi"
    private const val ACTION_OPEN_BLUETOOTH = "open_bluetooth"
    private const val ACTION_ROTATION_AUTO = "rotation_auto"
    private const val ACTION_ROTATION_PORTRAIT = "rotation_portrait"
    private const val ACTION_ROTATION_LANDSCAPE = "rotation_landscape"
    private const val ACTION_HOTSPOT_ON = "hotspot_on"
    private const val ACTION_HOTSPOT_OFF = "hotspot_off"
    private const val ACTION_DND_ON = "dnd_on"
    private const val ACTION_DND_OFF = "dnd_off"

    fun requiredPermissions() = listOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Brightness
            Regex("""^brightness\s+up$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BRIGHTNESS_UP)
            }
            Regex("""^brightness\s+down$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BRIGHTNESS_DOWN)
            }
            Regex("""^brightness\s+auto$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BRIGHTNESS_AUTO)
            }
            // WiFi
            Regex("""^wifi\s+on$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_WIFI_ON)
            }
            Regex("""^wifi\s+off$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_WIFI_OFF)
            }
            Regex("""^wifi$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_WIFI_TOGGLE)
            }
            Regex("""^turn\s+(on|off)\s+wifi$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^turn\s+(on|off)\s+wifi$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (state == "on") ACTION_WIFI_ON else ACTION_WIFI_OFF)
            }
            // Bluetooth
            Regex("""^bluetooth\s+on$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BLUETOOTH_ON)
            }
            Regex("""^bluetooth\s+off$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BLUETOOTH_OFF)
            }
            Regex("""^bluetooth$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BLUETOOTH_TOGGLE)
            }
            Regex("""^turn\s+(on|off)\s+bluetooth$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^turn\s+(on|off)\s+bluetooth$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (state == "on") ACTION_BLUETOOTH_ON else ACTION_BLUETOOTH_OFF)
            }
            // Location
            Regex("""^location\s+on$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LOCATION_ON)
            }
            Regex("""^location\s+off$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LOCATION_OFF)
            }
            // Airplane mode
            Regex("""^airplane\s+mode\s+on$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_AIRPLANE_ON)
            }
            Regex("""^airplane\s+mode\s+off$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_AIRPLANE_OFF)
            }
            // Screenshot
            Regex("""^screenshot$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SCREENSHOT)
            }
            Regex("""^take\s+screenshot$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SCREENSHOT)
            }
            // Open settings
            Regex("""^open\s+settings$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_SETTINGS)
            }
            Regex("""^open\s+wifi\s+settings$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_WIFI)
            }
            Regex("""^open\s+bluetooth\s+settings$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_BLUETOOTH)
            }
            // Rotation
            Regex("""^rotation\s+auto$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_ROTATION_AUTO)
            }
            Regex("""^rotation\s+(portrait|landscape)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^rotation\s+(portrait|landscape)$""", RegexOption.IGNORE_CASE).find(text)
                val mode = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (mode == "portrait") ACTION_ROTATION_PORTRAIT else ACTION_ROTATION_LANDSCAPE)
            }
            // Hotspot
            Regex("""^hotspot\s+(on|off)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^hotspot\s+(on|off)$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (state == "on") ACTION_HOTSPOT_ON else ACTION_HOTSPOT_OFF)
            }
            // Do Not Disturb
            Regex("""^dnd\s+(on|off|zen\s+mode)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^dnd\s+(on|off|zen\s+mode)$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (state == "on" || state == "zen mode") ACTION_DND_ON else ACTION_DND_OFF)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("System action unclear.", "system_error")

        return when (action) {
            ACTION_BRIGHTNESS_UP -> adjustBrightness(context, 50)
            ACTION_BRIGHTNESS_DOWN -> adjustBrightness(context, -50)
            ACTION_BRIGHTNESS_AUTO -> {
                setAutoBrightness(context)
                LocalResponse("Auto brightness enabled.", "system_brightness")
            }
            ACTION_WIFI_ON -> setWifi(context, true)
            ACTION_WIFI_OFF -> setWifi(context, false)
            ACTION_WIFI_TOGGLE -> setWifi(context, !isWifiEnabled(context))
            ACTION_BLUETOOTH_ON -> setBluetooth(context, true)
            ACTION_BLUETOOTH_OFF -> setBluetooth(context, false)
            ACTION_BLUETOOTH_TOGGLE -> setBluetooth(context, !isBluetoothEnabled(context))
            ACTION_LOCATION_ON -> openLocationSettings(context)
            ACTION_LOCATION_OFF -> openLocationSettings(context)
            ACTION_AIRPLANE_ON -> setAirplaneMode(context, true)
            ACTION_AIRPLANE_OFF -> setAirplaneMode(context, false)
            ACTION_SCREENSHOT -> takeScreenshot(context)
            ACTION_OPEN_SETTINGS -> openSettings(context)
            ACTION_OPEN_WIFI -> openWifiSettings(context)
            ACTION_OPEN_BLUETOOTH -> openBluetoothSettings(context)
            ACTION_ROTATION_AUTO -> setRotation(context, Settings.System.UPDATE_MODE_AUTO)
            ACTION_ROTATION_PORTRAIT -> setRotation(context, 0)
            ACTION_ROTATION_LANDSCAPE -> setRotation(context, 1)
            ACTION_HOTSPOT_ON, ACTION_HOTSPOT_OFF -> {
                LocalResponse("Hotspot control requires manual settings.", "system_hotspot")
            }
            ACTION_DND_ON, ACTION_DND_OFF -> {
                LocalResponse("DND control requires manual settings.", "system_dnd")
            }
            else -> LocalResponse("Unknown system action.", "system_error")
        }
    }

    private fun adjustBrightness(context: Context, delta: Int): LocalResponse {
        return try {
            val window = (context as? android.app.Activity)?.window
            val layoutParams = window?.attributes ?: return LocalResponse("Couldn't adjust brightness.", "system_error")
            
            var currentBrightness = layoutParams.screenBrightness
            if (currentBrightness < 0) currentBrightness = 0.5f
            
            val newBrightness = (currentBrightness + delta / 255f).coerceIn(0.01f, 1f)
            layoutParams.screenBrightness = newBrightness
            window.attributes = layoutParams
            
            val percent = (newBrightness * 100).toInt()
            LocalResponse("Brightness at $percent percent.", "system_brightness")
        } catch (e: Exception) {
            LocalResponse("Couldn't adjust brightness.", "system_error")
        }
    }

    private fun setAutoBrightness(context: Context) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    private fun setWifi(context: Context, enable: Boolean): LocalResponse {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = enable
            LocalResponse(if (enable) "WiFi on." else "WiFi off.", "system_wifi")
        } catch (e: Exception) {
            LocalResponse("Couldn't control WiFi.", "system_error")
        }
    }

    private fun isBluetoothEnabled(context: Context): Boolean {
        val nfcManager = context.getSystemService(Context.NFC_SERVICE) as? NfcAdapter
        // Note: Direct Bluetooth state check requires BLUETOOTH permission
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun setBluetooth(context: Context, enable: Boolean): LocalResponse {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (enable) {
                if (!adapter.isEnabled) adapter.enable()
            } else {
                if (adapter.isEnabled) adapter.disable()
            }
            LocalResponse(if (enable) "Bluetooth on." else "Bluetooth off.", "system_bluetooth")
        } catch (e: Exception) {
            LocalResponse("Couldn't control Bluetooth.", "system_error")
        }
    }

    private fun setAirplaneMode(context: Context, enable: Boolean): LocalResponse {
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.AIRPLANE_MODE_ON,
                if (enable) 1 else 0
            )
            val intent = context.packageManager.getLaunchIntentForPackage("com.android.settings")
            intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            LocalResponse(if (enable) "Airplane mode on." else "Airplane mode off.", "system_airplane")
        } catch (e: Exception) {
            LocalResponse("Couldn't control airplane mode.", "system_error")
        }
    }

    private fun setRotation(context: Context, mode: Int): LocalResponse {
        return try {
            @Suppress("DEPRECATION")
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_ROTATION,
                mode
            )
            LocalResponse("Rotation set.", "system_rotation")
        } catch (e: Exception) {
            LocalResponse("Couldn't set rotation.", "system_error")
        }
    }

    private fun takeScreenshot(context: Context): LocalResponse {
        // Screenshot requires root or MediaProjection API - open the screenshot shortcut
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://media/internal/images/screenshot")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Taking screenshot.", "system_screenshot")
        } catch (e: Exception) {
            // Open quick settings as fallback
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening settings for screenshot.", "system_screenshot")
            } catch (e2: Exception) {
                LocalResponse("Screenshot not available.", "system_error")
            }
        }
    }

    private fun openSettings(context: Context): LocalResponse {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening settings.", "system_settings")
        } catch (e: Exception) {
            LocalResponse("Couldn't open settings.", "system_error")
        }
    }

    private fun openWifiSettings(context: Context): LocalResponse {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening WiFi settings.", "system_wifi")
        } catch (e: Exception) {
            LocalResponse("Couldn't open WiFi settings.", "system_error")
        }
    }

    private fun openBluetoothSettings(context: Context): LocalResponse {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening Bluetooth settings.", "system_bluetooth")
        } catch (e: Exception) {
            LocalResponse("Couldn't open Bluetooth settings.", "system_error")
        }
    }

    private fun openLocationSettings(context: Context): LocalResponse {
        return try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening location settings.", "system_location")
        } catch (e: Exception) {
            LocalResponse("Couldn't open location settings.", "system_error")
        }
    }
}