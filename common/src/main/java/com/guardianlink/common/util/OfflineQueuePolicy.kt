package com.guardianlink.common.util

enum class OfflineItemStatus { PENDING, IN_FLIGHT, SUCCEEDED, DEAD }

data class OfflineQueueState(
    val type: String,
    val id: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastAttempt: Long? = null,
    val status: OfflineItemStatus = OfflineItemStatus.PENDING
)

object OfflineQueuePolicy {
    const val MAX_RETRIES = 8
    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L

    fun backoffMs(retryCount: Int): Long {
        val exponent = retryCount.coerceIn(0, 30)
        return (BASE_BACKOFF_MS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun isEligible(item: OfflineQueueState, nowMs: Long, online: Boolean): Boolean =
        online &&
            item.status == OfflineItemStatus.PENDING &&
            (item.lastAttempt == null || nowMs - item.lastAttempt >= backoffMs(item.retryCount))

    fun afterFailure(item: OfflineQueueState, attemptAt: Long): OfflineQueueState {
        val nextRetry = item.retryCount + 1
        return item.copy(
            retryCount = nextRetry,
            lastAttempt = attemptAt,
            status = if (nextRetry >= MAX_RETRIES) OfflineItemStatus.DEAD else OfflineItemStatus.PENDING
        )
    }

    fun afterSuccess(item: OfflineQueueState): OfflineQueueState =
        item.copy(status = OfflineItemStatus.SUCCEEDED)
}