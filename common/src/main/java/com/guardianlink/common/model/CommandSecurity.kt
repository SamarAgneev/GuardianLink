package com.guardianlink.common.model

import java.util.Locale

private val PRIVILEGED_COMMANDS = setOf(
    CommandType.LOCK_DEVICE,
    CommandType.UNLOCK_DEVICE,
    CommandType.REBOOT,
    CommandType.START_CAMERA_STREAM,
    CommandType.STOP_CAMERA_STREAM,
    CommandType.CAPTURE_PHOTO,
    CommandType.SWITCH_CAMERA,
    CommandType.START_SCREEN_MIRROR,
    CommandType.STOP_SCREEN_MIRROR,
    CommandType.START_AUDIO_STREAM,
    CommandType.STOP_AUDIO_STREAM,
    CommandType.BLOCK_APP,
    CommandType.UNBLOCK_APP,
    CommandType.GET_INSTALLED_APPS,
    CommandType.SYNC_SETTINGS,
    CommandType.SET_LOCATION_INTERVAL,
    CommandType.SET_DAILY_LIMIT,
    CommandType.ENFORCE_SCHEDULE,
    CommandType.SOS_TRIGGERED
)

data class CommandValidationResult(
    val isAllowed: Boolean,
    val status: CommandStatus,
    val message: String = ""
) {
    fun reject(status: CommandStatus = CommandStatus.REJECTED, message: String): CommandValidationResult =
        CommandValidationResult(false, status, message)
}

object CommandSecurity {

    fun isPrivileged(commandType: CommandType): Boolean = commandType in PRIVILEGED_COMMANDS

    /**
     * Optional expected RTDB key for this command (the key the command node was
     * actually stored under). When supplied, this is cross-checked against both
     * [Command.id] and [Command.replayToken]: the write path
     * (`ParentFirebaseManager.sendCommand`) sets both fields to the same value as
     * the RTDB key at creation time, and the RTDB rules make the command body
     * (including `id` and `replayToken`) immutable after creation. A mismatch
     * here means the payload under this key does not match what was originally
     * authorized for it — e.g. a stale/duplicated payload written under a
     * different key — and is rejected rather than executed. This is the concrete
     * enforcement of `replayToken`, previously defined on the model but never
     * read anywhere.
     */
    fun validateForExecution(
        command: Command,
        expectedDeviceId: String,
        authorizedParentId: String,
        nowMs: Long = System.currentTimeMillis(),
        expectedCommandKey: String? = null
    ): CommandValidationResult {
        if (command.id.isBlank()) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Command id is required")
        }
        // Enforced only when populated: current ParentFirebaseManager.sendCommand always sets
        // replayToken == id, but we don't hard-require it here so that any command already
        // queued before this validation was added (or sent by an older parent-app build that
        // predates this field) is not locked out. A populated-but-mismatched token is always
        // treated as tampering/corruption and rejected.
        if (command.replayToken.isNotBlank() && command.replayToken != command.id) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Command replay token does not match command id")
        }
        if (expectedCommandKey != null && expectedCommandKey.isNotBlank() && command.id != expectedCommandKey) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Command id does not match the key it was stored under")
        }
        if (command.childDeviceId.isBlank() || command.parentId.isBlank()) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Command target and parent are required")
        }
        if (command.childDeviceId != expectedDeviceId) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Command target does not match device")
        }
        if (command.parentId != authorizedParentId) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Parent identity is not authorized for this device")
        }
        if (command.status != CommandStatus.PENDING) {
            return CommandValidationResult(
                false,
                when (command.status) {
                    CommandStatus.EXPIRED -> CommandStatus.EXPIRED
                    CommandStatus.REJECTED -> CommandStatus.REJECTED
                    else -> CommandStatus.REJECTED
                },
                "Command is no longer pending"
            )
        }
        if (command.expiresAt > 0L && command.expiresAt <= nowMs) {
            return CommandValidationResult(false, CommandStatus.EXPIRED, "Command has expired")
        }
        if (isPrivileged(command.type) && command.parentId != authorizedParentId) {
            return CommandValidationResult(false, CommandStatus.REJECTED, "Privileged command requires authenticated parent")
        }
        return CommandValidationResult(true, CommandStatus.PENDING, "Command is valid")
    }

    fun commandKeyOrId(command: Command, fallbackKey: String): String =
        command.id.takeIf { it.isNotBlank() } ?: fallbackKey

    fun safeStatusName(status: String?): String =
        if (status.isNullOrBlank()) "PENDING" else status.trim().uppercase(Locale.US)
}
