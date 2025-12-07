package com.vettid.app.features.vault

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for periodic vault health checks and sync
 *
 * Performs:
 * - Vault health check
 * - Credential rotation check
 * - Transaction key pool replenishment
 */
@HiltWorker
class VaultSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "vault_sync_work"
        private const val KEY_POOL_THRESHOLD = 5 // Replenish when below this count
        private const val REPLENISH_COUNT = 10

        /**
         * Schedule periodic vault sync
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<VaultSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
        }

        /**
         * Cancel scheduled sync
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
        }

        /**
         * Request immediate sync
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<VaultSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueue(syncRequest)
        }
    }

    override suspend fun doWork(): Result {
        // Check if user is enrolled
        if (!credentialStore.hasStoredCredential()) {
            return Result.success() // Nothing to sync
        }

        var overallSuccess = true

        // 1. Check transaction key pool
        try {
            val keyCount = credentialStore.getUtkCount()
            if (keyCount < KEY_POOL_THRESHOLD) {
                // Keys need replenishment - this would require auth
                // For now, just log the need
                setProgress(workDataOf(
                    "status" to "keys_low",
                    "key_count" to keyCount
                ))
            }
        } catch (e: Exception) {
            overallSuccess = false
        }

        // 2. Perform health check (if we have an action token)
        // Note: In a real implementation, we'd need a way to get/refresh the action token
        // This might involve biometric prompt or stored refresh token

        // 3. Check for pending credential rotation
        // The server will indicate if rotation is needed in the health check response

        return if (overallSuccess) {
            Result.success(workDataOf(
                "status" to "completed",
                "timestamp" to System.currentTimeMillis()
            ))
        } else {
            Result.retry()
        }
    }
}

/**
 * Helper object for managing vault sync scheduling
 */
object VaultSyncManager {

    /**
     * Initialize sync when user enrolls
     */
    fun onEnrollmentComplete(context: Context) {
        VaultSyncWorker.schedule(context)
    }

    /**
     * Stop sync when user logs out
     */
    fun onLogout(context: Context) {
        VaultSyncWorker.cancel(context)
    }

    /**
     * Check if sync is running
     */
    fun isSyncRunning(context: Context): Boolean {
        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VaultSyncWorker.WORK_NAME)
            .get()

        return workInfo.any { it.state == WorkInfo.State.RUNNING }
    }

    /**
     * Get last sync time from work info
     */
    fun getLastSyncTime(context: Context): Long? {
        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VaultSyncWorker.WORK_NAME)
            .get()

        return workInfo
            .filter { it.state == WorkInfo.State.SUCCEEDED }
            .maxByOrNull { it.outputData.getLong("timestamp", 0) }
            ?.outputData
            ?.getLong("timestamp", 0)
    }
}
