package com.jarvis.hermes

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * Persists pending location-based reminders to SharedPreferences.
 * Lightweight — we only ever have a handful at once.
 */
object RemindersStore {

    private const val PREFS = "jarvis_hermes"
    private const val KEY = "reminders"

    fun all(ctx: Context): List<Reminder> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<Reminder>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                out.add(Reminder.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        return out
    }

    fun add(ctx: Context, text: String, lat: Double, lng: Double,
            radius: Float, trigger: Reminder.Trigger): Reminder {
        val r = Reminder(
            id = UUID.randomUUID().toString(),
            text = text,
            latitude = lat,
            longitude = lng,
            radiusMeters = radius,
            trigger = trigger,
            createdAt = System.currentTimeMillis()
        )
        val list = all(ctx).toMutableList().apply { add(r) }
        save(ctx, list)
        return r
    }

    fun remove(ctx: Context, id: String): Boolean {
        val list = all(ctx).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) save(ctx, list)
        return removed
    }

    fun byId(ctx: Context, id: String): Reminder? = all(ctx).firstOrNull { it.id == id }

    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
