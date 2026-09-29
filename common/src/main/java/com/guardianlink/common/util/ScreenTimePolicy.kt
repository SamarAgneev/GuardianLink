package com.guardianlink.common.util

import java.time.LocalTime

object ScreenTimePolicy {

    fun isBedtimeActive(
        enabled: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        now: LocalTime = LocalTime.now()
    ): Boolean {
        if (!enabled) return false

        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        val nowMinutes = now.hour * 60 + now.minute

        if (startMinutes == endMinutes) return false

        return if (startMinutes < endMinutes) {
            nowMinutes >= startMinutes && nowMinutes < endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    fun isDailyLimitReached(dailyLimitMinutes: Int, totalUsageMs: Long): Boolean {
        if (dailyLimitMinutes <= 0) return false
        return totalUsageMs >= dailyLimitMinutes * 60_000L
    }

    fun isEnforcementAvailable(accessibilityEnabled: Boolean, usageAccessGranted: Boolean): Boolean =
        accessibilityEnabled && usageAccessGranted
}
