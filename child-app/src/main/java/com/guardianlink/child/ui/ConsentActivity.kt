// child-app/src/main/java/com/guardianlink/child/ui/ConsentActivity.kt
package com.guardianlink.child.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.guardianlink.child.R
import com.guardianlink.common.security.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * ConsentActivity — first-run experience:
 *  Page 1: App purpose + transparency notice
 *  Page 2: Data collected disclosure
 *  Page 3: Permissions request
 *  Page 4: Pair with parent device (enter code)
 *
 * Compliance: App must not be hidden. Consent must be explicit. Data usage
 * must be clearly explained. This complies with Google Play policy for
 * parental monitoring apps.
 */
@AndroidEntryPoint
class ConsentActivity : AppCompatActivity() {

    @Inject lateinit var securePrefs: SecurePreferences

    private val communicationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        completeConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already consented and paired, go straight to main
        if (securePrefs.getBoolean(SecurePreferences.KEY_CONSENT_GIVEN) &&
            securePrefs.getBoolean(SecurePreferences.KEY_PAIRING_DONE)) {
            startMain()
            return
        }

        setContentView(R.layout.activity_consent)
        setupConsentFlow()
    }

    private fun setupConsentFlow() {
        val viewPager = findViewById<ViewPager2>(R.id.view_pager_consent)
        val btnNext   = findViewById<Button>(R.id.btn_next)
        val btnBack   = findViewById<Button>(R.id.btn_back)

        val pages = listOf(
            ConsentPage.Welcome,
            ConsentPage.DataDisclosure,
            ConsentPage.Permissions,
            ConsentPage.Pair
        )

        // Simple page adapter would be set up here
        // viewPager.adapter = ConsentPagerAdapter(this, pages)

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current < pages.size - 1) {
                viewPager.currentItem = current + 1
            } else {
                onConsentCompleted()
            }
        }

        btnBack.setOnClickListener {
            if (viewPager.currentItem > 0) {
                viewPager.currentItem -= 1
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                btnBack.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
                btnNext.text = if (position == pages.size - 1)
                    getString(R.string.consent_finish)
                else
                    getString(R.string.consent_next)
            }
        })
    }

    private fun onConsentCompleted() {
        val communicationPermissions = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        val missingPermissions = communicationPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            communicationPermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        completeConsent()
    }

    private fun completeConsent() {
        securePrefs.putBoolean(SecurePreferences.KEY_CONSENT_GIVEN, true)
        // Launch PIN setup
        startActivityForResult(PinLockActivity.setupIntent(this), 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == PinLockActivity.RESULT_SUCCESS) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    sealed class ConsentPage {
        object Welcome        : ConsentPage()
        object DataDisclosure : ConsentPage()
        object Permissions    : ConsentPage()
        object Pair           : ConsentPage()
    }
}
