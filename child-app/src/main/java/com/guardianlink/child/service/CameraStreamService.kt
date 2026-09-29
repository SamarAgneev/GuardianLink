// child-app/src/main/java/com/guardianlink/child/service/CameraStreamService.kt
package com.guardianlink.child.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Base64
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardianlink.child.R
import com.guardianlink.child.firebase.ChildFirebaseManager
import com.guardianlink.child.webrtc.ChildWebRtcClient
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.common.constants.NotificationIds
import com.guardianlink.common.model.StreamSessionStatus
import com.guardianlink.common.model.StreamType
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.StreamSessionPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class CameraStreamService : LifecycleService() {

    companion object {
        const val ACTION_START         = "guardianlink.action.START_CAMERA"
        const val ACTION_STOP          = "guardianlink.action.STOP_CAMERA"
        const val ACTION_CAPTURE_PHOTO = "guardianlink.action.CAPTURE_PHOTO"
        const val ACTION_SWITCH_CAMERA = "guardianlink.action.SWITCH_CAMERA"
        const val EXTRA_USE_FRONT      = "front"
        const val EXTRA_SESSION_ID     = "session_id"
        const val EXTRA_EXPIRES_AT     = "expires_at"
    }

    @Inject lateinit var firebaseManager: ChildFirebaseManager
    @Inject lateinit var securePrefs: SecurePreferences
    @Inject lateinit var webRtcClient: ChildWebRtcClient

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var useFrontCamera = true
    private var streamingJob: Job? = null
    private var pendingAction: String? = null
    private var sessionId = ""
    private var sessionExpiresAt = 0L
    @Volatile private var firstFrameSent = false
    private var streaming = false

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                // If a different session is already active on this device, end
                // it cleanly first — otherwise its Firestore record is left
                // ACTIVE indefinitely (until the scheduled expiry sweep catches
                // it), and its WebRTC session is silently abandoned rather than
                // stopped.
                if (streaming && sessionId.isNotBlank() && sessionId != newSessionId) {
                    Timber.w("New camera session $newSessionId is superseding still-active session $sessionId")
                    webRtcClient.stopSession()
                    updateStreamSession(StreamSessionStatus.STOPPED, false, "Superseded by a new stream session")
                    updateStreamStatusAsync(
                        securePrefs.getString(SecurePreferences.KEY_DEVICE_ID),
                        streamType(),
                        false
                    )
                    firstFrameSent = false
                }

                useFrontCamera = intent.getBooleanExtra(EXTRA_USE_FRONT, true)
                sessionId = newSessionId
                sessionExpiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, 0L)
                streaming = true
                if (!canStartStreaming()) {
                    reportStreamFailure("Camera permission is missing or the stream session is invalid or expired")
                    stopSelf()
                } else {
                    try {
                        webRtcClient.startSession(sessionId, sessionExpiresAt)
                        bindCamera()
                    } catch (e: Exception) {
                        reportStreamFailure("WebRTC camera session could not start")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                unbindCamera()
                stopSelf()
            }
            ACTION_CAPTURE_PHOTO -> {
                useFrontCamera = intent.getBooleanExtra(EXTRA_USE_FRONT, true)
                if (!hasCameraPermission()) {
                    Timber.w("Photo capture denied because camera permission is missing")
                    stopSelf()
                    return START_NOT_STICKY
                }
                pendingAction = ACTION_CAPTURE_PHOTO
                bindCamera()
            }
            ACTION_SWITCH_CAMERA -> {
                if (!hasCameraPermission()) {
                    Timber.w("Camera switch denied because camera permission is missing")
                    stopSelf()
                    return START_NOT_STICKY
                }
                useFrontCamera = !useFrontCamera
                unbindCamera()
                bindCamera()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        unbindCamera()
        cameraExecutor.shutdown()
        if (streaming) {
            webRtcClient.stopSession()
            updateStreamSession(StreamSessionStatus.STOPPED, false, "Camera stream stopped")
            updateStreamStatusAsync(
                securePrefs.getString(SecurePreferences.KEY_DEVICE_ID),
                streamType(),
                false
            )
        }
        super.onDestroy()
    }

    // ── Camera Binding ────────────────────────────────────────────────────────

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                startCameraUseCase()
            } catch (e: Exception) {
                Timber.e(e, "Camera provider initialization failed")
                reportStreamFailure("Camera provider initialization failed")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCameraUseCase() {
        val cameraProvider = cameraProvider ?: return

        val selector = if (useFrontCamera)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(cameraExecutor) { imageProxy ->
                    if (pendingAction == null) {
                        processFrame(imageProxy)
                    }
                    imageProxy.close()
                }
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(android.util.Size(1280, 720))
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, selector, imageAnalysis, imageCapture
            )
            if (streaming) {
                updateStreamSession(
                    StreamSessionStatus.STARTING,
                    false,
                    "Camera permission granted; waiting for first frame"
                )
            }

            // Execute pending action
            when (pendingAction) {
                ACTION_CAPTURE_PHOTO -> {
                    pendingAction = null
                    takePhoto()
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Camera binding failed")
            if (streaming) reportStreamFailure("Camera binding failed: ${e.message.orEmpty()}")
            stopSelf()
        }
    }

    // ── Streaming Frames ──────────────────────────────────────────────────────

    private fun processFrame(imageProxy: ImageProxy) {
        if (!hasCameraPermission()) {
            reportStreamFailure("Camera permission was revoked during capture")
            stopSelf()
            return
        }
        if (streaming && sessionExpiresAt <= System.currentTimeMillis()) {
            reportStreamFailure("Camera stream session expired")
            stopSelf()
            return
        }

        // Convert YUV to JPEG
        val jpegBytes = imageProxyToJpeg(imageProxy) ?: return

        if (webRtcClient.isConnected()) {
            webRtcClient.sendCameraFrame(jpegBytes)
        } else {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.pushCameraFrame(deviceId, sessionId, jpegBytes)
        }

        if (streaming && !firstFrameSent) {
            firstFrameSent = true
            updateStreamSession(StreamSessionStatus.ACTIVE, true, "First camera frame captured")
            updateStreamStatusAsync(
                securePrefs.getString(SecurePreferences.KEY_DEVICE_ID),
                streamType(),
                true
            )
        }
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        return try {
            val width = imageProxy.width
            val height = imageProxy.height
            val nv21 = ByteArray(width * height + 2 * (width / 2) * (height / 2))

            // Y plane: rowStride can exceed width (padding), so each row must be
            // copied respecting rowStride rather than assuming the buffer is
            // tightly packed.
            val yPlane = imageProxy.planes[0]
            copyPlaneRespectingStride(
                yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                width, height, nv21, 0
            )

            // U/V planes for NV21 must be interleaved as V,U,V,U,... . Many real
            // devices report these with pixelStride == 2 (the U and V planes are
            // both views over one already-interleaved buffer), in which case
            // simply concatenating buffer.remaining() bytes from each plane (the
            // previous implementation) reads padding/foreign-channel bytes into
            // the output and produces a visually corrupted (wrong-color)
            // image — it only happened to work when pixelStride was 1. This
            // walks each chroma row using its actual pixelStride/rowStride
            // instead of assuming either.
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            var offset = width * height
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                    val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    nv21[offset++] = vBuffer.get(vIndex)
                    nv21[offset++] = uBuffer.get(uIndex)
                }
            }

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width, height, null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, width, height),
                60, out
            )
            out.toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "Frame conversion error")
            null
        }
    }

    private fun copyPlaneRespectingStride(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        dest: ByteArray,
        destOffset: Int
    ) {
        var outIndex = destOffset
        if (pixelStride == 1 && rowStride == width) {
            // Fast path: tightly packed, no padding — safe to bulk-copy.
            val duplicate = buffer.duplicate()
            duplicate.position(0)
            duplicate.get(dest, destOffset, width * height)
            return
        }
        for (row in 0 until height) {
            for (col in 0 until width) {
                dest[outIndex++] = buffer.get(row * rowStride + col * pixelStride)
            }
        }
    }

    // ── Photo Capture ─────────────────────────────────────────────────────────

    private fun takePhoto() {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        val filename = "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val outputFile = File(cacheDir, filename)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture?.takePicture(
            outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            firebaseManager.uploadPhoto(deviceId, outputFile, filename)
                            outputFile.delete()
                        } catch (e: Exception) {
                            Timber.e(e, "Photo upload failed")
                        }
                    }
                    // Stop service after single photo capture
                    stopSelf()
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "Photo capture failed")
                    stopSelf()
                }
            }
        )
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        imageCapture = null
        streamingJob?.cancel()
    }

    private fun canStartStreaming(): Boolean {
        val permissionGranted = hasCameraPermission()
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        val parentId = securePrefs.getString(SecurePreferences.KEY_PARENT_ID)
        return permissionGranted &&
            deviceId.isNotBlank() &&
            parentId.isNotBlank() &&
            StreamSessionPolicy.isValidSessionId(sessionId) &&
            sessionExpiresAt > System.currentTimeMillis()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun reportStreamFailure(reason: String) {
        if (!streaming || sessionId.isBlank()) return
        webRtcClient.stopSession()
        updateStreamSession(StreamSessionStatus.FAILED, false, reason)
        streaming = false
        Timber.w("Camera stream failed: $reason")
    }

    private fun updateStreamSession(status: StreamSessionStatus, active: Boolean, reason: String) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.updateStreamSession(sessionId, deviceId, status, active, reason)
        }
    }

    private fun updateStreamStatusAsync(deviceId: String, type: StreamType, active: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.updateStreamStatus(deviceId, type, active)
        }
    }

    private fun streamType(): StreamType =
        if (useFrontCamera) StreamType.CAMERA_FRONT else StreamType.CAMERA_BACK

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, NotificationChannels.STREAM_CHANNEL)
            .setContentTitle("GuardianLink camera")
            .setContentText("Waiting for camera permission and the first captured frame")
            .setSmallIcon(R.drawable.ic_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NotificationIds.CAMERA_STREAM, notification)
    }
}
