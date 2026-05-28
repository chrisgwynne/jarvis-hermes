package com.jarvis.hermes.actions

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar action handler.
 *
 *  - Event creation parses dates (tomorrow, next monday, may 15, etc.) and
 *    times (3pm, 15:30, half past four).
 *  - Event queries read upcoming events from the user's visible calendars.
 *  - When no calendar is writable we fall back to the system "create event"
 *    intent so the user picks.
 */
object CalendarAction {

    private const val ACTION_ADD = "add"
    private const val ACTION_QUERY = "query"
    private const val ACTION_OPEN = "open"

    fun requiredPermissions() = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(add\s+event|schedule|create\s+event|new\s+event)\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^(add\s+event|schedule|create\s+event|new\s+event)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_ADD, "title" to (m?.groupValues?.get(2) ?: ""))
            }
            Regex("""^what('?s| is)?\s*(on|happening|going\s*on)\s*(today|tomorrow|this\s+week|next\s+week)?\??$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^what('?s| is)?\s*(on|happening|going\s*on)\s*(today|tomorrow|this\s+week|next\s+week)?\??$""", RegexOption.IGNORE_CASE).find(text)
                val window = m?.groupValues?.get(3)?.lowercase()?.ifBlank { "tomorrow" } ?: "tomorrow"
                mapOf("action" to ACTION_QUERY, "window" to window)
            }
            Regex("""^(what\s+do\s+i\s+have|show\s+(my\s+)?schedule|my\s+events?)(\s+today|\s+tomorrow)?\??$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_QUERY, "window" to (if (text.contains("today", ignoreCase = true)) "today" else "tomorrow"))
            }
            Regex("""^open\s+calendar$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Calendar action unclear.", "calendar_error")

        if (action == ACTION_OPEN) return openCalendar(context)

        val missing = requiredPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            // Fall back to system intents that don't need permission.
            return when (action) {
                ACTION_ADD -> openInsertEventComposer(context, params["title"].orEmpty())
                else -> LocalResponse("Calendar permission not granted.", "calendar_permission")
            }
        }

        return when (action) {
            ACTION_ADD -> addEvent(context, params["title"].orEmpty())
            ACTION_QUERY -> queryEvents(context, params["window"] ?: "tomorrow")
            else -> LocalResponse("Unknown calendar action.", "calendar_error")
        }
    }

    private fun openInsertEventComposer(context: Context, title: String): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening calendar to add event.", "calendar_open")
        } catch (_: Exception) {
            LocalResponse("Couldn't open calendar.", "calendar_error")
        }
    }

    private fun openCalendar(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://com.android.calendar/time/")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Opening calendar.", "calendar_open")
            } else {
                openInsertEventComposer(context, "")
            }
        } catch (_: Exception) {
            LocalResponse("Couldn't open calendar.", "calendar_error")
        }
    }

    private fun addEvent(context: Context, title: String): LocalResponse {
        if (title.isBlank()) return LocalResponse("What's the event called?", "calendar_add")

        val eventStart = parseEventTime(title)
        val eventEnd = eventStart + 60 * 60 * 1000L

        val calendarId = getDefaultCalendarId(context)
            ?: return openInsertEventComposer(context, title)

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, eventStart)
                put(CalendarContract.Events.DTEND, eventEnd)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val fmt = SimpleDateFormat("EEE d MMM 'at' h:mm a", Locale.getDefault())
                LocalResponse("Added: $title on ${fmt.format(Date(eventStart))}.", "calendar_added",
                    mapOf("title" to title, "start" to eventStart.toString()))
            } else {
                openInsertEventComposer(context, title)
            }
        } catch (_: Exception) {
            openInsertEventComposer(context, title)
        }
    }

    private fun queryEvents(context: Context, window: String): LocalResponse {
        val now = System.currentTimeMillis()
        val end = when (window) {
            "today" -> now + 24L * 60 * 60 * 1000
            "this week" -> now + 7L * 24 * 60 * 60 * 1000
            "next week" -> now + 14L * 24 * 60 * 60 * 1000
            else -> now + 48L * 60 * 60 * 1000 // tomorrow → 2 days
        }
        val events = getUpcomingEvents(context, now, end)
        if (events.isEmpty()) return LocalResponse("No events $window.", "calendar_empty")
        return LocalResponse(events.take(5).joinToString(". "), "calendar_events")
    }

    private fun getDefaultCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null
            ) ?: return null
            cursor.use {
                var firstWritable: Long? = null
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val primary = it.getInt(1)
                    if (primary == 1) return id
                    if (firstWritable == null) firstWritable = id
                }
                firstWritable
            }
        } catch (_: SecurityException) { null }
    }

    private fun getUpcomingEvents(context: Context, start: Long, end: Long): List<String> {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(start.toString(), end.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val events = mutableListOf<String>()
        val fmt = SimpleDateFormat("EEE 'at' h:mm a", Locale.getDefault())
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, args, sort
            )?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Untitled"
                    val ts = it.getLong(1)
                    events.add("$title ${fmt.format(Date(ts))}")
                }
            }
        } catch (_: SecurityException) { /* permission gone */ }
        return events
    }

    /**
     * Parse a date+time out of free text. Falls back to tomorrow 9am if
     * nothing recognisable is found.
     */
    private fun parseEventTime(text: String): Long {
        val now = LocalDate.now()
        var targetDate: LocalDate? = null
        var targetTime: LocalTime = LocalTime.of(9, 0)
        val lower = text.lowercase()

        // Relative phrases
        if (lower.contains("tomorrow")) targetDate = now.plusDays(1)
        else if (lower.contains("today")) targetDate = now
        else if (Regex("""next\s+week""").containsMatchIn(lower)) targetDate = now.plusWeeks(1)
        else {
            Regex("""in\s+(\d+)\s+days?""").find(lower)?.let {
                targetDate = now.plusDays(it.groupValues[1].toLong())
            }
            Regex("""in\s+(\d+)\s+weeks?""").find(lower)?.let {
                targetDate = now.plusWeeks(it.groupValues[1].toLong())
            }
        }

        // Weekday names — "next monday", "monday", "this friday".
        val days = mapOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )
        for ((name, dow) in days) {
            val rx = Regex("""(next\s+|this\s+)?$name""")
            val m = rx.find(lower) ?: continue
            val isNext = m.groupValues[1].trim() == "next"
            val base = targetDate ?: now
            targetDate = if (isNext) base.with(TemporalAdjusters.next(dow))
                         else base.with(TemporalAdjusters.nextOrSame(dow))
            break
        }

        // Month + day like "may 15"
        Regex("""(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?""")
            .find(lower)?.let { m ->
                val monthName = m.groupValues[1].replaceFirstChar { it.uppercase() }
                val day = m.groupValues[2].toIntOrNull() ?: 1
                try {
                    val fmt = DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH)
                    val candidate = LocalDate.parse("$monthName $day ${now.year}", fmt)
                    targetDate = if (candidate.isBefore(now)) candidate.plusYears(1) else candidate
                } catch (_: Exception) { /* ignore malformed */ }
            }

        if (targetDate == null) targetDate = now.plusDays(1)

        // Time — "at 3pm", "3:30pm", "15:30", "half past 4"
        Regex("""(?:at\s+)?(\d{1,2}):(\d{2})\s*(am|pm)?""").find(lower)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val period = m.groupValues[3].lowercase()
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            targetTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        } ?: Regex("""(?:at\s+)?(\d{1,2})\s*(am|pm)""").find(lower)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val period = m.groupValues[2].lowercase()
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            targetTime = LocalTime.of(hour.coerceIn(0, 23), 0)
        }

        return targetDate!!.atTime(targetTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
