// common/src/main/java/com/guardianlink/common/model/Models.kt
package com.guardianlink.common.model

import com.google.firebase.Timestamp
import com.guardianlink.common.util.ScreenTimePolicy
import java.time.LocalTime

// ─────────────────────────────────────────────────────────────────────────────
// COMMAND SYSTEM  (Parent → Backend → Child)
// ─────────────────────────────────────────────────────────────────────────────

enum class CommandType {
    // Camera
    START_CAMERA_STREAM, STOP_CAMERA_STREAM, CAPTURE_PHOTO, SWITCH_CAMERA,
    // Screen
    START_SCREEN_MIRROR, STOP_SCREEN_MIRROR,
    // Audio
    START_AUDIO_STREAM, STOP_AUDIO_STREAM,
    // Device control
    LOCK_DEVICE, UNLOCK_DEVICE, REBOOT,
    // App control
    BLOCK_APP, UNBLOCK_APP, GET_INSTALLED_APPS,
    // Location
    GET_LOCATION, SET_LOCATION_INTERVAL,
    // Screen time
    SET_DAILY_LIMIT, ENFORCE_SCHEDULE,
    // Emergency
    SOS_TRIGGERED,
    // Monitoring
    GET_CALL_LOGS, GET_SMS_LOGS, GET_APP_USAGE,
    // Settings
    SYNC_SETTINGS, PING
}

enum class CommandStatus { PENDING, DELIVERED, EXECUTING, COMPLETED, FAILED, EXPIRED, REJECTED }

data class Command(
    val id: String = "",
    val parentId: String = "",
    val childDeviceId: String = "",
    val type: CommandType = CommandType.PING,
    val payload: Map<String, Any> = emptyMap(),
    val status: CommandStatus = CommandStatus.PENDING,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val executedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val replayToken: String = "",
    val authorizedParentId: String = "",
    val lastUpdatedAt: Long = 0L
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0L && expiresAt <= nowMs

    fun isTerminal(): Boolean = status in setOf(
        CommandStatus.COMPLETED,
        CommandStatus.FAILED,
        CommandStatus.EXPIRED,
        CommandStatus.REJECTED
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// LOCATION
// ─────────────────────────────────────────────────────────────────────────────

data class LocationData(
    val deviceId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val altitude: Double = 0.0,
    val address: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val batteryLevel: Int = 0
)

data class PhotoMetadata(
    val deviceId: String = "",
    val filename: String = "",
    val objectKey: String = "",
    val url: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val etag: String = ""
)

data class GeoFence(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 200f,
    val alertOnEnter: Boolean = true,
    val alertOnExit: Boolean = true,
    val isActive: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// ALERTS
// ─────────────────────────────────────────────────────────────────────────────

enum class AlertSeverity { LOW, MEDIUM, HIGH, CRITICAL }

enum class AlertType {
    SOS_PANIC,
    GEOFENCE_ENTER, GEOFENCE_EXIT,
    UNKNOWN_CONTACT, BLOCKED_APP_ATTEMPT,
    TAMPER_DETECTED, ADMIN_DISABLED, PERMISSION_REVOKED,
    LOW_BATTERY, OFFLINE,
    NEW_APP_INSTALLED, ADULT_CONTENT_BLOCKED,
    SCREEN_TIME_EXCEEDED, CALL_LOG, SMS_KEYWORD
}

enum class ProtectionState { PROTECTED, DEGRADED, DISABLED }

data class ProtectionStatus(
    val state: ProtectionState = ProtectionState.PROTECTED,
    val issues: List<String> = emptyList()
) {
    fun summary(): String = if (issues.isEmpty()) "All protections active." else issues.joinToString(separator = "; ")
}

data class AlertData(
    val id: String = "",
    val deviceId: String = "",
    val childName: String = "",
    val type: AlertType = AlertType.OFFLINE,
    val severity: AlertSeverity = AlertSeverity.LOW,
    val title: String = "",
    val message: String = "",
    val metadata: Map<String, Any> = emptyMap(),
    val timestamp: Timestamp = Timestamp.now(),
    val isRead: Boolean = false,
    val isResolved: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// APP USAGE
// ─────────────────────────────────────────────────────────────────────────────

data class AppInfo(
    val packageName: String = "",
    val appName: String = "",
    val category: String = "",
    val isBlocked: Boolean = false,
    val dailyLimitMinutes: Int = -1,  // -1 = no limit
    val iconBase64: String = ""
)

data class AppUsageData(
    val deviceId: String = "",
    val packageName: String = "",
    val appName: String = "",
    val foregroundTimeMs: Long = 0L,
    val lastUsed: Timestamp = Timestamp.now(),
    val openCount: Int = 0,
    val date: String = ""   // "yyyy-MM-dd"
)

data class AppUsageSession(
    val packageName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val durationMs: Long = 0L
)

// ─────────────────────────────────────────────────────────────────────────────
// COMMUNICATION LOGS
// ─────────────────────────────────────────────────────────────────────────────

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED }

data class CallLogEntry(
    val id: String = "",
    val deviceId: String = "",
    val number: String = "",
    val contactName: String = "",
    val type: CallType = CallType.INCOMING,
    val durationSeconds: Long = 0L,
    val timestamp: Timestamp = Timestamp.now(),
    val isUnknownContact: Boolean = false
)

enum class SmsDirection { INBOX, SENT }

data class SmsLogEntry(
    val id: String = "",
    val deviceId: String = "",
    val number: String = "",
    val contactName: String = "",
    val body: String = "",
    val direction: SmsDirection = SmsDirection.INBOX,
    val timestamp: Timestamp = Timestamp.now(),
    val isUnknownContact: Boolean = false,
    val containsKeyword: Boolean = false,
    val flaggedKeywords: List<String> = emptyList()
)

// ─────────────────────────────────────────────────────────────────────────────
// DEVICE STATUS
// ─────────────────────────────────────────────────────────────────────────────

enum class DeviceOnlineStatus { ONLINE, OFFLINE, IDLE }

data class DeviceStatus(
    val deviceId: String = "",
    val childName: String = "",
    val parentId: String = "",
    val onlineStatus: DeviceOnlineStatus = DeviceOnlineStatus.OFFLINE,
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val networkType: String = "",        // "WiFi", "4G", "5G", "None"
    val currentApp: String = "",
    val screenOn: Boolean = false,
    val isCameraStreaming: Boolean = false,
    val isAudioStreaming: Boolean = false,
    val isScreenMirroring: Boolean = false,
    val lastSeen: Timestamp = Timestamp.now(),
    val appVersion: String = "",
    val androidVersion: String = "",
    val deviceModel: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN TIME SETTINGS
// ─────────────────────────────────────────────────────────────────────────────

data class DailySchedule(
    val dayOfWeek: Int = 1,         // 1=Monday ... 7=Sunday
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 21,
    val endMinute: Int = 0,
    val isEnabled: Boolean = true
)

data class ScreenTimeSettings(
    val deviceId: String = "",
    val dailyLimitMinutes: Int = 120,
    val schedules: List<DailySchedule> = emptyList(),
    val bedtimeEnabled: Boolean = true,
    val bedtimeStartHour: Int = 22,
    val bedtimeStartMinute: Int = 0,
    val bedtimeEndHour: Int = 7,
    val bedtimeEndMinute: Int = 0,
    val weekendDifferentLimits: Boolean = false,
    val weekendLimitMinutes: Int = 180
) {
    val bedtimeStartMinutes: Int
        get() = bedtimeStartHour * 60 + bedtimeStartMinute

    val bedtimeEndMinutes: Int
        get() = bedtimeEndHour * 60 + bedtimeEndMinute

    fun isBedtimeActive(now: LocalTime = LocalTime.now()): Boolean =
        ScreenTimePolicy.isBedtimeActive(
            enabled = bedtimeEnabled,
            startHour = bedtimeStartHour,
            startMinute = bedtimeStartMinute,
            endHour = bedtimeEndHour,
            endMinute = bedtimeEndMinute,
            now = now
        )

    fun isDailyLimitReached(totalUsageMs: Long): Boolean =
        ScreenTimePolicy.isDailyLimitReached(dailyLimitMinutes, totalUsageMs)
}

// ─────────────────────────────────────────────────────────────────────────────
// PARENTAL CONTROL SETTINGS
// ─────────────────────────────────────────────────────────────────────────────

data class ParentalSettings(
    val deviceId: String = "",
    val blockedApps: List<String> = emptyList(),
    val blockedWebsites: List<String> = emptyList(),
    val blockedKeywords: List<String> = emptyList(),
    val adultContentFilterEnabled: Boolean = true,
    val socialMediaMonitored: Boolean = true,
    val allowUnknownApps: Boolean = false,
    val locationTrackingEnabled: Boolean = true,
    val locationIntervalSeconds: Int = 10,
    val callMonitoringEnabled: Boolean = true,
    val smsMonitoringEnabled: Boolean = true,
    val screenMirroringEnabled: Boolean = true,
    val cameraMonitoringEnabled: Boolean = true,
    val audioMonitoringEnabled: Boolean = false,
    val geofences: List<GeoFence> = emptyList(),
    val screenTimeSettings: ScreenTimeSettings = ScreenTimeSettings(),
    val lastUpdated: Timestamp = Timestamp.now()
) {
    fun isBlockedApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val normalized = packageName.trim().lowercase()
        return blockedApps.any { it.trim().lowercase() == normalized }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PAIRING
// ─────────────────────────────────────────────────────────────────────────────

data class PairingCode(
    val code: String = "",
    val parentId: String = "",
    val parentName: String = "",
    val deviceId: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val expiresAt: Timestamp = Timestamp.now(),
    val isUsed: Boolean = false,
    val usedByDeviceId: String = "",
    val usedAt: Timestamp? = null,
    val status: String = "ACTIVE"
)

data class ChildDevice(
    val deviceId: String = "",
    val childName: String = "",
    val parentId: String = "",
    val pairedAt: Timestamp = Timestamp.now(),
    val fcmToken: String = "",
    val isActive: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// REPORTS
// ─────────────────────────────────────────────────────────────────────────────

data class DailyReport(
    val deviceId: String = "",
    val date: String = "",
    val totalScreenTimeMs: Long = 0L,
    val topApps: List<AppUsageData> = emptyList(),
    val locationHistory: List<LocationData> = emptyList(),
    val callCount: Int = 0,
    val smsCount: Int = 0,
    val alertsTriggered: Int = 0,
    val blockedAttempts: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// STREAMING
// ─────────────────────────────────────────────────────────────────────────────

enum class StreamType { SCREEN, CAMERA_FRONT, CAMERA_BACK, AUDIO }

enum class StreamSessionStatus { PENDING, STARTING, ACTIVE, STOPPED, FAILED, EXPIRED }

data class StreamSession(
    val sessionId: String = "",
    val deviceId: String = "",
    val parentId: String = "",
    val type: StreamType = StreamType.SCREEN,
    val startedAt: Timestamp = Timestamp.now(),
    val expiresAt: Timestamp = Timestamp.now(),
    val webRtcOffer: String = "",
    val webRtcAnswer: String = "",
    val status: StreamSessionStatus = StreamSessionStatus.PENDING,
    val isActive: Boolean = false,
    val endedAt: Timestamp? = null,
    val failureReason: String = "",
    val lastUpdatedAt: Timestamp = Timestamp.now()
)
