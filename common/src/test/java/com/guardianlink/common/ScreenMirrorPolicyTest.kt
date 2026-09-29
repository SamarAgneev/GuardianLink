package com.guardianlink.common

import com.guardianlink.common.util.ScreenMirrorPolicy
import com.guardianlink.common.util.ScreenMirrorState
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMirrorPolicyTest {
    @Test
    fun consentFlowDoesNotBecomeActiveBeforeFirstCapture() {
        assertEquals(ScreenMirrorState.CONSENT_REQUIRED, ScreenMirrorPolicy.consentRequested().state)
        assertEquals(ScreenMirrorState.STARTING, ScreenMirrorPolicy.consentGranted().state)
        assertEquals(ScreenMirrorState.ACTIVE, ScreenMirrorPolicy.captureStarted().state)
    }

    @Test
    fun denialAndCancellationAreFailuresWithReasons() {
        assertEquals(
            "User cancelled MediaProjection consent",
            ScreenMirrorPolicy.consentDenied("User cancelled MediaProjection consent").reason
        )
        assertEquals(ScreenMirrorState.FAILED, ScreenMirrorPolicy.captureFailed("no display").state)
    }

    @Test
    fun projectionStopIsNotReportedAsActive() {
        assertEquals(ScreenMirrorState.STOPPED, ScreenMirrorPolicy.projectionStopped().state)
    }
}