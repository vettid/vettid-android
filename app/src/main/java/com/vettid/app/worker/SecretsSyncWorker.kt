package com.vettid.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.storage.MinorSecretsStore
import com.vettid.app.core.storage.SyncStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for syncing minor secrets to the vault.
 *
 * This worker handles background sync of minor secrets via the
 * secrets.datastore.* NATS topics.
 *
 * Note: Critical secrets are NOT synced by this worker - they are
 * stored directly in the Protean Credential via credential.secret.add.
 */
@HiltWorker
class SecretsSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val minorSecretsStore: MinorSecretsStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SecretsSyncWorker"
        private const val WORK_NAME = "secrets_sync"
        private const val WORK_NAME_IMMEDIATE = "secrets_sync_immediate"

        /**
         * Schedule an immediate one-time sync.
         */
        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SecretsSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME_IMMEDIATE)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_IMMEDIATE,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            Log.i(TAG, "Scheduled immediate secrets sync")
        }

        /**
         * Schedule periodic sync.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SecretsSyncWorker>(
                6, TimeUnit.HOURS  // Sync every 6 hours
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

            Log.i(TAG, "Scheduled periodic secrets sync")
        }

        /**
         * Cancel all secrets sync work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
            Log.i(TAG, "Cancelled secrets sync work")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting secrets sync work")

        // Check if there are pending changes
        if (!minorSecretsStore.hasPendingSync()) {
            Log.i(TAG, "No pending secrets to sync")
            return Result.success()
        }

        // Check NATS connection
        if (!connectionManager.isConnected()) {
            Log.w(TAG, "NATS not connected, will retry")
            return Result.retry()
        }

        return try {
            val pendingSecrets = minorSecretsStore.exportPendingForSync()

            if (pendingSecrets.isEmpty()) {
                Log.i(TAG, "No pending secrets after filtering")
                minorSecretsStore.markSyncComplete()
                return Result.success()
            }

            var successCount = 0
            var failCount = 0

            for (secretData in pendingSecrets) {
                val secretId = secretData["id"] as String

                try {
                    val payload = buildSecretPayload(secretData)
                    val result = ownerSpaceClient.sendToVault("secrets.datastore.add", payload)

                    result.fold(
                        onSuccess = {
                            Log.d(TAG, "Synced secret: $secretId")
                            minorSecretsStore.updateSyncStatus(secretId, SyncStatus.SYNCED)
                            successCount++
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to sync secret $secretId: ${error.message}")
                            minorSecretsStore.updateSyncStatus(secretId, SyncStatus.ERROR)
                            failCount++
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing secret $secretId", e)
                    minorSecretsStore.updateSyncStatus(secretId, SyncStatus.ERROR)
                    failCount++
                }
            }

            Log.i(TAG, "Sync complete: $successCount succeeded, $failCount failed")

            if (failCount == 0) {
                minorSecretsStore.markSyncComplete()
                Result.success()
            } else if (successCount > 0) {
                // Partial success - retry for the failures
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.success()  // Don't keep retrying indefinitely
                }
            } else {
                // All failed
                if (runAttemptCount < 5) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Secrets sync error", e)
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun buildSecretPayload(data: Map<String, Any?>): JsonObject {
        return JsonObject().apply {
            data.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    null -> {} // Skip null values
                    else -> addProperty(key, value.toString())
                }
            }
        }
    }
}
