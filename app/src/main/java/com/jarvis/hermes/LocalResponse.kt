package com.jarvis.hermes

/**
 * Response from a locally-handled command.
 * @param text Spoken confirmation / result (TTS will speak this)
 * @param action The action type identifier
 * @param params Additional parameters for the action
 */
data class LocalResponse(
    val text: String,
    val action: String,
    val params: Map<String, String> = emptyMap()
)