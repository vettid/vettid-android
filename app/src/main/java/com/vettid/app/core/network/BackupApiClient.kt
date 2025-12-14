package com.vettid.app.core.network

import android.util.Log
import com.google.gson.Gson
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for backup management API.
 */
@Singleton
class BackupApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val credentialStore: CredentialStore
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "BackupApiClient"
        private const val BASE_URL = "https://tiqpij5mue.execute-api.us-east-1.amazonaws.com"
    }

    private fun getAuthToken(): String? = credentialStore.getAuthToken()

    /**
     * Trigger a manual backup.
     */
    suspend fun triggerBackup(includeMessages: Boolean = true): Result<Backup> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val requestBody = gson.toJson(TriggerBackupRequest(includeMessages))
            val request = Request.Builder()
                .url("$BASE_URL/vault/backup")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val backup = gson.fromJson(responseBody, Backup::class.java)
                Result.success(backup)
            } else {
                Log.e(TAG, "Trigger backup failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to trigger backup"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trigger backup error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }

    /**
     * List available backups.
     */
    suspend fun listBackups(limit: Int = 50, offset: Int = 0): Result<BackupListResponse> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val request = Request.Builder()
                .url("$BASE_URL/vault/backups?limit=$limit&offset=$offset")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val backupList = gson.fromJson(responseBody, BackupListResponse::class.java)
                Result.success(backupList)
            } else {
                Log.e(TAG, "List backups failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to list backups"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "List backups error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }

    /**
     * Get a specific backup by ID.
     */
    suspend fun getBackup(backupId: String): Result<Backup> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val request = Request.Builder()
                .url("$BASE_URL/vault/backups/$backupId")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val backup = gson.fromJson(responseBody, Backup::class.java)
                Result.success(backup)
            } else {
                Log.e(TAG, "Get backup failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to get backup"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get backup error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }

    /**
     * Restore from a backup.
     */
    suspend fun restoreBackup(
        backupId: String,
        overwriteConflicts: Boolean = false
    ): Result<RestoreResult> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val requestBody = gson.toJson(RestoreBackupRequest(backupId, overwriteConflicts))
            val request = Request.Builder()
                .url("$BASE_URL/vault/restore")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val result = gson.fromJson(responseBody, RestoreResult::class.java)
                Result.success(result)
            } else {
                Log.e(TAG, "Restore backup failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to restore backup"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore backup error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }

    /**
     * Delete a specific backup.
     */
    suspend fun deleteBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val request = Request.Builder()
                .url("$BASE_URL/vault/backups/$backupId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val responseBody = response.body?.string()
                Log.e(TAG, "Delete backup failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to delete backup"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete backup error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }

    /**
     * Get backup settings.
     */
    suspend fun getBackupSettings(): Result<BackupSettings> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val request = Request.Builder()
                .url("$BASE_URL/vault/backup/settings")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val settings = gson.fromJson(responseBody, BackupSettings::class.java)
                Result.success(settings)
            } else {
                Log.e(TAG, "Get backup settings failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to get backup settings"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get backup settings error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }

    /**
     * Update backup settings.
     */
    suspend fun updateBackupSettings(settings: BackupSettings): Result<BackupSettings> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                BackupApiException("Not authenticated")
            )

            val updateRequest = UpdateBackupSettingsRequest(
                autoBackupEnabled = settings.autoBackupEnabled,
                backupFrequency = settings.backupFrequency,
                backupTimeUtc = settings.backupTimeUtc,
                retentionDays = settings.retentionDays,
                includeMessages = settings.includeMessages,
                wifiOnly = settings.wifiOnly
            )

            val requestBody = gson.toJson(updateRequest)
            val request = Request.Builder()
                .url("$BASE_URL/vault/backup/settings")
                .addHeader("Authorization", "Bearer $token")
                .put(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val updatedSettings = gson.fromJson(responseBody, BackupSettings::class.java)
                Result.success(updatedSettings)
            } else {
                Log.e(TAG, "Update backup settings failed: ${response.code} - $responseBody")
                Result.failure(BackupApiException(responseBody ?: "Failed to update backup settings"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update backup settings error", e)
            Result.failure(BackupApiException(e.message ?: "Network error"))
        }
    }
}
