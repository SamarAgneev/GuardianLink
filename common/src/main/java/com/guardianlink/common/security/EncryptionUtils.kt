// common/src/main/java/com/guardianlink/common/security/EncryptionUtils.kt
package com.guardianlink.common.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EncryptionUtils {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "GuardianLinkKey"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    // ── Key generation ────────────────────────────────────────────────────────

    fun generateKeyIfAbsent() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    // ── Encrypt / Decrypt ────────────────────────────────────────────────────

    /**
     * Encrypts plaintext using AES-256-GCM via Android Keystore.
     * Returns Base64(IV + ciphertext).
     */
    fun encrypt(plaintext: String): String {
        generateKeyIfAbsent()
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64(IV + ciphertext) string.
     */
    fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(
            Cipher.DECRYPT_MODE, getSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    // ── Hash ─────────────────────────────────────────────────────────────────

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256

    /**
     * Prefix marking the current, versioned PIN-hash format:
     * `"pbkdf2:<iterations>:<base64-derived-key>"`. A stored value without
     * this prefix is the legacy plain-SHA-256 format ([hashPin]) that this
     * scheme replaces — Base64 output never contains a literal colon, so
     * this reliably distinguishes the two formats without a separate
     * metadata field. Keeping the iteration count in the stored value (not
     * hardcoded on the read side) means a future bump to
     * [PBKDF2_ITERATIONS] doesn't invalidate PINs hashed under the previous
     * count.
     */
    private const val PBKDF2_PREFIX = "pbkdf2:"

    /**
     * Result of verifying a PIN against whatever format its stored hash
     * happens to be in. [isLegacyFormat] tells the caller (see
     * `PinManager.verifyPin`) whether it should transparently migrate the
     * stored hash to the current PBKDF2 format now that the plaintext PIN
     * is known-correct.
     */
    data class PinVerificationResult(val matches: Boolean, val isLegacyFormat: Boolean)

    /** Legacy PIN hash: single-round SHA-256(pin + salt). Kept only so
     *  [verifyPinHash] can still recognize and verify against PINs stored
     *  before the PBKDF2 migration — never used for new PINs (see [savePin]
     *  call sites, which all now go through [hashPinPbkdf2]).
     *
     *  Uses `java.util.Base64` rather than `android.util.Base64` purely so
     *  this is unit-testable under plain JUnit without adding Robolectric as
     *  a new test dependency — behavior-preserving for already-stored
     *  hashes, since both encoders produce identical standard-alphabet,
     *  padded, non-wrapped Base64 for the same input bytes. */
    fun hashPin(pin: String, salt: String): String {
        val combined = (pin + salt).toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return java.util.Base64.getEncoder().encodeToString(digest.digest(combined))
    }

    /**
     * Current PIN hashing scheme: PBKDF2WithHmacSHA256 with [iterations]
     * rounds (defaulting to [PBKDF2_ITERATIONS]), returned in the versioned
     * `"pbkdf2:<iterations>:<base64>"` format described on [PBKDF2_PREFIX].
     * [salt] is the same Base64-encoded random salt [generateSalt] already
     * produces; it is decoded to raw bytes here for use as the PBKDF2 salt
     * parameter (falling back to its raw UTF-8 bytes if it isn't valid
     * Base64, so this never throws on an unexpected salt value).
     */
    fun hashPinPbkdf2(pin: String, salt: String, iterations: Int = PBKDF2_ITERATIONS): String {
        val saltBytes = try {
            java.util.Base64.getDecoder().decode(salt)
        } catch (e: Exception) {
            salt.toByteArray(Charsets.UTF_8)
        }
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), saltBytes, iterations, PBKDF2_KEY_LENGTH_BITS)
        val factory = javax.crypto.SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val derived = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(derived)
        return "$PBKDF2_PREFIX$iterations:$encoded"
    }

    /**
     * Verifies [pin] against [storedHash], transparently supporting both the
     * current PBKDF2 format and the legacy plain-SHA-256 format — so a PIN
     * saved before this migration keeps working without the user needing to
     * re-enter or reset anything (see `PinManager.verifyPin` for the
     * migration step this enables). A malformed/corrupted stored value
     * (unexpected format, non-numeric iteration count, etc.) is treated as a
     * verification failure, never as a crash.
     */
    fun verifyPinHash(pin: String, salt: String, storedHash: String): PinVerificationResult {
        return try {
            if (storedHash.startsWith(PBKDF2_PREFIX)) {
                val rest = storedHash.removePrefix(PBKDF2_PREFIX)
                val separatorIndex = rest.indexOf(':')
                if (separatorIndex <= 0) return PinVerificationResult(matches = false, isLegacyFormat = false)
                val iterations = rest.substring(0, separatorIndex).toIntOrNull()
                    ?: return PinVerificationResult(matches = false, isLegacyFormat = false)
                val expected = rest.substring(separatorIndex + 1)
                val computed = hashPinPbkdf2(pin, salt, iterations)
                    .removePrefix(PBKDF2_PREFIX).substringAfter(':')
                PinVerificationResult(matches = constantTimeEquals(computed, expected), isLegacyFormat = false)
            } else {
                val legacyHash = hashPin(pin, salt)
                PinVerificationResult(matches = constantTimeEquals(legacyHash, storedHash), isLegacyFormat = true)
            }
        } catch (e: Exception) {
            PinVerificationResult(matches = false, isLegacyFormat = false)
        }
    }

    /** Constant-time string comparison, so PIN verification doesn't leak
     *  timing information about how many leading characters matched. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    fun generateSalt(): String =
        Base64.encodeToString(
            java.security.SecureRandom().generateSeed(32),
            Base64.NO_WRAP
        )
}

// ─────────────────────────────────────────────────────────────────────────────
// SECURE PREFERENCES — wrapper around EncryptedSharedPreferences
// ─────────────────────────────────────────────────────────────────────────────

class SecurePreferences(context: Context) {

    companion object {
        private const val FILE_NAME = "guardian_secure_prefs"

        // Keys
        const val KEY_PIN_HASH        = "pin_hash"
        const val KEY_PIN_SALT        = "pin_salt"
        const val KEY_DEVICE_ID       = "device_id"
        const val KEY_PARENT_ID       = "parent_id"
        const val KEY_FCM_TOKEN       = "fcm_token"
        const val KEY_CONSENT_GIVEN   = "consent_given"
        const val KEY_ADMIN_ENABLED   = "admin_enabled"
        const val KEY_PAIRING_DONE    = "pairing_done"
        const val KEY_CHILD_NAME      = "child_name"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL    = "locked_until"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)

    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)

    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun remove(key: String) = prefs.edit().remove(key).apply()
    fun clear() = prefs.edit().clear().apply()
}
