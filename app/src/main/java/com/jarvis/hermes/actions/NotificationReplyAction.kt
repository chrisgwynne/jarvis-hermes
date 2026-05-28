package com.jarvis.hermes.actions

import android.content.Context
import com.jarvis.hermes.JarvisNotificationListener
import com.jarvis.hermes.LocalResponse

/**
 * In-line reply to messaging notifications via RemoteInput.
 *
 * Requires the user to have granted notification access to
 * `JarvisNotificationListener`. Forms supported:
 *  - "reply to john: running late"
 *  - "reply whatsapp: yes"
 *  - "reply: ok"  (replies to most recent messaging notification)
 *  - "what messages do I have" / "any new messages"
 *
 * Sits AFTER SmsAction in the classifier order — "reply" still goes to
 * SmsAction for SMS contexts; this matches the "reply to X: …" form
 * which SmsAction doesn't match.
 */
object NotificationReplyAction {

    private const val ACTION_REPLY = "reply"
    private const val ACTION_LIST = "list"
    private const val ACTION_DISMISS = "dismiss"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^reply\s+to\s+(.+?):\s*(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^reply\s+to\s+(.+?):\s*(.+)$""", RegexOption.IGNORE_CASE).find(text)!!
                mapOf("action" to ACTION_REPLY,
                      "target" to m.groupValues[1].trim(),
                      "message" to m.groupValues[2].trim())
            }
            Regex("""^reply\s+(whatsapp|signal|telegram|messenger|messages|sms):\s*(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^reply\s+(whatsapp|signal|telegram|messenger|messages|sms):\s*(.+)$""", RegexOption.IGNORE_CASE).find(text)!!
                mapOf("action" to ACTION_REPLY,
                      "target" to m.groupValues[1].trim(),
                      "message" to m.groupValues[2].trim())
            }
            Regex("""^reply:\s*(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^reply:\s*(.+)$""", RegexOption.IGNORE_CASE).find(text)!!
                mapOf("action" to ACTION_REPLY,
                      "target" to "",
                      "message" to m.groupValues[1].trim())
            }
            Regex("""^(any\s+new|what)\s+(messages?|notifications?)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_LIST)
            Regex("""^dismiss\s+(.+?)\s+notifications?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^dismiss\s+(.+?)\s+notifications?$""", RegexOption.IGNORE_CASE).find(text)!!
                mapOf("action" to ACTION_DISMISS, "target" to m.groupValues[1].trim())
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val listener = JarvisNotificationListener.instance
            ?: return LocalResponse(
                "Enable notification access to use this.",
                "notification_reply_unavailable"
            )
        return when (params["action"]) {
            ACTION_LIST -> LocalResponse(listener.listSummary(), "notification_list")
            ACTION_REPLY -> {
                val target = params["target"].orEmpty()
                val message = params["message"].orEmpty()
                if (message.isBlank()) return LocalResponse("What's the reply?", "notification_reply")
                val packageHint = packageFromTarget(target)
                val ok = listener.reply(packageHint, message)
                if (ok) LocalResponse("Replied: $message", "notification_reply")
                else LocalResponse(
                    if (target.isBlank()) "No reply action available on the recent messages."
                    else "Couldn't reply — no recent message from $target.",
                    "notification_reply_failed"
                )
            }
            ACTION_DISMISS -> {
                val target = params["target"].orEmpty()
                val n = listener.dismissByPackage(target)
                if (n > 0) LocalResponse("Dismissed $n notification${if (n != 1) "s" else ""}.", "notification_dismissed")
                else LocalResponse("No notifications from $target.", "notification_dismiss_failed")
            }
            else -> LocalResponse("Unknown notification reply action.", "notification_reply_error")
        }
    }

    private fun packageFromTarget(target: String): String? {
        if (target.isBlank()) return null
        return when (target.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "signal" -> "org.thoughtcrime.securesms"
            "telegram" -> "org.telegram.messenger"
            "messenger" -> "com.facebook.orca"
            "messages", "sms" -> "com.google.android.apps.messaging"
            else -> null // free-text sender — try any messaging app
        }
    }
}
