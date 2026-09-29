package com.guardianlink.common

import com.guardianlink.common.model.Command
import com.guardianlink.common.model.CommandSecurity
import com.guardianlink.common.model.CommandStatus
import com.guardianlink.common.model.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSecurityTest {

    @Test
    fun validCommandIsAllowed() {
        val command = Command(
            id = "cmd-1",
            parentId = "parent-123",
            childDeviceId = "device-abc",
            type = CommandType.PING,
            status = CommandStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000L
        )

        val result = CommandSecurity.validateForExecution(
            command = command,
            expectedDeviceId = "device-abc",
            authorizedParentId = "parent-123",
            nowMs = System.currentTimeMillis()
        )

        assertTrue(result.isAllowed)
        assertEquals(CommandStatus.PENDING, result.status)
    }

    @Test
    fun wrongParentIsRejected() {
        val command = Command(
            id = "cmd-2",
            parentId = "parent-123",
            childDeviceId = "device-abc",
            type = CommandType.LOCK_DEVICE,
            status = CommandStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000L
        )

        val result = CommandSecurity.validateForExecution(
            command = command,
            expectedDeviceId = "device-abc",
            authorizedParentId = "parent-999",
            nowMs = System.currentTimeMillis()
        )

        assertFalse(result.isAllowed)
        assertEquals(CommandStatus.REJECTED, result.status)
    }

    @Test
    fun wrongDeviceIsRejected() {
        val command = Command(
            id = "cmd-3",
            parentId = "parent-123",
            childDeviceId = "device-other",
            type = CommandType.GET_LOCATION,
            status = CommandStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000L
        )

        val result = CommandSecurity.validateForExecution(
            command = command,
            expectedDeviceId = "device-abc",
            authorizedParentId = "parent-123",
            nowMs = System.currentTimeMillis()
        )

        assertFalse(result.isAllowed)
        assertEquals(CommandStatus.REJECTED, result.status)
    }

    @Test
    fun expiredCommandIsExpired() {
        val command = Command(
            id = "cmd-4",
            parentId = "parent-123",
            childDeviceId = "device-abc",
            type = CommandType.PING,
            status = CommandStatus.PENDING,
            createdAt = System.currentTimeMillis() - 120_000L,
            expiresAt = System.currentTimeMillis() - 5_000L
        )

        val result = CommandSecurity.validateForExecution(
            command = command,
            expectedDeviceId = "device-abc",
            authorizedParentId = "parent-123",
            nowMs = System.currentTimeMillis()
        )

        assertFalse(result.isAllowed)
        assertEquals(CommandStatus.EXPIRED, result.status)
    }

    @Test
    fun allPrivilegedDispatcherOperationsRequireAuthorizedParent() {
        val operations = listOf(
            CommandType.LOCK_DEVICE,
            CommandType.BLOCK_APP,
            CommandType.START_CAMERA_STREAM,
            CommandType.START_AUDIO_STREAM,
            CommandType.START_SCREEN_MIRROR,
            CommandType.REBOOT
        )

        operations.forEachIndexed { index, operation ->
            val result = CommandSecurity.validateForExecution(
                command = validCommand("cmd-operation-$index", operation),
                expectedDeviceId = "device-abc",
                authorizedParentId = "parent-123",
                nowMs = 1_000L
            )
            assertTrue("$operation should be accepted for its authorized parent", result.isAllowed)
        }
    }

    @Test
    fun invalidCommandAndReplayStatesAreRejected() {
        val invalid = CommandSecurity.validateForExecution(
            command = validCommand("", CommandType.LOCK_DEVICE),
            expectedDeviceId = "device-abc",
            authorizedParentId = "parent-123",
            nowMs = 1_000L
        )
        assertFalse(invalid.isAllowed)
        assertEquals(CommandStatus.REJECTED, invalid.status)

        val command = Command(
            id = "cmd-replay",
            parentId = "parent-123", childDeviceId = "device-abc",
            type = CommandType.LOCK_DEVICE, status = CommandStatus.COMPLETED,
            createdAt = 0L, expiresAt = 2_000L
        )
        val replay = CommandSecurity.validateForExecution(
            command, "device-abc", "parent-123", nowMs = 1_000L
        )
        assertFalse(replay.isAllowed)
        assertEquals(CommandStatus.REJECTED, replay.status)
    }

    @Test
    fun missingTargetAndUnauthorizedParentAreRejected() {
        val missingTarget = CommandSecurity.validateForExecution(
            validCommand("cmd-missing", CommandType.BLOCK_APP).copy(childDeviceId = ""),
            "device-abc", "parent-123", nowMs = 1_000L
        )
        assertFalse(missingTarget.isAllowed)

        val unauthorizedParent = CommandSecurity.validateForExecution(
            validCommand("cmd-parent", CommandType.START_AUDIO_STREAM),
            "device-abc", "parent-other", nowMs = 1_000L
        )
        assertFalse(unauthorizedParent.isAllowed)
    }

    @Test
    fun mismatchedReplayTokenIsRejected() {
        // Mirrors production: ParentFirebaseManager.sendCommand always sets
        // replayToken == id. A payload where they've diverged indicates
        // tampering or corruption and must be rejected even though every
        // other field looks valid.
        val command = validCommand("cmd-tampered", CommandType.PING).copy(replayToken = "not-the-id")
        val result = CommandSecurity.validateForExecution(
            command, "device-abc", "parent-123", nowMs = 1_000L
        )
        assertFalse(result.isAllowed)
        assertEquals(CommandStatus.REJECTED, result.status)
    }

    @Test
    fun blankReplayTokenIsAllowedForBackwardCompatibility() {
        // A command with no replayToken at all (e.g. queued before this field/check
        // existed, or from an older parent-app build) must not be locked out —
        // enforcement is "reject on mismatch", not "require presence".
        val command = validCommand("cmd-legacy", CommandType.PING) // replayToken defaults to ""
        val result = CommandSecurity.validateForExecution(
            command, "device-abc", "parent-123", nowMs = 1_000L
        )
        assertTrue(result.isAllowed)
    }

    @Test
    fun matchingReplayTokenAndCommandKeyAreAllowed() {
        val command = validCommand("cmd-ok", CommandType.PING).copy(replayToken = "cmd-ok")
        val result = CommandSecurity.validateForExecution(
            command, "device-abc", "parent-123", nowMs = 1_000L,
            expectedCommandKey = "cmd-ok"
        )
        assertTrue(result.isAllowed)
    }

    @Test
    fun commandIdNotMatchingItsOwnRtdbKeyIsRejected() {
        // command.id should always equal the RTDB key it's stored under
        // (ParentFirebaseManager writes both from the same generated value).
        // A mismatch means this payload was found under the wrong node.
        val command = validCommand("cmd-real", CommandType.PING).copy(replayToken = "cmd-real")
        val result = CommandSecurity.validateForExecution(
            command, "device-abc", "parent-123", nowMs = 1_000L,
            expectedCommandKey = "some-other-key"
        )
        assertFalse(result.isAllowed)
        assertEquals(CommandStatus.REJECTED, result.status)
    }

    private fun validCommand(id: String, type: CommandType) = Command(
        id = id,
        parentId = "parent-123",
        childDeviceId = "device-abc",
        type = type,
        status = CommandStatus.PENDING,
        createdAt = 0L,
        expiresAt = 2_000L
    )
}
