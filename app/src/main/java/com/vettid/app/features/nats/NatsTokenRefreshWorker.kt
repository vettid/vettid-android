package com.vettid.app.features.nats

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NatsConnectionState
import com.vettid.app.core.storage.CredentialStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for refreshing NATS tokens.
 *
 * Runs every 6 hours to check if token needs refresh.
 * If connected and token is about to expire (< 1 hour remaining),
 * requests a new token and reconnects.
 */
@HiltWorker
class NatsTokenRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val connectionManager: NatsConnectionManager,
    private val credentialStore: CredentialStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.d(TAG, "Running NATS token refresh check")

        // Check if we have a stored action token for API calls
        val actionToken = inputData.getString(KEY_ACTION_TOKEN)

        // Check connection state
        val connectionState = connectionManager.connectionState.value

        when (connectionState) {
            is NatsConnectionState.Connected -> {
                val credentials = connectionState.credentials

                // Check if token needs refresh (expires in < 1 hour)
                if (credentials.needsRefresh(bufferMinutes = 60)) {
                    android.util.Log.i(TAG, "NATS token needs refresh, expires at ${credentials.expiresAt}")

                    if (actionToken.isNullOrEmpty()) {
                        android.util.Log.w(TAG, "No action token available for refresh")
                        return Result.retry()
                    }

                    val result = connectionManager.refreshTokenIfNeeded(actionToken)
                    return if (result.isSuccess) {
                        android.util.Log.i(TAG, "NATS token refreshed successfully")
                        Result.success()
                    } else {
                        android.util.Log.e(TAG, "NATS token refresh failed: ${result.exceptionOrNull()?.message}")
                        Result.retry()
                    }
                } else {
                    android.util.Log.d(TAG, "NATS token still valid, expires at ${credentials.expiresAt}")
                    return Result.success()
                }
            }
            is NatsConnectionState.Disconnected,
            is NatsConnectionState.Error -> {
                android.util.Log.d(TAG, "NATS not connected, skipping refresh")
                return Result.success()
            }
            else -> {
                android.util.Log.d(TAG, "NATS in transitional state: $connectionState")
                return Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "NatsTokenRefreshWorker"
        const val WORK_NAME = "nats_token_refresh"
        private const val KEY_ACTION_TOKEN = "action_token"

        /**
         * Schedule periodic token refresh.
         *
         * @param context Application context
         * @param actionToken Optional action token for API authentication
         */
        fun schedule(context: Context, actionToken: String? = null) {
            val inputData = workDataOf(
                KEY_ACTION_TOKEN to actionToken
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NatsTokenRefreshWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setInputData(inputData)
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

            android.util.Log.i(TAG, "Scheduled NATS token refresh worker (6-hour interval)")
        }

        /**
         * Cancel the periodic token refresh.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            android.util.Log.i(TAG, "Cancelled NATS token refresh worker")
        }

        /**
         * Trigger an immediate token refresh.
         *
         * @param context Application context
         * @param actionToken Action token for API authentication
         */
        fun triggerImmediateRefresh(context: Context, actionToken: String) {
            val inputData = workDataOf(
                KEY_ACTION_TOKEN to actionToken
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NatsTokenRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(request)

            android.util.Log.i(TAG, "Triggered immediate NATS token refresh")
        }

        /**
         * Update the action token for the scheduled worker.
         *
         * @param context Application context
         * @param actionToken New action token
         */
        fun updateActionToken(context: Context, actionToken: String) {
            // Re-schedule with new token
            schedule(context, actionToken)
        }
    }
}

/**
 * Helper object for managing NATS token refresh scheduling.
 */
object NatsTokenRefreshManager {

    /**
     * Start token refresh scheduling after successful authentication.
     */
    fun startRefreshSchedule(context: Context, actionToken: String) {
        NatsTokenRefreshWorker.schedule(context, actionToken)
    }

    /**
     * Stop token refresh scheduling (e.g., on logout).
     */
    fun stopRefreshSchedule(context: Context) {
        NatsTokenRefreshWorker.cancel(context)
    }

    /**
     * Update the action token when it's refreshed during authentication.
     */
    fun updateToken(context: Context, actionToken: String) {
        NatsTokenRefreshWorker.updateActionToken(context, actionToken)
    }

    /**
     * Force an immediate refresh check.
     */
    fun forceRefresh(context: Context, actionToken: String) {
        NatsTokenRefreshWorker.triggerImmediateRefresh(context, actionToken)
    }
}
