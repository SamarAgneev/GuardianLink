// parent-app/src/main/java/com/guardianlink/parent/firebase/ParentFCMService.kt
package com.guardianlink.parent.firebase

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.main.MainActivity
import timber.log.Timber

/**
 * ParentFCMService — delivers real-time push alerts to the parent device.
 *
 * Firebase Cloud Functions (not included here) would trigger these pushes
 * when new alerts are written to Firestore by the child device.
 */
class ParentFCMService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Log only which keys were present, not their values — this data
        // payload carries alert title/body content, which shouldn't end up
        // in logs.
        Timber.i("FCM message received (keys: ${message.data.keys})")

        val title    = message.data["title"]    ?: message.notification?.title ?: "GuardianLink Alert"
        val body     = message.data["body"]     ?: message.notification?.body  ?: ""
        val severity = message.data["severity"] ?: "MEDIUM"

        showNotification(title, body, severity)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Never log the token itself — it's a push-messaging credential.
        Timber.i("Parent FCM token refreshed")
        // Store token in Firestore for server-side targeting
        // FirebaseFirestore...
    }

    private fun showNotification(title: String, body: String, severity: String) {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = when (severity) {
            "CRITICAL" -> R.drawable.ic_alert_critical
            "HIGH"     -> R.drawable.ic_alert_high
            else       -> R.drawable.ic_alert
        }

        val priority = when (severity) {
            "CRITICAL" -> NotificationCompat.PRIORITY_MAX
            "HIGH"     -> NotificationCompat.PRIORITY_HIGH
            else       -> NotificationCompat.PRIORITY_DEFAULT
        }

        val notification = NotificationCompat.Builder(this, NotificationChannels.ALERT_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(iconRes)
            .setContentIntent(tapIntent)
            .setPriority(priority)
            .setAutoCancel(true)
            .build()

        val notifId = System.currentTimeMillis().toInt()
        try {
            NotificationManagerCompat.from(this).notify(notifId, notification)
        } catch (e: SecurityException) {
            Timber.e(e, "Notification permission not granted")
        }
    }
}
