// parent-app/src/main/java/com/guardianlink/parent/ui/pairing/PairingActivity.kt
package com.guardianlink.parent.ui.pairing

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import com.guardianlink.parent.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PairingActivity : AppCompatActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var tvCode: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var btnGenerate: Button
    private lateinit var btnGoToDashboard: Button
    private lateinit var progress: ProgressBar
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_parent)

        tvCode          = findViewById(R.id.tv_pairing_code)
        tvTimer         = findViewById(R.id.tv_code_timer)
        tvInstruction   = findViewById(R.id.tv_pairing_instruction)
        btnGenerate     = findViewById(R.id.btn_generate_code)
        btnGoToDashboard = findViewById(R.id.btn_go_dashboard)
        progress        = findViewById(R.id.progress_pairing)

        btnGenerate.setOnClickListener { generateCode() }

        btnGoToDashboard.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Listen for pairing code from ViewModel
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pairingCode.collect { code ->
                    if (code.isNotBlank()) {
                        displayCode(code)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }

    private fun generateCode() {
        progress.visibility = View.VISIBLE
        btnGenerate.isEnabled = false
        viewModel.generatePairingCode()
    }

    private fun displayCode(code: String) {
        progress.visibility = View.GONE
        btnGenerate.isEnabled = true

        // Format as XXX-XXX for readability
        val formatted = if (code.length == 6) "${code.substring(0,3)}-${code.substring(3)}" else code
        tvCode.text = formatted
        tvCode.visibility = View.VISIBLE

        tvInstruction.text = "Enter this code in the GuardianLink Child App on your child's device"

        // 10-minute countdown
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(10 * 60 * 1000L, 1000L) {
            override fun onTick(remainingMs: Long) {
                val mins = remainingMs / 1000 / 60
                val secs = (remainingMs / 1000) % 60
                tvTimer.text = "Expires in %02d:%02d".format(mins, secs)
            }
            override fun onFinish() {
                tvCode.text = "Code expired"
                tvTimer.text = "Generate a new code"
            }
        }.start()
    }
}
