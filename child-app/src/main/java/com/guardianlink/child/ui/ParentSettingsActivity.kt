// child-app/src/main/java/com/guardianlink/child/ui/ParentSettingsActivity.kt
package com.guardianlink.child.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.guardianlink.child.R
import com.guardianlink.child.admin.GuardianDeviceAdminReceiver
import com.guardianlink.child.service.MonitoringService
import com.guardianlink.common.security.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * ParentSettingsActivity — accessible only after PIN verification.
 *
 * Provides parent controls when physically at child's device:
 *   - Disable admin (requires PIN)
 *   - Unpair device (requires PIN)
 *   - View monitoring status
 */
@AndroidEntryPoint
class ParentSettingsActivity : AppCompatActivity() {

    @Inject lateinit var securePrefs: SecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_settings_child)

        val btnChangePin = findViewById<Button>(R.id.btn_change_pin)
        val btnUnpair  = findViewById<Button>(R.id.btn_unpair_device)
        val btnDisableAdmin = findViewById<Button>(R.id.btn_disable_admin)
        val btnStopMonitoring = findViewById<Button>(R.id.btn_stop_monitoring)

        btnChangePin.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(PinLockActivity.changeIntent(this), REQUEST_CHANGE_PIN)
        }

        btnUnpair.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unpair Device")
                .setMessage("This will stop all monitoring. Are you sure?")
                .setPositiveButton("Unpair") { _, _ -> unpairDevice() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnDisableAdmin.setOnClickListener {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, GuardianDeviceAdminReceiver::class.java)
            dpm?.removeActiveAdmin(admin)
            Toast.makeText(this, "Device admin disabled", Toast.LENGTH_SHORT).show()
        }

        btnStopMonitoring.setOnClickListener {
            stopService(MonitoringService.stopIntent(this))
            Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unpairDevice() {
        securePrefs.clear()
        stopService(MonitoringService.stopIntent(this))
        // Restart to consent/pairing flow
        val intent = Intent(this, ConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CHANGE_PIN) return
        // Only a generic success/failure signal is ever surfaced here — the
        // PIN itself, its hash, or any entered digits are never available to
        // this activity (PinLockActivity keeps them local to itself) and
        // must never be logged or displayed.
        val message = if (resultCode == PinLockActivity.RESULT_SUCCESS) {
            "PIN changed successfully"
        } else {
            "PIN change cancelled"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_CHANGE_PIN = 2001
    }
}
