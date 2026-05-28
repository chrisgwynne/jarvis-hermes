package com.jarvis.hermes

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Voice memo manager.
 * Handles recording, playback, and listing of voice memos.
 */
object VoiceMemoManager {

    private const val PREFS_NAME = "jarvis_hermes"
    private const val KEY_MEMOS_METADATA = "memos_metadata"
    private const val MEMOS_FOLDER = "memos"
    private const val MAX_MEMO_DURATION_MS = 30_000L // 30 seconds

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordingPath: String? = null
    private var isRecording = false
    private var recordingStartTime = 0L

    data class Memo(
        val id: String,
        val timestamp: Long,
        val durationMs: Long,
        val filePath: String
    )

    /**
     * Start recording a voice memo.
     * Records for up to MAX_MEMO_DURATION_MS (30 seconds).
     */
    fun startRecording(ctx: Context): Boolean {
        if (isRecording) return false

        val memosDir = getMemosDir(ctx)
        if (!memosDir.exists()) {
            memosDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val fileName = "memo_$timestamp.m4a"
        val filePath = File(memosDir, fileName).absolutePath
        currentRecordingPath = filePath

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(filePath)
                setMaxDuration(MAX_MEMO_DURATION_MS.toInt())

                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecording(ctx)
                    }
                }

                prepare()
                start()
            }

            isRecording = true
            recordingStartTime = timestamp
            return true
        } catch (e: IOException) {
            currentRecordingPath = null
            return false
        } catch (e: IllegalStateException) {
            currentRecordingPath = null
            return false
        }
    }

    /**
     * Stop recording and save the memo.
     * Returns the saved Memo or null if recording failed.
     */
    fun stopRecording(ctx: Context): Memo? {
        if (!isRecording || currentRecordingPath == null) {
            return null
        }

        val filePath = currentRecordingPath!!
        val durationMs = System.currentTimeMillis() - recordingStartTime

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Recording may have been too short
        }

        mediaRecorder = null
        isRecording = false
        currentRecordingPath = null

        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return null
        }

        val memo = Memo(
            id = UUID.randomUUID().toString(),
            timestamp = recordingStartTime,
            durationMs = durationMs,
            filePath = filePath
        )

        saveMemo(ctx, memo)
        return memo
    }

    /**
     * Cancel current recording without saving.
     */
    fun cancelRecording(ctx: Context) {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore
        }

        mediaRecorder = null
        isRecording = false

        // Delete the file
        currentRecordingPath?.let {
            File(it).delete()
        }
        currentRecordingPath = null
    }

    /**
     * Play a memo by ID.
     */
    fun playMemo(ctx: Context, memoId: String): Boolean {
        val memo = getMemo(ctx, memoId) ?: return false
        return playMemoFile(ctx, memo.filePath)
    }

    /**
     * Play a memo file directly.
     */
    fun playMemoFile(ctx: Context, filePath: String): Boolean {
        stopPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                }
                start()
            }
            return true
        } catch (e: IOException) {
            return false
        } catch (e: IllegalStateException) {
            return false
        }
    }

    /**
     * Stop playback.
     */
    fun stopPlayback() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
                release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        mediaPlayer = null
    }

    /**
     * Get list of all memos.
     */
    fun getMemos(ctx: Context): List<Memo> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MEMOS_METADATA, "[]") ?: "[]"

        val memos = mutableListOf<Memo>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                memos.add(Memo(
                    id = obj.getString("id"),
                    timestamp = obj.getLong("timestamp"),
                    durationMs = obj.getLong("durationMs"),
                    filePath = obj.getString("filePath")
                ))
            }
        } catch (e: Exception) {
            // Ignore
        }

        return memos.sortedByDescending { it.timestamp }
    }

    /**
     * Get memo by ID.
     */
    fun getMemo(ctx: Context, memoId: String): Memo? {
        return getMemos(ctx).find { it.id == memoId }
    }

    /**
     * Delete a memo by ID.
     */
    fun deleteMemo(ctx: Context, memoId: String): Boolean {
        val memo = getMemo(ctx, memoId) ?: return false

        // Delete file
        File(memo.filePath).delete()

        // Remove from metadata
        val memos = getMemos(ctx).toMutableList()
        memos.removeAll { it.id == memoId }
        saveMemosList(ctx, memos)

        return true
    }

    /**
     * Delete all memos.
     */
    fun deleteAllMemos(ctx: Context) {
        val memos = getMemos(ctx)
        memos.forEach {
            File(it.filePath).delete()
        }
        saveMemosList(ctx, emptyList())
    }

    private fun saveMemo(ctx: Context, memo: Memo) {
        val memos = getMemos(ctx).toMutableList()
        memos.add(0, memo) // Add to beginning (newest first)

        // Keep only last 20 memos
        val trimmed = memos.take(20)
        saveMemosList(ctx, trimmed)
    }

    private fun saveMemosList(ctx: Context, memos: List<Memo>) {
        val array = org.json.JSONArray()
        memos.forEach { memo ->
            val obj = org.json.JSONObject().apply {
                put("id", memo.id)
                put("timestamp", memo.timestamp)
                put("durationMs", memo.durationMs)
                put("filePath", memo.filePath)
            }
            array.put(obj)
        }

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEMOS_METADATA, array.toString())
            .apply()
    }

    private fun getMemosDir(ctx: Context): File {
        return File(ctx.filesDir, MEMOS_FOLDER)
    }

    /**
     * Format timestamp for display.
     */
    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Format duration for display.
     */
    fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / 1000) / 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    /**
     * Check if currently recording.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Check if currently playing.
     */
    fun isCurrentlyPlaying(): Boolean = mediaPlayer?.isPlaying == true

    /**
     * Get current playback position.
     */
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    /**
     * Get total duration of playing memo.
     */
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
}