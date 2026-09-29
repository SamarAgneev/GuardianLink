// child-app/src/main/java/com/guardianlink/child/ui/PairingActivity.kt
package com.guardianlink.child.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.guardianlink.child.R
import com.guardianlink.child.firebase.ChildFirebaseManager
import com.guardianlink.common.security.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PairingActivity : AppCompatActivity() {

    @Inject lateinit var firebaseManager: ChildFirebaseManager
    @Inject lateinit var securePrefs: SecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        val etCode    = findViewById<EditText>(R.id.et_pairing_code)
        val etName    = findViewById<EditText>(R.id.et_child_name)
        val btnPair   = findViewById<Button>(R.id.btn_pair)
        val progress  = findViewById<ProgressBar>(R.id.progress_pairing)

        btnPair.setOnClickListener {
            val code = etCode.text.toString().trim().uppercase()
            val name = etName.text.toString().trim()

            if (code.length != 6) {
                Toast.makeText(this, "Enter a valid 6-character code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.isBlank()) {
                Toast.makeText(this, "Enter child's name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progress.visibility = android.view.View.VISIBLE
            btnPair.isEnabled = false

            lifecycleScope.launch {
                val result = firebaseManager.redeemPairingCode(code, name)
                progress.visibility = android.view.View.GONE
                btnPair.isEnabled = true

                when (result) {
                    is PairingResult.Success -> {
                        securePrefs.putBoolean(SecurePreferences.KEY_PAIRING_DONE, true)
                        securePrefs.putString(SecurePreferences.KEY_CHILD_NAME, name)
                        startActivity(Intent(this@PairingActivity, MainActivity::class.java))
                        finish()
                    }
                    is PairingResult.InvalidCode ->
                        Toast.makeText(this@PairingActivity, "Invalid or expired code", Toast.LENGTH_LONG).show()
                    is PairingResult.Error ->
                        Toast.makeText(this@PairingActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

sealed class PairingResult {
    data class Success(val parentId: String) : PairingResult()
    object InvalidCode : PairingResult()
    data class Error(val message: String?) : PairingResult()
}
