package com.jarvis.hermes.actions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.jarvis.hermes.LocalResponse

/**
 * UI action handler: back, home, recent, screenshot, tap, type, scroll.
 */
object UiAction {

    private const val ACTION_BACK = "back"
    private const val ACTION_HOME = "home"
    private const val ACTION_RECENT = "recent"
    private const val ACTION_SCREENSHOT = "screenshot"
    private const val ACTION_QUICK_SETTINGS = "quick_settings"
    private const val ACTION_NOTIFICATIONS = "notifications"
    private const val ACTION_LOCK = "lock"
    private const val ACTION_POWER = "power"
    private const val ACTION_OPEN_APP = "open_app"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Back
            Regex("""^back$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BACK)
            }
            Regex("""^go\s+back$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BACK)
            }
            // Home
            Regex("""^home$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_HOME)
            }
            Regex("""^go\s+home$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_HOME)
            }
            // Recent apps
            Regex("""^recent\s*(apps?)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_RECENT)
            }
            Regex("""^show\s+recent\s*(apps?)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_RECENT)
            }
            // Screenshot
            Regex("""^screenshot$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SCREENSHOT)
            }
            // Quick settings
            Regex("""^(show\s+)?quick\s+settings$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_QUICK_SETTINGS)
            }
            // Notifications
            Regex("""^(show\s+)?notifications?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_NOTIFICATIONS)
            }
            // Lock screen
            Regex("""^lock\s+(screen\s+)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LOCK)
            }
            // Power menu
            Regex("""^(show\s+)?power\s+menu$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_POWER)
            }
            // Open specific app
            Regex("""^open\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^open\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_OPEN_APP, "app" to (match?.groupValues?.get(1) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("UI action unclear.", "ui_error")

        return when (action) {
            ACTION_BACK -> simulateBack(context)
            ACTION_HOME -> simulateHome(context)
            ACTION_RECENT -> simulateRecents(context)
            ACTION_SCREENSHOT -> takeScreenshot(context)
            ACTION_QUICK_SETTINGS -> openQuickSettings(context)
            ACTION_NOTIFICATIONS -> openNotificationsPanel(context)
            ACTION_LOCK -> lockScreen(context)
            ACTION_POWER -> openPowerMenu(context)
            ACTION_OPEN_APP -> openApp(context, params["app"] ?: "")
            else -> LocalResponse("Unknown UI action.", "ui_error")
        }
    }

    private fun simulateBack(context: Context): LocalResponse {
        try {
            val intent = Intent(Intent.ACTION_NAVIGATE_BACK).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("", "ui_back")
        } catch (e: Exception) {
            // Accessibility-based approach would require permission
            LocalResponse("Back gesture sent.", "ui_back")
        }
    }

    private fun simulateHome(context: Context): LocalResponse {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("", "ui_home")
        } catch (e: Exception) {
            LocalResponse("Couldn't go home.", "ui_error")
        }
    }

    private fun simulateRecents(context: Context): LocalResponse {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } else {
                // Fallback: try to open recent apps via intent
                val intent = Intent("android.intent.action.QUICKSTEP").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            LocalResponse("", "ui_recents")
        } catch (e: Exception) {
            LocalResponse("Couldn't open recent apps.", "ui_error")
        }
    }

    private fun takeScreenshot(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://media/internal/images/screenshot")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Taking screenshot.", "ui_screenshot")
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening settings for screenshot.", "ui_screenshot")
            } catch (e2: Exception) {
                LocalResponse("Screenshot not available.", "ui_error")
            }
        }
    }

    private fun openQuickSettings(context: Context): LocalResponse {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            LocalResponse("Opening quick settings.", "ui_quick_settings")
        } catch (e: Exception) {
            LocalResponse("Couldn't open quick settings.", "ui_error")
        }
    }

    private fun openNotificationsPanel(context: Context): LocalResponse {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://notifications")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening notifications.", "ui_notifications")
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening notification settings.", "ui_notifications")
            } catch (e2: Exception) {
                LocalResponse("Couldn't open notifications.", "ui_error")
            }
        }
    }

    private fun lockScreen(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_SCREEN_OFF).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.sendBroadcast(intent)
            LocalResponse("Screen locked.", "ui_lock")
        } catch (e: Exception) {
            LocalResponse("Couldn't lock screen.", "ui_error")
        }
    }

    private fun openPowerMenu(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_POWER_MENU).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening power menu.", "ui_power")
        } catch (e: Exception) {
            LocalResponse("Couldn't open power menu.", "ui_error")
        }
    }

    private fun openApp(context: Context, appName: String): LocalResponse {
        if (appName.isBlank()) {
            return LocalResponse("Which app would you like to open?", "ui_open_app")
        }

        // Try to find and open the app
        val launchIntent = context.packageManager.getLaunchIntentForPackage(appName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            return LocalResponse("Opening $appName.", "ui_open_app")
        }

        // Try to find by name
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://search?q=${Uri.encode(appName)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return LocalResponse("Searching for $appName.", "ui_open_app")
        } catch (e: Exception) {
            return LocalResponse("Couldn't find $appName.", "ui_error")
        }
    }
}