// child-app/src/main/java/com/guardianlink/child/security/PinManager.kt
package com.guardianlink.child.security

import com.guardianlink.common.security.EncryptionUtils
import com.guardianlink.common.security.SecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinManager @Inject constructor(
    private val securePrefs: SecurePreferences
) {
    companion object {
        private const val MAX_ATTEMPTS = 5
    }

    private var pinVerified = false

    fun savePin(pin: String) {
        val salt = EncryptionUtils.generateSalt()
        val hash = EncryptionUtils.hashPinPbkdf2(pin, salt)
        securePrefs.putString(SecurePreferences.KEY_PIN_SALT, salt)
        securePrefs.putString(SecurePreferences.KEY_PIN_HASH, hash)
        securePrefs.putInt(SecurePreferences.KEY_FAILED_ATTEMPTS, 0)
    }

    fun verifyPin(pin: String): Boolean {
        val salt = securePrefs.getString(SecurePreferences.KEY_PIN_SALT)
        val storedHash = securePrefs.getString(SecurePreferences.KEY_PIN_HASH)
        if (salt.isBlank() || storedHash.isBlank()) return false

        val result = EncryptionUtils.verifyPinHash(pin, salt, storedHash)
        if (result.matches) {
            securePrefs.putInt(SecurePreferences.KEY_FAILED_ATTEMPTS, 0)
            pinVerified = true
            if (result.isLegacyFormat) {
                // Transparent migration: the PIN just verified correctly
                // against the legacy SHA-256 format. Re-hash it with
                // PBKDF2 now, using the same salt, so future verifications
                // use the stronger scheme — the user doesn't need to
                // re-enter or reset their PIN for this to happen.
                val migratedHash = EncryptionUtils.hashPinPbkdf2(pin, salt)
                securePrefs.putString(SecurePreferences.KEY_PIN_HASH, migratedHash)
            }
        }
        return result.matches
    }

    fun isPinSet(): Boolean =
        securePrefs.getString(SecurePreferences.KEY_PIN_HASH).isNotBlank()

    fun isPinVerified(): Boolean = pinVerified
    fun setPinVerified(v: Boolean) { pinVerified = v }

    fun incrementFailedAttempts(): Int {
        val current = securePrefs.getInt(SecurePreferences.KEY_FAILED_ATTEMPTS) + 1
        securePrefs.putInt(SecurePreferences.KEY_FAILED_ATTEMPTS, current)
        return current
    }

    fun lockOut(durationMs: Long) {
        securePrefs.putLong(SecurePreferences.KEY_LOCKED_UNTIL, System.currentTimeMillis() + durationMs)
    }

    fun isLockedOut(): Boolean {
        val lockUntil = securePrefs.getLong(SecurePreferences.KEY_LOCKED_UNTIL)
        return lockUntil > System.currentTimeMillis()
    }

    fun getLockoutEndTime(): Long =
        securePrefs.getLong(SecurePreferences.KEY_LOCKED_UNTIL)

    fun isBiometricEnabled(): Boolean =
        securePrefs.getBoolean("biometric_enabled", false)
}
