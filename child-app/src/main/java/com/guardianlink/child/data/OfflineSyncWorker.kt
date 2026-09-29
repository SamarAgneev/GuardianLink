package com.guardianlink.child.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class OfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val queue = OfflineQueue(applicationContext)
        queue.drain()
        return if (queue.pendingCount() == 0) Result.success() else Result.retry()
    }
}