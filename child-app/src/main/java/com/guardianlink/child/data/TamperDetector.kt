// child-app/src/main/java/com/guardianlink/child/data/TamperDetector.kt
package com.guardianlink.child.data

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.guardianlink.child.admin.GuardianDeviceAdminReceiver
import com.guardianlink.child.service.MonitoringService
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.model.ProtectionState
import com.guardianlink.common.model.ProtectionStatus
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.ProtectionStateEvaluator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TamperDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePreferences
) {
    private val db = FirebaseFirestore.getInstance()
    private var watchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastReportedState: ProtectionState = ProtectionState.PROTECTED

    fun startWatching() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                val status = evaluateProtectionState()
                if (status.state != lastReportedState) {
                    pushStateAlert(status)
                    lastReportedState = status.state
                }
                delay(30_000)
            }
        }
    }

    fun stopWatching() {
        watchJob?.cancel()
    }

    fun evaluateProtectionState(): ProtectionStatus {
        return ProtectionStateEvaluator.evaluate(
            deviceAdminEnabled = isDeviceAdminEnabled(),
            monitoringServiceRunning = isMonitoringServiceRunning(),
            accessibilityEnabled = isAccessibilityEnabled(),
            vpnEnabled = isOwnVpnActive(),
            locationPermissionGranted = hasLocationPermission(),
            notificationsEnabled = areNotificationsEnabled(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
            foreignVpnActive = isAnyVpnActive() && !isOwnVpnActive()
        )
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
        return dpm?.isAdminActive(admin) == true
    }

    private fun isMonitoringServiceRunning(): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        return activityManager.getRunningServices(Int.MAX_VALUE)
            .any { it.service?.className == MonitoringService::class.java.name }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val target = "${context.packageName}/${com.guardianlink.child.accessibility.AppBlockerAccessibilityService::class.java.name}"
        return enabledServices.split(":").any { it == target }
    }

    /**
     * Whether GuardianLink's own content-filter VPN is the one currently
     * established, per [com.guardianlink.child.vpn.ContentFilterVpnService.isActive] —
     * not merely "some VPN transport is active" (see [isAnyVpnActive]).
     * Android permits only one active VPN at a time, so if a different VPN
     * app is running, GuardianLink's own service will have been torn down
     * and this correctly reports false even though [isAnyVpnActive] is true.
     */
    private fun isOwnVpnActive(): Boolean =
        com.guardianlink.child.vpn.ContentFilterVpnService.isActive

    /** Whether any VPN transport is active on the device, regardless of which app owns it. */
    private fun isAnyVpnActive(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun pushStateAlert(status: ProtectionStatus) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) return

        val title = when (status.state) {
            ProtectionState.PROTECTED -> "✅ Protection Active"
            ProtectionState.DEGRADED -> "⚠ Protection Degraded"
            ProtectionState.DISABLED -> "🚫 Protection Disabled"
        }

        val alertType = when (status.state) {
            ProtectionState.PROTECTED -> AlertType.TAMPER_DETECTED
            ProtectionState.DEGRADED -> AlertType.PERMISSION_REVOKED
            ProtectionState.DISABLED -> AlertType.ADMIN_DISABLED
        }

        val message = if (status.issues.isEmpty()) {
            "GuardianLink is operating with active protections."
        } else {
            "GuardianLink protection state is ${status.state.name}: ${status.issues.joinToString(separator = "; ")}."
        }

        db.collection(FirebasePaths.COLLECTION_ALERTS)
            .add(
                mapOf(
                    "deviceId" to deviceId,
                    "type" to alertType.name,
                    "severity" to when (status.state) {
                        ProtectionState.PROTECTED -> "LOW"
                        ProtectionState.DEGRADED -> "MEDIUM"
                        ProtectionState.DISABLED -> "CRITICAL"
                    },
                    "title" to title,
                    "message" to message,
                    "isRead" to false,
                    "isResolved" to false,
                    "timestamp" to Timestamp.now()
                )
            )
            .addOnFailureListener { e -> Timber.e(e, "Failed to push protection alert") }
    }
}
