// child-app/src/main/java/com/guardianlink/child/receiver/BootReceiver.kt
package com.guardianlink.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.guardianlink.child.admin.GuardianDeviceAdminReceiver
import com.guardianlink.child.service.MonitoringService
import com.guardianlink.common.security.SecurePreferences
import timber.log.Timber

/**
 * BootReceiver — auto-starts MonitoringService after device reboot.
 * Requires RECEIVE_BOOT_COMPLETED permission.
 *
 * It does not bypass Android controls, and it refuses to start monitoring when the device admin
 * or required permissions are disabled or absent.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
        if (intent.action !in validActions) return

        val prefs = SecurePreferences(context)
        val isPaired = prefs.getBoolean(SecurePreferences.KEY_PAIRING_DONE)
        val isConsented = prefs.getBoolean(SecurePreferences.KEY_CONSENT_GIVEN)

        if (!isPaired || !isConsented) {
            Timber.d("Boot received but device not paired — skipping service start")
            return
        }

        val adminActive = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            ?.isAdminActive(android.content.ComponentName(context, GuardianDeviceAdminReceiver::class.java)) == true
        val accessibilityEnabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("${context.packageName}/${com.guardianlink.child.accessibility.AppBlockerAccessibilityService::class.java.name}") == true
        val hasLocationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // Deliberately NOT ProtectionStateEvaluator.evaluate() here. That
        // evaluator (see TamperDetector, its only other caller) reports the
        // *live* protection status of an already-running app, and treats
        // "monitoring service running" and "VPN enabled" as CRITICAL —
        // false in either forces DISABLED. At boot time neither has started
        // yet (starting them is the whole point of this receiver), so
        // gating the start attempt on that evaluator's PROTECTED result was
        // a deadlock: it could never return PROTECTED, so the service could
        // never actually restart after a reboot. What we actually need here
        // is just the boot-independent preconditions — things that must
        // already be true before we can sensibly attempt a start at all.
        // The real, live protection status (including whether the service
        // and VPN actually came up) gets computed correctly afterward, for
        // real, by TamperDetector once the service is running.
        val missingPreconditions = buildList {
            if (!adminActive) add("Device admin disabled")
            if (!accessibilityEnabled) add("Accessibility service disabled")
            if (!hasLocationPermission) add("Location permission revoked")
        }

        if (missingPreconditions.isNotEmpty()) {
            Timber.w("Boot received but required preconditions are missing: ${missingPreconditions.joinToString()}. Skipping service start.")
            return
        }

        Timber.i("Boot completed — starting MonitoringService")

        val serviceIntent = MonitoringService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
