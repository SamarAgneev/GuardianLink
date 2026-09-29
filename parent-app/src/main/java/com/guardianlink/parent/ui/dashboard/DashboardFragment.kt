// parent-app/src/main/java/com/guardianlink/parent/ui/dashboard/DashboardFragment.kt
package com.guardianlink.parent.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardianlink.common.model.CommandType
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.live.LiveViewActivity
import com.guardianlink.parent.ui.photos.PhotosActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private val viewModel: DashboardViewModel by activityViewModels()

    // Views
    private lateinit var tvChildName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvCurrentApp: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvLastSeen: TextView
    private lateinit var ivStatusDot: View
    private lateinit var btnLiveScreen: Button
    private lateinit var btnLiveCamera: Button
    private lateinit var btnLiveAudio: Button
    private lateinit var btnPhotos: Button
    private lateinit var btnLockDevice: Button
    private lateinit var btnSendCommand: Button
    private lateinit var rvAlerts: RecyclerView
    private lateinit var rvAppUsage: RecyclerView
    private lateinit var chipGroupDevices: com.google.android.material.chip.ChipGroup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupDeviceSelector()
        setupQuickActions()
        observeData()
    }

    private fun bindViews(view: View) {
        tvChildName   = view.findViewById(R.id.tv_child_name)
        tvStatus      = view.findViewById(R.id.tv_online_status)
        tvBattery     = view.findViewById(R.id.tv_battery)
        tvCurrentApp  = view.findViewById(R.id.tv_current_app)
        tvLocation    = view.findViewById(R.id.tv_location)
        tvLastSeen    = view.findViewById(R.id.tv_last_seen)
        ivStatusDot   = view.findViewById(R.id.view_status_dot)
        btnLiveScreen = view.findViewById(R.id.btn_live_screen)
        btnLiveCamera = view.findViewById(R.id.btn_live_camera)
        btnLiveAudio  = view.findViewById(R.id.btn_live_audio)
        btnPhotos     = view.findViewById(R.id.btn_photos)
        btnLockDevice = view.findViewById(R.id.btn_lock_device)
        rvAlerts      = view.findViewById(R.id.rv_recent_alerts)
        rvAppUsage    = view.findViewById(R.id.rv_app_usage)
        chipGroupDevices = view.findViewById(R.id.chip_group_devices)
    }

    private fun setupDeviceSelector() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.childDevices.collect { devices ->
                    chipGroupDevices.removeAllViews()
                    devices.forEach { device ->
                        val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                            text   = device.childName
                            tag    = device.deviceId
                            isCheckable = true
                            setOnClickListener { viewModel.selectDevice(device.deviceId) }
                        }
                        chipGroupDevices.addView(chip)
                    }
                    // Auto-select first if none selected
                    if (devices.isNotEmpty() && chipGroupDevices.checkedChipId == View.NO_ID) {
                        (chipGroupDevices.getChildAt(0) as? com.google.android.material.chip.Chip)
                            ?.isChecked = true
                    }
                }
            }
        }
    }

    private fun setupQuickActions() {
        // Live Screen Mirror
        btnLiveScreen.setOnClickListener {
            viewModel.sendCommand(CommandType.START_SCREEN_MIRROR)
            startActivity(Intent(requireContext(), LiveViewActivity::class.java).apply {
                putExtra(LiveViewActivity.EXTRA_MODE, LiveViewActivity.MODE_SCREEN)
            })
        }

        // Live Camera
        btnLiveCamera.setOnClickListener {
            viewModel.sendCommand(CommandType.START_CAMERA_STREAM)
            startActivity(Intent(requireContext(), LiveViewActivity::class.java).apply {
                putExtra(LiveViewActivity.EXTRA_MODE, LiveViewActivity.MODE_CAMERA)
            })
        }

        // Live Audio
        btnLiveAudio.setOnClickListener {
            viewModel.sendCommand(CommandType.START_AUDIO_STREAM)
        }

        btnPhotos.setOnClickListener {
            startActivity(Intent(requireContext(), PhotosActivity::class.java).apply {
                putExtra(PhotosActivity.EXTRA_DEVICE_ID, viewModel.selectedDeviceId.value)
            })
        }

        // Lock Device
        btnLockDevice.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Lock Device")
                .setMessage("Are you sure you want to lock the child's device?")
                .setPositiveButton("Lock") { _, _ ->
                    viewModel.sendCommand(CommandType.LOCK_DEVICE)
                    Toast.makeText(requireContext(), "Lock command sent", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Device Status
                launch {
                    viewModel.deviceStatus.collect { status ->
                        val online = status["online"] as? Boolean ?: false
                        tvStatus.text  = if (online) "Online" else "Offline"
                        tvStatus.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                if (online) R.color.status_online else R.color.status_offline
                            )
                        )
                        ivStatusDot.setBackgroundResource(
                            if (online) R.drawable.dot_green else R.drawable.dot_red
                        )
                        val battery = status["battery"] as? Long
                        tvBattery.text = battery?.let { "$it%" } ?: "—"
                    }
                }

                // Live Location
                launch {
                    viewModel.liveLocation.collect { loc ->
                        tvLocation.text = when {
                            loc == null          -> "No location data"
                            loc.address.isNotBlank() -> loc.address
                            else -> "%.4f, %.4f".format(loc.latitude, loc.longitude)
                        }
                    }
                }

                // Alerts (show top 5)
                launch {
                    viewModel.alerts.collect { alerts ->
                        val topAlerts = alerts.take(5)
                        // AlertsAdapter would bind this list
                        // rvAlerts.adapter = AlertsAdapter(topAlerts) { alert ->
                        //     viewModel.markAlertRead(alert.id)
                        // }
                    }
                }

                // App Usage for today
                launch {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    viewModel.loadAppUsage(today)
                    viewModel.appUsage.collect { apps ->
                        // AppUsageAdapter would bind top 5 apps
                    }
                }

                // Command state feedback
                launch {
                    viewModel.commandState.collect { state ->
                        when (state) {
                            is CommandState.Sent ->
                                Toast.makeText(requireContext(), "Command sent ✓", Toast.LENGTH_SHORT).show()
                            is CommandState.Error ->
                                Toast.makeText(requireContext(), "Error: ${state.message}", Toast.LENGTH_LONG).show()
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
