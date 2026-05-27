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
        // Simple time parser - look for common patterns
        val now = Calendar.getInstance()
        
        // Look for "tomorrow"
        if (text.contains("tomorrow")) {
            now.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        // Look for "at X" or "at X pm/am"
        val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(text)
        if (timeMatch != null) {
            var hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val period = timeMatch.groupValues[3].lowercase()
            
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            
            now.set(Calendar.HOUR_OF_DAY, hour)
            now.set(Calendar.MINUTE, minute)
            now.set(Calendar.SECOND, 0)
            return now.timeInMillis
        }
        
        return null
    }
}