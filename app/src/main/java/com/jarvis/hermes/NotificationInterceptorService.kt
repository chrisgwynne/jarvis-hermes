package com.jarvis.hermes

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent

/**
 * Notification interceptor (AccessibilityService).
 *
 * Two jobs:
 *  1. Reads notifications from messaging apps and forwards them to VoiceService
 *     via SharedPreferences.
 *  2. Exposes the global actions (back, home, recents, lock, etc.) to UiAction
 *     via a singleton reference, so spoken commands can drive system UI.
 *
 * If the user hasn't enabled the service, `instance` is null and callers fall
 * back to whatever non-accessibility behaviour they support.
 */
class NotificationInterceptorService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "jarvis_hermes"
        private const val KEY_LATEST_NOTIFICATION = "latest_notification"
        private const val KEY_NOTIFICATION_TIMESTAMP = "notification_timestamp"

        @Volatile var instance: NotificationInterceptorService? = null
            private set

        // Re-export the SDK constants so UiAction doesn't need an accessibility import.
        const val GLOBAL_BACK = GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS = GLOBAL_ACTION_RECENTS
        const val GLOBAL_NOTIFICATIONS = GLOBAL_ACTION_NOTIFICATIONS
        const val GLOBAL_QUICK_SETTINGS = GLOBAL_ACTION_QUICK_SETTINGS
        const val GLOBAL_POWER_DIALOG = GLOBAL_ACTION_POWER_DIALOG
        const val GLOBAL_LOCK_SCREEN = GLOBAL_ACTION_LOCK_SCREEN

        // Messaging app packages.
        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",    // Google Messages / SMS
            "com.whatsapp",                          // WhatsApp
            "org.thoughtcrime.securesms",            // Signal
            "com.facebook.orca",                     // Facebook Messenger
            "com.facebook.mlite",                    // Messenger Lite
            "com.instagram.android",                 // Instagram DM
            "com.twitter.android",                   // Twitter DM
            "com.skype.raider",                      // Skype
            "com.viber.voip",                        // Viber
            "org.telegram.messenger",                // Telegram
            "jp.naver.line.android",                 // LINE
            "com.kakao.talk",                        // KakaoTalk
            "com.discord",                           // Discord
            "com.Slack"                              // Slack (case-sensitive in store)
        )
    }

    override fun onServiceConnected() {
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            notificationTimeout = 100
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // We don't need window content for notifications, but we need it for
            // the global actions to be permitted on some OEMs.
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (!MESSAGING_PACKAGES.any { packageName == it || packageName.startsWith(it) }) return

        val parcelable = event.parcelableData
        val (sender, message) = if (parcelable is Notification) {
            extractFromNotification(parcelable, packageName)
        } else {
            extractFromText(event, packageName)
        }

        if (sender.isBlank() && message.isBlank()) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LATEST_NOTIFICATION, "$sender|$message")
            .putString("latest_notification_pkg", packageName)
            .putLong(KEY_NOTIFICATION_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    override fun onInterrupt() { /* required, unused */ }

    private fun extractFromNotification(n: Notification, pkg: String): Pair<String, String> {
        val extras: Bundle = n.extras ?: return "" to ""
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sender = title.ifBlank { friendlyAppName(pkg) }
        return sender to text
    }

    private fun extractFromText(event: AccessibilityEvent, pkg: String): Pair<String, String> {
        val parts = event.text?.map { it.toString() }?.filter { it.isNotBlank() }.orEmpty()
        if (parts.isEmpty()) return "" to ""
        val sender = parts.firstOrNull().orEmpty().take(40).ifBlank { friendlyAppName(pkg) }
        val message = parts.drop(1).joinToString(" ").ifBlank { parts.joinToString(" ") }
        return sender to message
    }

    private fun friendlyAppName(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "com.google.android.apps.messaging" -> "Messages"
        "org.thoughtcrime.securesms" -> "Signal"
        "org.telegram.messenger" -> "Telegram"
        "com.facebook.orca", "com.facebook.mlite" -> "Messenger"
        else -> pkg.substringAfterLast('.')
    }
}
