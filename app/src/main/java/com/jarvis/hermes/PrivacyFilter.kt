package com.jarvis.hermes

/**
 * Filter for sensitive notifications — banking, OTP, 2FA. These should
 * never be read aloud (vibration only) and are never shown in summaries.
 *
 * The patterns are conservative — match common keywords across English
 * banking and authentication apps. Override via Settings to add more.
 */
object PrivacyFilter {

    private val DEFAULT_SENSITIVE_PATTERNS = listOf(
        // Authentication codes
        "\\botp\\b",
        "\\b2fa\\b",
        "\\bauthent",
        "verification code",
        "verify your",
        "security code",
        "one[- ]?time (code|password|pin)",
        "log[- ]?in code",
        // Banking
        "\\bbank\\b",
        "wallet",
        "credit card",
        "debit card",
        "transaction",
        "account balance",
        "overdraft",
        // Common 2FA brands
        "authenticator",
        "duo (security|mobile)",
        "microsoft authenticator",
        "google authenticator"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val SENSITIVE_PACKAGES = setOf(
        "com.duo.android",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator"
    )

    fun isSensitive(packageName: String, sender: String, message: String): Boolean {
        if (packageName in SENSITIVE_PACKAGES) return true
        val haystack = "$sender $message"
        return DEFAULT_SENSITIVE_PATTERNS.any { it.containsMatchIn(haystack) }
    }
}
