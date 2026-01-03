package com.vettid.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Protean Credential - an encrypted blob containing all user secrets.
 *
 * The Protean Credential is created INSIDE the Nitro Enclave and contains:
 * - Identity keypair (Ed25519)
 * - Vault master secret
 * - Crypto keys (secp256k1, etc.)
 * - Seed phrases
 * - Challenge configuration (auth type, hash, salt)
 *
 * This manager handles:
 * 1. Secure local storage using EncryptedSharedPreferences
 * 2. Automatic backup to VettID after credential creation
 * 3. Credential versioning
 * 4. Recovery initiation (24-hour delayed recovery flow)
 *
 * Security Properties:
 * - The credential blob is already encrypted by the enclave
 * - Local storage adds device binding via Android Keystore
 * - VettID cannot read the contents (encrypted with user's DEK)
 * - 24-hour recovery delay prevents immediate credential theft
 */
@Singleton
class ProteanCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProteanCredential"
        private const val PREFS_NAME = "vettid_protean_credential"
        private const val KEY_CREDENTIAL_BLOB = "credential_blob"
        private const val KEY_METADATA = "metadata"
        private const val KEY_BACKUP_STATUS = "backup_status"
        private const val KEY_USER_GUID = "user_guid"
        private const val KEY_RECOVERY_REQUEST_ID = "recovery_request_id"

        // Work Manager unique work name
        const val BACKUP_WORK_NAME = "protean_credential_backup"

        // Recovery API endpoints
        private const val API_BASE_URL = "https://api.vettid.com"
        private const val RECOVERY_REQUEST_ENDPOINT = "$API_BASE_URL/vault/recovery/request"
        private const val RECOVERY_STATUS_ENDPOINT = "$API_BASE_URL/vault/recovery/status"
        private const val RECOVERY_DOWNLOAD_ENDPOINT = "$API_BASE_URL/vault/recovery/download"
        private const val RECOVERY_CANCEL_ENDPOINT = "$API_BASE_URL/vault/recovery/cancel"

        // Recovery delay (24 hours)
        val RECOVERY_DELAY: Duration = Duration.ofHours(24)
    }

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy { createSecurePrefs() }
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    private fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // MARK: - Credential Storage

    /**
     * Store a new Protean Credential received from the enclave.
     *
     * @param credentialBlob The encrypted credential blob (base64 encoded)
     * @param userGuid User identifier
     * @param version Credential version number
     * @param triggerBackup Whether to trigger automatic backup
     */
    fun storeCredential(
        credentialBlob: String,
        userGuid: String,
        version: Int = 1,
        triggerBackup: Boolean = true
    ) {
        Log.i(TAG, "Storing Protean Credential version $version for user $userGuid")

        val metadata = CredentialMetadata(
            version = version,
            createdAt = Date(),
            backedUpAt = null,
            sizeBytes = credentialBlob.length
        )

        prefs.edit()
            .putString(KEY_CREDENTIAL_BLOB, credentialBlob)
            .putString(KEY_METADATA, gson.toJson(metadata))
            .putString(KEY_USER_GUID, userGuid)
            .putString(KEY_BACKUP_STATUS, BackupStatus.PENDING.name)
            .apply()

        Log.d(TAG, "Credential stored locally (${metadata.sizeBytes} bytes)")

        if (triggerBackup) {
            scheduleBackup()
        }
    }

    /**
     * Store a new Protean Credential from raw bytes.
     */
    fun storeCredential(
        credentialBytes: ByteArray,
        userGuid: String,
        version: Int = 1,
        triggerBackup: Boolean = true
    ) {
        val base64 = Base64.encodeToString(credentialBytes, Base64.NO_WRAP)
        storeCredential(base64, userGuid, version, triggerBackup)
    }

    /**
     * Get the stored credential blob.
     *
     * @return Base64-encoded credential blob, or null if not stored
     */
    fun getCredential(): String? {
        return prefs.getString(KEY_CREDENTIAL_BLOB, null)
    }

    /**
     * Get the stored credential as raw bytes.
     */
    fun getCredentialBytes(): ByteArray? {
        val base64 = getCredential() ?: return null
        return try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode credential", e)
            null
        }
    }

    /**
     * Get credential metadata.
     */
    fun getMetadata(): CredentialMetadata? {
        val json = prefs.getString(KEY_METADATA, null) ?: return null
        return try {
            gson.fromJson(json, CredentialMetadata::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse metadata", e)
            null
        }
    }

    /**
     * Get the user GUID associated with the stored credential.
     */
    fun getUserGuid(): String? {
        return prefs.getString(KEY_USER_GUID, null)
    }

    /**
     * Check if a credential is stored.
     */
    fun hasCredential(): Boolean {
        return prefs.contains(KEY_CREDENTIAL_BLOB)
    }

    /**
     * Get the current backup status.
     */
    fun getBackupStatus(): BackupStatus {
        val status = prefs.getString(KEY_BACKUP_STATUS, null)
        return status?.let { BackupStatus.valueOf(it) } ?: BackupStatus.NONE
    }

    /**
     * Update the backup status.
     */
    fun updateBackupStatus(status: BackupStatus, backupId: String? = null) {
        prefs.edit()
            .putString(KEY_BACKUP_STATUS, status.name)
            .apply()

        if (status == BackupStatus.COMPLETED) {
            // Update metadata with backup timestamp
            getMetadata()?.let { metadata ->
                val updated = metadata.copy(backedUpAt = Date())
                prefs.edit()
                    .putString(KEY_METADATA, gson.toJson(updated))
                    .apply()
            }
        }

        Log.d(TAG, "Backup status updated: $status")
    }

    // MARK: - Credential Update

    /**
     * Update the credential (e.g., when user changes PIN).
     *
     * @param newCredentialBlob The new encrypted credential blob
     * @param triggerBackup Whether to trigger backup after update
     */
    fun updateCredential(
        newCredentialBlob: String,
        triggerBackup: Boolean = true
    ) {
        val currentMetadata = getMetadata()
        val userGuid = getUserGuid()

        if (currentMetadata == null || userGuid == null) {
            Log.e(TAG, "Cannot update credential - no existing credential")
            return
        }

        val newVersion = currentMetadata.version + 1
        Log.i(TAG, "Updating credential to version $newVersion")

        storeCredential(
            credentialBlob = newCredentialBlob,
            userGuid = userGuid,
            version = newVersion,
            triggerBackup = triggerBackup
        )
    }

    // MARK: - Backup

    /**
     * Schedule a background backup using WorkManager.
     *
     * Backup will be attempted when network is available.
     */
    fun scheduleBackup() {
        Log.i(TAG, "Scheduling credential backup")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupRequest = OneTimeWorkRequestBuilder<CredentialBackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(
                CredentialBackupWorker.KEY_USER_GUID to getUserGuid()
            ))
            .build()

        workManager.enqueueUniqueWork(
            BACKUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            backupRequest
        )

        updateBackupStatus(BackupStatus.PENDING)
    }

    /**
     * Cancel any pending backup.
     */
    fun cancelPendingBackup() {
        workManager.cancelUniqueWork(BACKUP_WORK_NAME)
        Log.d(TAG, "Pending backup cancelled")
    }

    // MARK: - Clear/Reset

    /**
     * Clear all stored credential data.
     *
     * WARNING: This will delete the local credential. Ensure it's backed up first!
     */
    fun clearCredential() {
        Log.w(TAG, "Clearing Protean Credential")
        prefs.edit()
            .remove(KEY_CREDENTIAL_BLOB)
            .remove(KEY_METADATA)
            .remove(KEY_BACKUP_STATUS)
            .remove(KEY_USER_GUID)
            .apply()
        cancelPendingBackup()
    }

    /**
     * Import a recovered credential.
     *
     * @param credentialBlob The recovered credential blob
     * @param metadata Metadata from the backup
     */
    fun importRecoveredCredential(
        credentialBlob: String,
        userGuid: String,
        version: Int
    ) {
        Log.i(TAG, "Importing recovered credential version $version")

        val metadata = CredentialMetadata(
            version = version,
            createdAt = Date(),
            backedUpAt = Date(), // Already backed up since we recovered it
            sizeBytes = credentialBlob.length
        )

        prefs.edit()
            .putString(KEY_CREDENTIAL_BLOB, credentialBlob)
            .putString(KEY_METADATA, gson.toJson(metadata))
            .putString(KEY_USER_GUID, userGuid)
            .putString(KEY_BACKUP_STATUS, BackupStatus.COMPLETED.name)
            .apply()

        Log.i(TAG, "Recovered credential imported successfully")
    }

    // MARK: - Recovery Flow (24-hour delayed recovery)

    /**
     * Request credential recovery.
     *
     * This starts the 24-hour recovery timer. The user will receive a notification
     * and can cancel the recovery from any authenticated device.
     *
     * @param email The email address associated with the account
     * @return RecoveryRequest containing request ID and when recovery will be available
     */
    suspend fun requestRecovery(email: String): Result<RecoveryRequest> {
        Log.i(TAG, "Requesting credential recovery for $email")

        return try {
            val url = URL(RECOVERY_REQUEST_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val requestBody = gson.toJson(mapOf("email" to email))
            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            val responseCode = connection.responseCode
            if (responseCode != 200 && responseCode != 201) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Recovery request failed: $responseCode - $errorBody")
                return Result.failure(RecoveryException("Recovery request failed: $responseCode", responseCode))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val response = gson.fromJson(responseBody, RecoveryRequestResponse::class.java)

            val request = RecoveryRequest(
                requestId = response.requestId,
                availableAt = Instant.parse(response.availableAt),
                email = email
            )

            // Store request ID for later reference
            prefs.edit()
                .putString(KEY_RECOVERY_REQUEST_ID, request.requestId)
                .apply()

            Log.i(TAG, "Recovery requested, available at: ${request.availableAt}")
            Result.success(request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request recovery", e)
            Result.failure(RecoveryException("Failed to request recovery: ${e.message}", cause = e))
        }
    }

    /**
     * Check the status of a recovery request.
     *
     * @param requestId The recovery request ID
     * @return RecoveryStatus with current state and timing info
     */
    suspend fun checkRecoveryStatus(requestId: String): Result<RecoveryStatus> {
        Log.d(TAG, "Checking recovery status for $requestId")

        return try {
            val url = URL("$RECOVERY_STATUS_ENDPOINT?request_id=$requestId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Recovery status check failed: $responseCode - $errorBody")
                return Result.failure(RecoveryException("Status check failed: $responseCode", responseCode))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val response = gson.fromJson(responseBody, RecoveryStatusResponse::class.java)

            val status = RecoveryStatus(
                requestId = requestId,
                state = RecoveryState.valueOf(response.status.uppercase()),
                availableAt = Instant.parse(response.availableAt),
                expiresAt = Instant.parse(response.expiresAt)
            )

            Log.d(TAG, "Recovery status: ${status.state}, available at: ${status.availableAt}")
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check recovery status", e)
            Result.failure(RecoveryException("Failed to check status: ${e.message}", cause = e))
        }
    }

    /**
     * Download recovered credential after 24-hour delay.
     *
     * @param requestId The recovery request ID
     * @param attestationBase64 Nitro attestation document (base64) to prove genuine device
     * @return The recovered sealed credential
     */
    suspend fun downloadRecoveredCredential(
        requestId: String,
        attestationBase64: String
    ): Result<SealedCredential> {
        Log.i(TAG, "Downloading recovered credential for $requestId")

        return try {
            val url = URL(RECOVERY_DOWNLOAD_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val requestBody = gson.toJson(mapOf(
                "request_id" to requestId,
                "attestation" to attestationBase64
            ))
            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Recovery download failed: $responseCode - $errorBody")
                return Result.failure(RecoveryException("Download failed: $responseCode", responseCode))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val response = gson.fromJson(responseBody, RecoveryDownloadResponse::class.java)

            val credential = SealedCredential(
                userGuid = response.userGuid,
                sealedData = Base64.decode(response.sealedCredential, Base64.NO_WRAP),
                version = response.version
            )

            // Import the recovered credential
            importRecoveredCredential(
                credentialBlob = response.sealedCredential,
                userGuid = response.userGuid,
                version = response.version
            )

            // Clear the recovery request ID
            prefs.edit().remove(KEY_RECOVERY_REQUEST_ID).apply()

            Log.i(TAG, "Recovered credential downloaded and imported successfully")
            Result.success(credential)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download recovered credential", e)
            Result.failure(RecoveryException("Failed to download: ${e.message}", cause = e))
        }
    }

    /**
     * Cancel a pending recovery request.
     *
     * Can be called from any authenticated device to prevent unauthorized recovery.
     *
     * @param requestId The recovery request ID to cancel
     * @param authToken Authentication token for the user
     */
    suspend fun cancelRecovery(requestId: String, authToken: String): Result<Unit> {
        Log.i(TAG, "Cancelling recovery request $requestId")

        return try {
            val url = URL(RECOVERY_CANCEL_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $authToken")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val requestBody = gson.toJson(mapOf("request_id" to requestId))
            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            val responseCode = connection.responseCode
            if (responseCode != 200 && responseCode != 204) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Recovery cancellation failed: $responseCode - $errorBody")
                return Result.failure(RecoveryException("Cancellation failed: $responseCode", responseCode))
            }

            // Clear stored recovery request ID if it matches
            if (prefs.getString(KEY_RECOVERY_REQUEST_ID, null) == requestId) {
                prefs.edit().remove(KEY_RECOVERY_REQUEST_ID).apply()
            }

            Log.i(TAG, "Recovery cancelled successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel recovery", e)
            Result.failure(RecoveryException("Failed to cancel: ${e.message}", cause = e))
        }
    }

    /**
     * Get the stored recovery request ID, if any.
     */
    fun getPendingRecoveryRequestId(): String? {
        return prefs.getString(KEY_RECOVERY_REQUEST_ID, null)
    }

    /**
     * Calculate time remaining until recovery is available.
     *
     * @param availableAt When recovery becomes available
     * @return Duration until available, or Duration.ZERO if already available
     */
    fun getTimeUntilRecoveryAvailable(availableAt: Instant): Duration {
        val now = Instant.now()
        return if (now.isBefore(availableAt)) {
            Duration.between(now, availableAt)
        } else {
            Duration.ZERO
        }
    }
}

/**
 * Metadata about the stored credential.
 */
data class CredentialMetadata(
    /** Version number of the credential */
    val version: Int,

    /** When this version was created */
    @SerializedName("created_at")
    val createdAt: Date,

    /** When this version was last backed up (null if not backed up) */
    @SerializedName("backed_up_at")
    val backedUpAt: Date?,

    /** Size of the credential blob in bytes */
    @SerializedName("size_bytes")
    val sizeBytes: Int
)

/**
 * Status of credential backup.
 */
enum class BackupStatus {
    /** No backup has been attempted */
    NONE,
    /** Backup is scheduled/in progress */
    PENDING,
    /** Backup completed successfully */
    COMPLETED,
    /** Backup failed */
    FAILED
}

// MARK: - Recovery Data Classes

/**
 * A sealed credential blob from the enclave.
 */
data class SealedCredential(
    val userGuid: String,
    val sealedData: ByteArray,
    val version: Int = 1,
    val createdAt: Date = Date()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SealedCredential
        return userGuid == other.userGuid && sealedData.contentEquals(other.sealedData)
    }

    override fun hashCode(): Int {
        var result = userGuid.hashCode()
        result = 31 * result + sealedData.contentHashCode()
        return result
    }
}

/**
 * Recovery request information.
 */
data class RecoveryRequest(
    /** Unique ID for this recovery request */
    val requestId: String,
    /** When the recovery will be available (after 24-hour delay) */
    val availableAt: Instant,
    /** Email used for the request */
    val email: String
)

/**
 * Current state of a recovery request.
 */
enum class RecoveryState {
    /** Waiting for 24-hour delay to complete */
    PENDING,
    /** Recovery is available for download */
    READY,
    /** User cancelled the recovery */
    CANCELLED,
    /** Recovery window expired */
    EXPIRED
}

/**
 * Status of a recovery request.
 */
data class RecoveryStatus(
    val requestId: String,
    val state: RecoveryState,
    val availableAt: Instant,
    val expiresAt: Instant
) {
    /** Whether recovery can be downloaded now */
    val isReady: Boolean get() = state == RecoveryState.READY

    /** Whether recovery is still pending the 24-hour delay */
    val isPending: Boolean get() = state == RecoveryState.PENDING
}

/**
 * Exception for recovery operation failures.
 */
class RecoveryException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

// MARK: - API Response DTOs

internal data class RecoveryRequestResponse(
    @SerializedName("request_id")
    val requestId: String,
    @SerializedName("available_at")
    val availableAt: String
)

internal data class RecoveryStatusResponse(
    val status: String,
    @SerializedName("available_at")
    val availableAt: String,
    @SerializedName("expires_at")
    val expiresAt: String
)

internal data class RecoveryDownloadResponse(
    @SerializedName("user_guid")
    val userGuid: String,
    @SerializedName("sealed_credential")
    val sealedCredential: String,
    val version: Int
)
