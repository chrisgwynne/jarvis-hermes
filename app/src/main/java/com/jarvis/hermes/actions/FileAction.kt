package com.jarvis.hermes.actions

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.jarvis.hermes.LocalResponse

/**
 * File action handler: open downloads, show photos, manage files.
 */
object FileAction {

    private const val ACTION_OPEN_DOWNLOADS = "open_downloads"
    private const val ACTION_OPEN_PHOTOS = "open_photos"
    private const val ACTION_OPEN_DOCUMENTS = "open_documents"
    private const val ACTION_OPEN_FILES = "open_files"
    private const val ACTION_SHOW_STORAGE = "show_storage"
    private const val ACTION_OPEN_FOLDER = "open_folder"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(show|open)\s+(my\s+)?downloads$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN_DOWNLOADS)
            Regex("""^(show|open)\s+(my\s+)?(photos|gallery|pictures)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN_PHOTOS)
            Regex("""^open\s+(my\s+)?documents$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN_DOCUMENTS)
            Regex("""^open\s+(file\s*manager|files)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_OPEN_FILES)
            Regex("""^(show|check|how\s+much)\s+(storage|space|memory)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SHOW_STORAGE)
            Regex("""^open\s+(.+)\s+folder$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^open\s+(.+)\s+folder$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_OPEN_FOLDER, "folder" to (match?.groupValues?.get(1) ?: ""))
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("File action unclear.", "file_error")

        return when (action) {
            ACTION_OPEN_DOWNLOADS -> openDownloads(context)
            ACTION_OPEN_PHOTOS -> openPhotos(context)
            ACTION_OPEN_DOCUMENTS -> openDocuments(context)
            ACTION_OPEN_FILES -> openFileManager(context)
            ACTION_SHOW_STORAGE -> showStorage(context)
            ACTION_OPEN_FOLDER -> openFolder(context, params["folder"] ?: "")
            else -> LocalResponse("Unknown file action.", "file_error")
        }
    }

    private fun openDownloads(context: Context): LocalResponse {
        // DownloadManager's view downloads intent is the canonical path on all versions.
        return try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Opening downloads.", "file_downloads")
            } else {
                openFileManager(context)
            }
        } catch (e: Exception) {
            openFileManager(context)
        }
    }

    private fun openPhotos(context: Context): LocalResponse {
        // Try the system Photos app via well-known package names, then fall back to
        // the gallery intent.
        val galleryPkgs = listOf(
            "com.google.android.apps.photos",
            "com.sec.android.gallery3d",
            "com.miui.gallery"
        )
        for (pkg in galleryPkgs) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                context.startActivity(launch)
                return LocalResponse("Opening photos.", "file_photos")
            } catch (_: Exception) { /* try next */ }
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening photos.", "file_photos")
        } catch (e: Exception) {
            LocalResponse("No gallery app available.", "file_error")
        }
    }

    private fun openDocuments(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening documents.", "file_documents")
        } catch (e: Exception) {
            LocalResponse("Couldn't open documents.", "file_error")
        }
    }

    private fun openFileManager(context: Context): LocalResponse {
        val candidates = listOf(
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.sec.android.app.myfiles",
            "com.mi.android.globalFileexplorer",
            "com.amazon.fileexplorer"
        )
        for (pkg in candidates) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                context.startActivity(intent)
                return LocalResponse("Opening file manager.", "file_manager")
            } catch (_: Exception) { /* try next */ }
        }
        return try {
            val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening storage settings.", "file_storage")
        } catch (e: Exception) {
            LocalResponse("No file manager available.", "file_error")
        }
    }

    private fun showStorage(context: Context): LocalResponse {
        return try {
            val path = Environment.getDataDirectory()
            val stat = android.os.StatFs(path.path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            if (totalBytes <= 0L) return LocalResponse("Couldn't read storage.", "file_error")
            val usedPercent = ((totalBytes - availableBytes) * 100 / totalBytes).toInt()
            val totalGB = totalBytes / (1024L * 1024L * 1024L)
            val availableGB = availableBytes / (1024L * 1024L * 1024L)
            LocalResponse(
                "Storage $usedPercent percent used. $availableGB gigabytes free of $totalGB total.",
                "file_storage"
            )
        } catch (e: Exception) {
            LocalResponse("Couldn't read storage.", "file_error")
        }
    }

    private fun openFolder(context: Context, folderName: String): LocalResponse {
        return try {
            val uri: Uri? = when (folderName.lowercase()) {
                "downloads" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI else null
                "pictures", "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                "videos", "movies" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            if (uri == null) return openFileManager(context)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Opening $folderName.", "file_folder")
            } else {
                openFileManager(context)
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't open $folderName.", "file_error")
        }
    }
}
