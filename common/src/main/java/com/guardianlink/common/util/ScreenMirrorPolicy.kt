package com.guardianlink.common.util

enum class ScreenMirrorState {
    IDLE,
    CONSENT_REQUIRED,
    STARTING,
    ACTIVE,
    STOPPED,
    FAILED
}

data class ScreenMirrorTransition(
    val state: ScreenMirrorState,
    val reason: String = ""
)

object ScreenMirrorPolicy {
    fun consentRequested() = ScreenMirrorTransition(ScreenMirrorState.CONSENT_REQUIRED)

    fun consentGranted() = ScreenMirrorTransition(ScreenMirrorState.STARTING)

    fun captureStarted() = ScreenMirrorTransition(ScreenMirrorState.ACTIVE)

    fun consentDenied(reason: String) = ScreenMirrorTransition(
        ScreenMirrorState.FAILED,
        reason.ifBlank { "MediaProjection consent was denied" }
    )

    fun captureFailed(reason: String) = ScreenMirrorTransition(
        ScreenMirrorState.FAILED,
        reason.ifBlank { "Screen capture failed to start" }
    )

    fun projectionStopped() = ScreenMirrorTransition(
        ScreenMirrorState.STOPPED,
        "MediaProjection stopped"
    )
}