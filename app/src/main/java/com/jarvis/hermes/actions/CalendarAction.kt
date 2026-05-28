package com.jarvis.hermes.actions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.LocalResponse
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import java.util.*

/**
 * Calendar action handler: add event, what's on tomorrow, schedule.
 */
object CalendarAction {

    private const val ACTION_ADD = "add"
    private const val ACTION_TOMORROW = "tomorrow"
    private const val ACTION_TODAY = "today"
    private const val ACTION_QUERY = "query"
    private const val ACTION_OPEN = "open"

    fun requiredPermissions() = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // "add event meeting tomorrow at 3pm"
            Regex("""^add\s+event\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^add\s+event\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_ADD, "title" to (match?.groupValues?.get(1) ?: ""))
            }
            // "schedule meeting at 3pm"
            Regex("""^schedule\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^schedule\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_ADD, "title" to (match?.groupValues?.get(1) ?: ""))
            }
            // "what's on tomorrow" or "what's happening tomorrow"
            Regex("""^what('?s| is)\s*(on|happening|going\s*on)\s*(tomorrow|today)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^what('?s| is)\s*(on|happening|going\s*on)\s*(tomorrow|today)?$""", RegexOption.IGNORE_CASE).find(text)
                val day = match?.groupValues?.get(3)?.lowercase() ?: "tomorrow"
                mapOf("action" to ACTION_TOMORROW, "day" to day)
            }
            // "what do I have tomorrow" / "show my schedule"
            Regex("""^(what\s+(do\s+I\s+have|is\s+(on|scheduled))|show\s+(my\s+)?schedule).*$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_QUERY)
            }
            // "open calendar"
            Regex("""^open\s+calendar$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Calendar action unclear.", "calendar_error")

        val missingPerms = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty() && action != ACTION_OPEN) {
            return LocalResponse("Calendar permission not granted.", "calendar_permission")
        }

        return when (action) {
            ACTION_ADD -> {
                val title = params["title"] ?: ""
                if (title.isBlank()) {
                    return LocalResponse("What's the event name?", "calendar_add")
                }
                // Parse time from title if possible, otherwise default to tomorrow 9am
                val eventTime = parseEventTime(title) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
                val endTime = eventTime + 60 * 60 * 1000 // 1 hour duration

                val calendarId = getDefaultCalendar(context) ?: "1"

                try {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, eventTime)
                        put(CalendarContract.Events.DTEND, endTime)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.CALENDAR_ID, calendarId.toLong())
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    if (uri != null) {
                        LocalResponse("Added event: $title.", "calendar_added", mapOf("title" to title))
                    } else {
                        LocalResponse("Couldn't add event.", "calendar_error")
                    }
                } catch (e: Exception) {
                    LocalResponse("Couldn't add event.", "calendar_error")
                }
            }
            ACTION_TOMORROW, ACTION_QUERY -> {
                val events = getUpcomingEvents(context, if (action == ACTION_TOMORROW) 2 else 1)
                if (events.isEmpty()) {
                    return LocalResponse("No upcoming events.", "calendar_empty")
                }
                val summary = events.take(5).joinToString(". ")
                LocalResponse(summary, "calendar_events", mapOf("events" to events.joinToString("|")))
            }
            ACTION_OPEN -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    LocalResponse("Opening calendar.", "calendar_open")
                } catch (e: Exception) {
                    LocalResponse("Couldn't open calendar.", "calendar_error")
                }
            }
            else -> LocalResponse("Unknown calendar action.", "calendar_error")
        }
    }

    private fun getDefaultCalendar(context: Context): String? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val isPrimary = it.getInt(1)
                if (isPrimary == 1) return id
            }
            // If no primary, return first visible
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private fun getUpcomingEvents(context: Context, daysAhead: Int): List<String> {
        val start = System.currentTimeMillis()
        val end = start + daysAhead.toLong() * 24 * 60 * 60 * 1000

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(start.toString(), end.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val events = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(0) ?: "Untitled"
                val startMs = cursor.getLong(1)
                val startStr = dateFormat.format(Date(startMs))
                events.add("$title at $startStr")
            }
        }
        return events
    }

    private fun parseEventTime(text: String): Long? {
        val now = LocalDate.now()
        val nowTime = LocalTime.now()
        var targetDate: LocalDate? = null
        var targetTime: LocalTime = LocalTime.of(9, 0) // default to 9am

        val lowerText = text.lowercase()

        // Check for "tomorrow"
        if (lowerText.contains("tomorrow")) {
            targetDate = now.plusDays(1)
        }
        // Check for "today"
        else if (lowerText.contains("today")) {
            targetDate = now
        }
        // Check for "next week"
        else if (Regex("""next\s+week""", RegexOption.IGNORE_CASE).containsMatchIn(lowerText)) {
            targetDate = now.plusWeeks(1)
        }
        // Check for "in X days/weeks"
        else {
            val inDaysMatch = Regex("""in\s+(\d+)\s+(day|days)""", RegexOption.IGNORE_CASE).find(lowerText)
            if (inDaysMatch != null) {
                val days = inDaysMatch.groupValues[1].toIntOrNull() ?: 0
                targetDate = now.plusDays(days.toLong())
            }
            val inWeeksMatch = Regex("""in\s+(\d+)\s+(week|weeks)""", RegexOption.IGNORE_CASE).find(lowerText)
            if (inWeeksMatch != null) {
                val weeks = inWeeksMatch.groupValues[1].toIntOrNull() ?: 0
                targetDate = now.plusWeeks(weeks.toLong())
            }
        }

        // Check for specific weekday names
        val dayOfWeekMap = mapOf(
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )

        for ((dayName, dayOfWeek) in dayOfWeekMap) {
            val pattern = Regex("""(next\s+)?$dayName""", RegexOption.IGNORE_CASE)
            val match = pattern.find(lowerText)
            if (match != null) {
                targetDate = if (match.groupValues[1].isNotEmpty()) {
                    // "next monday" etc
                    now.with(TemporalAdjusters.next(dayOfWeek))
                } else {
                    // "next week tuesday" or just weekday name
                    if (targetDate == null) {
                        now.with(TemporalAdjusters.nextOrSame(dayOfWeek))
                    } else {
                        targetDate.with(TemporalAdjusters.nextOrSame(dayOfWeek))
                    }
                }
                break
            }
        }

        // Check for month day pattern (e.g., "may 15th", "january 5")
        try {
            val monthDayMatch = Regex("""(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?""", RegexOption.IGNORE_CASE).find(lowerText)
            if (monthDayMatch != null) {
                val monthStr = monthDayMatch.groupValues[1].lowercase()
                val day = monthDayMatch.groupValues[2].toIntOrNull() ?: 1
                val month = when (monthStr) {
                    "january" -> 1; "february" -> 2; "march" -> 3; "april" -> 4
                    "may" -> 5; "june" -> 6; "july" -> 7; "august" -> 8
                    "september" -> 9; "october" -> 10; "november" -> 11; "december" -> 12
                    else -> 1
                }
                val formatter = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)
                val parsedDate = LocalDate.parse("${monthStr.replaceFirstChar { it.uppercase() }} $day", formatter)
                // If the date is in the past, schedule for next year
                targetDate = if (parsedDate.isBefore(now)) {
                    parsedDate.plusYears(1)
                } else {
                    parsedDate
                }
            }
        } catch (e: Exception) {
            // Fall through - keep targetDate as is
        }

        // If no date found, default to tomorrow
        if (targetDate == null) {
            targetDate = now.plusDays(1)
        }

        // Parse time from text
        val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(lowerText)
        if (timeMatch != null) {
            var hour = timeMatch.groupValues[1].toIntOrNull() ?: 9
            val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val period = timeMatch.groupValues[3].lowercase()

            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0

            targetTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }

        // Combine date and time into ZonedDateTime and convert to epoch millis
        val zonedDateTime = targetDate.atTime(targetTime).atZone(ZoneId.systemDefault())
        return zonedDateTime.toInstant().toEpochMilli()
    }
}