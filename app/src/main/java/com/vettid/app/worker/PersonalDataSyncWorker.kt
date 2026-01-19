package com.vettid.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.storage.PersonalDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for syncing personal data to the vault.
 *
 * This worker handles background sync of personal data changes to the vault
 * via the profile.update NATS topic. It supports:
 * - Immediate one-time sync when changes are made
 * - Retry with exponential backoff on failure
 * - Network connectivity constraints
 */
@HiltWorker
class PersonalDataSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val personalDataStore: PersonalDataStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PersonalDataSyncWorker"
        private const val WORK_NAME = "personal_data_sync"
        private const val WORK_NAME_IMMEDIATE = "personal_data_sync_immediate"

        /**
         * Schedule an immediate one-time sync.
         * Use this when the user makes changes to personal data.
         */
        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<PersonalDataSyncWorker>()
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

            Log.i(TAG, "Scheduled immediate personal data sync")
        }

        /**
         * Schedule periodic sync (e.g., daily).
         * This ensures data stays in sync even if immediate syncs fail.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<PersonalDataSyncWorker>(
                24, TimeUnit.HOURS
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

            Log.i(TAG, "Scheduled periodic personal data sync")
        }

        /**
         * Cancel all personal data sync work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
            Log.i(TAG, "Cancelled personal data sync work")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting personal data sync work")

        // Check if there are pending changes
        if (!personalDataStore.hasPendingSync()) {
            Log.i(TAG, "No pending changes to sync")
            return Result.success()
        }

        // Check NATS connection
        if (!connectionManager.isConnected()) {
            Log.w(TAG, "NATS not connected, will retry")
            return Result.retry()
        }

        return try {
            val data = personalDataStore.exportForSync()

            // Build payload for profile.update
            val payload = buildPayload(data)

            val result = ownerSpaceClient.sendToVault("profile.update", payload)

            result.fold(
                onSuccess = { requestId ->
                    Log.i(TAG, "Personal data sync request sent: $requestId")
                    // Mark sync complete
                    personalDataStore.markSyncComplete()
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Personal data sync failed: ${error.message}")
                    if (runAttemptCount < 5) {
                        Result.retry()
                    } else {
                        // After 5 retries, give up but keep pending flag
                        // so it will be retried on next periodic sync
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Personal data sync error", e)
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun buildPayload(data: Map<String, Any?>): JsonObject {
        return JsonObject().apply {
            data.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is List<*> -> {
                        val jsonArray = JsonArray()
                        @Suppress("UNCHECKED_CAST")
                        (value as? List<Map<String, Any?>>)?.forEach { item ->
                            val jsonObj = JsonObject()
                            item.forEach { (k, v) ->
                                when (v) {
                                    is String -> jsonObj.addProperty(k, v)
                                    is Number -> jsonObj.addProperty(k, v)
                                    is Boolean -> jsonObj.addProperty(k, v)
                                    else -> jsonObj.addProperty(k, v?.toString())
                                }
                            }
                            jsonArray.add(jsonObj)
                        }
                        add(key, jsonArray)
                    }
                    null -> {} // Skip null values
                    else -> addProperty(key, value.toString())
                }
            }
        }
    }
}
