// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import android.content.Context
import androidx.work.*
import dev.bilidesk.android.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** A connected, unique one-shot task retains an unfinished upload across normal process exit. No credentials in WorkData. */
class SyncUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        try { if (Graph.musicSync.flushBackground()) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        private const val NAME = "bilidesk-encrypted-sync-upload"
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
