// child-app/src/main/java/com/guardianlink/child/firebase/GuardianFCMService.kt
package com.guardianlink.child.firebase

import android.content.Intent
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.guardianlink.child.service.MonitoringService
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.security.SecurePreferences
import timber.log.Timber

/**
 * GuardianFCMService — receives push notifications from parent's commands.
 * Used as a wake-up signal when the device may have killed our services.
 */
class GuardianFCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Never log the token itself — it's a push-messaging credential.
        Timber.i("FCM token refreshed")

        val prefs = SecurePreferences(this)
        prefs.putString(SecurePreferences.KEY_FCM_TOKEN, token)

        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isNotBlank()) {
            // Update FCM token in Firestore
            FirebaseFirestore.getInstance()
                .collection(FirebasePaths.COLLECTION_DEVICES)
                .document(deviceId)
                .update("fcmToken", token)
                .addOnFailureListener { e -> Timber.e(e, "Failed to update FCM token") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Log only which keys were present, not their values — the data
        // payload can carry alert content and other device-specific
        // telemetry that shouldn't end up in logs.
        Timber.i("FCM message received (keys: ${message.data.keys})")

        // Wake up monitoring service
        val intent = MonitoringService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
