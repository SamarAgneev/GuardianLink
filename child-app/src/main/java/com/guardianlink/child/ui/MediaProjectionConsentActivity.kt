// child-app/src/main/java/com/guardianlink/child/ui/MediaProjectionConsentActivity.kt
package com.guardianlink.child.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardianlink.child.service.ScreenMirrorService

/**
 * Presents Android's system-owned MediaProjection consent dialog.
 * The result data is forwarded once to ScreenMirrorService and never stored.
 */
class MediaProjectionConsentActivity : AppCompatActivity() {

    companion object {
        private const val STATE_SESSION_ID = "screen_mirror_session_id"
        private const val STATE_REQUESTED = "screen_mirror_consent_requested"
    }

    private var pendingSessionId = ""
    private var consentRequested = false

    private val projectionConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultData = result.data
            if (result.resultCode == Activity.RESULT_OK && resultData != null) {
                val serviceIntent = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_START
                    putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, resultData)
                    putExtra(ScreenMirrorService.EXTRA_SESSION_ID, pendingSessionId)
                }

                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                reportConsentFailure(
                    if (result.resultCode == Activity.RESULT_CANCELED) {
                        "User cancelled MediaProjection consent"
                    } else {
                        "MediaProjection consent was denied"
                    }
                )
            }

            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingSessionId = savedInstanceState?.getString(STATE_SESSION_ID)
            ?: intent.getStringExtra(ScreenMirrorService.EXTRA_SESSION_ID).orEmpty()
        consentRequested = savedInstanceState?.getBoolean(STATE_REQUESTED) == true

        if (consentRequested) {
            reportConsentFailure("MediaProjection consent was interrupted by process recreation")
            finish()
            return
        }

        val projectionManager =
            getSystemService(MediaProjectionManager::class.java)
                ?: run {
                    reportConsentFailure("MediaProjection is unavailable on this device")
                    finish()
                    return
                }

        consentRequested = true
        projectionConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SESSION_ID, pendingSessionId)
        outState.putBoolean(STATE_REQUESTED, consentRequested)
        super.onSaveInstanceState(outState)
    }

    private fun reportConsentFailure(reason: String) {
        ContextCompat.startForegroundService(this, Intent(this, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_CONSENT_FAILED
            putExtra(ScreenMirrorService.EXTRA_SESSION_ID, pendingSessionId)
            putExtra(ScreenMirrorService.EXTRA_FAILURE_REASON, reason)
        })
    }
}
