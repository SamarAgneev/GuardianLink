// parent-app/src/main/java/com/guardianlink/parent/ui/auth/AuthActivity.kt
package com.guardianlink.parent.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.main.MainActivity
import com.guardianlink.parent.ui.pairing.PairingActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSignIn: Button
    private lateinit var btnRegister: Button
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnResetPassword: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        setContentView(R.layout.activity_auth)
        bindViews()
        setupActions()

        val currentUser = auth.currentUser
        if (currentUser != null) {
            if (currentUser.isEmailVerified) {
                goToMain()
                return
            }
            showError("Please verify your email before continuing.")
            auth.signOut()
        }
    }

    private fun bindViews() {
        etEmail    = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        btnSignIn = findViewById(R.id.btn_sign_in)
        btnRegister = findViewById(R.id.btn_register)
        btnResetPassword = findViewById(R.id.btn_reset_password)
        tvError = findViewById(R.id.tv_auth_error)
        progress = findViewById(R.id.progress_auth)
    }

    private fun setupActions() {
        btnSignIn.setOnClickListener { signIn() }
        btnRegister.setOnClickListener { register() }
        btnResetPassword.setOnClickListener { resetPassword() }
    }

    private fun signIn() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (!validateInput(email, password)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val user = auth.signInWithEmailAndPassword(email, password).await().user
                if (user == null) {
                    showError("Unable to sign in.")
                    setLoading(false)
                    return@launch
                }
                if (!user.isEmailVerified) {
                    showError("Please verify your email before continuing.")
                    auth.signOut()
                    setLoading(false)
                    return@launch
                }
                FirebaseFirestore.getInstance().collection("users").document(user.uid)
                    .set(
                        mapOf(
                            "uid" to user.uid,
                            "email" to user.email,
                            "role" to "parent",
                            "emailVerified" to true,
                            "updatedAt" to Timestamp.now()
                        )
                    ).await()
                goToMain()
            } catch (e: Exception) {
                showError(e.message ?: "Sign in failed")
                setLoading(false)
            }
        }
    }

    private fun register() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (!validateInput(email, password)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val created = auth.createUserWithEmailAndPassword(email, password).await()
                val user = created.user ?: throw IllegalStateException("User was not created")
                FirebaseFirestore.getInstance().collection("users").document(user.uid)
                    .set(
                        mapOf(
                            "uid" to user.uid,
                            "email" to user.email,
                            "role" to "parent",
                            "emailVerified" to false,
                            "createdAt" to Timestamp.now(),
                            "updatedAt" to Timestamp.now()
                        )
                    ).await()
                user.sendEmailVerification().await()
                auth.signOut()
                showError("Verification email sent. Please verify your email and sign in.")
                setLoading(false)
            } catch (e: Exception) {
                showError(e.message ?: "Registration failed")
                setLoading(false)
            }
        }
    }

    private fun resetPassword() {
        val email = etEmail.text.toString().trim()
        if (email.isBlank() || !email.contains("@")) {
            showError("Enter a valid email before resetting your password")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                auth.sendPasswordResetEmail(email).await()
                showError("Password reset email sent.")
            } catch (e: Exception) {
                showError(e.message ?: "Password reset failed")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        return when {
            email.isBlank()         -> { showError("Enter email"); false }
            !email.contains("@")    -> { showError("Invalid email"); false }
            password.length < 6     -> { showError("Password must be at least 6 characters"); false }
            else                    -> true
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun logout() {
        auth.signOut()
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignIn.isEnabled  = !loading
        btnRegister.isEnabled = !loading
    }

    private fun showError(msg: String) {
        tvError.text       = msg
        tvError.visibility = View.VISIBLE
    }
}
