package com.jarvis.hermes.actions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse

/**
 * System action handler: brightness, wifi, bluetooth, screenshot, settings.
 *
 * Many of these toggles cannot be performed silently on modern Android:
 *  - WiFi: WifiManager.setWifiEnabled is no-op on API 29+, so we open settings.
 *  - Bluetooth: BluetoothAdapter.enable/disable is restricted on API 33+, so we
 *    open settings.
 *  - Brightness: Settings.System.putInt requires WRITE_SETTINGS (Settings.canWrite).
 *  - Airplane mode: requires system signature permission, so we open settings.
 *
 * Where the action is forbidden we deep-link to the relevant settings panel and
 * tell the user that they need to flick the switch themselves — this is the
 * Google-recommended pattern.
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
    private const val ACTION_LOCATION = "location_settings"
    private const val ACTION_AIRPLANE = "airplane_settings"
    private const val ACTION_OPEN_SETTINGS = "open_settings"
    private const val ACTION_OPEN_WIFI = "open_wifi"
    private const val ACTION_OPEN_BLUETOOTH = "open_bluetooth"
    private const val ACTION_ROTATION_AUTO = "rotation_auto"
    private const val ACTION_ROTATION_PORTRAIT = "rotation_portrait"
    private const val ACTION_ROTATION_LANDSCAPE = "rotation_landscape"
    private const val ACTION_DND = "dnd_settings"

    fun requiredPermissions() = listOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^brightness\s+up$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BRIGHTNESS_UP)
            Regex("""^brightness\s+down$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BRIGHTNESS_DOWN)
            Regex("""^(brightness\s+auto|auto\s+brightness)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BRIGHTNESS_AUTO)
            Regex("""^(wifi\s+on|turn\s+on\s+wifi)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_WIFI_ON)
            Regex("""^(wifi\s+off|turn\s+off\s+wifi)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_WIFI_OFF)
            Regex("""^wifi$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_WIFI_TOGGLE)
            Regex("""^(bluetooth\s+on|turn\s+on\s+bluetooth)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BLUETOOTH_ON)
            Regex("""^(bluetooth\s+off|turn\s+off\s+bluetooth)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BLUETOOTH_OFF)
            Regex("""^bluetooth$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BLUETOOTH_TOGGLE)
            Regex("""^location\s+(on|off|settings)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_LOCATION)
            Regex("""^airplane\s+mode\s*(on|off|toggle)?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_AIRPLANE)
            Regex("""^open\s+settings$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_OPEN_SETTINGS)
            Regex("""^open\s+wifi(\s+settings)?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_OPEN_WIFI)
            Regex("""^open\s+bluetooth(\s+settings)?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_OPEN_BLUETOOTH)
            Regex("""^(rotation\s+auto|auto\s+rotate(\s+on)?)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_ROTATION_AUTO)
            Regex("""^(rotation\s+portrait|lock\s+portrait|portrait\s+only)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_ROTATION_PORTRAIT)
            Regex("""^(rotation\s+landscape|lock\s+landscape|landscape\s+only)$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_ROTATION_LANDSCAPE)
            Regex("""^(dnd|do\s+not\s+disturb)(\s+(on|off|toggle))?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_DND)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("System action unclear.", "system_error")

        return when (action) {
            ACTION_BRIGHTNESS_UP -> adjustBrightness(context, +25)
            ACTION_BRIGHTNESS_DOWN -> adjustBrightness(context, -25)
            ACTION_BRIGHTNESS_AUTO -> setAutoBrightness(context)
            ACTION_WIFI_ON -> setWifi(context, true)
            ACTION_WIFI_OFF -> setWifi(context, false)
            ACTION_WIFI_TOGGLE -> setWifi(context, !isWifiEnabled(context))
            ACTION_BLUETOOTH_ON -> setBluetooth(context, true)
            ACTION_BLUETOOTH_OFF -> setBluetooth(context, false)
            ACTION_BLUETOOTH_TOGGLE -> openSettingsPanel(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings.")
            ACTION_LOCATION -> openSettingsPanel(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Opening location settings.")
            ACTION_AIRPLANE -> openSettingsPanel(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opening airplane mode settings.")
            ACTION_OPEN_SETTINGS -> openSettingsPanel(context, Settings.ACTION_SETTINGS, "Opening settings.")
            ACTION_OPEN_WIFI -> openSettingsPanel(context, Settings.ACTION_WIFI_SETTINGS, "Opening WiFi settings.")
            ACTION_OPEN_BLUETOOTH -> openSettingsPanel(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings.")
            ACTION_ROTATION_AUTO -> setRotation(context, auto = true, landscape = false)
            ACTION_ROTATION_PORTRAIT -> setRotation(context, auto = false, landscape = false)
            ACTION_ROTATION_LANDSCAPE -> setRotation(context, auto = false, landscape = true)
            ACTION_DND -> openSettingsPanel(context, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "Opening Do Not Disturb settings.")
            else -> LocalResponse("Unknown system action.", "system_error")
        }
    }

    private fun openSettingsPanel(context: Context, action: String, message: String): LocalResponse {
        return try {
            val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            LocalResponse(message, "system_settings")
        } catch (e: Exception) {
            LocalResponse("Couldn't open settings.", "system_error")
        }
    }

    private fun adjustBrightness(context: Context, deltaPercent: Int): LocalResponse {
        if (!Settings.System.canWrite(context)) {
            // Send user to grant the permission first.
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) { /* ignore */ }
            return LocalResponse("Grant write-settings permission to change brightness.", "system_brightness")
        }
        return try {
            val cr = context.contentResolver
            val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val deltaRaw = (deltaPercent * 255) / 100
            val newVal = (current + deltaRaw).coerceIn(1, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, newVal)
            val percent = (newVal * 100) / 255
            LocalResponse("Brightness $percent percent.", "system_brightness")
        } catch (e: Exception) {
            LocalResponse("Couldn't change brightness.", "system_error")
        }
    }

    private fun setAutoBrightness(context: Context): LocalResponse {
        if (!Settings.System.canWrite(context)) {
            return LocalResponse("Grant write-settings permission first.", "system_brightness")
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
            LocalResponse("Auto brightness on.", "system_brightness")
        } catch (e: Exception) {
            LocalResponse("Couldn't change brightness mode.", "system_error")
        }
    }

    private fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled == true
    }

    private fun setWifi(context: Context, enable: Boolean): LocalResponse {
        // WifiManager.setWifiEnabled is a no-op on API 29+ and throws on 30+.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openSettingsPanel(
                context,
                Settings.Panel.ACTION_WIFI,
                if (enable) "Opening WiFi panel to turn on." else "Opening WiFi panel to turn off."
            )
        } else {
            try {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = enable
                LocalResponse(if (enable) "WiFi on." else "WiFi off.", "system_wifi")
            } catch (e: Exception) {
                LocalResponse("Couldn't control WiFi.", "system_error")
            }
        }
    }

    private fun bluetoothAdapter(context: Context) =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun setBluetooth(context: Context, enable: Boolean): LocalResponse {
        // On API 33+, BluetoothAdapter.enable/disable is restricted to system apps.
        // We always open the settings panel so the user can flick it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect) {
                return openSettingsPanel(
                    context,
                    Settings.ACTION_BLUETOOTH_SETTINGS,
                    "Bluetooth permission needed — opening settings."
                )
            }
        }
        return openSettingsPanel(
            context,
            Settings.ACTION_BLUETOOTH_SETTINGS,
            if (enable) "Opening Bluetooth settings to turn on." else "Opening Bluetooth settings to turn off."
        )
    }

    private fun setRotation(context: Context, auto: Boolean, landscape: Boolean): LocalResponse {
        if (!Settings.System.canWrite(context)) {
            return LocalResponse("Grant write-settings permission to change rotation.", "system_rotation")
        }
        return try {
            val cr = context.contentResolver
            Settings.System.putInt(
                cr,
                Settings.System.ACCELEROMETER_ROTATION,
                if (auto) 1 else 0
            )
            if (!auto) {
                // 0 = portrait, 1 = landscape, 2 = reverse portrait, 3 = reverse landscape
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, if (landscape) 1 else 0)
            }
            LocalResponse(
                when {
                    auto -> "Auto rotate on."
                    landscape -> "Locked landscape."
                    else -> "Locked portrait."
                },
                "system_rotation"
            )
        } catch (e: Exception) {
            LocalResponse("Couldn't set rotation.", "system_error")
        }
    }
}
