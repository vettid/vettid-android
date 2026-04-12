package com.vettid.app.core.notifications

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.feed.FeedNotificationService
import com.vettid.app.features.feed.FeedRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager fallback for feed notifications when the foreground service is killed.
 *
 * Runs every 15 minutes (Android minimum for periodic work), connects to NATS,
 * syncs feed events, and shows notifications for new items. Lightweight:
 * connect → sync → generate notifications → done.
 *
 * Skips if the foreground service is already running (it handles real-time).
 */
@HiltWorker
class FeedPollWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
    private val natsAutoConnector: NatsAutoConnector,
    private val credentialStore: CredentialStore,
    private val feedNotificationService: FeedNotificationService
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "FeedPollWorker"
        private const val WORK_NAME = "vettid_feed_poll"

        /**
         * Schedule the periodic feed poll.
         * Uses KEEP policy — won't restart if already scheduled.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<FeedPollWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Feed poll worker scheduled (every 15 min)")
        }

        /**
         * Cancel the periodic feed poll.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Feed poll worker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        // Skip if user isn't enrolled
        if (!credentialStore.hasStoredCredential()) {
            Log.d(TAG, "Not enrolled — skipping poll")
            return Result.success()
        }

        Log.d(TAG, "Starting feed poll")

        try {
            // Connect to NATS if not already connected
            val wasConnected = natsAutoConnector.isConnected()
            if (!wasConnected) {
                val connectResult = natsAutoConnector.autoConnect(autoStartVault = false)
                if (connectResult !is NatsAutoConnector.ConnectionResult.Success) {
                    Log.w(TAG, "Failed to connect: $connectResult")
                    return Result.retry()
                }
            }

            // Ensure feed notification service is listening
            feedNotificationService.startListening()

            // Sync feed — this triggers notifications for new events via FeedNotificationService
            feedRepository.sync()
                .onSuccess { result ->
                    Log.i(TAG, "Feed poll sync: ${result.newEvents} new, ${result.updatedEvents} updated")
                }
                .onFailure { error ->
                    Log.w(TAG, "Feed poll sync failed: ${error.message}")
                }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Feed poll error", e)
            return Result.retry()
        }
    }
}
