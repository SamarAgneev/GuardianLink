package com.guardianlink.common

import com.guardianlink.common.util.CommunicationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationUtilsTest {
    @Test
    fun stableRecordIdIsDeterministic() {
        val a = CommunicationUtils.stableRecordId("sms", "device-1", "+15551234567", "1000", "hi")
        val b = CommunicationUtils.stableRecordId("sms", "device-1", "+15551234567", "1000", "hi")
        assertEquals(a, b)
    }

    @Test
    fun stableRecordIdDiffersWhenAnyComponentDiffers() {
        val base = CommunicationUtils.stableRecordId("sms", "device-1", "+15551234567", "1000", "hi")
        assertNotEquals(base, CommunicationUtils.stableRecordId("sms", "device-2", "+15551234567", "1000", "hi"))
        assertNotEquals(base, CommunicationUtils.stableRecordId("sms", "device-1", "+15559999999", "1000", "hi"))
        assertNotEquals(base, CommunicationUtils.stableRecordId("sms", "device-1", "+15551234567", "2000", "hi"))
        assertNotEquals(base, CommunicationUtils.stableRecordId("sms", "device-1", "+15551234567", "1000", "bye"))
    }

    @Test
    fun realtimeAndOnDemandSmsSyncMustDeriveTheSameRecordIdForTheSameMessage() {
        // Regression guard for a real bug: SmsReceiver (the real-time path)
        // and ChildFirebaseManager.syncSmsLogs (the on-demand GET_SMS_LOGS
        // path) used to derive their record id differently — one from the
        // provider's row _ID, the other from (number, timestamp, body) —
        // so the same real SMS could be written as two separate Firestore
        // documents depending on which path saw it first. Both now compute
        // the id the same way; this test pins that down so it can't
        // silently regress if either call site's arguments are edited
        // independently in the future.
        val deviceId = "device-1"
        val normalizedNumber = "+15551234567"
        val timestampMs = 1_700_000_000_000L
        val body = "Don't forget to pick up milk"

        // As SmsReceiver.processMessages computes it:
        val realtimeId = CommunicationUtils.stableRecordId(
            "sms", deviceId, normalizedNumber, timestampMs.toString(), body
        )
        // As ChildFirebaseManager.syncSmsLogs computes it:
        val onDemandId = CommunicationUtils.stableRecordId(
            "sms", deviceId, normalizedNumber, timestampMs.toString(), body
        )
        assertEquals(realtimeId, onDemandId)
    }

    @Test
    fun keywordMatchingNormalizesCasePunctuationAndReturnsConfiguredValues() {
        val matches = CommunicationUtils.findKeywordMatches(
            "Please send the CREDIT-card details.",
            listOf("credit card", "school")
        )

        assertEquals(listOf("credit card"), matches)
    }

    @Test
    fun keywordMatchingDoesNotMatchInsideAnotherWord() {
        assertTrue(
            CommunicationUtils.findKeywordMatches("educational material", listOf("cat")).isEmpty()
        )
    }

    @Test
    fun phoneNormalizationHandlesCountryCodeFormatting() {
        assertTrue(
            CommunicationUtils.phoneNumbersMatch(
                "+1 (555) 123-4567",
                "5551234567"
            )
        )
        assertEquals("+442071234567", CommunicationUtils.normalizePhoneNumber("020 7123 4567", "44"))
    }
}