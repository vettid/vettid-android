package com.vettid.app.core.storage

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vettid.app.core.network.VaultServiceClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker for backing up the Protean Credential to VettID.
 *
 * This worker is scheduled after credential creation/update and will:
 * 1. Retrieve the credential blob from secure storage
 * 2. Upload it to the VettID backup API
 * 3. Update the backup status
 *
 * The worker requires network connectivity and will retry on failure.
 */
@HiltWorker
class CredentialBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val credentialManager: ProteanCredentialManager,
    private val vaultServiceClient: VaultServiceClient,
    private val credentialStore: CredentialStore
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CredentialBackup"
        const val KEY_USER_GUID = "user_guid"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting credential backup")

        // Get the credential blob
        val credentialBlob = credentialManager.getCredential()
        if (credentialBlob == null) {
            Log.e(TAG, "No credential to backup")
            credentialManager.updateBackupStatus(BackupStatus.FAILED)
            return Result.failure()
        }

        // Get auth token
        val authToken = credentialStore.getAuthToken()
        if (authToken == null) {
            Log.e(TAG, "No auth token available for backup")
            credentialManager.updateBackupStatus(BackupStatus.FAILED)
            return Result.retry()
        }

        // Get metadata for versioning
        val metadata = credentialManager.getMetadata()
        val version = metadata?.version ?: 1

        Log.d(TAG, "Backing up credential version $version")

        return try {
            val result = vaultServiceClient.backupCredential(
                authToken = authToken,
                credentialBlob = credentialBlob,
                version = version
            )

            result.fold(
                onSuccess = { response: com.vettid.app.core.network.ProteanBackupResponse ->
                    Log.i(TAG, "Backup successful: ${response.backupId}")
                    credentialManager.updateBackupStatus(BackupStatus.COMPLETED, response.backupId)
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Backup failed: ${error.message}")

                    // Retry for transient errors
                    if (runAttemptCount < 3) {
                        credentialManager.updateBackupStatus(BackupStatus.PENDING)
                        Result.retry()
                    } else {
                        credentialManager.updateBackupStatus(BackupStatus.FAILED)
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup error", e)

            if (runAttemptCount < 3) {
                credentialManager.updateBackupStatus(BackupStatus.PENDING)
                Result.retry()
            } else {
                credentialManager.updateBackupStatus(BackupStatus.FAILED)
                Result.failure()
            }
        }
    }
}
