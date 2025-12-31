package com.vettid.app.features.nats

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.storage.CredentialStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for refreshing NATS credentials via vault.
 *
 * Runs every 6 hours to check if credentials need refresh.
 * Uses vault-based `credentials.refresh` handler instead of API calls.
 *
 * Note: The vault also proactively pushes new credentials 2 hours before
 * expiry via `forApp.credentials.rotate`, so this worker serves as a
 * backup mechanism if the app was offline during the push.
 */
@HiltWorker
class NatsTokenRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val autoConnector: NatsAutoConnector,
    private val credentialStore: CredentialStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.d(TAG, "Running NATS credential refresh check")

        // Check if connected
        if (!autoConnector.isConnected()) {
            android.util.Log.d(TAG, "NATS not connected, skipping refresh")
            return Result.success()
        }

        // Check if credentials need refresh (within 2 hour buffer)
        if (!autoConnector.needsCredentialRefresh(bufferMinutes = 120)) {
            android.util.Log.d(TAG, "NATS credentials still valid, no refresh needed")
            return Result.success()
        }

        android.util.Log.i(TAG, "NATS credentials need refresh, requesting from vault")

        // Get device ID for refresh request
        val deviceId = credentialStore.getUserGuid() ?: run {
            android.util.Log.w(TAG, "No user GUID available for refresh")
            return Result.retry()
        }

        // Request refresh via vault handler
        val result = autoConnector.requestCredentialRefresh(deviceId)

        return if (result.isSuccess) {
            val refreshResult = result.getOrThrow()
            android.util.Log.i(TAG, "NATS credentials refreshed successfully, expires: ${refreshResult.expiresAt}")
            Result.success()
        } else {
            android.util.Log.e(TAG, "NATS credential refresh failed: ${result.exceptionOrNull()?.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NatsTokenRefreshWorker"
        const val WORK_NAME = "nats_token_refresh"

        /**
         * Schedule periodic credential refresh.
         *
         * @param context Application context
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NatsTokenRefreshWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            android.util.Log.i(TAG, "Scheduled NATS credential refresh worker (6-hour interval)")
        }

        /**
         * Cancel the periodic credential refresh.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            android.util.Log.i(TAG, "Cancelled NATS credential refresh worker")
        }

        /**
         * Trigger an immediate credential refresh.
         *
         * @param context Application context
         */
        fun triggerImmediateRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NatsTokenRefreshWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)

            android.util.Log.i(TAG, "Triggered immediate NATS credential refresh")
        }
    }
}

/**
 * Helper object for managing NATS credential refresh scheduling.
 *
 * Uses vault-based refresh via `credentials.refresh` handler.
 * No action tokens needed - the vault handles authentication.
 */
object NatsTokenRefreshManager {

    /**
     * Start credential refresh scheduling after successful NATS connection.
     */
    fun startRefreshSchedule(context: Context) {
        NatsTokenRefreshWorker.schedule(context)
    }

    /**
     * Stop credential refresh scheduling (e.g., on logout).
     */
    fun stopRefreshSchedule(context: Context) {
        NatsTokenRefreshWorker.cancel(context)
    }

    /**
     * Force an immediate credential refresh check.
     */
    fun forceRefresh(context: Context) {
        NatsTokenRefreshWorker.triggerImmediateRefresh(context)
    }
}
