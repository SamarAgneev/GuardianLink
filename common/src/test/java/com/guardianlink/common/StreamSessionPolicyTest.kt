package com.guardianlink.common

import com.google.firebase.Timestamp
import com.guardianlink.common.model.StreamSession
import com.guardianlink.common.model.StreamSessionStatus
import com.guardianlink.common.model.StreamType
import com.guardianlink.common.util.StreamSessionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class StreamSessionPolicyTest {
    private val session = StreamSession(
        sessionId = "123e4567-e89b-12d3-a456-426614174000",
        deviceId = "child-a",
        parentId = "parent-a",
        type = StreamType.CAMERA_FRONT,
        status = StreamSessionStatus.PENDING,
        expiresAt = Timestamp(Date(2_000L))
    )

    @Test
    fun authorizedUnexpiredPendingSessionCanStart() {
        assertTrue(StreamSessionPolicy.canStart(session, "child-a", "parent-a", 1_000L))
    }

    @Test
    fun wrongFamilyAndExpiredSessionsCannotStart() {
        assertFalse(StreamSessionPolicy.canStart(session, "child-b", "parent-a", 1_000L))
        assertFalse(StreamSessionPolicy.canStart(session, "child-a", "parent-b", 1_000L))
        assertFalse(StreamSessionPolicy.canStart(session, "child-a", "parent-a", 2_000L))
    }

    @Test
    fun terminalOrMalformedSessionsCannotBeAuthorized() {
        assertFalse(
            StreamSessionPolicy.isAuthorized(
                session.copy(sessionId = "old-session", status = StreamSessionStatus.EXPIRED),
                "child-a",
                "parent-a"
            )
        )
    }
}