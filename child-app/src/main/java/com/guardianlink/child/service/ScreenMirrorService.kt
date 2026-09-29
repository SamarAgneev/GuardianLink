// child-app/src/main/java/com/guardianlink/child/service/ScreenMirrorService.kt
package com.guardianlink.child.service

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardianlink.child.R
import com.guardianlink.child.firebase.ChildFirebaseManager
import com.guardianlink.child.webrtc.ChildWebRtcClient
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.common.constants.NotificationIds
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.ScreenMirrorPolicy
import com.guardianlink.common.util.ScreenMirrorState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ScreenMirrorService : LifecycleService() {

    companion object {
        const val ACTION_START = "guardianlink.action.START_SCREEN_MIRROR"
        const val ACTION_STOP = "guardianlink.action.STOP_SCREEN_MIRROR"
        const val ACTION_CONSENT_FAILED = "guardianlink.action.SCREEN_MIRROR_CONSENT_FAILED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FAILURE_REASON = "failure_reason"

        private const val CAPTURE_FPS = 5
        private const val CAPTURE_WIDTH = 720
        private const val CAPTURE_HEIGHT = 1280
        private const val JPEG_QUALITY = 60
        private val SESSION_ID_PATTERN = Regex("[a-fA-F0-9-]{36}")
    }

    @Inject lateinit var firebaseManager: ChildFirebaseManager
    @Inject lateinit var securePrefs: SecurePreferences
    @Inject lateinit var webRtcClient: ChildWebRtcClient

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var sessionId = ""
    @Volatile private var captureStarted = false
    @Volatile private var state = ScreenMirrorState.IDLE

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                sessionId = secureSessionId(intent.getStringExtra(EXTRA_SESSION_ID))
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startMirroring(resultCode, resultData)
                } else {
                    requestMediaProjectionConsent()
                }
            }

            ACTION_CONSENT_FAILED -> {
                sessionId = secureSessionId(intent.getStringExtra(EXTRA_SESSION_ID))
                reportFailure(intent.getStringExtra(EXTRA_FAILURE_REASON).orEmpty())
                stopSelf()
            }

            ACTION_STOP -> {
                sessionId = secureSessionId(null)
                stopMirroring("Stopped by request")
                stopSelf()
            }
        }

        // Projection consent is one-time and must never be silently replayed after process death.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopMirroring("Service destroyed")
        super.onDestroy()
    }

    private fun startMirroring(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null || captureJob?.isActive == true) {
            Timber.w("Ignoring duplicate screen mirror start")
            return
        }

        state = ScreenMirrorPolicy.consentGranted().state
        updateStreamStatus(state, "MediaProjection consent granted; waiting for first frame")

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager?.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            reportFailure("MediaProjection could not be created")
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.i("MediaProjection stopped externally")
                lifecycleScope.launch {
                    stopMirroring("MediaProjection stopped by Android or user")
                }
            }
        }, null)

        if (!setupVirtualDisplay()) {
            reportFailure("VirtualDisplay could not be created")
            stopMirroring("VirtualDisplay setup failed")
            return
        }

        startCapture()
        Timber.i("Screen mirroring is starting; stream remains inactive until first frame")
    }

    private fun setupVirtualDisplay(): Boolean {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(metrics)

        imageReader = ImageReader.newInstance(
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GuardianLinkMirror-$sessionId",
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        return imageReader != null && virtualDisplay != null
    }

    private fun startCapture() {
        captureJob?.cancel()
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            val intervalMs = 1000L / CAPTURE_FPS
            while (isActive) {
                captureFrame()
                delay(intervalMs)
            }
        }
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        var bitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null

        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * CAPTURE_WIDTH
            bitmap = Bitmap.createBitmap(
                CAPTURE_WIDTH + rowPadding / pixelStride,
                CAPTURE_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)
            croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, CAPTURE_WIDTH, CAPTURE_HEIGHT)

            val output = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            val frameBytes = output.toByteArray()

            if (!captureStarted) {
                captureStarted = true
                state = ScreenMirrorPolicy.captureStarted().state
                updateStreamStatus(state, "First frame captured")
            }

            if (webRtcClient.isConnected()) {
                webRtcClient.sendScreenFrame(frameBytes)
            } else {
                val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
                firebaseManager.pushScreenFrame(deviceId, sessionId, frameBytes)
            }
        } catch (e: Exception) {
            Timber.e(e, "Frame capture error")
        } finally {
            bitmap?.recycle()
            if (croppedBitmap != null && croppedBitmap !== bitmap) croppedBitmap.recycle()
            image.close()
        }
    }

    private fun stopMirroring(reason: String) {
        if (captureJob == null && mediaProjection == null && virtualDisplay == null && imageReader == null) {
            return
        }

        captureJob?.cancel()
        captureJob = null

        val projection = mediaProjection
        mediaProjection = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()

        if (state != ScreenMirrorState.FAILED) {
            state = ScreenMirrorPolicy.projectionStopped().state
        }
        updateStreamStatus(state, reason)
        captureStarted = false
    }

    private fun reportFailure(reason: String) {
        val message = reason.ifBlank { "MediaProjection consent was denied" }
        state = ScreenMirrorPolicy.consentDenied(message).state
        updateStreamStatus(state, message)
        Timber.w("Screen mirroring failed: $message")
    }

    private fun requestMediaProjectionConsent() {
        sessionId = secureSessionId(sessionId)
        state = ScreenMirrorPolicy.consentRequested().state
        updateStreamStatus(state, "Waiting for explicit MediaProjection consent")
        startActivity(Intent(this, com.guardianlink.child.ui.MediaProjectionConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_SESSION_ID, sessionId))
    }

    private fun secureSessionId(candidate: String?): String {
        val value = candidate.orEmpty()
        return if (SESSION_ID_PATTERN.matches(value)) value else UUID.randomUUID().toString()
    }

    private fun updateStreamStatus(status: ScreenMirrorState, reason: String) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.updateScreenStreamStatus(deviceId, sessionId, status.name, reason)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, NotificationChannels.STREAM_CHANNEL)
            .setContentTitle("GuardianLink screen mirror")
            .setContentText("Waiting for explicit Android screen-capture permission")
            .setSmallIcon(R.drawable.ic_screen_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NotificationIds.SCREEN_MIRROR, notification)
    }
}
