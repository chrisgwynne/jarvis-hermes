package com.jarvis.hermes.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.session.KeyEvent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jarvis.hermes.LocalResponse
import java.io.File

/**
 * Media action handler: play music, next track, previous, pause, resume.
 */
object MediaAction {

    private const val ACTION_PLAY = "play"
    private const val ACTION_PAUSE = "pause"
    private const val ACTION_RESUME = "resume"
    private const val ACTION_NEXT = "next"
    private const val ACTION_PREVIOUS = "previous"
    private const val ACTION_STOP = "stop"
    private const val ACTION_NOW_PLAYING = "now_playing"
    private const val ACTION_SHUFFLE = "shuffle"

    private var mediaPlayer: MediaPlayer? = null

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Play music / play
            Regex("""^(play\s+music|play|start\s+music|resume\s+music)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_PLAY)
            }
            // Pause
            Regex("""^(pause|pause\s+music)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_PAUSE)
            }
            // Resume
            Regex("""^(resume|resume\s+music|continue)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_RESUME)
            }
            // Next track
            Regex("""^(next\s+track|next|skip)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_NEXT)
            }
            // Previous track
            Regex("""^(previous\s+track|previous|back)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_PREVIOUS)
            }
            // Stop
            Regex("""^(stop|stop\s+music)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_STOP)
            }
            // Now playing
            Regex("""^(now\s+playing|what('?s| is)\s+playing|current\s+(song|track))$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_NOW_PLAYING)
            }
            // Shuffle
            Regex("""^shuffle$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SHUFFLE)
            }
            // Play specific song/artist
            Regex("""^play\s+(.+)by\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^play\s+(.+)by\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_PLAY, "song" to (match?.groupValues?.get(1) ?: ""), "artist" to (match?.groupValues?.get(2) ?: ""))
            }
            Regex("""^play\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^play\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_PLAY, "query" to (match?.groupValues?.get(1) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Media action unclear.", "media_error")

        return when (action) {
            ACTION_PLAY -> {
                val query = params["query"] ?: params["song"] ?: ""
                openMusicPlayer(context, query)
            }
            ACTION_PAUSE -> {
                pauseMusic()
            }
            ACTION_RESUME -> {
                resumeMusic()
            }
            ACTION_NEXT -> {
                mediaControl(context, "next")
            }
            ACTION_PREVIOUS -> {
                mediaControl(context, "previous")
            }
            ACTION_STOP -> {
                stopMusic()
            }
            ACTION_NOW_PLAYING -> {
                LocalResponse("Use your music app to see what's playing.", "media_now_playing")
            }
            ACTION_SHUFFLE -> {
                mediaControl(context, "shuffle")
            }
            else -> LocalResponse("Unknown media action.", "media_error")
        }
    }

    private fun openMusicPlayer(context: Context, query: String): LocalResponse {
        try {
            val intent = if (query.isNotBlank()) {
                Intent(Intent.ACTION_VIEW).apply {
                    val mimeType = if (query.contains(".")) {
                        when {
                            query.endsWith(".mp3") -> "audio/mpeg"
                            query.endsWith(".wav") -> "audio/wav"
                            query.endsWith(".flac") -> "audio/flac"
                            else -> "audio/*"
                        }
                    } else {
                        "audio/*"
                    }
                    setDataAndType(Uri.parse("content://media/internal/audio/media"), mimeType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_MAIN).apply {
                    setPackage("com.google.android.apps.youtube music".takeIf { 
                        context.packageManager.getLaunchIntentForPackage(it) != null 
                    } ?: "com.android.music")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            LocalResponse("Opening music.", "media_play")
        } catch (e: Exception) {
            // Try generic music intent
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("http://www.youtube.com")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening music.", "media_play")
            } catch (e2: Exception) {
                LocalResponse("Music player not available.", "media_error")
            }
        }
    }

    private fun pauseMusic(): LocalResponse {
        return try {
            mediaControl(context, "pause")
            LocalResponse("Music paused.", "media_pause")
        } catch (e: Exception) {
            LocalResponse("Couldn't pause music.", "media_error")
        }
    }

    private fun resumeMusic(): LocalResponse {
        return try {
            mediaControl(context, "play")
            LocalResponse("Resuming music.", "media_resume")
        } catch (e: Exception) {
            LocalResponse("Couldn't resume music.", "media_error")
        }
    }

    private fun stopMusic(): LocalResponse {
        return try {
            mediaControl(context, "stop")
            LocalResponse("Music stopped.", "media_stop")
        } catch (e: Exception) {
            LocalResponse("Couldn't stop music.", "media_error")
        }
    }

    private fun mediaControl(context: Context, action: String): LocalResponse {
        try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return sendMediaButtonIntent(context, action)

            val controllers = mediaSessionManager.getActiveSessions(ComponentName(context, Any::class.java))
            if (controllers.isEmpty()) {
                return sendMediaButtonIntent(context, action)
            }

            val controller = controllers.firstOrNull() ?: return sendMediaButtonIntent(context, action)

            when (action) {
                "pause" -> controller.dispatchMediaButtonEvent(buildMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PAUSE))
                "play" -> controller.dispatchMediaButtonEvent(buildMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY))
                "next" -> controller.dispatchMediaButtonEvent(buildMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_NEXT))
                "previous" -> controller.dispatchMediaButtonEvent(buildMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                "stop" -> controller.dispatchMediaButtonEvent(buildMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_STOP))
                else -> return LocalResponse("", "media_control")
            }
            return LocalResponse("", "media_control")
        } catch (e: Exception) {
            return sendMediaButtonIntent(context, action)
        }
    }

    private fun sendMediaButtonIntent(context: Context, action: String): LocalResponse {
        val keyCode = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return LocalResponse("Media control not available.", "media_error")
        }

        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        intent.component = ComponentName(context, "com.android.music.MediaButtonIntentReceiver")
        intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        context.sendOrderedBroadcast(intent, null)
        return LocalResponse("", "media_control")
    }

    private fun buildMediaButtonEvent(keyCode: Int): KeyEvent {
        return KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
    }
}