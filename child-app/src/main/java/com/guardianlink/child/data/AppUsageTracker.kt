// child-app/src/main/java/com/guardianlink/child/data/AppUsageTracker.kt
package com.guardianlink.child.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.guardianlink.common.constants.FirebasePaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUsageTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val db = FirebaseFirestore.getInstance()
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // In-memory session tracker
    private val activeSessions = mutableMapOf<String, Long>() // pkg -> start time ms

    /**
     * Continuous loop that polls UsageEvents every 60 seconds to detect
     * app foreground/background transitions, accumulating session durations.
     */
    fun hasUsageAccess(): Boolean {
        return context.packageManager.checkPermission(
            android.Manifest.permission.PACKAGE_USAGE_STATS,
            context.packageName
        ) == PackageManager.PERMISSION_GRANTED && usm != null
    }

    fun getInstalledPackages(): List<String> =
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .mapNotNull { it.packageName.takeIf { pkg -> pkg.isNotBlank() } }
            .sorted()

    fun getForegroundAppPackageName(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 30_000L
        val events = usm?.queryEvents(startTime, endTime) ?: return null
        val event = UsageEvents.Event()
        var currentForeground: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> currentForeground = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (currentForeground == event.packageName) currentForeground = null
                }
            }
        }

        return currentForeground
    }

    fun getPackageUsageMs(packageName: String, startMs: Long = getTodayStartMs()): Long {
        if (packageName.isBlank()) return 0L
        val endTime = System.currentTimeMillis()
        return usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endTime)
            ?.firstOrNull { it.packageName == packageName }
            ?.totalTimeInForeground
            ?: 0L
    }

    fun getDailyUsageSummary(): Map<String, Long> {
        val startTime = getTodayStartMs()
        val endTime = System.currentTimeMillis()
        return usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            ?.filter { !it.packageName.isNullOrBlank() && it.totalTimeInForeground > 0L }
            ?.associate { it.packageName to it.totalTimeInForeground }
            ?: emptyMap()
    }

    suspend fun startTracking() = withContext(Dispatchers.IO) {
        Timber.i("AppUsageTracker started")
        while (isActive) {
            try {
                processUsageEvents()
            } catch (e: Exception) {
                Timber.e(e, "Usage tracking error")
            }
            delay(60_000) // Poll every minute
        }
    }

    private fun processUsageEvents() {
        val endTime   = System.currentTimeMillis()
        val startTime = endTime - 70_000L // Slightly wider than our interval

        val events = usm?.queryEvents(startTime, endTime) ?: return
        val event  = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    activeSessions[pkg] = event.timeStamp
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val sessionStart = activeSessions.remove(pkg) ?: continue
                    val duration     = event.timeStamp - sessionStart
                    if (duration > 1000L) { // Ignore sub-second transitions
                        recordSession(pkg, sessionStart, event.timeStamp, duration)
                    }
                }
            }
        }
    }

    private fun recordSession(
        packageName: String, startMs: Long, endMs: Long, durationMs: Long
    ) {
        val appName = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) { packageName }

        val today = dateFormatter.format(Date(startMs))

        db.collection(FirebasePaths.COLLECTION_APP_USAGE)
            .add(mapOf(
                "packageName"     to packageName,
                "appName"         to appName,
                "startTime"       to Timestamp(Date(startMs)),
                "endTime"         to Timestamp(Date(endMs)),
                "durationMs"      to durationMs,
                "date"            to today,
                "timestamp"       to Timestamp.now()
            ))
            .addOnFailureListener { e -> Timber.e(e, "Failed to log app session") }
    }

    /**
     * Full daily usage sync to Firebase — called on-demand by parent command.
     */
    suspend fun syncToFirebase(deviceId: String) = withContext(Dispatchers.IO) {
        val endTime   = System.currentTimeMillis()
        val startTime = getTodayStartMs()

        val stats = usm?.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        ) ?: return@withContext

        val usageList = stats
            .filter {
                it.totalTimeInForeground > 0 &&
                    !it.packageName.isNullOrBlank() &&
                    it.packageName != context.packageName &&
                    !it.packageName.startsWith("android") &&
                    !it.packageName.contains("launcher")
            }
            .sortedByDescending { it.totalTimeInForeground }
            .take(30)
            .map { stat ->
                val appName = try {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(stat.packageName, 0)
                    ).toString()
                } catch (e: Exception) { stat.packageName }

                mapOf(
                    "deviceId"          to deviceId,
                    "packageName"       to stat.packageName,
                    "appName"           to appName,
                    "foregroundTimeMs"  to stat.totalTimeInForeground,
                    "lastUsed"          to Timestamp(Date(stat.lastTimeUsed)),
                    "date"              to dateFormatter.format(Date())
                )
            }

        // Write as a daily summary
        db.collection(FirebasePaths.COLLECTION_DEVICES)
            .document(deviceId)
            .collection("usage_summaries")
            .document(dateFormatter.format(Date()))
            .set(mapOf("deviceId" to deviceId, "apps" to usageList, "syncedAt" to Timestamp.now()))
            .await()

        Timber.i("App usage synced: ${usageList.size} apps")
    }

    private fun getTodayStartMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
