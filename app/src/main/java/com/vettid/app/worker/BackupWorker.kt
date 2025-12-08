package com.vettid.app.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vettid.app.core.network.BackupApiClient
import com.vettid.app.core.network.BackupFrequency
import com.vettid.app.core.network.BackupSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for scheduled automatic backups.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val backupApiClient: BackupApiClient
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BackupWorker"
        private const val WORK_NAME = "auto_backup"
        private const val KEY_INCLUDE_MESSAGES = "include_messages"
        private const val KEY_WIFI_ONLY = "wifi_only"

        /**
         * Schedule periodic backup based on settings.
         */
        fun schedule(context: Context, settings: BackupSettings) {
            if (!settings.autoBackupEnabled) {
                cancel(context)
                return
            }

            val intervalHours = when (settings.backupFrequency) {
                BackupFrequency.DAILY -> 24L
                BackupFrequency.WEEKLY -> 24L * 7
                BackupFrequency.MONTHLY -> 24L * 30
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = workDataOf(
                KEY_INCLUDE_MESSAGES to settings.includeMessages,
                KEY_WIFI_ONLY to settings.wifiOnly
            )

            val workRequest = PeriodicWorkRequestBuilder<BackupWorker>(
                intervalHours, TimeUnit.HOURS
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

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )

            Log.i(TAG, "Scheduled backup every $intervalHours hours, WiFi only: ${settings.wifiOnly}")
        }

        /**
         * Cancel scheduled backups.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled scheduled backups")
        }

        /**
         * Trigger an immediate one-time backup.
         */
        fun triggerNow(context: Context, includeMessages: Boolean = true, wifiOnly: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val inputData = workDataOf(
                KEY_INCLUDE_MESSAGES to includeMessages,
                KEY_WIFI_ONLY to wifiOnly
            )

            val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag("manual_backup")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i(TAG, "Triggered immediate backup")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting backup work")

        val includeMessages = inputData.getBoolean(KEY_INCLUDE_MESSAGES, true)
        val wifiOnly = inputData.getBoolean(KEY_WIFI_ONLY, false)

        // Check WiFi constraint if required
        if (wifiOnly && !isOnWifi()) {
            Log.i(TAG, "Skipping backup - WiFi required but not connected")
            return Result.retry()
        }

        return try {
            backupApiClient.triggerBackup(includeMessages).fold(
                onSuccess = { backup ->
                    Log.i(TAG, "Backup completed successfully: ${backup.backupId}")
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Backup failed: ${error.message}")
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup error", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
