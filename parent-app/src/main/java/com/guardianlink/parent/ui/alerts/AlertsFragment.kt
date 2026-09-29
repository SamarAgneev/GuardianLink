// parent-app/src/main/java/com/guardianlink/parent/ui/alerts/AlertsFragment.kt
package com.guardianlink.parent.ui.alerts

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.guardianlink.common.model.AlertData
import com.guardianlink.common.model.AlertSeverity
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class AlertsFragment : Fragment(R.layout.fragment_alerts) {

    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var alertsAdapter: AlertsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvAlerts    = view.findViewById<RecyclerView>(R.id.rv_alerts)
        val btnMarkAll  = view.findViewById<Button>(R.id.btn_mark_all_read)
        val tvEmpty     = view.findViewById<TextView>(R.id.tv_alerts_empty)

        alertsAdapter = AlertsAdapter(
            onRead    = { alert -> viewModel.markAlertRead(alert.id) },
            onResolve = { alert -> viewModel.resolveAlert(alert.id) }
        )

        rvAlerts.apply {
            adapter = alertsAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            addItemDecoration(
                androidx.recyclerview.widget.DividerItemDecoration(
                    requireContext(), androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
                )
            )
        }

        btnMarkAll.setOnClickListener {
            viewModel.alerts.value.filter { !it.isRead }
                .forEach { viewModel.markAlertRead(it.id) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.alerts.collect { alerts ->
                    alertsAdapter.submitList(alerts)
                    tvEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AlertsAdapter
// ─────────────────────────────────────────────────────────────────────────────

class AlertsAdapter(
    private val onRead: (AlertData) -> Unit,
    private val onResolve: (AlertData) -> Unit
) : ListAdapter<AlertData, AlertsAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView      = view.findViewById(R.id.tv_alert_title)
        val tvMessage: TextView    = view.findViewById(R.id.tv_alert_message)
        val tvTime: TextView       = view.findViewById(R.id.tv_alert_time)
        val tvSeverity: TextView   = view.findViewById(R.id.tv_alert_severity)
        val btnResolve: Button     = view.findViewById(R.id.btn_resolve_alert)
        val vSeverityBar: View     = view.findViewById(R.id.view_severity_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val alert = getItem(position)

        holder.tvTitle.text    = alert.title
        holder.tvMessage.text  = alert.message
        holder.tvTime.text     = timeFormat.format(alert.timestamp.toDate())
        holder.tvSeverity.text = alert.severity.name

        // Severity color coding
        val (barColor, textColor) = when (alert.severity) {
            AlertSeverity.CRITICAL -> Color.parseColor("#F44336") to Color.parseColor("#F44336")
            AlertSeverity.HIGH     -> Color.parseColor("#FF9800") to Color.parseColor("#FF9800")
            AlertSeverity.MEDIUM   -> Color.parseColor("#FFC107") to Color.parseColor("#F57F17")
            AlertSeverity.LOW      -> Color.parseColor("#4CAF50") to Color.parseColor("#2E7D32")
        }
        holder.vSeverityBar.setBackgroundColor(barColor)
        holder.tvSeverity.setTextColor(textColor)

        // Dim read alerts
        holder.itemView.alpha = if (alert.isRead) 0.6f else 1.0f

        holder.btnResolve.visibility = if (alert.isResolved) View.GONE else View.VISIBLE
        holder.btnResolve.setOnClickListener { onResolve(alert) }

        holder.itemView.setOnClickListener {
            if (!alert.isRead) onRead(alert)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AlertData>() {
        override fun areItemsTheSame(o: AlertData, n: AlertData) = o.id == n.id
        override fun areContentsTheSame(o: AlertData, n: AlertData) = o == n
    }
}
