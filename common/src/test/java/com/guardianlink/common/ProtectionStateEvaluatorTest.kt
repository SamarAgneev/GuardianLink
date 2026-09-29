package com.guardianlink.common

import com.guardianlink.common.model.ProtectionState
import com.guardianlink.common.util.ProtectionStateEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStateEvaluatorTest {

    @Test
    fun allSignalsHealthyIsProtected() {
        val status = ProtectionStateEvaluator.evaluate()
        assertEquals(ProtectionState.PROTECTED, status.state)
        assertTrue(status.issues.isEmpty())
    }

    @Test
    fun deviceAdminDisabledIsDisabledState() {
        val status = ProtectionStateEvaluator.evaluate(deviceAdminEnabled = false)
        assertEquals(ProtectionState.DISABLED, status.state)
        assertTrue(status.issues.contains("Device admin disabled"))
    }

    @Test
    fun batteryOptimizationAloneIsDegradedNotDisabled() {
        val status = ProtectionStateEvaluator.evaluate(batteryOptimizationIgnored = false)
        assertEquals(ProtectionState.DEGRADED, status.state)
    }

    @Test
    fun ownVpnInactiveIsDisabledState() {
        val status = ProtectionStateEvaluator.evaluate(vpnEnabled = false)
        assertEquals(ProtectionState.DISABLED, status.state)
        assertTrue(status.issues.contains("VPN disabled"))
    }

    @Test
    fun foreignVpnActiveAddsExplicitIssueWhenOwnVpnIsOff() {
        // A different VPN app has displaced GuardianLink's — vpnEnabled is
        // false (our own filter isn't the active VPN) and foreignVpnActive
        // is true (something else is). The parent dashboard should get a
        // specific reason, not just a generic "VPN disabled".
        val status = ProtectionStateEvaluator.evaluate(vpnEnabled = false, foreignVpnActive = true)
        assertEquals(ProtectionState.DISABLED, status.state)
        assertTrue(status.issues.contains("VPN disabled"))
        assertTrue(status.issues.any { it.contains("different VPN app") })
    }

    @Test
    fun foreignVpnActiveIsIgnoredWhenOwnVpnIsAlreadyOn() {
        // If our own VPN is active, whether some other network capability
        // also reports a VPN transport is not meaningful — no extra issue
        // should be added.
        val status = ProtectionStateEvaluator.evaluate(vpnEnabled = true, foreignVpnActive = true)
        assertEquals(ProtectionState.PROTECTED, status.state)
        assertTrue(status.issues.none { it.contains("different VPN app") })
    }

    @Test
    fun multipleCriticalIssuesAreAllReported() {
        val status = ProtectionStateEvaluator.evaluate(
            deviceAdminEnabled = false,
            accessibilityEnabled = false,
            locationPermissionGranted = false
        )
        assertEquals(ProtectionState.DISABLED, status.state)
        assertEquals(3, status.issues.size)
    }
}
