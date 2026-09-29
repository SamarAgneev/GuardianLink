// child-app/src/main/java/com/guardianlink/child/ui/MainActivity.kt
package com.guardianlink.child.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.guardianlink.child.R
import com.guardianlink.child.data.SosManager
import com.guardianlink.child.service.MonitoringService
import com.guardianlink.child.worker.ServiceRestartWorker
import com.guardianlink.common.security.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainActivity — the visible, non-hidden child UI.
 *
 * GuardianLink's child app must NOT hide its presence. This screen:
 *   - Shows the child the monitoring is active (transparency requirement)
 *   - Provides a prominent SOS / panic button
 *   - Allows child to access their profile / info
 *   - Restricts access to monitoring settings (requires parent PIN)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var securePrefs: SecurePreferences
    @Inject lateinit var sosManager: SosManager

    private lateinit var tvChildName: TextView
    private lateinit var tvMonitoringStatus: TextView
    private lateinit var ivShield: ImageView
    private lateinit var fabSos: FloatingActionButton
    private lateinit var btnParentSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_main)

        bindViews()
        startMonitoringService()
        schedulePeriodicWork()
        displayStatus()
        setupActions()
    }

    private fun bindViews() {
        tvChildName        = findViewById(R.id.tv_child_name)
        tvMonitoringStatus = findViewById(R.id.tv_monitoring_status)
        ivShield           = findViewById(R.id.iv_shield)
        fabSos             = findViewById(R.id.fab_sos)
        btnParentSettings  = findViewById(R.id.btn_parent_settings)
    }

    private fun displayStatus() {
        val name = securePrefs.getString(SecurePreferences.KEY_CHILD_NAME)
        tvChildName.text = if (name.isNotBlank()) "Hi, $name!" else "GuardianLink"
        tvMonitoringStatus.text = "Your device is being monitored by your parent.\nThis helps keep you safe."
    }

    private fun setupActions() {
        // SOS panic button — always accessible, single long-press
        fabSos.setOnLongClickListener {
            confirmSos()
            true
        }

        // Parent settings — protected by PIN
        btnParentSettings.setOnClickListener {
            startActivityForResult(
                PinLockActivity.verifyIntent(this),
                REQUEST_PIN_FOR_SETTINGS
            )
        }
    }

    private fun confirmSos() {
        android.app.AlertDialog.Builder(this)
            .setTitle("🚨 Send Emergency Alert?")
            .setMessage("This will immediately alert your parent with your location. Only use in a real emergency.")
            .setPositiveButton("SEND SOS") { _, _ ->
                lifecycleScope.launch {
                    sosManager.triggerSos()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "🚨 Emergency alert sent to parent",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PIN_FOR_SETTINGS &&
            resultCode == PinLockActivity.RESULT_SUCCESS) {
            // Navigate to monitoring settings (parent-controlled section)
            startActivity(Intent(this, ParentSettingsActivity::class.java))
        }
    }

    // ── Service Management ────────────────────────────────────────────────────

    private fun startMonitoringService() {
        val intent = MonitoringService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun schedulePeriodicWork() {
        ServiceRestartWorker.schedulePeriodicWork(applicationContext)
    }

    companion object {
        private const val REQUEST_PIN_FOR_SETTINGS = 1001
    }
}
