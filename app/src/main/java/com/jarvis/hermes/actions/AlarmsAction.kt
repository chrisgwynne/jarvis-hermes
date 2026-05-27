package com.jarvis.hermes.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import com.jarvis.hermes.LocalResponse
import java.text.SimpleDateFormat
import java.util.*

/**
 * Alarms action handler: set alarm, timer, snooze, dismiss.
 */
object AlarmsAction {

    private const val ACTION_SET_ALARM = "set_alarm"
    private const val ACTION_DISMISS_ALARM = "dismiss_alarm"
    private const val ACTION_SNOOZE = "snooze"
    private const val ACTION_SET_TIMER = "set_timer"
    private const val ACTION_CANCEL_TIMER = "cancel_timer"
    private const val ACTION_OPEN_CLOCK = "open_clock"
    private const val ACTION_LIST_ALARMS = "list_alarms"
    private const val ACTION_OPEN_ALARMS = "open_alarms"

    // Shared timer state for cancellation
    private var activeTimer: CountDownTimer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Set alarm: "set alarm for 7am" or "alarm at 7"
            Regex("""^(set\s+)?alarm\s*(at|for)?\s*(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^(set\s+)?alarm\s*(at|for)?\s*(.+)$""", RegexOption.IGNORE_CASE).find(text)
                val timeStr = match?.groupValues?.get(3) ?: ""
                mapOf("action" to ACTION_SET_ALARM, "time" to timeStr)
            }
            // "alarm 7am" (shortcut)
            Regex("""^alarm\s+(\d{1,2})(am|pm)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^alarm\s+(\d{1,2})(am|pm)?$""", RegexOption.IGNORE_CASE).find(text)
                val hour = match?.groupValues?.get(1) ?: ""
                val period = match?.groupValues?.get(2) ?: ""
                mapOf("action" to ACTION_SET_ALARM, "time" to "$hour $period".trim())
            }
            // Dismiss alarm
            Regex("""^dismiss\s+(alarm|the\s+alarm)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_DISMISS_ALARM)
            }
            // Snooze
            Regex("""^snooze$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SNOOZE)
            }
            // Timer: "timer 20 minutes" or "set timer for 5 minutes"
            Regex("""^(set\s+)?timer\s*(for|(\d+)\s*minutes?)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^(set\s+)?timer\s*(for\s*)?(\d+)?\s*(minutes?|mins?)?$""", RegexOption.IGNORE_CASE).find(text)
                val minutes = match?.groupValues?.get(3) ?: ""
                mapOf("action" to ACTION_SET_TIMER, "minutes" to minutes)
            }
            Regex("""^timer\s+(\d+)\s*(seconds?|minutes?|hours?)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^timer\s+(\d+)\s*(seconds?|minutes?|hours?)$""", RegexOption.IGNORE_CASE).find(text)
                val duration = match?.groupValues?.get(1) ?: ""
                val unit = match?.groupValues?.get(2) ?: "minutes"
                mapOf("action" to ACTION_SET_TIMER, "duration" to duration, "unit" to unit)
            }
            // Cancel timer
            Regex("""^(cancel|stop|clear)\s+timer$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_CANCEL_TIMER)
            }
            // Open clock app
            Regex("""^open\s+clock$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_CLOCK)
            }
            // List alarms
            Regex("""^(show|list)\s+alarms?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LIST_ALARMS)
            }
            // Open alarms
            Regex("""^open\s+alarms?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_ALARMS)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Alarm action unclear.", "alarms_error")

        return when (action) {
            ACTION_SET_ALARM -> setAlarm(context, params["time"] ?: "")
            ACTION_DISMISS_ALARM -> dismissAlarm(context)
            ACTION_SNOOZE -> snoozeAlarm(context)
            ACTION_SET_TIMER -> setTimer(context, params["minutes"] ?: "", params["duration"] ?: "", params["unit"] ?: "")
            ACTION_CANCEL_TIMER -> cancelTimer()
            ACTION_OPEN_CLOCK -> openClockApp(context)
            ACTION_LIST_ALARMS -> listAlarms(context)
            ACTION_OPEN_ALARMS -> openAlarmApp(context)
            else -> LocalResponse("Unknown alarm action.", "alarms_error")
        }
    }

    private fun setAlarm(context: Context, timeStr: String): LocalResponse {
        // Parse the time and open alarm clock
        val calendar = parseTime(timeStr) ?: Calendar.getInstance().apply {
            // Default to next occurrence of the time
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, calendar.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, calendar.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            LocalResponse("Setting alarm for ${timeFormat.format(calendar.time)}.", "alarms_set")
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            LocalResponse("Couldn't dismiss alarm.", "alarms_error")
        }
    }

    private fun snoozeAlarm(context: Context): LocalResponse {
        return try {
            val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Snoozed for 9 minutes.", "alarms_snoozed")
        } catch (e: Exception) {
            LocalResponse("Couldn't snooze alarm.", "alarms_error")
        }
    }

    private fun setTimer(context: Context, minutes: String, duration: String, unit: String): LocalResponse {
        // Determine the timer duration in milliseconds
        var totalSeconds = 0

        if (minutes.isNotBlank()) {
            totalSeconds = (minutes.toIntOrNull() ?: 0) * 60
        } else if (duration.isNotBlank()) {
            val value = duration.toIntOrNull() ?: 0
            totalSeconds = when {
                unit.startsWith("hour") -> value * 3600
                unit.startsWith("min") -> value * 60
                unit.startsWith("sec") -> value
                else -> value * 60
            }
        } else {
            return LocalResponse("How long should the timer be?", "alarms_timer")
        }

        if (totalSeconds <= 0) {
            return LocalResponse("Invalid timer duration.", "alarms_error")
        }

        val totalMs = totalSeconds * 1000L

        // Cancel any existing timer
        activeTimer?.cancel()

        // Start new timer
        activeTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Could update notification here
            }

            override fun onFinish() {
                // Timer complete - open alarm clock
                try {
                    val intent = Intent(AlarmClock.ACTION_SHOW_TIMER).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback - open clock
                    openClockApp(context)
                }
            }
        }.start()

        val minutesDisplay = totalSeconds / 60
        val secondsDisplay = totalSeconds % 60
        val timeStr = if (secondsDisplay > 0) {
            "$minutesDisplay minutes and $secondsDisplay seconds"
        } else {
            "$minutesDisplay minutes"
        }

        return LocalResponse("Timer set for $timeStr.", "alarms_timer",
            mapOf("seconds" to totalSeconds.toString()))
    }

    private fun cancelTimer(): LocalResponse {
        activeTimer?.cancel()
        activeTimer = null
        return LocalResponse("Timer cancelled.", "alarms_timer_cancelled")
    }

    private fun openClockApp(context: Context): LocalResponse {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening clock.", "alarms_open")
        } catch (e: Exception) {
            LocalResponse("Couldn't open clock.", "alarms_error")
        }
    }

    private fun listAlarms(context: Context): LocalResponse {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening alarms.", "alarms_list")
        } catch (e: Exception) {
            LocalResponse("Couldn't list alarms.", "alarms_error")
        }
    }

    private fun openAlarmApp(context: Context): LocalResponse {
        return openClockApp(context)
    }

    private fun parseTime(timeStr: String): Calendar? {
        val calendar = Calendar.getInstance()
        
        // Try parsing "7am", "7pm", "7:30am"
        val match = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""", RegexOption.IGNORE_CASE).find(timeStr)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val period = match.groupValues[3].lowercase()
            
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            
            // If time has passed today, set for tomorrow
            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            
            return calendar
        }
        
        return null
    }
}