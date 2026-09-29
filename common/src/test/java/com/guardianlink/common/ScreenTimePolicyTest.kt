package com.guardianlink.common

import com.guardianlink.common.model.ParentalSettings
import com.guardianlink.common.model.ScreenTimeSettings
import com.guardianlink.common.util.ScreenTimePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ScreenTimePolicyTest {

    @Test
    fun normalScheduleBlocksOnlyDuringWindow() {
        val settings = ScreenTimeSettings(
            bedtimeEnabled = true,
            bedtimeStartHour = 22,
            bedtimeStartMinute = 0,
            bedtimeEndHour = 7,
            bedtimeEndMinute = 0
        )

        assertTrue(settings.isBedtimeActive(LocalTime.of(22, 30)))
        assertTrue(settings.isBedtimeActive(LocalTime.of(6, 30)))
        assertFalse(settings.isBedtimeActive(LocalTime.of(15, 0)))
    }

    @Test
    fun overnightScheduleHandlesMidnightCrossing() {
        val settings = ScreenTimeSettings(
            bedtimeEnabled = true,
            bedtimeStartHour = 22,
            bedtimeStartMinute = 30,
            bedtimeEndHour = 6,
            bedtimeEndMinute = 30
        )

        assertTrue(settings.isBedtimeActive(LocalTime.of(22, 45)))
        assertTrue(settings.isBedtimeActive(LocalTime.of(6, 0)))
        assertFalse(settings.isBedtimeActive(LocalTime.of(12, 0)))
    }

    @Test
    fun dailyLimitChecksTotalUsage() {
        val settings = ScreenTimeSettings(dailyLimitMinutes = 120)

        assertTrue(settings.isDailyLimitReached(120L * 60L * 1000L))
        assertTrue(settings.isDailyLimitReached((120L * 60L * 1000L) + 1L))
        assertFalse(settings.isDailyLimitReached((120L * 60L * 1000L) - 1L))
    }

    @Test
    fun blockedAppMatchesExactly() {
        val settings = ParentalSettings(blockedApps = listOf(" com.example.blocked "))

        assertTrue(settings.isBlockedApp("com.example.blocked"))
        assertTrue(settings.isBlockedApp("COM.EXAMPLE.BLOCKED"))
    }

    @Test
    fun allowedAppRemainsAllowed() {
        val settings = ParentalSettings(blockedApps = listOf("com.example.blocked"))

        assertFalse(settings.isBlockedApp("com.example.allowed"))
        assertFalse(settings.isBlockedApp(""))
    }

    @Test
    fun serviceUnavailableWhenEitherRequirementMissing() {
        assertTrue(ScreenTimePolicy.isEnforcementAvailable(true, true))
        assertFalse(ScreenTimePolicy.isEnforcementAvailable(true, false))
        assertFalse(ScreenTimePolicy.isEnforcementAvailable(false, true))
        assertFalse(ScreenTimePolicy.isEnforcementAvailable(false, false))
    }
}
