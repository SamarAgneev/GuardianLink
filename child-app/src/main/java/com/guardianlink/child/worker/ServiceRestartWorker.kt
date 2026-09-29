// child-app/src/main/java/com/guardianlink/child/worker/ServiceRestartWorker.kt
package com.guardianlink.child.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.guardianlink.child.service.MonitoringService
import com.guardianlink.common.security.SecurePreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * ServiceRestartWorker — a WorkManager fallback that ensures MonitoringService
 * is running. WorkManager is more resilient to OEM battery killers than plain
 * Services, making this an important defense-in-depth layer.
 */
@HiltWorker
class ServiceRestartWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = SecurePreferences(applicationContext)
        val isPaired   = prefs.getBoolean(SecurePreferences.KEY_PAIRING_DONE)
        val isConsented = prefs.getBoolean(SecurePreferences.KEY_CONSENT_GIVEN)

        if (!isPaired || !isConsented) {
            Timber.d("Worker: device not paired — skipping restart")
            return Result.success()
        }

        Timber.i("ServiceRestartWorker: starting MonitoringService")
        val intent = MonitoringService.startIntent(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }

        // Schedule periodic keep-alive
        schedulePeriodicWork(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_TAG = "guardian_periodic_keepalive"

        fun schedulePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(PERIODIC_WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
