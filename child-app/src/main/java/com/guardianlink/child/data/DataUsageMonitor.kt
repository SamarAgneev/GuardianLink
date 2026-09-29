// child-app/src/main/java/com/guardianlink/child/data/DataUsageMonitor.kt
package com.guardianlink.child.data

import android.annotation.SuppressLint
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.firebase.createDocumentIfAbsent
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.CommunicationUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataUsageMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePreferences,
    private val offlineQueue: OfflineQueue
) {
    private val db = FirebaseFirestore.getInstance()
    private val dataAlertThresholdBytes = 500L * 1024 * 1024

    @SuppressLint("MissingPermission")
    suspend fun checkAndReportMobileDataUsage() = withContext(Dispatchers.IO) {
        try {
            val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return@withContext
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return@withContext
            val subscriberId = try { tm.subscriberId } catch (_: Exception) { "" }
            val endTime = System.currentTimeMillis()
            val startTime = getTodayStartMs()
            val bucket = NetworkStats.Bucket()
            val stats = nsm.querySummary(
                ConnectivityManager.TYPE_MOBILE,
                subscriberId,
                startTime,
                endTime
            )

            var totalBytes = 0L
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                totalBytes += bucket.rxBytes + bucket.txBytes
            }

            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            if (deviceId.isBlank()) return@withContext
            val date = getTodayString()
            val mbUsed = totalBytes / 1024 / 1024
            val usageData = mapOf(
                "bytesUsed" to totalBytes,
                "mbUsed" to mbUsed,
                "date" to date,
                "updatedAt" to Timestamp.now()
            )

            try {
                db.collection(FirebasePaths.COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection("data_usage")
                    .document(date)
                    .set(usageData)
                    .await()
            } catch (e: Exception) {
                offlineQueue.enqueueFirestoreCreate(
                    "${FirebasePaths.COLLECTION_DEVICES}/$deviceId/data_usage",
                    date,
                    usageData,
                    OfflineQueue.PRIORITY_LOG
                )
            }

            if (totalBytes > dataAlertThresholdBytes) {
                val alertId = CommunicationUtils.stableRecordId("data_usage", deviceId, date)
                val alert = mapOf(
                    "deviceId" to deviceId,
                    "type" to "DATA_USAGE_HIGH",
                    "severity" to "LOW",
                    "title" to "High Data Usage",
                    "message" to "Device has used ${mbUsed}MB of mobile data today",
                    "isRead" to false,
                    "isResolved" to false,
                    "timestamp" to Timestamp.now()
                )
                try {
                    db.collection(FirebasePaths.COLLECTION_ALERTS).document(alertId).createDocumentIfAbsent(alert)
                } catch (e: FirebaseFirestoreException) {
                    if (e.code != FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                        offlineQueue.enqueueFirestoreCreate(
                            FirebasePaths.COLLECTION_ALERTS,
                            alertId,
                            alert,
                            OfflineQueue.PRIORITY_ALERT
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to check data usage")
        }
    }

    private fun getTodayStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getTodayString(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}
