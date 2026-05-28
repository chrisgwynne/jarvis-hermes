package com.jarvis.hermes.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.jarvis.hermes.LocalResponse
import com.jarvis.hermes.NotificationInterceptorService

/**
 * UI action handler: home, recent, open app, lock, etc.
 *
 * Many of the "system gesture" actions (back, home, recents, power menu, lock,
 * notification shade) require an AccessibilityService with GLOBAL_ACTION
 * permission. Where we have the service, we route through it; otherwise we
 * fall back to a sensible intent (home screen) or tell the user.
 */
object UiAction {

    private const val ACTION_BACK = "back"
    private const val ACTION_HOME = "home"
    private const val ACTION_RECENT = "recent"
    private const val ACTION_QUICK_SETTINGS = "quick_settings"
    private const val ACTION_NOTIFICATIONS_SHADE = "notifications_shade"
    private const val ACTION_LOCK = "lock"
    private const val ACTION_POWER = "power"
    private const val ACTION_OPEN_APP = "open_app"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(go\s+)?back$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_BACK)
            Regex("""^(go\s+)?home$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_HOME)
            Regex("""^(show\s+)?recent(\s+apps?)?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_RECENT)
            Regex("""^(show\s+)?quick\s+settings$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_QUICK_SETTINGS)
            Regex("""^(show\s+)?notifications?(\s+shade)?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_NOTIFICATIONS_SHADE)
            Regex("""^lock(\s+screen)?$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_LOCK)
            Regex("""^(show\s+)?power\s+menu$""", RegexOption.IGNORE_CASE).matches(text) -> mapOf("action" to ACTION_POWER)
            Regex("""^open\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^open\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_OPEN_APP, "app" to (m?.groupValues?.get(1) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("UI action unclear.", "ui_error")

        return when (action) {
            ACTION_BACK -> performGlobal(context, NotificationInterceptorService.GLOBAL_BACK, "Going back.", "Back not available — enable accessibility.")
            ACTION_HOME -> goHome(context)
            ACTION_RECENT -> performGlobal(context, NotificationInterceptorService.GLOBAL_RECENTS, "Showing recents.", "Recents not available — enable accessibility.")
            ACTION_QUICK_SETTINGS -> performGlobal(context, NotificationInterceptorService.GLOBAL_QUICK_SETTINGS, "Quick settings.", "Quick settings not available — enable accessibility.")
            ACTION_NOTIFICATIONS_SHADE -> performGlobal(context, NotificationInterceptorService.GLOBAL_NOTIFICATIONS, "Notifications.", "Cannot pull shade — enable accessibility.")
            ACTION_LOCK -> performGlobal(context, NotificationInterceptorService.GLOBAL_LOCK_SCREEN, "Locking.", "Cannot lock — enable accessibility.")
            ACTION_POWER -> performGlobal(context, NotificationInterceptorService.GLOBAL_POWER_DIALOG, "Power menu.", "Cannot open power menu — enable accessibility.")
            ACTION_OPEN_APP -> openApp(context, params["app"] ?: "")
            else -> LocalResponse("Unknown UI action.", "ui_error")
        }
    }

    private fun performGlobal(context: Context, globalAction: Int, ok: String, fallback: String): LocalResponse {
        val service = NotificationInterceptorService.instance
        return if (service != null) {
            try {
                if (service.performGlobalAction(globalAction)) LocalResponse(ok, "ui_action")
                else LocalResponse(fallback, "ui_error")
            } catch (e: Exception) {
                LocalResponse(fallback, "ui_error")
            }
        } else {
            LocalResponse(fallback, "ui_error")
        }
    }

    private fun goHome(context: Context): LocalResponse {
        return try {
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

    /**
     * Resolve an app by display name OR package name, then launch it.
     * Fall back to a Play Store search if nothing matches.
     */
    private fun openApp(context: Context, query: String): LocalResponse {
        if (query.isBlank()) return LocalResponse("Which app should I open?", "ui_open_app")

        val pm = context.packageManager
        val target = query.trim().lowercase()

        // 1. Direct package name hit.
        pm.getLaunchIntentForPackage(target)?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return try {
                context.startActivity(it)
                LocalResponse("Opening $query.", "ui_open_app")
            } catch (e: Exception) {
                LocalResponse("Couldn't open $query.", "ui_error")
            }
        }

        // 2. Search across installed launchable apps by label.
        val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label == target || label.contains(target)
        }
        if (match != null) {
            return try {
                val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                    ?: return LocalResponse("Couldn't open $query.", "ui_error")
                launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launch)
                LocalResponse("Opening ${match.loadLabel(pm)}.", "ui_open_app")
            } catch (e: Exception) {
                LocalResponse("Couldn't open $query.", "ui_error")
            }
        }

        // 3. Fall back to Play Store search.
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://search?q=${Uri.encode(query)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("$query isn't installed. Opening Play Store.", "ui_open_app")
        } catch (e: Exception) {
            LocalResponse("Couldn't find $query.", "ui_error")
        }
    }
}
