package com.jarvis.hermes

import org.json.JSONArray
import org.json.JSONObject

/**
 * Location-based reminder. Fired when the user enters or exits a geofence.
 */
data class Reminder(
    val id: String,
    val text: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val trigger: Trigger,
    val createdAt: Long
) {
    enum class Trigger { ENTER, EXIT }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("latitude", latitude)
        put("longitude", longitude)
        put("radiusMeters", radiusMeters.toDouble())
        put("trigger", trigger.name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Reminder = Reminder(
            id = json.getString("id"),
            text = json.getString("text"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            radiusMeters = json.getDouble("radiusMeters").toFloat(),
            trigger = Trigger.valueOf(json.optString("trigger", "ENTER")),
            createdAt = json.getLong("createdAt")
        )
    }
}
