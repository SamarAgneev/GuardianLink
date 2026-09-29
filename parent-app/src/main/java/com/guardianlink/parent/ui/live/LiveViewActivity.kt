// parent-app/src/main/java/com/guardianlink/parent/ui/live/LiveViewActivity.kt
package com.guardianlink.parent.ui.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.guardianlink.common.model.CommandType
import com.guardianlink.common.model.StreamType
import com.guardianlink.parent.data.webrtc.ParentWebRtcClient
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LiveViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE  = "mode"
        const val MODE_SCREEN = "screen"
        const val MODE_CAMERA = "camera"
    }

    private val viewModel: DashboardViewModel by viewModels()
    @javax.inject.Inject lateinit var parentWebRtcClient: ParentWebRtcClient

    private lateinit var ivLiveFrame: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnStop: Button
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnCapture: ImageButton
    private lateinit var progressLoading: ProgressBar

    private var mode: String = MODE_SCREEN
    private var streamJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on during live view
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_live_view)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREEN

        bindViews()
        configureUiForMode()
        startStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun bindViews() {
        ivLiveFrame      = findViewById(R.id.iv_live_frame)
        tvStatus         = findViewById(R.id.tv_stream_status)
        btnStop          = findViewById(R.id.btn_stop_stream)
        btnSwitchCamera  = findViewById(R.id.btn_switch_camera)
        btnCapture       = findViewById(R.id.btn_capture_photo)
        progressLoading  = findViewById(R.id.progress_loading)
    }

    private fun configureUiForMode() {
        when (mode) {
            MODE_SCREEN -> {
                title = "Live Screen Mirror"
                btnSwitchCamera.visibility = View.GONE
                btnCapture.visibility      = View.GONE
            }
            MODE_CAMERA -> {
                title = "Live Camera"
                btnSwitchCamera.visibility = View.VISIBLE
                btnCapture.visibility      = View.VISIBLE

                btnSwitchCamera.setOnClickListener {
                    viewModel.sendCommand(CommandType.SWITCH_CAMERA)
                }
                btnCapture.setOnClickListener {
                    viewModel.sendCommand(CommandType.CAPTURE_PHOTO)
                    Toast.makeText(this, "Photo captured", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStop.setOnClickListener {
            stopStreaming()
            finish()
        }
    }

    private fun startStreaming() {
        tvStatus.text = "Connecting…"
        progressLoading.visibility = View.VISIBLE

        streamJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val frameFlow = when (mode) {
                    MODE_CAMERA -> viewModel.firebaseManager().observeCameraFrames(
                        viewModel.selectedDeviceId.value
                    )
                    else -> viewModel.firebaseManager().observeScreenFrames(
                        viewModel.selectedDeviceId.value
                    )
                }

                frameFlow.collectLatest { base64Frame ->
                    base64Frame ?: return@collectLatest
                    displayFrame(base64Frame)
                }
            }
        }

        if (mode == MODE_CAMERA) {
            lifecycleScope.launch {
                repeat(5) {
                    val deviceId = viewModel.selectedDeviceId.value
                    val session = viewModel.firebaseManager()
                        .findLatestStreamSession(
                            deviceId,
                            StreamType.CAMERA_FRONT
                        )
                    if (session != null && parentWebRtcClient.startSession(session, deviceId) { bytes ->
                            displayRawFrame(bytes)
                        }) {
                        return@launch
                    }
                    kotlinx.coroutines.delay(200L)
                }
            }
        }
    }

    private fun displayFrame(base64: String) {
        try {
            val bytes  = Base64.decode(base64, Base64.NO_WRAP)
            displayRawFrame(bytes)
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "Frame error"
            }
        }
    }

    private fun displayRawFrame(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        runOnUiThread {
            if (!isDestroyed) {
                progressLoading.visibility = View.GONE
                tvStatus.text = "● Live"
                ivLiveFrame.setImageBitmap(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        parentWebRtcClient.stopSession()
        val stopCommand = when (mode) {
            MODE_CAMERA -> CommandType.STOP_CAMERA_STREAM
            else        -> CommandType.STOP_SCREEN_MIRROR
        }
        viewModel.sendCommand(stopCommand)
    }

    // Expose firebase manager for direct flow access
    private fun DashboardViewModel.firebaseManager() =
        com.guardianlink.parent.data.firebase.ParentFirebaseManager(this@LiveViewActivity)
}
