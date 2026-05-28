package com.jarvis.hermes.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.hermes.LocalResponse

/**
 * Notifications action handler: read messages, show notifications, open the
 * notification listener settings page.
 *
 * Reading and dismissing arbitrary notifications requires the user to bind a
 * NotificationListenerService — there is no "READ_NOTIFICATIONS" runtime
 * permission. We surface the settings panel for that.
 */
object NotificationsAction {

    private const val ACTION_READ = "read"
    private const val ACTION_DISMISS = "dismiss"
    private const val ACTION_OPEN = "open"
    private const val ACTION_CLEAR = "clear"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(read|show|what('?s| is)\s+on)\s*(my)?\s*(notifications?|messages?)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_READ)
            Regex("""^what\s+notifications?\s+(do\s+I\s+have|are\s+there)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_READ)
            Regex("""^dismiss\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^dismiss\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_DISMISS, "app" to (match?.groupValues?.get(1) ?: ""))
            }
            Regex("""^open\s+(.+?)\s+(notifications?|messages?)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^open\s+(.+?)\s+(notifications?|messages?)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_OPEN, "app" to (match?.groupValues?.get(1) ?: ""))
            }
            Regex("""^clear\s+(all\s+)?notifications?$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_CLEAR)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Notification action unclear.", "notifications_error")

        return when (action) {
            ACTION_READ -> readNotifications(context)
            ACTION_DISMISS -> dismissNotification(context, params["app"] ?: "")
            ACTION_OPEN -> openApp(context, params["app"] ?: "")
            ACTION_CLEAR -> clearOwnNotifications(context)
            else -> LocalResponse("Unknown notification action.", "notifications_error")
        }
    }

    private fun readNotifications(context: Context): LocalResponse {
        // Without a NotificationListenerService we cannot enumerate other apps'
        // notifications. nm.getActiveNotifications() only returns *this* app's.
        // The most useful action is to surface the listener settings so the
        // user can enable our accessibility-based reader.
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse(
                "Enable Jarvis in notification access to read notifications.",
                "notifications_read"
            )
        } catch (e: Exception) {
            LocalResponse("Couldn't open notification settings.", "notifications_error")
        }
    }

    private fun dismissNotification(context: Context, appName: String): LocalResponse {
        // Dismissing other apps' notifications requires a NotificationListenerService.
        // Cancel only our own — surface settings otherwise.
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val mine = nm.activeNotifications.filter { it.packageName == context.packageName }
            if (mine.isNotEmpty()) {
                mine.forEach { nm.cancel(it.id) }
                LocalResponse("Dismissed Jarvis notifications.", "notifications_dismiss")
            } else {
                LocalResponse("Use the notification shade to dismiss other apps' notifications.", "notifications_dismiss")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't dismiss notification.", "notifications_error")
        }
    }

    private fun openApp(context: Context, appName: String): LocalResponse {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(appName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                LocalResponse("Opening $appName.", "notifications_open")
            } else {
                LocalResponse("Couldn't find $appName.", "notifications_error")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't open $appName.", "notifications_error")
        }
    }

    private fun clearOwnNotifications(context: Context): LocalResponse {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Cancel only ours — cancelAll() is restricted to ours anyway.
            nm.cancelAll()
            LocalResponse("Cleared Jarvis notifications.", "notifications_cleared")
        } catch (e: Exception) {
            LocalResponse("Couldn't clear notifications.", "notifications_error")
        }
    }
}
