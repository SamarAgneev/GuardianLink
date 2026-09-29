package com.guardianlink.common.util

import com.guardianlink.common.model.ProtectionState
import com.guardianlink.common.model.ProtectionStatus

object ProtectionStateEvaluator {

    fun evaluate(
        deviceAdminEnabled: Boolean = true,
        monitoringServiceRunning: Boolean = true,
        accessibilityEnabled: Boolean = true,
        vpnEnabled: Boolean = true,
        locationPermissionGranted: Boolean = true,
        notificationsEnabled: Boolean = true,
        batteryOptimizationIgnored: Boolean = true,
        foreignVpnActive: Boolean = false
    ): ProtectionStatus {
        val issues = mutableListOf<String>()

        if (!deviceAdminEnabled) issues += "Device admin disabled"
        if (!monitoringServiceRunning) issues += "Monitoring service stopped"
        if (!accessibilityEnabled) issues += "Accessibility service disabled"
        if (!vpnEnabled) issues += "VPN disabled"
        // Only meaningful to call out when our own VPN isn't the one active —
        // i.e. a different VPN app has displaced GuardianLink's content
        // filter (Android permits only one active VPN at a time). This is
        // additional context for *why* VPN protection is off, not a
        // separate on/off signal — vpnEnabled already reflects whether
        // GuardianLink's own filter is the currently active VPN.
        if (foreignVpnActive && !vpnEnabled) issues += "A different VPN app is active instead of GuardianLink's content filter"
        if (!locationPermissionGranted) issues += "Location permission revoked"
        if (!notificationsEnabled) issues += "Notification permission revoked"
        if (!batteryOptimizationIgnored) issues += "Battery optimization active"

        if (issues.isEmpty()) {
            return ProtectionStatus(ProtectionState.PROTECTED, emptyList())
        }

        val criticalIssues = setOf(
            "Device admin disabled",
            "Monitoring service stopped",
            "Accessibility service disabled",
            "VPN disabled"
        )
        val state = if (issues.any { it in criticalIssues }) {
            ProtectionState.DISABLED
        } else {
            ProtectionState.DEGRADED
        }

        return ProtectionStatus(state, issues)
    }
}
