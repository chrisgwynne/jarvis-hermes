package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    fun requiredPermissions() = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private var cameraExecutor: ExecutorService? = null

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Take photo
            Regex("""^(take\s+)?(a\s+)?photo$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_PHOTO)
            }
            // Take photo with front camera
            Regex("""^(take\s+)?(a\s+)?selfie$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_FRONT_PHOTO)
            }
            // Record video
            Regex("""^(record\s+)?(a\s+)?video$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_VIDEO)
            }
            // Torch on/off
            Regex("""^torch\s+(on|off)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^torch\s+(on|off)$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (state == "on") ACTION_TORCH_ON else ACTION_TORCH_OFF)
            }
            // Torch toggle
            Regex("""^torch$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_TORCH_TOGGLE)
            }
            // Flashlight on/off (synonym for torch)
            Regex("""^flashlight\s+(on|off)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^flashlight\s+(on|off)$""", RegexOption.IGNORE_CASE).find(text)
                val state = match?.groupValues?.get(1)?.lowercase()
                mapOf("action" to if (state == "on") ACTION_TORCH_ON else ACTION_TORCH_OFF)
            }
            // Scan QR
            Regex("""^scan\s*(qr)?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_SCAN_QR)
            }
            // Open camera
            Regex("""^open\s+camera$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_PREVIEW)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Camera action unclear.", "camera_error")

        val missingPerms = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty() && action !in listOf(ACTION_TORCH_ON, ACTION_TORCH_OFF, ACTION_TORCH_TOGGLE)) {
            return LocalResponse("Camera permission not granted.", "camera_permission")
        }

        return when (action) {
            ACTION_PHOTO, ACTION_FRONT_PHOTO -> {
                takePhoto(context, action == ACTION_FRONT_PHOTO)
            }
            ACTION_VIDEO -> {
                recordVideo(context)
            }
            ACTION_TORCH_ON, ACTION_TORCH_OFF -> {
                setTorch(context, action == ACTION_TORCH_ON)
            }
            ACTION_TORCH_TOGGLE -> {
                val currentState = getTorchState(context)
                setTorch(context, !currentState)
            }
            ACTION_SCAN_QR -> {
                openQRC Scanner(context)
            }
            ACTION_PREVIEW -> {
                openCameraPreview(context)
            }
            else -> LocalResponse("Unknown camera action.", "camera_error")
        }
    }

    private fun takePhoto(context: Context, useFront: Boolean): LocalResponse {
        val filename = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            filename
        )

        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(file))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return LocalResponse("Opening camera for photo.", "camera_photo")
            }
        } catch (e: Exception) {
            return LocalResponse("Couldn't open camera.", "camera_error")
        }

        // Fallback: open camera app
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening camera.", "camera_photo")
        } catch (e: Exception) {
            LocalResponse("Camera not available.", "camera_error")
        }

        return LocalResponse("Camera not available.", "camera_error")
    }

    private fun recordVideo(context: Context): LocalResponse {
        try {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return LocalResponse("Opening camera for video.", "camera_video")
            }
        } catch (e: Exception) {
            return LocalResponse("Couldn't open camera.", "camera_error")
        }
        return LocalResponse("Camera not available.", "camera_error")
    }

    private fun setTorch(context: Context, on: Boolean): LocalResponse {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return LocalResponse("No camera with flash.", "camera_error")

            cameraManager.setTorchMode(cameraId, on)
            return LocalResponse(if (on) "Flashlight on." else "Flashlight off.", "camera_torch")
        } catch (e: Exception) {
            return LocalResponse("Couldn't control flashlight.", "camera_error")
        }
    }

    private fun getTorchState(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.getTorchMode(cameraId)
        } catch (e: Exception) {
            false
        }
    }

    private fun openQRScanner(context: Context): LocalResponse {
        try {
            val intent = Intent("com.google.zxing.client.android.SCAN").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return LocalResponse("Opening QR scanner.", "camera_qr")
            }
        } catch (e: Exception) {
            // Fall through to generic camera
        }
        
        try {
            val intent = Intent(MediaStore.ACTION_SCAN_MODE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening camera scanner.", "camera_qr")
        } catch (e: Exception) {
            LocalResponse("QR scanner not available.", "camera_error")
        }
    }

    private fun openCameraPreview(context: Context): LocalResponse {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return LocalResponse("Opening camera.", "camera_preview")
        } catch (e: Exception) {
            return LocalResponse("Camera not available.", "camera_error")
        }
    }
}