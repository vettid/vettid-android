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

        // Work Manager unique work name
        const val BACKUP_WORK_NAME = "protean_credential_backup"
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
