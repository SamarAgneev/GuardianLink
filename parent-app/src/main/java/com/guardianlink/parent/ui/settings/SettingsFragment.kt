// parent-app/src/main/java/com/guardianlink/parent/ui/settings/SettingsFragment.kt
package com.guardianlink.parent.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.guardianlink.common.model.CommandType
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: DashboardViewModel by activityViewModels()

    // ── Monitoring Toggles ────────────────────────────────────────────────────
    private lateinit var swLocation: SwitchMaterial
    private lateinit var swScreenMirror: SwitchMaterial
    private lateinit var swCamera: SwitchMaterial
    private lateinit var swAudio: SwitchMaterial
    private lateinit var swCallMonitor: SwitchMaterial
    private lateinit var swSmsMonitor: SwitchMaterial
    private lateinit var swAdultFilter: SwitchMaterial

    // ── Screen Time ───────────────────────────────────────────────────────────
    private lateinit var sliderDailyLimit: com.google.android.material.slider.Slider
    private lateinit var tvDailyLimitValue: TextView
    private lateinit var swBedtime: SwitchMaterial

    // ── App / Website Blocking ────────────────────────────────────────────────
    private lateinit var rvBlockedApps: RecyclerView
    private lateinit var rvBlockedSites: RecyclerView
    private lateinit var btnAddBlockedSite: Button

    // ── Location Settings ─────────────────────────────────────────────────────
    private lateinit var rgLocationInterval: RadioGroup

    // ── Reports ───────────────────────────────────────────────────────────────
    private lateinit var btnRequestAppUsage: Button
    private lateinit var btnRequestCallLogs: Button
    private lateinit var btnRequestSmsLogs: Button
    private lateinit var btnDeleteChildData: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        observeSettings()
        viewModel.loadSettings()
    }

    private fun bindViews(view: View) {
        // Monitoring
        swLocation    = view.findViewById(R.id.sw_location_tracking)
        swScreenMirror = view.findViewById(R.id.sw_screen_mirror)
        swCamera      = view.findViewById(R.id.sw_camera_monitor)
        swAudio       = view.findViewById(R.id.sw_audio_monitor)
        swCallMonitor = view.findViewById(R.id.sw_call_monitor)
        swSmsMonitor  = view.findViewById(R.id.sw_sms_monitor)
        swAdultFilter = view.findViewById(R.id.sw_adult_filter)

        // Screen time
        sliderDailyLimit  = view.findViewById(R.id.slider_daily_limit)
        tvDailyLimitValue = view.findViewById(R.id.tv_daily_limit_value)
        swBedtime         = view.findViewById(R.id.sw_bedtime)

        // Blocked sites
        rvBlockedSites    = view.findViewById(R.id.rv_blocked_sites)
        btnAddBlockedSite = view.findViewById(R.id.btn_add_blocked_site)

        // Data requests
        btnRequestAppUsage = view.findViewById(R.id.btn_request_app_usage)
        btnRequestCallLogs = view.findViewById(R.id.btn_request_call_logs)
        btnRequestSmsLogs  = view.findViewById(R.id.btn_request_sms_logs)
        btnDeleteChildData = view.findViewById(R.id.btn_delete_child_data)

        setupListeners()
    }

    private fun setupListeners() {
        // Monitoring switches
        swLocation.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("locationTrackingEnabled", checked)
            if (checked) viewModel.sendCommand(CommandType.GET_LOCATION)
        }
        swScreenMirror.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("screenMirroringEnabled", checked)
        }
        swCamera.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("cameraMonitoringEnabled", checked)
        }
        swAudio.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("audioMonitoringEnabled", checked)
        }
        swCallMonitor.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("callMonitoringEnabled", checked)
        }
        swSmsMonitor.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("smsMonitoringEnabled", checked)
        }
        swAdultFilter.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("adultContentFilterEnabled", checked)
        }

        // Screen time slider
        sliderDailyLimit.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val mins = value.toInt()
            tvDailyLimitValue.text = formatDuration(mins)
            viewModel.updateSetting("dailyLimitMinutes", mins)
            viewModel.sendCommand(
                CommandType.SET_DAILY_LIMIT,
                mapOf("limitMinutes" to mins)
            )
        }

        swBedtime.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSetting("bedtimeEnabled", checked)
        }

        // Add blocked site
        btnAddBlockedSite.setOnClickListener { showAddSiteDialog() }

        // Manual data sync buttons
        btnRequestAppUsage.setOnClickListener {
            viewModel.sendCommand(CommandType.GET_APP_USAGE)
            Toast.makeText(requireContext(), "App usage sync requested", Toast.LENGTH_SHORT).show()
        }
        btnRequestCallLogs.setOnClickListener {
            viewModel.sendCommand(CommandType.GET_CALL_LOGS)
            viewModel.loadCallLogs()
        }
        btnRequestSmsLogs.setOnClickListener {
            viewModel.sendCommand(CommandType.GET_SMS_LOGS)
            viewModel.loadSmsLogs()
        }
        btnDeleteChildData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete all child data?")
                .setMessage("This permanently removes the selected child's location, media, logs, alerts, reports, and device data.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.deleteSelectedDeviceData { success ->
                        Toast.makeText(
                            requireContext(),
                            if (success) "Child data deleted" else "Data deletion failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    settings ?: return@collect

                    // Disable listeners temporarily to avoid feedback loops
                    swLocation.setOnCheckedChangeListener(null)
                    swScreenMirror.setOnCheckedChangeListener(null)
                    swCallMonitor.setOnCheckedChangeListener(null)
                    swSmsMonitor.setOnCheckedChangeListener(null)
                    swAdultFilter.setOnCheckedChangeListener(null)

                    swLocation.isChecked    = settings.locationTrackingEnabled
                    swScreenMirror.isChecked = settings.screenMirroringEnabled
                    swCamera.isChecked      = settings.cameraMonitoringEnabled
                    swAudio.isChecked       = settings.audioMonitoringEnabled
                    swCallMonitor.isChecked = settings.callMonitoringEnabled
                    swSmsMonitor.isChecked  = settings.smsMonitoringEnabled
                    swAdultFilter.isChecked = settings.adultContentFilterEnabled
                    swBedtime.isChecked     = settings.screenTimeSettings.bedtimeEnabled

                    val limitMins = settings.screenTimeSettings.dailyLimitMinutes.toFloat()
                    sliderDailyLimit.value = limitMins.coerceIn(
                        sliderDailyLimit.valueFrom, sliderDailyLimit.valueTo
                    )
                    tvDailyLimitValue.text = formatDuration(limitMins.toInt())

                    // Re-attach listeners
                    setupListeners()
                }
            }
        }
    }

    private fun showAddSiteDialog() {
        val input = EditText(requireContext()).apply {
            hint    = "e.g. example.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Block Website")
            .setMessage("Enter domain to block (without https://)")
            .setView(input)
            .setPositiveButton("Block") { _, _ ->
                val domain = input.text.toString().trim().lowercase()
                    .removePrefix("https://").removePrefix("http://").removeSuffix("/")
                if (domain.isNotBlank()) {
                    viewModel.blockWebsite(domain)
                    Toast.makeText(requireContext(), "Blocked: $domain", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDuration(minutes: Int): String {
        return when {
            minutes <= 0    -> "Unlimited"
            minutes < 60    -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
