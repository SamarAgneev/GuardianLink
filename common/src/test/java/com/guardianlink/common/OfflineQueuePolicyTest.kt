package com.guardianlink.common

import com.guardianlink.common.util.OfflineItemStatus
import com.guardianlink.common.util.OfflineQueuePolicy
import com.guardianlink.common.util.OfflineQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueuePolicyTest {
    private val item = OfflineQueueState("ALERT", "alert-1", 100L)

    @Test
    fun duplicateIdentityIsStableAndRetryIsBounded() {
        assertEquals(item.id, item.copy().id)
        val dead = (1..OfflineQueuePolicy.MAX_RETRIES).fold(item) { current, attempt ->
            OfflineQueuePolicy.afterFailure(current, attempt.toLong())
        }
        assertEquals(OfflineItemStatus.DEAD, dead.status)
        assertEquals(OfflineQueuePolicy.MAX_RETRIES, dead.retryCount)
    }

    @Test
    fun backoffDelaysReconnectRetry() {
        val failed = OfflineQueuePolicy.afterFailure(item, 1_000L)
        assertFalse(OfflineQueuePolicy.isEligible(failed, 2_000L, online = true))
        assertTrue(OfflineQueuePolicy.isEligible(failed, 6_000L, online = true))
        assertFalse(OfflineQueuePolicy.isEligible(failed, 6_000L, online = false))
    }

    @Test
    fun successfulItemIsTerminalAcrossProcessRestart() {
        val succeeded = OfflineQueuePolicy.afterSuccess(item)
        assertEquals(OfflineItemStatus.SUCCEEDED, succeeded.status)
        assertFalse(OfflineQueuePolicy.isEligible(succeeded, Long.MAX_VALUE, online = true))
    }
}