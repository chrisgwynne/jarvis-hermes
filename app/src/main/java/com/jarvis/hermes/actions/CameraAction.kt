package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarvis.hermes.LocalResponse
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera action handler: take photo, record video, torch, scan QR.
 */
object CameraAction {

    private const val ACTION_PHOTO = "photo"
    private const val ACTION_VIDEO = "video"
    private const val ACTION_TORCH_ON = "torch_on"
    private const val ACTION_TORCH_OFF = "torch_off"
    private const val ACTION_TORCH_TOGGLE = "torch_toggle"
    private const val ACTION_SCAN_QR = "scan_qr"
    private const val ACTION_FRONT_PHOTO = "front_photo"
    private const val ACTION_PREVIEW = "preview"

    fun requiredPermissions() = listOf(Manifest.permission.CAMERA)

    // Torch needs no CAMERA permission since API 23 — only setTorchMode call.
    private fun requiredPermissionsForTorch() = emptyList<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Front camera / selfie
            Regex("""^(take\s+)?(a\s+)?selfie$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_FRONT_PHOTO)
            // "take a front photo" / "front camera"
            Regex("""^(take\s+)?(a\s+)?front\s+(camera\s+)?photo$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_FRONT_PHOTO)
            // Take photo (rear)
            Regex("""^(take\s+)?(a\s+)?photo$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_PHOTO)
            Regex("""^(take\s+)?(a\s+)?(rear|back)\s+photo$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_PHOTO)
            // Record video
            Regex("""^(record\s+)?(a\s+)?video$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_VIDEO)
            // Torch / flashlight
            Regex("""^(torch|flashlight)\s+(on|off)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^(torch|flashlight)\s+(on|off)$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(2)?.lowercase()
                mapOf("action" to if (state == "on") ACTION_TORCH_ON else ACTION_TORCH_OFF)
            }
            Regex("""^(torch|flashlight)$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_TORCH_TOGGLE)
            // Scan QR
            Regex("""^scan(\s+qr)?$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_SCAN_QR)
            // Open camera
            Regex("""^open\s+camera$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_PREVIEW)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Camera action unclear.", "camera_error")

        val isTorchAction = action == ACTION_TORCH_ON || action == ACTION_TORCH_OFF || action == ACTION_TORCH_TOGGLE
        if (!isTorchAction) {
            val missing = requiredPermissions().filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                return LocalResponse("Camera permission not granted.", "camera_permission")
            }
        }

        return when (action) {
            ACTION_PHOTO -> takePhoto(context, useFront = false)
            ACTION_FRONT_PHOTO -> takePhoto(context, useFront = true)
            ACTION_VIDEO -> recordVideo(context)
            ACTION_TORCH_ON -> setTorch(context, true)
            ACTION_TORCH_OFF -> setTorch(context, false)
            ACTION_TORCH_TOGGLE -> setTorch(context, !getTorchState(context))
            ACTION_SCAN_QR -> openQRScanner(context)
            ACTION_PREVIEW -> openCameraPreview(context)
            else -> LocalResponse("Unknown camera action.", "camera_error")
        }
    }

    private fun outputPhotoUri(context: Context): Uri? {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            if (!dir.exists()) dir.mkdirs()
            val filename = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val file = File(dir, filename)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun takePhoto(context: Context, useFront: Boolean): LocalResponse {
        val outputUri = outputPhotoUri(context)
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                if (outputUri != null) {
                    putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (useFront) {
                    // Hint to use front camera. Many OEM camera apps honour this extra.
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse(if (useFront) "Opening front camera." else "Opening camera.", "camera_photo")
            } else {
                // Fallback: launch the system camera
                openCameraPreview(context)
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't open camera.", "camera_error")
        }
    }

    private fun recordVideo(context: Context): LocalResponse {
        return try {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Opening camera for video.", "camera_video")
            } else {
                LocalResponse("No video camera app installed.", "camera_error")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't open camera.", "camera_error")
        }
    }

    private fun setTorch(context: Context, on: Boolean): LocalResponse {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val ch = cameraManager.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return LocalResponse("No camera with flash.", "camera_error")

            cameraManager.setTorchMode(cameraId, on)
            LocalResponse(if (on) "Flashlight on." else "Flashlight off.", "camera_torch")
        } catch (e: Exception) {
            LocalResponse("Couldn't control flashlight.", "camera_error")
        }
    }

    private fun getTorchState(context: Context): Boolean {
        // CameraManager has no portable "get current torch mode" API across all OEMs.
        // Track our own state via SharedPreferences so toggle works reliably.
        val prefs = context.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
        return prefs.getBoolean("torch_state", false)
    }

    private fun openQRScanner(context: Context): LocalResponse {
        // Try popular QR scanners by intent.
        val candidates = listOf(
            Intent("com.google.zxing.client.android.SCAN"),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra("android.intent.extras.CAMERA_FACING", 0)
            }
        )
        for (intent in candidates) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return LocalResponse("Opening QR scanner.", "camera_qr")
                }
            } catch (_: Exception) { /* try next */ }
        }
        return LocalResponse("No QR scanner installed.", "camera_error")
    }

    private fun openCameraPreview(context: Context): LocalResponse {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                LocalResponse("Opening camera.", "camera_preview")
            } else {
                LocalResponse("Camera app not available.", "camera_error")
            }
        } catch (e: Exception) {
            LocalResponse("Couldn't open camera.", "camera_error")
        }
    }
}
