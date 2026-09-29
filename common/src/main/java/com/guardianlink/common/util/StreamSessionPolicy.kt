package com.guardianlink.common.util

import com.guardianlink.common.model.StreamSession
import com.guardianlink.common.model.StreamSessionStatus

object StreamSessionPolicy {
    private val SESSION_ID_PATTERN = Regex("[a-fA-F0-9-]{36}")

    fun isValidSessionId(sessionId: String): Boolean = SESSION_ID_PATTERN.matches(sessionId)

    fun isAuthorized(
        session: StreamSession,
        expectedDeviceId: String,
        authorizedParentId: String
    ): Boolean =
        isValidSessionId(session.sessionId) &&
            session.deviceId == expectedDeviceId &&
            session.parentId == authorizedParentId

    fun canStart(
        session: StreamSession,
        expectedDeviceId: String,
        authorizedParentId: String,
        nowMs: Long
    ): Boolean =
        isAuthorized(session, expectedDeviceId, authorizedParentId) &&
            session.status == StreamSessionStatus.PENDING &&
            session.expiresAt.toDate().time > nowMs
}