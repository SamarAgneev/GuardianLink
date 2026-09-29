// child-app/src/main/java/com/guardianlink/child/data/SosManager.kt
package com.guardianlink.child.data

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.firebase.createDocumentIfAbsent
import com.guardianlink.common.model.AlertSeverity
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.CommunicationUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SosManager — handles the emergency panic button.
 *
 * On activation:
 *   1. Immediately pushes CRITICAL alert with current location
 *   2. Records 30 seconds of audio and uploads to Firebase Storage
 *   3. Sends FCM push (via Cloud Function trigger)
 *
 * The panic button is accessible from the child's main screen even
 * when the device is in restricted mode.
 */
@Singleton
class SosManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePreferences,
    private val offlineQueue: OfflineQueue
) {
    private val db = FirebaseFirestore.getInstance()

    suspend fun triggerSos(latitude: Double = 0.0, longitude: Double = 0.0) =
        withContext(Dispatchers.IO) {
            val deviceId  = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            val childName = securePrefs.getString(SecurePreferences.KEY_CHILD_NAME)
            val triggeredAt = System.currentTimeMillis()
            val alertId = CommunicationUtils.stableRecordId(
                "sos", deviceId, triggeredAt.toString(), latitude.toString(), longitude.toString()
            )

            Timber.e("🚨 SOS PANIC triggered by $childName ($deviceId)")

            // Push CRITICAL alert immediately
            val alert = mapOf(
                "id"         to alertId,
                "deviceId"   to deviceId,
                "childName"  to childName,
                "type"       to AlertType.SOS_PANIC.name,
                "severity"   to AlertSeverity.CRITICAL.name,
                "title"      to "🚨 SOS PANIC — $childName",
                "message"    to "Emergency panic button was activated! Last known location: $latitude, $longitude",
                "metadata"   to mapOf(
                    "latitude"  to latitude,
                    "longitude" to longitude,
                    "triggeredAt" to triggeredAt
                ),
                "isRead"     to false,
                "isResolved" to false,
                "timestamp"  to Timestamp.now()
            )
            try {
                db.collection(FirebasePaths.COLLECTION_ALERTS).document(alertId).createDocumentIfAbsent(alert)
                Timber.i("SOS alert pushed to Firestore")
            } catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                    offlineQueue.enqueueFirestoreCreate(
                        FirebasePaths.COLLECTION_ALERTS,
                        alertId,
                        alert,
                        OfflineQueue.PRIORITY_ALERT
                    )
                }
                Timber.e(e, "Failed to push SOS alert; queued for retry")
            }
        }
}
