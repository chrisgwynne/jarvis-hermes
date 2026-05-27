package com.jarvis.hermes

import android.content.Context
import com.jarvis.hermes.actions.*

/**
 * Pattern-matching router for local voice commands.
 * Receives speech text from VoiceService.
 * Match against local patterns → execute locally → return response.
 * If no match → return null (caller sends to Hermes).
 */
object LocalCommandClassifier {

    /**
     * Handle a recognized text command.
     * Returns a LocalResponse if matched locally, null if should passthrough to Hermes.
     */
    fun handle(context: Context, text: String): LocalResponse? {
        val lower = text.lowercase().trim()

        // Try each handler in priority order
        PhoneAction.canHandle(lower)?.let { params ->
            return PhoneAction.execute(context, params)
        }
        SmsAction.canHandle(lower)?.let { params ->
            return SmsAction.execute(context, params)
        }
        ContactsAction.canHandle(lower)?.let { params ->
            return ContactsAction.execute(context, params)
        }
        CalendarAction.canHandle(lower)?.let { params ->
            return CalendarAction.execute(context, params)
        }
        CameraAction.canHandle(lower)?.let { params ->
            return CameraAction.execute(context, params)
        }
        MediaAction.canHandle(lower)?.let { params ->
            return MediaAction.execute(context, params)
        }
        SystemAction.canHandle(lower)?.let { params ->
            return SystemAction.execute(context, params)
        }
        NotificationsAction.canHandle(lower)?.let { params ->
            return NotificationsAction.execute(context, params)
        }
        FileAction.canHandle(lower)?.let { params ->
            return FileAction.execute(context, params)
        }
        LocationAction.canHandle(lower)?.let { params ->
            return LocationAction.execute(context, params)
        }
        SensorsAction.canHandle(lower)?.let { params ->
            return SensorsAction.execute(context, params)
        }
        ClipboardAction.canHandle(lower)?.let { params ->
            return ClipboardAction.execute(context, params)
        }
        UiAction.canHandle(lower)?.let { params ->
            return UiAction.execute(context, params)
        }
        AlarmsAction.canHandle(lower)?.let { params ->
            return AlarmsAction.execute(context, params)
        }

        return null
    }

    /**
     * Returns a list of all locally-available capabilities for system context.
     * Used to inform Hermes what is handled on-device.
     */
    fun getLocalCapabilities(): String {
        return listOf(
            "Phone: call, dial, hang up, end call, speaker toggle, mute",
            "SMS: send message, text, reply",
            "Contacts: show contact, call contact, add contact",
            "Calendar: add event, what's on tomorrow, schedule",
            "Camera: take photo, record video, torch, scan QR",
            "Media: play, pause, next, previous, now playing",
            "System: brightness, wifi, bluetooth, screenshot, settings",
            "Notifications: read messages, show notifications",
            "Files: open downloads, show photos",
            "Location: where am I, share location",
            "Sensors: battery, accelerometer, compass",
            "Clipboard: copy, what's on clipboard",
            "UI: back, home, recent, screenshot",
            "Alarms: set alarm, timer, snooze, dismiss"
        ).joinToString(". ")
    }
}
