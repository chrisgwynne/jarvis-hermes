package com.jarvis.hermes.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import com.jarvis.hermes.LocalResponse
import java.io.File

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
            // Open downloads
            Regex("""^open\s+downloads$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_DOWNLOADS)
            }
            Regex("""^(show|open)\s+(my\s+)?downloads$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_DOWNLOADS)
            }
            // Open photos / gallery
            Regex("""^open\s+(my\s+)?photos$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_PHOTOS)
            }
            Regex("""^(show|open)\s+(my\s+)?gallery$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_PHOTOS)
            }
            Regex("""^open\s+(my\s+)?pictures$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_PHOTOS)
            }
            // Open documents
            Regex("""^open\s+(my\s+)?documents$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_DOCUMENTS)
            }
            // Open file manager / files
            Regex("""^open\s+(file\s*manager|files)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_OPEN_FILES)
            }
            // Show storage
            Regex("""^(show|check|how\s+much)\s+(storage|space|memory)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SHOW_STORAGE)
            }
            // Open specific folder
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
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("content://media/external/downloads")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toURI().toString()), "*/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            LocalResponse("Opening downloads.", "file_downloads")
        } catch (e: Exception) {
            // Fallback to Downloads app
            try {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalResponse("Opening downloads.", "file_downloads")
            } catch (e2: Exception) {
                LocalResponse("Couldn't open downloads.", "file_error")
            }
        }
    }

    private fun openPhotos(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening photos.", "file_photos")
        } catch (e: Exception) {
            LocalResponse("Couldn't open photos.", "file_error")
        }
    }

    private fun openDocuments(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Files.getContentUri("external"), "*/*")
                putExtra(Intent.EXTRA_INITIAL_URLS, arrayOf(
                    MediaStore.Files.getContentUri("external").toString()
                ))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening documents.", "file_documents")
        } catch (e: Exception) {
            LocalResponse("Couldn't open documents.", "file_error")
        }
    }

    private fun openFileManager(context: Context): LocalResponse {
        // Try common file managers
        val fileManagers = listOf(
            "com.google.android.documentsui" to "Files",
            "com.sec.android.app.myfiles" to "My Files",
            "com.amazon.fileexplorer" to "Files",
            "com.android.documentsui" to "Files"
        )

        for ((packageName, _) in fileManagers) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    return LocalResponse("Opening file manager.", "file_manager")
                }
            } catch (e: Exception) {
                continue
            }
        }

        // Fallback to settings
        return try {
            val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening storage settings.", "file_storage")
        } catch (e: Exception) {
            LocalResponse("File manager not available.", "file_error")
        }
    }

    private fun showStorage(context: Context): LocalResponse {
        val path = Environment.getDataDirectory()
        val stat = android.os.StatFs(path.path)
        val totalBytes = stat.totalBytes
        val availableBytes = stat.availableBytes
        val usedPercent = ((totalBytes - availableBytes) * 100 / totalBytes).toInt()

        val totalGB = totalBytes / (1024 * 1024 * 1024)
        val availableGB = availableBytes / (1024 * 1024 * 1024)

        return LocalResponse(
            "Storage: $usedPercent percent used. $availableGB gigabytes available of $totalGB total.",
            "file_storage"
        )
    }

    private fun openFolder(context: Context, folderName: String): LocalResponse {
        return try {
            val uri = when (folderName.lowercase()) {
                "downloads" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                "pictures", "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                "videos", "movies" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "documents" -> MediaStore.Files.getContentUri("external")
                else -> MediaStore.Files.getContentUri("external")
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening $folderName.", "file_folder")
        } catch (e: Exception) {
            LocalResponse("Couldn't open $folderName.", "file_error")
        }
    }
}