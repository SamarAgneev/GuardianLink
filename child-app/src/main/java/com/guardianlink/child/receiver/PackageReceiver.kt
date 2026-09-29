// child-app/src/main/java/com/guardianlink/child/receiver/PackageReceiver.kt
package com.guardianlink.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.guardianlink.child.admin.GuardianDeviceAdminReceiver
import com.guardianlink.child.data.OfflineQueue
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.firebase.createDocumentIfAbsent
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.security.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class PackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return

        // Ignore self updates
        if (packageName == context.packageName) return

        val prefs = SecurePreferences(context)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) return

        val appName = try {
            context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
                .toString()
        } catch (e: Exception) { packageName }

        Timber.i("New app installed: $appName ($packageName)")

        val alertId = com.guardianlink.common.util.CommunicationUtils.stableRecordId(
            "new_app", deviceId, packageName, intent.getLongExtra(Intent.EXTRA_TIME, System.currentTimeMillis()).toString()
        )
        val alert = mapOf(
                "deviceId"   to deviceId,
                "type"       to AlertType.NEW_APP_INSTALLED.name,
                "severity"   to "MEDIUM",
                "title"      to "New App Installed",
                "message"    to "\"$appName\" was installed on the device",
                "metadata"   to mapOf("packageName" to packageName, "appName" to appName),
                "isRead"     to false,
                "isResolved" to false,
                "timestamp"  to Timestamp.now()
            )
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseFirestore.getInstance()
                    .collection(FirebasePaths.COLLECTION_ALERTS)
                    .document(alertId)
                    .createDocumentIfAbsent(alert)
            } catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                    OfflineQueue(context).enqueueFirestoreCreate(
                        FirebasePaths.COLLECTION_ALERTS,
                        alertId,
                        alert,
                        OfflineQueue.PRIORITY_ALERT
                    )
                }
                Timber.e(e, "Failed to alert new install; queued for retry")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
