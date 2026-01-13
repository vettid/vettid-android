package com.vettid.app.core.network

import android.util.Base64
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
 * HTTP client for credential backup and recovery API.
 */
@Singleton
class CredentialBackupApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val credentialStore: CredentialStore
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "CredentialBackupApi"
        private const val BASE_URL = "https://api.vettid.dev"
    }

    private fun getAuthToken(): String? = credentialStore.getAuthToken()

    /**
     * Create a credential backup.
     * The encrypted blob is already encrypted with the recovery phrase-derived key.
     */
    suspend fun createCredentialBackup(
        encryptedBackup: EncryptedCredentialBackup
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                CredentialBackupException("Not authenticated")
            )

            val request = CreateCredentialBackupRequest(
                encryptedBlob = Base64.encodeToString(encryptedBackup.ciphertext, Base64.NO_WRAP),
                salt = Base64.encodeToString(encryptedBackup.salt, Base64.NO_WRAP),
                nonce = Base64.encodeToString(encryptedBackup.nonce, Base64.NO_WRAP)
            )

            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url("$BASE_URL/vault/credentials/backup")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val responseBody = response.body?.string()
                Log.e(TAG, "Create credential backup failed: ${response.code} - $responseBody")
                Result.failure(CredentialBackupException(responseBody ?: "Failed to create credential backup"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create credential backup error", e)
            Result.failure(CredentialBackupException(e.message ?: "Network error"))
        }
    }

    /**
     * Get credential backup status.
     */
    suspend fun getCredentialBackupStatus(): Result<CredentialBackupStatus> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                CredentialBackupException("Not authenticated")
            )

            val request = Request.Builder()
                .url("$BASE_URL/vault/credentials/backup")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val status = gson.fromJson(responseBody, CredentialBackupStatus::class.java)
                Result.success(status)
            } else {
                Log.e(TAG, "Get credential backup status failed: ${response.code} - $responseBody")
                Result.failure(CredentialBackupException(responseBody ?: "Failed to get backup status"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get credential backup status error", e)
            Result.failure(CredentialBackupException(e.message ?: "Network error"))
        }
    }

    /**
     * Download credential backup for recovery.
     * Returns the encrypted backup data that needs to be decrypted with the recovery phrase.
     */
    suspend fun downloadCredentialBackup(): Result<EncryptedCredentialBackup> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                CredentialBackupException("Not authenticated")
            )

            val request = Request.Builder()
                .url("$BASE_URL/vault/credentials/backup/download")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val backupResponse = gson.fromJson(responseBody, CredentialBackupResponse::class.java)
                val encryptedBackup = EncryptedCredentialBackup(
                    ciphertext = Base64.decode(backupResponse.encryptedBlob, Base64.NO_WRAP),
                    salt = Base64.decode(backupResponse.salt, Base64.NO_WRAP),
                    nonce = Base64.decode(backupResponse.nonce, Base64.NO_WRAP)
                )
                Result.success(encryptedBackup)
            } else {
                Log.e(TAG, "Download credential backup failed: ${response.code} - $responseBody")
                Result.failure(CredentialBackupException(responseBody ?: "Failed to download backup"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download credential backup error", e)
            Result.failure(CredentialBackupException(e.message ?: "Network error"))
        }
    }

    /**
     * Recover credentials from backup.
     * This should be called after decrypting the backup with the recovery phrase.
     */
    suspend fun recoverCredentials(
        encryptedBlob: ByteArray,
        deviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext Result.failure(
                CredentialBackupException("Not authenticated")
            )

            val request = RecoverCredentialsRequest(
                encryptedBlob = Base64.encodeToString(encryptedBlob, Base64.NO_WRAP),
                deviceId = deviceId
            )

            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url("$BASE_URL/vault/credentials/recover")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val responseBody = response.body?.string()
                Log.e(TAG, "Recover credentials failed: ${response.code} - $responseBody")
                Result.failure(CredentialBackupException(responseBody ?: "Failed to recover credentials"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recover credentials error", e)
            Result.failure(CredentialBackupException(e.message ?: "Network error"))
        }
    }
}
