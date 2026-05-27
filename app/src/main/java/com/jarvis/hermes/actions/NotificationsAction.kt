package com.jarvis.hermes.actions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.LocalResponse

/**
 * Notifications action handler: read messages, show notifications.
 */
object NotificationsAction {

    private const val ACTION_READ = "read"
    private const val ACTION_DISMISS = "dismiss"
    private const val ACTION_OPEN = "open"
    private const val ACTION_CLEAR = "clear"

    fun requiredPermissions() = listOf(
        Manifest.permission.READ_NOTIFICATIONS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Read notifications
            Regex("""^(read|show|what\s+do\s+I\s+have|what('?s| is)\s+on)\s*(my)?\s*(notifications?|messages?)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_READ)
            }
            // Dismiss notification
            Regex("""^dismiss\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^dismiss\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_DISMISS, "app" to (match?.groupValues?.get(1) ?: ""))
            }
            // Open notification source
            Regex("""^open\s+(.+?)\s+(notifications?|messages?)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^open\s+(.+?)\s+(notifications?|messages?)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_OPEN, "app" to (match?.groupValues?.get(1) ?: ""))
            }
            // Clear all notifications
            Regex("""^clear\s+(all\s+)?notifications?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_CLEAR)
            }
            // "what notifications do I have"
            Regex("""^what\s+notifications?\s+(do\s+I\s+have|are\s+there)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_READ)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Notification action unclear.", "notifications_error")

        val missingPerms = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            return LocalResponse("Notification permission not granted.", "notifications_permission")
        }

        return when (action) {
            ACTION_READ -> readNotifications(context)
            ACTION_DISMISS -> {
                val app = params["app"] ?: ""
                dismissNotification(context, app)
            }
            ACTION_OPEN -> {
                val app = params["app"] ?: ""
                openNotifications(context, app)
            }
            ACTION_CLEAR -> clearNotifications(context)
            else -> LocalResponse("Unknown notification action.", "notifications_error")
        }
    }

    private fun readNotifications(context: Context): LocalResponse {
        // Note: Reading notifications requires NotificationListenerService
        // which needs user approval. This opens the notification settings.
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            
            // Try to get notifications directly
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notifications = nm.activeNotifications
                if (notifications.isNotEmpty()) {
                    val summary = notifications.take(5).joinToString(". ") { it.packageName.substringAfterLast('.') }
                    return LocalResponse("Recent notifications: $summary", "notifications_read")
                }
            } catch (e: Exception) {
                // Fall through
            }
            
            return LocalResponse("Opening notification settings.", "notifications_read")
        } catch (e: Exception) {
            return LocalResponse("Couldn't access notifications.", "notifications_error")
        }
    }

    private fun dismissNotification(context: Context, appName: String): LocalResponse {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifications = nm.activeNotifications
            
            val toDismiss = notifications.find { 
                it.packageName.contains(appName, ignoreCase = true) ||
                getAppName(context, it.packageName).contains(appName, ignoreCase = true)
            }
            
            if (toDismiss != null) {
                nm.cancel(toDismiss.key)
                LocalResponse("Dismissed notification from ${toDismiss.packageName.substringAfterLast('.')}.", "notifications_dismiss")
            } else {
                LocalResponse("Couldn't find notification from $appName.", "notifications_error")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't dismiss notification.", "notifications_error")
        }
    }

    private fun openNotifications(context: Context, appName: String): LocalResponse {
        return try {
            // Try to launch the app
            val launchIntent = context.packageManager.getLaunchIntentForPackage(appName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                LocalResponse("Opening $appName.", "notifications_open")
            } else {
                LocalResponse("Couldn't open $appName.", "notifications_error")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't open $appName.", "notifications_error")
        }
    }

    private fun clearNotifications(context: Context): LocalResponse {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancelAll()
            LocalResponse("Cleared all notifications.", "notifications_cleared")
        } catch (e: Exception) {
            LocalResponse("Couldn't clear notifications.", "notifications_error")
        }
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
