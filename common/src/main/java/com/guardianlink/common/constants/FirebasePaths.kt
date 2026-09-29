// common/src/main/java/com/guardianlink/common/constants/FirebasePaths.kt
package com.guardianlink.common.constants

/**
 * Centralized Firebase Realtime Database and Firestore path constants.
 * Keeps path strings in one place to avoid typos and aid refactoring.
 */
object FirebasePaths {

    // ── Firestore Collections ─────────────────────────────────────────────────

    const val COLLECTION_USERS         = "users"
    const val COLLECTION_DEVICES       = "devices"
    const val COLLECTION_COMMANDS      = "commands"
    const val COLLECTION_ALERTS        = "alerts"
    const val COLLECTION_CALL_LOGS     = "call_logs"
    const val COLLECTION_SMS_LOGS      = "sms_logs"
    const val COLLECTION_APP_USAGE     = "app_usage"
    const val COLLECTION_REPORTS       = "reports"
    const val COLLECTION_SETTINGS      = "settings"
    const val COLLECTION_PAIRING_CODES = "pairing_codes"
    const val COLLECTION_STREAM_SESSIONS = "stream_sessions"

    // ── Realtime DB Paths ─────────────────────────────────────────────────────

    /** /location/{deviceId} — live location updates */
    fun locationPath(deviceId: String) = "location/$deviceId"

    /** /status/{deviceId} — online/offline and battery */
    fun statusPath(deviceId: String) = "status/$deviceId"

    /** /commands/{deviceId}/{commandId} */
    fun commandPath(deviceId: String) = "commands/$deviceId"

    /** /webrtc/{sessionId}/offer|answer|candidates */
    fun webRtcPath(sessionId: String) = "webrtc/$sessionId"

    /** /stream_status/{deviceId} */
    fun streamStatusPath(deviceId: String) = "stream_status/$deviceId"

    // ── Firebase Storage Paths ────────────────────────────────────────────────

    fun photoPath(deviceId: String, filename: String) =
        "photos/$deviceId/$filename"

    fun screenShotPath(deviceId: String, filename: String) =
        "screenshots/$deviceId/$filename"
}

object NotificationChannels {
    const val MONITORING_SERVICE  = "monitoring_service"
    const val ALERT_CHANNEL       = "alerts"
    const val STREAM_CHANNEL      = "streaming"
    const val GENERAL             = "general"
}

object NotificationIds {
    const val FOREGROUND_SERVICE  = 1001
    const val SCREEN_MIRROR       = 1002
    const val CAMERA_STREAM       = 1003
    const val AUDIO_STREAM        = 1004
    const val ALERT_NOTIFICATION  = 2000
}

object Extras {
    const val COMMAND_ID      = "command_id"
    const val DEVICE_ID       = "device_id"
    const val SESSION_ID      = "session_id"
    const val STREAM_TYPE     = "stream_type"
}
