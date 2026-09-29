// child-app/src/main/java/com/guardianlink/child/admin/GuardianDeviceAdminReceiver.kt
package com.guardianlink.child.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.security.SecurePreferences
import timber.log.Timber

/**
 * Device admin lifecycle callback.
 *
 * This receiver only reports legitimate Android admin state transitions. It does not hide or
 * override any user or system control. If a user disables admin in Android Settings, the app
 * must report that the protection state is DISABLED rather than claim that tamper prevention is
 * still active.
 */
class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Timber.i("Device admin enabled")
        val prefs = SecurePreferences(context)
        prefs.putBoolean(SecurePreferences.KEY_ADMIN_ENABLED, true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.w("Device admin DISABLED — protection state is now DISABLED")

        val prefs = SecurePreferences(context)
        prefs.putBoolean(SecurePreferences.KEY_ADMIN_ENABLED, false)

        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isNotBlank()) {
            pushTamperAlert(
                deviceId = deviceId,
                type = AlertType.ADMIN_DISABLED,
                title = "⚠ Device Admin Disabled",
                message = "GuardianLink device-admin protection was disabled through Android controls. Protection state is now DISABLED."
            )
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Timber.w("Device password failed")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val prefs = SecurePreferences(context)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isNotBlank()) {
            pushTamperAlert(
                deviceId = deviceId,
                type = AlertType.TAMPER_DETECTED,
                title = "⚠ Disable Admin Attempt",
                message = "A user or system action requested disabling device admin. Protection state is now DEGRADED until resolved."
            )
        }
        return "Disabling GuardianLink admin will stop monitoring. Android policy state will be reported as DISABLED."
    }

    private fun pushTamperAlert(deviceId: String, type: AlertType, title: String, message: String) {
        FirebaseFirestore.getInstance()
            .collection(FirebasePaths.COLLECTION_ALERTS)
            .add(
                mapOf(
                    "deviceId"  to deviceId,
                    "type"      to type.name,
                    "severity"  to "CRITICAL",
                    "title"     to title,
                    "message"   to message,
                    "isRead"    to false,
                    "isResolved" to false,
                    "timestamp" to Timestamp.now()
                )
            )
            .addOnFailureListener { e -> Timber.e(e, "Failed to push security alert") }
    }
}
