package com.vettid.app.features.feed

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for periodic feed sync.
 *
 * Security:
 * - Only runs when network is available
 * - Respects battery optimization (no running when device is low battery)
 * - Uses exponential backoff on failure
 */
@HiltWorker
class FeedSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val feedRepository: FeedRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FeedSyncWorker"
        private const val WORK_NAME = "feed_sync"

        // Sync interval (15 minutes minimum for periodic work)
        private const val SYNC_INTERVAL_MINUTES = 15L

        /**
         * Schedule periodic feed sync.
         * Call this after user authentication.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<FeedSyncWorker>(
                SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

            Log.i(TAG, "Scheduled periodic feed sync every $SYNC_INTERVAL_MINUTES minutes")
        }

        /**
         * Cancel periodic feed sync.
         * Call this on user logout.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic feed sync")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting feed sync")

        return try {
            val syncResult = feedRepository.sync()

            syncResult.fold(
                onSuccess = { result ->
                    Log.i(TAG, "Feed sync success: +${result.newEvents} new, ${result.updatedEvents} updated, -${result.deletedEvents} deleted")
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Feed sync failed", error)
                    // Retry on transient failures
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Feed sync exception", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
