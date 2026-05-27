package com.jarvis.hermes

/**
 * Builds the system prompt context for Hermes.
 * Includes information about the user, device capabilities,
 * and what is handled locally vs remotely.
 */
object SystemPromptBuilder {

    private const val DEFAULT_SYSTEM_PROMPT = """You are talking to Chris through his Android phone. You can handle: liverpool fc, wiki queries, general conversation, todo lists, web search, reminders, smart home. Local actions (call, text, camera, calendar, alarms) are handled on-device. Don't try to handle them."""

    /**
     * Build the full system prompt with local capabilities context.
     */
    fun build(customPrompt: String? = null): String {
        val base = customPrompt ?: DEFAULT_SYSTEM_PROMPT
        
        val localCapabilities = """
Local actions are handled on-device and include:
- Phone: call, dial, hang up, end call, speaker, mute
- SMS: send message, text, reply
- Contacts: show contact, call contact, add contact
- Calendar: add event, what's on tomorrow, schedule
- Camera: take photo, record video, torch, scan QR
- Media: play, pause, next, previous
- System: brightness, wifi, bluetooth, screenshot, settings
- Notifications: read messages, show notifications
- Files: open downloads, show photos
- Location: where am I, share location, navigate
- Sensors: battery, accelerometer, compass, steps
- Clipboard: copy, what's on clipboard
- UI: back, home, recent, screenshot
- Alarms: set alarm, timer, snooze, dismiss
""".trimIndent()

        return "$base\n\n$localCapabilities"
    }

    /**
     * Get the default system prompt.
     */
    fun getDefault(): String = DEFAULT_SYSTEM_PROMPT

    /**
     * Build conversation history for context.
     */
    fun buildConversationHistory(
        userMessages: List<String>,
        jarvisMessages: List<String>,
        maxPairs: Int = 10
    ): String {
        val history = StringBuilder()
        val count = minOf(userMessages.size, jarvisMessages.size, maxPairs)
        
        for (i in 0 until count) {
            history.appendLine("You: ${userMessages[i]}")
            history.appendLine("Jarvis: ${jarvisMessages[i]}")
        }
        
        return history.toString().trim()
    }

    /**
     * Get the key that system prompt is stored under in SharedPreferences.
     */
    const val PREFS_KEY_SYSTEM_PROMPT = "system_prompt"
}