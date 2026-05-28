package com.jarvis.hermes.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.hermes.LocalResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Alarms / timers handler.
 *
 * All timers go through the system AlarmClock provider so they survive app
 * death and don't need any custom long-running infra inside our process.
 */
object AlarmsAction {

    private const val ACTION_SET_ALARM = "set_alarm"
    private const val ACTION_DISMISS_ALARM = "dismiss_alarm"
    private const val ACTION_SNOOZE = "snooze"
    private const val ACTION_SET_TIMER = "set_timer"
    private const val ACTION_OPEN_CLOCK = "open_clock"
    private const val ACTION_LIST_ALARMS = "list_alarms"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(set\s+)?alarm\s+(at|for)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^(set\s+)?alarm\s+(at|for)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SET_ALARM, "time" to (m?.groupValues?.get(3) ?: ""))
            }
            Regex("""^alarm\s+(\d{1,2}(?::\d{2})?\s*(am|pm)?)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^alarm\s+(\d{1,2}(?::\d{2})?\s*(am|pm)?)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SET_ALARM, "time" to (m?.groupValues?.get(1) ?: ""))
            }
            Regex("""^dismiss\s+(alarm|the\s+alarm)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_DISMISS_ALARM)
            Regex("""^snooze$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SNOOZE)
            Regex("""^(set\s+)?timer\s+(?:for\s+)?(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^(set\s+)?timer\s+(?:for\s+)?(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SET_TIMER, "duration" to (m?.groupValues?.get(2) ?: ""), "unit" to (m?.groupValues?.get(3) ?: "minutes"))
            }
            Regex("""^open\s+(clock|alarms?)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN_CLOCK)
            Regex("""^(show|list)\s+alarms?$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_LIST_ALARMS)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Alarm action unclear.", "alarms_error")
        return when (action) {
            ACTION_SET_ALARM -> setAlarm(context, params["time"] ?: "")
            ACTION_DISMISS_ALARM -> dismissAlarm(context)
            ACTION_SNOOZE -> snoozeAlarm(context)
            ACTION_SET_TIMER -> setTimer(context, params["duration"] ?: "", params["unit"] ?: "minutes")
            ACTION_OPEN_CLOCK, ACTION_LIST_ALARMS -> openClockApp(context)
            else -> LocalResponse("Unknown alarm action.", "alarms_error")
        }
    }

    private fun setAlarm(context: Context, timeStr: String): LocalResponse {
        val cal = parseTime(timeStr) ?: Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return LocalResponse("No clock app to set alarm.", "alarms_error")
            }
            context.startActivity(intent)
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            LocalResponse("Setting alarm for ${fmt.format(cal.time)}.", "alarms_set")
        } catch (_: Exception) {
            LocalResponse("Couldn't set alarm.", "alarms_error")
        }
    }

    private fun dismissAlarm(context: Context): LocalResponse {
        return try {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Alarm dismissed.", "alarms_dismissed")
        } catch (_: Exception) { LocalResponse("Couldn't dismiss alarm.", "alarms_error") }
    }

    private fun snoozeAlarm(context: Context): LocalResponse {
        return try {
            val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Snoozed.", "alarms_snoozed")
        } catch (_: Exception) { LocalResponse("Couldn't snooze.", "alarms_error") }
    }

    private fun setTimer(context: Context, duration: String, unit: String): LocalResponse {
        val v = duration.toIntOrNull()?.takeIf { it > 0 }
            ?: return LocalResponse("Invalid timer duration.", "alarms_error")
        val seconds = when {
            unit.startsWith("hour", ignoreCase = true) || unit.startsWith("hr", ignoreCase = true) -> v * 3600
            unit.startsWith("min", ignoreCase = true) -> v * 60
            unit.startsWith("sec", ignoreCase = true) -> v
            else -> v * 60
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return LocalResponse("No clock app available.", "alarms_error")
            }
            context.startActivity(intent)
            val pretty = if (seconds >= 60) "${seconds / 60} minute${if (seconds / 60 != 1) "s" else ""}" else "$seconds seconds"
            LocalResponse("Timer set for $pretty.", "alarms_timer", mapOf("seconds" to seconds.toString()))
        } catch (_: Exception) {
            LocalResponse("Couldn't set timer.", "alarms_error")
        }
    }

    private fun openClockApp(context: Context): LocalResponse {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return LocalResponse("No clock app available.", "alarms_error")
            }
            context.startActivity(intent)
            LocalResponse("Opening clock.", "alarms_open")
        } catch (_: Exception) {
            LocalResponse("Couldn't open clock.", "alarms_error")
        }
    }

    private fun parseTime(timeStr: String): Calendar? {
        val cal = Calendar.getInstance()
        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(timeStr) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val period = m.groupValues[3].lowercase()
        if (period == "pm" && hour < 12) hour += 12
        if (period == "am" && hour == 12) hour = 0
        cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal
    }
}
