// parent-app/src/main/java/com/guardianlink/parent/ui/activity/ActivityFragment.kt
package com.guardianlink.parent.ui.activity

import android.graphics.Color
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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.guardianlink.common.model.AppUsageData
import com.guardianlink.common.model.CallLogEntry
import com.guardianlink.common.model.SmsLogEntry
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@AndroidEntryPoint
class ActivityFragment : Fragment(R.layout.fragment_activity) {

    private val viewModel: DashboardViewModel by activityViewModels()

    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var tvDateLabel: TextView
    private lateinit var btnPrevDay: ImageButton
    private lateinit var btnNextDay: ImageButton

    // App Usage
    private lateinit var pieChart: PieChart
    private lateinit var tvTotalScreenTime: TextView
    private lateinit var rvAppUsage: RecyclerView

    // Calls
    private lateinit var rvCallLogs: RecyclerView
    private lateinit var tvCallCount: TextView

    // SMS
    private lateinit var rvSmsLogs: RecyclerView
    private lateinit var tvSmsCount: TextView

    private var currentDate = Date()
    private val dateFormat    = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFormat = SimpleDateFormat("EEEE, MMM d", Locale.US)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupDateNavigation()
        setupTabs()
        loadData()
    }

    private fun bindViews(view: View) {
        tabLayout       = view.findViewById(R.id.tab_layout_activity)
        tvDateLabel     = view.findViewById(R.id.tv_activity_date)
        btnPrevDay      = view.findViewById(R.id.btn_prev_day)
        btnNextDay      = view.findViewById(R.id.btn_next_day)
        pieChart        = view.findViewById(R.id.pie_chart_apps)
        tvTotalScreenTime = view.findViewById(R.id.tv_total_screen_time)
        rvAppUsage      = view.findViewById(R.id.rv_app_usage_detail)
        rvCallLogs      = view.findViewById(R.id.rv_call_logs)
        tvCallCount     = view.findViewById(R.id.tv_call_count)
        rvSmsLogs       = view.findViewById(R.id.rv_sms_logs)
        tvSmsCount      = view.findViewById(R.id.tv_sms_count)
    }

    private fun setupDateNavigation() {
        updateDateLabel()

        btnPrevDay.setOnClickListener {
            currentDate = Date(currentDate.time - 86_400_000L)
            updateDateLabel()
            loadData()
        }

        btnNextDay.setOnClickListener {
            val tomorrow = Date(currentDate.time + 86_400_000L)
            if (tomorrow.after(Date())) {
                Toast.makeText(requireContext(), "Cannot view future dates", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentDate = tomorrow
            updateDateLabel()
            loadData()
        }
    }

    private fun updateDateLabel() {
        tvDateLabel.text = displayFormat.format(currentDate)
        // Disable "next" if today
        btnNextDay.isEnabled = !dateFormat.format(currentDate).equals(dateFormat.format(Date()))
        btnNextDay.alpha = if (btnNextDay.isEnabled) 1.0f else 0.4f
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Screen Time"))
        tabLayout.addTab(tabLayout.newTab().setText("Calls"))
        tabLayout.addTab(tabLayout.newTab().setText("Messages"))

        tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                updateTabVisibility(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun updateTabVisibility(position: Int) {
        pieChart.visibility        = if (position == 0) View.VISIBLE else View.GONE
        tvTotalScreenTime.visibility = if (position == 0) View.VISIBLE else View.GONE
        rvAppUsage.visibility      = if (position == 0) View.VISIBLE else View.GONE
        rvCallLogs.visibility      = if (position == 1) View.VISIBLE else View.GONE
        tvCallCount.visibility     = if (position == 1) View.VISIBLE else View.GONE
        rvSmsLogs.visibility       = if (position == 2) View.VISIBLE else View.GONE
        tvSmsCount.visibility      = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun loadData() {
        val dateStr = dateFormat.format(currentDate)

        viewModel.loadAppUsage(dateStr)
        viewModel.loadCallLogs()
        viewModel.loadSmsLogs()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.appUsage.collect { apps ->
                        updateScreenTimeChart(apps)
                        updateAppUsageList(apps)
                    }
                }

                launch {
                    viewModel.callLogs.collect { calls ->
                        tvCallCount.text = "${calls.size} calls on ${displayFormat.format(currentDate)}"
                        updateCallLogList(calls)
                    }
                }

                launch {
                    viewModel.smsLogs.collect { messages ->
                        tvSmsCount.text = "${messages.size} messages on ${displayFormat.format(currentDate)}"
                        updateSmsLogList(messages)
                    }
                }
            }
        }
    }

    // ── Chart Rendering ───────────────────────────────────────────────────────

    private fun updateScreenTimeChart(apps: List<AppUsageData>) {
        val totalMs = apps.sumOf { it.foregroundTimeMs }
        tvTotalScreenTime.text = "Total: ${formatDuration(totalMs)}"

        if (apps.isEmpty()) {
            pieChart.visibility = View.GONE
            return
        }
        pieChart.visibility = View.VISIBLE

        val colors = listOf(
            Color.parseColor("#2196F3"), Color.parseColor("#4CAF50"),
            Color.parseColor("#FF9800"), Color.parseColor("#E91E63"),
            Color.parseColor("#9C27B0"), Color.parseColor("#00BCD4"),
            Color.parseColor("#FF5722"), Color.parseColor("#607D8B")
        )

        val entries = apps.take(8).mapIndexed { index, app ->
            PieEntry(
                app.foregroundTimeMs.toFloat(),
                app.appName.take(12)
            )
        }

        val dataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextSize = 10f
            valueTextColor = Color.WHITE
            sliceSpace = 2f
            selectionShift = 8f
        }

        val data = PieData(dataSet).apply {
            setValueFormatter(object : ValueFormatter() {
                override fun getFormattedValue(value: Float) =
                    formatDuration(value.toLong())
            })
        }

        pieChart.apply {
            this.data        = data
            description.isEnabled = false
            isDrawHoleEnabled     = true
            holeRadius            = 55f
            transparentCircleRadius = 60f
            centerText            = formatDuration(totalMs)
            setCenterTextSize(13f)
            legend.apply {
                form      = Legend.LegendForm.CIRCLE
                textSize  = 11f
                isWordWrapEnabled = true
            }
            animateY(800)
            invalidate()
        }
    }

    private fun updateAppUsageList(apps: List<AppUsageData>) {
        // AppUsageAdapter would be set here
        // rvAppUsage.adapter = AppUsageAdapter(apps)
        rvAppUsage.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun updateCallLogList(calls: List<CallLogEntry>) {
        // CallLogAdapter would be set here
        rvCallLogs.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun updateSmsLogList(messages: List<SmsLogEntry>) {
        // SmsLogAdapter would be set here
        rvSmsLogs.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs  = ms / 1000
        val hours      = totalSecs / 3600
        val mins       = (totalSecs % 3600) / 60
        return when {
            hours > 0  -> "${hours}h ${mins}m"
            mins > 0   -> "${mins}m"
            else       -> "<1m"
        }
    }
}
