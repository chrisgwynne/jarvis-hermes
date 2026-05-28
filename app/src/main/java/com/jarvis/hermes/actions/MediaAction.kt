package com.jarvis.hermes.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.jarvis.hermes.LocalResponse
import com.jarvis.hermes.NotificationInterceptorService

/**
 * Media action handler: play, pause, next, previous, etc.
 *
 * Two strategies, in order:
 * 1. MediaSessionManager.getActiveSessions — only works if our NotificationListener
 *    is enabled; gives us a typed MediaController.
 * 2. AudioManager.dispatchMediaKeyEvent — works without any special permission
 *    and is the recommended fallback for routing media keys system-wide.
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

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(pause|pause\s+music)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_PAUSE)
            Regex("""^(resume|resume\s+music|continue|unpause)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_RESUME)
            Regex("""^(next|next\s+track|skip|skip\s+track)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_NEXT)
            Regex("""^(previous|previous\s+track|prev|prev\s+track|back\s+track)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_PREVIOUS)
            Regex("""^(stop|stop\s+music)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_STOP)
            Regex("""^(now\s+playing|what('?s| is)\s+playing|current\s+(song|track))$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_NOW_PLAYING)
            Regex("""^shuffle$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SHUFFLE)
            Regex("""^(play\s+music|start\s+music)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_PLAY)
            Regex("""^play\s+(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^play\s+(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf(
                    "action" to ACTION_PLAY,
                    "song" to (m?.groupValues?.get(1) ?: ""),
                    "artist" to (m?.groupValues?.get(2) ?: "")
                )
            }
            Regex("""^play\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^play\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_PLAY, "query" to (m?.groupValues?.get(1) ?: ""))
            }
            // Bare "play" — ambiguous, treat as resume.
            Regex("""^play$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_RESUME)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Media action unclear.", "media_error")

        return when (action) {
            ACTION_PLAY -> {
                val query = params["query"] ?: params["song"] ?: ""
                openMusicSearch(context, query, params["artist"] ?: "")
            }
            ACTION_PAUSE -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
            ACTION_RESUME -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, "Playing.")
            ACTION_NEXT -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT, "Next.")
            ACTION_PREVIOUS -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous.")
            ACTION_STOP -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_STOP, "Stopped.")
            ACTION_NOW_PLAYING -> nowPlaying(context)
            ACTION_SHUFFLE -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, "Shuffle.")
            else -> LocalResponse("Unknown media action.", "media_error")
        }
    }

    /**
     * Send a media key event. Tries the active MediaSession first (if our
     * NotificationListener is bound), then falls back to AudioManager dispatch.
     */
    private fun mediaKey(context: Context, keyCode: Int, spoken: String): LocalResponse {
        val sent = sendViaActiveSession(context, keyCode) || sendViaAudioManager(context, keyCode)
        return if (sent) LocalResponse(spoken, "media_control")
        else LocalResponse("No active media app.", "media_error")
    }

    private fun sendViaActiveSession(context: Context, keyCode: Int): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return false
            val component = ComponentName(context, NotificationInterceptorService::class.java)
            val controllers: List<MediaController> = try {
                msm.getActiveSessions(component)
            } catch (_: SecurityException) {
                // NotificationListener not granted — caller will fall through to AudioManager.
                return false
            }
            val controller = controllers.firstOrNull() ?: return false
            val now = SystemClock.uptimeMillis()
            controller.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            controller.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendViaAudioManager(context: Context, keyCode: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openMusicSearch(context: Context, query: String, artist: String): LocalResponse {
        val cleanQuery = listOf(query, artist).filter { it.isNotBlank() }.joinToString(" ").trim()

        // 1. Try MediaStore intent — opens the user's preferred music app with a search.
        if (cleanQuery.isNotBlank()) {
            try {
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(android.app.SearchManager.QUERY, cleanQuery)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return LocalResponse("Playing $cleanQuery.", "media_play")
                }
            } catch (_: Exception) { /* fall through */ }
        }

        // 2. Just resume whatever's currently loaded.
        if (cleanQuery.isBlank() && sendViaActiveSession(context, KeyEvent.KEYCODE_MEDIA_PLAY)) {
            return LocalResponse("Playing.", "media_play")
        }
        if (cleanQuery.isBlank() && sendViaAudioManager(context, KeyEvent.KEYCODE_MEDIA_PLAY)) {
            return LocalResponse("Playing.", "media_play")
        }

        // 3. Launch a music app (try common ones).
        val musicApps = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.amazon.mp3",
            "deezer.android.app",
            "com.android.music"
        )
        for (pkg in musicApps) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                context.startActivity(launch)
                return LocalResponse("Opening music.", "media_play")
            } catch (_: Exception) { /* try next */ }
        }
        return LocalResponse("No music app installed.", "media_error")
    }

    private fun nowPlaying(context: Context): LocalResponse {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return LocalResponse("Use your music app to see what's playing.", "media_now_playing")
            val component = ComponentName(context, NotificationInterceptorService::class.java)
            val controllers = try { msm.getActiveSessions(component) } catch (_: SecurityException) { return LocalResponse("Use your music app to see what's playing.", "media_now_playing") }
            val controller = controllers.firstOrNull()
                ?: return LocalResponse("Nothing is playing.", "media_now_playing")
            val md = controller.metadata
            val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            when {
                title != null && artist != null -> LocalResponse("Playing $title by $artist.", "media_now_playing")
                title != null -> LocalResponse("Playing $title.", "media_now_playing")
                else -> LocalResponse("Something is playing.", "media_now_playing")
            }
        } catch (_: Exception) {
            LocalResponse("Couldn't read what's playing.", "media_error")
        }
    }
}
