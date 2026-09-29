package com.guardianlink.common

import com.guardianlink.common.security.EncryptionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionUtilsPinTest {

    // Deliberately not using EncryptionUtils.generateSalt() here: it still
    // uses android.util.Base64 internally (unchanged legacy behavior, kept
    // for its own reasons — see EncryptionUtils), which isn't mockable
    // under a plain JUnit run without Robolectric. A fixed, valid Base64
    // salt constructed via java.util.Base64 exercises exactly the same
    // decode path hashPinPbkdf2/verifyPinHash actually use.
    private val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun pbkdf2HashRoundTripsCorrectly() {
        val hash = EncryptionUtils.hashPinPbkdf2("1234", salt)
        val result = EncryptionUtils.verifyPinHash("1234", salt, hash)
        assertTrue(result.matches)
        assertFalse("a freshly-created PBKDF2 hash must not be reported as legacy", result.isLegacyFormat)
    }

    @Test
    fun pbkdf2HashIsVersionedWithIterationCount() {
        val hash = EncryptionUtils.hashPinPbkdf2("1234", salt, iterations = 50_000)
        assertTrue(hash.startsWith("pbkdf2:50000:"))
    }

    @Test
    fun wrongPinIsRejectedForPbkdf2Format() {
        val hash = EncryptionUtils.hashPinPbkdf2("1234", salt)
        val result = EncryptionUtils.verifyPinHash("4321", salt, hash)
        assertFalse(result.matches)
    }

    @Test
    fun legacyShaHashIsStillVerifiedCorrectly() {
        // Simulates a PIN saved before the PBKDF2 migration existed.
        val legacyHash = EncryptionUtils.hashPin("9999", salt)
        val result = EncryptionUtils.verifyPinHash("9999", salt, legacyHash)
        assertTrue(result.matches)
        assertTrue("a bare (non-prefixed) stored hash must be recognized as legacy", result.isLegacyFormat)
    }

    @Test
    fun wrongPinIsRejectedForLegacyFormat() {
        val legacyHash = EncryptionUtils.hashPin("9999", salt)
        val result = EncryptionUtils.verifyPinHash("0000", salt, legacyHash)
        assertFalse(result.matches)
    }

    @Test
    fun migrationProducesAPbkdf2HashThatVerifiesTheSamePin() {
        // Mirrors PinManager.verifyPin's migration step: on a successful
        // legacy verification, the caller re-hashes with PBKDF2 and stores
        // that instead. Confirm the migrated hash still verifies correctly
        // and is no longer reported as legacy.
        val legacyHash = EncryptionUtils.hashPin("5678", salt)
        val legacyResult = EncryptionUtils.verifyPinHash("5678", salt, legacyHash)
        assertTrue(legacyResult.matches)
        assertTrue(legacyResult.isLegacyFormat)

        val migratedHash = EncryptionUtils.hashPinPbkdf2("5678", salt)
        val migratedResult = EncryptionUtils.verifyPinHash("5678", salt, migratedHash)
        assertTrue(migratedResult.matches)
        assertFalse(migratedResult.isLegacyFormat)
    }

    @Test
    fun corruptedPbkdf2PrefixedValueFailsSafelyWithoutCrashing() {
        val corrupted = "pbkdf2:not-a-number:garbage=="
        val result = EncryptionUtils.verifyPinHash("1234", salt, corrupted)
        assertFalse(result.matches)
    }

    @Test
    fun pbkdf2PrefixWithMissingSeparatorFailsSafely() {
        val corrupted = "pbkdf2:120000"
        val result = EncryptionUtils.verifyPinHash("1234", salt, corrupted)
        assertFalse(result.matches)
    }

    @Test
    fun emptyStoredHashFailsSafely() {
        val result = EncryptionUtils.verifyPinHash("1234", salt, "")
        assertFalse(result.matches)
    }

    @Test
    fun differentSaltProducesDifferentHash() {
        val saltA = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val saltB = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val hashA = EncryptionUtils.hashPinPbkdf2("1234", saltA)
        val hashB = EncryptionUtils.hashPinPbkdf2("1234", saltB)
        assertTrue(hashA != hashB)
    }
}
