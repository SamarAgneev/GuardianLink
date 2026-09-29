// child-app/src/main/java/com/guardianlink/child/ui/PinLockActivity.kt
package com.guardianlink.child.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.guardianlink.child.R
import com.guardianlink.child.security.PinManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PinLockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION   = "action"
        const val ACTION_VERIFY  = "verify"   // verify PIN to access protected section
        const val ACTION_SETUP   = "setup"    // first-time PIN setup
        const val ACTION_CHANGE  = "change"   // change existing PIN
        const val RESULT_SUCCESS = 200

        const val MAX_ATTEMPTS    = 5
        const val LOCK_DURATION_MS = 5 * 60 * 1000L // 5 minutes

        fun verifyIntent(context: Context) =
            Intent(context, PinLockActivity::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_VERIFY)
            }

        fun setupIntent(context: Context) =
            Intent(context, PinLockActivity::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_SETUP)
            }

        fun changeIntent(context: Context) =
            Intent(context, PinLockActivity::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_CHANGE)
            }
    }

    @Inject lateinit var pinManager: PinManager

    private val enteredDigits = StringBuilder()
    private var action = ACTION_VERIFY
    private var setupFirstPin = ""

    /** Sub-states of the ACTION_CHANGE flow: verify the existing PIN, then
     *  capture + confirm the new one (mirrors the two-step ACTION_SETUP flow,
     *  with an old-PIN verification step gating entry into it). */
    private enum class ChangePinPhase { VERIFY_OLD, ENTER_NEW, CONFIRM_NEW }
    private var changePinPhase = ChangePinPhase.VERIFY_OLD
    private var changePinNewFirstEntry = ""

    // Views
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvDots: TextView
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: prevent screenshots on PIN screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_pin_lock)

        action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_VERIFY

        // Check if locked out. ACTION_CHANGE also starts with an old-PIN check
        // (see changePin()/ChangePinPhase.VERIFY_OLD) and must respect the same
        // lockout as ACTION_VERIFY — otherwise "Change PIN" would be a side
        // channel around the failed-attempt lockout.
        if ((action == ACTION_VERIFY || action == ACTION_CHANGE) && pinManager.isLockedOut()) {
            showLockout()
            return
        }

        initViews()
        setupKeypad()

        // Offer biometric if available and action is VERIFY
        if (action == ACTION_VERIFY && pinManager.isBiometricEnabled()) {
            showBiometricPrompt()
        }
    }

    private fun initViews() {
        tvTitle    = findViewById(R.id.tv_pin_title)
        tvSubtitle = findViewById(R.id.tv_pin_subtitle)
        tvDots     = findViewById(R.id.tv_pin_dots)
        tvError    = findViewById(R.id.tv_pin_error)

        tvTitle.text = when (action) {
            ACTION_SETUP  -> getString(R.string.pin_create_title)
            ACTION_CHANGE -> when (changePinPhase) {
                ChangePinPhase.VERIFY_OLD  -> getString(R.string.pin_enter_current_title)
                ChangePinPhase.ENTER_NEW   -> getString(R.string.pin_change_title)
                ChangePinPhase.CONFIRM_NEW -> getString(R.string.pin_confirm_title)
            }
            else          -> getString(R.string.pin_enter_title)
        }
    }

    private fun setupKeypad() {
        val digitIds = mapOf(
            R.id.btn_0 to '0', R.id.btn_1 to '1', R.id.btn_2 to '2',
            R.id.btn_3 to '3', R.id.btn_4 to '4', R.id.btn_5 to '5',
            R.id.btn_6 to '6', R.id.btn_7 to '7', R.id.btn_8 to '8',
            R.id.btn_9 to '9'
        )
        digitIds.forEach { (id, digit) ->
            findViewById<android.widget.Button>(id)?.setOnClickListener {
                appendDigit(digit)
            }
        }

        findViewById<ImageButton>(R.id.btn_backspace)?.setOnClickListener { backspace() }
        findViewById<ImageButton>(R.id.btn_biometric)?.apply {
            visibility = if (pinManager.isBiometricEnabled() && action == ACTION_VERIFY)
                android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { showBiometricPrompt() }
        }
    }

    private fun appendDigit(digit: Char) {
        if (enteredDigits.length >= 6) return
        enteredDigits.append(digit)
        updateDots()

        if (enteredDigits.length == 4 || enteredDigits.length == 6) {
            // Auto-submit when 4 or 6 digits entered
            processPin(enteredDigits.toString())
        }
    }

    private fun backspace() {
        if (enteredDigits.isNotEmpty()) {
            enteredDigits.deleteCharAt(enteredDigits.length - 1)
            updateDots()
        }
    }

    private fun updateDots() {
        val filled   = "●".repeat(enteredDigits.length)
        val empty    = "○".repeat(maxOf(0, 4 - enteredDigits.length))
        tvDots.text = filled + empty
    }

    private fun processPin(pin: String) {
        when (action) {
            ACTION_VERIFY -> verifyPin(pin)
            ACTION_SETUP  -> setupPin(pin)
            ACTION_CHANGE -> changePin(pin)
        }
    }

    private fun verifyPin(pin: String) {
        if (pinManager.verifyPin(pin)) {
            // Success
            vibrate(VibrationPattern.SUCCESS)
            setResult(RESULT_SUCCESS)
            finish()
        } else {
            vibrate(VibrationPattern.ERROR)
            val attempts = pinManager.incrementFailedAttempts()
            val remaining = MAX_ATTEMPTS - attempts

            if (attempts >= MAX_ATTEMPTS) {
                pinManager.lockOut(LOCK_DURATION_MS)
                showLockout()
            } else {
                tvError.text = getString(R.string.pin_wrong_attempts, remaining)
                animateShake()
                enteredDigits.clear()
                updateDots()
            }
        }
    }

    private fun setupPin(pin: String) {
        if (setupFirstPin.isEmpty()) {
            setupFirstPin = pin
            enteredDigits.clear()
            updateDots()
            tvTitle.text = getString(R.string.pin_confirm_title)
        } else {
            if (pin == setupFirstPin) {
                pinManager.savePin(pin)
                vibrate(VibrationPattern.SUCCESS)
                setResult(RESULT_SUCCESS)
                finish()
            } else {
                vibrate(VibrationPattern.ERROR)
                tvError.text = getString(R.string.pin_mismatch)
                setupFirstPin = ""
                enteredDigits.clear()
                updateDots()
                tvTitle.text = getString(R.string.pin_create_title)
            }
        }
    }

    private fun changePin(pin: String) {
        when (changePinPhase) {
            ChangePinPhase.VERIFY_OLD -> {
                if (pinManager.verifyPin(pin)) {
                    // verifyPin() already reset the failed-attempt counter on success.
                    vibrate(VibrationPattern.SUCCESS)
                    changePinPhase = ChangePinPhase.ENTER_NEW
                    tvError.text = ""
                    enteredDigits.clear()
                    updateDots()
                    tvTitle.text = getString(R.string.pin_change_title)
                } else {
                    vibrate(VibrationPattern.ERROR)
                    val attempts = pinManager.incrementFailedAttempts()
                    val remaining = MAX_ATTEMPTS - attempts
                    if (attempts >= MAX_ATTEMPTS) {
                        pinManager.lockOut(LOCK_DURATION_MS)
                        showLockout()
                    } else {
                        tvError.text = getString(R.string.pin_wrong_attempts, remaining)
                        animateShake()
                        enteredDigits.clear()
                        updateDots()
                    }
                }
            }
            ChangePinPhase.ENTER_NEW -> {
                changePinNewFirstEntry = pin
                enteredDigits.clear()
                updateDots()
                tvTitle.text = getString(R.string.pin_confirm_title)
                changePinPhase = ChangePinPhase.CONFIRM_NEW
            }
            ChangePinPhase.CONFIRM_NEW -> {
                if (pin == changePinNewFirstEntry) {
                    // savePin() also resets the failed-attempt counter.
                    pinManager.savePin(pin)
                    vibrate(VibrationPattern.SUCCESS)
                    setResult(RESULT_SUCCESS)
                    finish()
                } else {
                    vibrate(VibrationPattern.ERROR)
                    tvError.text = getString(R.string.pin_mismatch)
                    changePinNewFirstEntry = ""
                    enteredDigits.clear()
                    updateDots()
                    changePinPhase = ChangePinPhase.ENTER_NEW
                    tvTitle.text = getString(R.string.pin_change_title)
                }
            }
        }
    }

    private fun showLockout() {
        val lockUntil = pinManager.getLockoutEndTime()
        val remaining = ((lockUntil - System.currentTimeMillis()) / 1000 / 60).toInt()
        tvError.text = getString(R.string.pin_locked_message, remaining)
        tvDots.visibility = android.view.View.INVISIBLE
    }

    // ── Biometric ─────────────────────────────────────────────────────────────

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                pinManager.setPinVerified(true)
                setResult(RESULT_SUCCESS)
                finish()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Fall back to PIN
            }
            override fun onAuthenticationFailed() {
                vibrate(VibrationPattern.ERROR)
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("GuardianLink")
            .setSubtitle("Authenticate to access parental controls")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
            .build()

        prompt.authenticate(info)
    }

    // ── Animations / Feedback ─────────────────────────────────────────────────

    private fun animateShake() {
        tvDots.animate()
            .translationX(20f).setDuration(50)
            .withEndAction {
                tvDots.animate().translationX(-20f).setDuration(50)
                    .withEndAction {
                        tvDots.animate().translationX(0f).setDuration(50).start()
                    }.start()
            }.start()
    }

    private fun vibrate(pattern: VibrationPattern) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        val effect = when (pattern) {
            VibrationPattern.SUCCESS -> VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
            VibrationPattern.ERROR   -> VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1)
        }
        vibrator.vibrate(effect)
    }

    enum class VibrationPattern { SUCCESS, ERROR }
}
