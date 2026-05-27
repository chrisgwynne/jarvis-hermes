package com.jarvis.hermes

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Notification interceptor service.
 * Monitors notifications from messaging apps and writes them
 * to SharedPreferences for VoiceService to read.
 */
class NotificationInterceptorService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "jarvis_hermes"
        private const val KEY_LATEST_NOTIFICATION = "latest_notification"
        private const val KEY_NOTIFICATION_TIMESTAMP = "notification_timestamp"

        // Messaging app packages
        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",    // Google Messages / SMS
            "com.whatsapp",                          // WhatsApp
            "org.thoughtcrime.securesms",            // Signal
            "com.signal.android",                    // Signal alternate
            "com.facebook.orca",                     // Facebook Messenger
            "com.facebook.mlite",                    // Facebook Messenger Lite
            "com.instagram.android",                 // Instagram DM
            "com.twitter.android",                  // Twitter DM
            "com.skype.raider",                     // Skype
            "com.viber.voip",                       // Viber
            "com.telegram.messenger",               // Telegram
            "com.zhiliaoapp.musically",            // TikTok DM
            "jp.naver.line.android",               // LINE
            "com.kakao.talk",                       // KakaoTalk
            "com.discord",                          // Discord
            "com.slack"                             // Slack
        )

        var latestNotificationText: String? = null
        var latestNotificationSender: String? = null
        var latestNotificationTimestamp: Long = 0L
    }

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            notificationTimeout = 100
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Check if it's a messaging app
        if (!isMessagingApp(packageName)) {
            return
        }

        // Get notification text
        val text = event.text
        if (text.isNullOrEmpty()) {
            return
        }

        val notificationText = text.joinToString(" ")
        val sender = extractSender(event)

        // Only process if we have meaningful content
        if (notificationText.isBlank()) {
            return
        }

        // Write to SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LATEST_NOTIFICATION, "$sender|$notificationText")
            .putLong(KEY_NOTIFICATION_TIMESTAMP, System.currentTimeMillis())
            .apply()

        latestNotificationText = notificationText
        latestNotificationSender = sender
        latestNotificationTimestamp = System.currentTimeMillis()

        // Broadcast to VoiceService
        val intent = Intent("com.jarvis.hermes.NOTIFICATION_RECEIVED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        // Required but not used
    }

    private fun isMessagingApp(packageName: String): Boolean {
        return MESSAGING_PACKAGES.any { pkg ->
            packageName == pkg || packageName.startsWith(pkg)
        }
    }

    private fun extractSender(event: AccessibilityEvent): String? {
        // Try to get sender from text (usually first line is sender)
        val text = event.text
        if (!text.isNullOrEmpty()) {
            // First item is usually the sender/app name or contact
            val first = text[0]?.toString() ?: ""
            // If it looks like a sender name (not app name), use it
            if (first.isNotBlank() && !first.contains(" ") && first.length < 30) {
                return first
            }
        }

        // Try to extract from content description
        val contentDesc = event.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank()) {
            return contentDesc
        }

        // Fallback to package name
        return event.packageName?.toString()
    }

    /**
     * Clear the latest notification (called by VoiceService after processing).
     */
    fun clearLatestNotification() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_LATEST_NOTIFICATION)
            .remove(KEY_NOTIFICATION_TIMESTAMP)
            .apply()

        latestNotificationText = null
        latestNotificationSender = null
        latestNotificationTimestamp = 0L
    }

    /**
     * Get latest notification.
     * Returns Pair(sender, message) or null.
     */
    fun getLatestNotification(): Pair<String, String>? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val data = prefs.getString(KEY_LATEST_NOTIFICATION, null) ?: return null

        val parts = data.split("|", limit = 2)
        return if (parts.size == 2) {
            Pair(parts[0], parts[1])
        } else null
    }
}