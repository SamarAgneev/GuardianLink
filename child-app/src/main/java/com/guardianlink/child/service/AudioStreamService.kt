// child-app/src/main/java/com/guardianlink/child/service/AudioStreamService.kt
package com.guardianlink.child.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject

@AndroidEntryPoint
class AudioStreamService : LifecycleService() {

    companion object {
        const val ACTION_START = "guardianlink.action.START_AUDIO"
        const val ACTION_STOP  = "guardianlink.action.STOP_AUDIO"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_EXPIRES_AT = "expires_at"

        private const val SAMPLE_RATE  = 44100
        private const val CHANNELS     = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
        private const val BIT_RATE_AAC = 64_000
        private const val MIME_TYPE    = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    @Inject lateinit var firebaseManager: ChildFirebaseManager
    @Inject lateinit var securePrefs: SecurePreferences
    @Inject lateinit var webRtcClient: ChildWebRtcClient

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var captureJob: Job? = null
    private var sessionId = ""
    private var sessionExpiresAt = 0L
    @Volatile private var firstFrameSent = false
    private var streaming = false
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING) * 2

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                if (streaming && sessionId.isNotBlank() && sessionId != newSessionId) {
                    // A second ACTION_START for a different session while
                    // already streaming would otherwise leak the current
                    // AudioRecord/MediaCodec (overwritten without being
                    // released) and leave two capture coroutines running
                    // concurrently against the microphone. Cleanly end the
                    // current session first — reporting STOPPED against its
                    // own (old) session id, before it gets overwritten below.
                    Timber.w("New audio session $newSessionId is superseding still-active session $sessionId")
                    stopStreaming("Superseded by a new stream session")
                }
                sessionId = newSessionId
                sessionExpiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, 0L)
                startStreaming()
            }
            ACTION_STOP -> {
                stopStreaming("Stopped by request")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopStreaming("Audio service destroyed")
        super.onDestroy()
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private fun startStreaming() {
        if (!canStartStreaming()) {
            reportStreamFailure("Microphone permission is missing or the stream session is invalid or expired")
            stopSelf()
            return
        }

        streaming = true
        updateStreamSession(
            StreamSessionStatus.STARTING,
            false,
            "Microphone permission granted; waiting for first encoded frame"
        )

        // Set up AudioRecord for PCM capture
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNELS, ENCODING, bufferSize
            )
            check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord is not initialized"
            }

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_AAC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioRecord?.startRecording()
            check(audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter recording state"
            }
        } catch (e: Exception) {
            Timber.e(e, "Audio capture initialization failed")
            reportStreamFailure("Audio capture initialization failed: ${e.message.orEmpty()}")
            stopStreaming("Audio capture initialization failed")
            stopSelf()
            return
        }

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            val pcmBuffer = ByteArray(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive) {
                if (!hasAudioPermission()) {
                    reportStreamFailure("Microphone permission was revoked during capture")
                    lifecycleScope.launch {
                        stopStreaming("Microphone permission revoked")
                        stopSelf()
                    }
                    break
                }
                if (sessionExpiresAt <= System.currentTimeMillis()) {
                    reportStreamFailure("Audio stream session expired")
                    lifecycleScope.launch {
                        stopStreaming("Audio stream session expired")
                        stopSelf()
                    }
                    break
                }
                val bytesRead = audioRecord?.read(pcmBuffer, 0, bufferSize) ?: break
                if (bytesRead < 0) {
                    reportStreamFailure("AudioRecord stopped reading microphone data")
                    lifecycleScope.launch {
                        stopStreaming("AudioRecord stopped")
                        stopSelf()
                    }
                    break
                }
                if (bytesRead == 0) continue

                // Feed PCM into AAC encoder
                val inputIndex = mediaCodec?.dequeueInputBuffer(10_000) ?: continue
                if (inputIndex >= 0) {
                    val inputBuffer: ByteBuffer = mediaCodec!!.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    inputBuffer.put(pcmBuffer, 0, bytesRead)
                    mediaCodec!!.queueInputBuffer(
                        inputIndex, 0, bytesRead, System.nanoTime() / 1000, 0
                    )
                }

                // Drain encoded AAC output
                var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: continue
                while (outputIndex >= 0) {
                    val outputBuffer: ByteBuffer = mediaCodec!!.getOutputBuffer(outputIndex)!!
                    val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val encodedData = ByteArray(outputBuffer.remaining())
                    outputBuffer.get(encodedData)

                    // Transmit encoded AAC frame
                    if (encodedData.isNotEmpty() && !isCodecConfig) {
                        sendAudioFrame(encodedData)
                        if (!firstFrameSent) {
                            firstFrameSent = true
                            updateStreamSession(
                                StreamSessionStatus.ACTIVE,
                                true,
                                "First encoded audio frame captured"
                            )
                            firebaseManager.updateStreamStatus(
                                securePrefs.getString(SecurePreferences.KEY_DEVICE_ID),
                                StreamType.AUDIO,
                                true
                            )
                        }
                    }

                    mediaCodec!!.releaseOutputBuffer(outputIndex, false)
                    outputIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        }

        Timber.i("Audio streaming started")
        try {
            webRtcClient.startSession(sessionId, sessionExpiresAt)
        } catch (e: Exception) {
            reportStreamFailure("WebRTC audio session could not start")
            stopStreaming("WebRTC audio session could not start")
            stopSelf()
        }
    }

    private fun sendAudioFrame(data: ByteArray) {
        if (webRtcClient.isConnected()) {
            webRtcClient.sendAudioFrame(data)
        } else {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.pushAudioChunk(deviceId, sessionId, data)
        }
    }

    private fun stopStreaming(reason: String) {
        captureJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio streaming")
        }

        audioRecord = null
        mediaCodec  = null
        if (streaming) {
            webRtcClient.stopSession()
            updateStreamSession(StreamSessionStatus.STOPPED, false, reason)
            lifecycleScope.launch(Dispatchers.IO) {
                firebaseManager.updateStreamStatus(
                    securePrefs.getString(SecurePreferences.KEY_DEVICE_ID),
                    StreamType.AUDIO,
                    false
                )
            }
        }
        streaming = false
        firstFrameSent = false
    }

    private fun canStartStreaming(): Boolean {
        val permissionGranted = hasAudioPermission()
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        val parentId = securePrefs.getString(SecurePreferences.KEY_PARENT_ID)
        return bufferSize > 0 &&
            permissionGranted &&
            deviceId.isNotBlank() &&
            parentId.isNotBlank() &&
            StreamSessionPolicy.isValidSessionId(sessionId) &&
            sessionExpiresAt > System.currentTimeMillis()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun reportStreamFailure(reason: String) {
        if (sessionId.isBlank()) return
        webRtcClient.stopSession()
        updateStreamSession(StreamSessionStatus.FAILED, false, reason)
        streaming = false
        Timber.w("Audio stream failed: $reason")
    }

    private fun updateStreamSession(status: StreamSessionStatus, active: Boolean, reason: String) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.updateStreamSession(sessionId, deviceId, status, active, reason)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, NotificationChannels.STREAM_CHANNEL)
            .setContentTitle("GuardianLink microphone")
            .setContentText("Waiting for microphone permission and the first encoded frame")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NotificationIds.AUDIO_STREAM, notification)
    }
}
