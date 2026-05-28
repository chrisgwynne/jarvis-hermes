package com.jarvis.hermes

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification listener.
 *
 * Three jobs:
 *  1. Mirror incoming messaging-app notifications into SharedPreferences
 *     so VoiceService can read them aloud (with PrivacyFilter applied).
 *  2. Expose `replyTo(packageName, message)` so the voice command
 *     "reply to john: running late" can send via the notification's
 *     RemoteInput Action — works for WhatsApp / Signal / Messages /
 *     Telegram without opening the app.
 *  3. Provide reliable access to active notifications for `dismissBy*`
 *     commands.
 *
 * Distinct from `NotificationInterceptorService` (AccessibilityService)
 * because the two grants are independent — users can enable one without
 * the other.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
            private set

        private const val PREFS = "jarvis_hermes"
        private const val KEY_LATEST_NOTIFICATION = "latest_notification"
        private const val KEY_LATEST_PACKAGE = "latest_notification_pkg"
        private const val KEY_LATEST_KEY = "latest_notification_key"
        private const val KEY_TIMESTAMP = "notification_timestamp"

        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.thoughtcrime.securesms",
            "com.facebook.orca",
            "com.facebook.mlite",
            "com.instagram.android",
            "com.twitter.android",
            "com.skype.raider",
            "com.viber.voip",
            "org.telegram.messenger",
            "jp.naver.line.android",
            "com.kakao.talk",
            "com.discord",
            "com.Slack"
        )

        fun friendlyAppName(pkg: String): String = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.google.android.apps.messaging" -> "Messages"
            "org.thoughtcrime.securesms" -> "Signal"
            "org.telegram.messenger" -> "Telegram"
            "com.facebook.orca", "com.facebook.mlite" -> "Messenger"
            else -> pkg.substringAfterLast('.')
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MESSAGING_PACKAGES) return

        val (sender, message) = extract(sbn) ?: return
        if (sender.isBlank() && message.isBlank()) return

        if (PrivacyFilter.isSensitive(sbn.packageName, sender, message)) {
            return // never persist sensitive content
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LATEST_NOTIFICATION, "$sender|$message")
            .putString(KEY_LATEST_PACKAGE, sbn.packageName)
            .putString(KEY_LATEST_KEY, sbn.key)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun extract(sbn: StatusBarNotification): Pair<String, String>? {
        val n = sbn.notification ?: return null
        val extras: Bundle = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        return title.ifBlank { friendlyAppName(sbn.packageName) } to text
    }

    /**
     * Reply to the most recent messaging notification from [packageName]
     * (or any messaging notification if [packageName] is null) using the
     * RemoteInput action. Returns true if a RemoteInput action was found
     * and fired.
     */
    fun reply(packageName: String?, message: String): Boolean {
        val targets = activeNotifications?.toList().orEmpty()
            .filter { it.packageName in MESSAGING_PACKAGES }
            .filter { packageName == null || it.packageName == packageName }
            .sortedByDescending { it.postTime }
        for (sbn in targets) {
            if (sendReply(sbn, message)) return true
        }
        return false
    }

    private fun sendReply(sbn: StatusBarNotification, message: String): Boolean {
        val actions = sbn.notification?.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            val replyInput = remoteInputs.firstOrNull { it.allowFreeFormInput }
                ?: remoteInputs.firstOrNull()
                ?: continue
            val intent = Intent()
            val bundle = Bundle().apply { putCharSequence(replyInput.resultKey, message) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            try {
                action.actionIntent.send(this, 0, intent)
                return true
            } catch (e: PendingIntent.CanceledException) {
                continue
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    /**
     * Dismiss notifications matching a package name (e.g. "dismiss
     * WhatsApp"). Returns the count dismissed.
     */
    fun dismissByPackage(packageName: String): Int {
        val matches = activeNotifications?.filter {
            it.packageName.equals(packageName, ignoreCase = true) ||
            friendlyAppName(it.packageName).equals(packageName, ignoreCase = true)
        }.orEmpty()
        var count = 0
        for (sbn in matches) {
            try { cancelNotification(sbn.key); count++ } catch (_: Exception) {}
        }
        return count
    }

    fun listSummary(): String {
        val all = activeNotifications?.toList().orEmpty()
            .filter { it.packageName in MESSAGING_PACKAGES }
            .filter {
                val ex = it.notification?.extras
                val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                !PrivacyFilter.isSensitive(it.packageName, title, text)
            }
        if (all.isEmpty()) return "No new messages."
        val byApp = all.groupBy { it.packageName }
        return byApp.entries.joinToString(". ") { (pkg, items) ->
            "${items.size} from ${friendlyAppName(pkg)}"
        }
    }
}
