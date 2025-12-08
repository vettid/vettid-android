package com.vettid.app.core.network

import com.google.gson.annotations.SerializedName

/**
 * Data models for backup and recovery system.
 */

// MARK: - Backup Models

/**
 * Represents a vault backup.
 */
data class Backup(
    @SerializedName("backup_id") val backupId: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("size_bytes") val sizeBytes: Long,
    val type: BackupType,
    val status: BackupStatus,
    @SerializedName("encryption_method") val encryptionMethod: String,
    @SerializedName("handlers_count") val handlersCount: Int = 0,
    @SerializedName("connections_count") val connectionsCount: Int = 0,
    @SerializedName("messages_count") val messagesCount: Int = 0
)

/**
 * Backup type enumeration.
 */
enum class BackupType {
    @SerializedName("auto")
    AUTO,
    @SerializedName("manual")
    MANUAL
}

/**
 * Backup status enumeration.
 */
enum class BackupStatus {
    @SerializedName("complete")
    COMPLETE,
    @SerializedName("partial")
    PARTIAL,
    @SerializedName("failed")
    FAILED,
    @SerializedName("in_progress")
    IN_PROGRESS
}

/**
 * Backup settings configuration.
 */
data class BackupSettings(
    @SerializedName("auto_backup_enabled") val autoBackupEnabled: Boolean,
    @SerializedName("backup_frequency") val backupFrequency: BackupFrequency,
    @SerializedName("backup_time_utc") val backupTimeUtc: String,  // HH:mm format
    @SerializedName("retention_days") val retentionDays: Int,
    @SerializedName("include_messages") val includeMessages: Boolean,
    @SerializedName("wifi_only") val wifiOnly: Boolean,
    @SerializedName("last_backup_at") val lastBackupAt: Long? = null
)

/**
 * Backup frequency enumeration.
 */
enum class BackupFrequency {
    @SerializedName("daily")
    DAILY,
    @SerializedName("weekly")
    WEEKLY,
    @SerializedName("monthly")
    MONTHLY
}

/**
 * Credential backup status.
 */
data class CredentialBackupStatus(
    val exists: Boolean,
    @SerializedName("created_at") val createdAt: Long?,
    @SerializedName("last_verified_at") val lastVerifiedAt: Long?
)

/**
 * Result of a backup restore operation.
 */
data class RestoreResult(
    val success: Boolean,
    @SerializedName("restored_items") val restoredItems: Int,
    val conflicts: List<String>,
    @SerializedName("requires_reauth") val requiresReauth: Boolean
)

// MARK: - API Request/Response Models

/**
 * Request to trigger a manual backup.
 */
data class TriggerBackupRequest(
    @SerializedName("include_messages") val includeMessages: Boolean = true
)

/**
 * Response containing list of backups.
 */
data class BackupListResponse(
    val backups: List<Backup>,
    val total: Int,
    @SerializedName("has_more") val hasMore: Boolean
)

/**
 * Request to restore from a backup.
 */
data class RestoreBackupRequest(
    @SerializedName("backup_id") val backupId: String,
    @SerializedName("overwrite_conflicts") val overwriteConflicts: Boolean = false
)

/**
 * Request to update backup settings.
 */
data class UpdateBackupSettingsRequest(
    @SerializedName("auto_backup_enabled") val autoBackupEnabled: Boolean? = null,
    @SerializedName("backup_frequency") val backupFrequency: BackupFrequency? = null,
    @SerializedName("backup_time_utc") val backupTimeUtc: String? = null,
    @SerializedName("retention_days") val retentionDays: Int? = null,
    @SerializedName("include_messages") val includeMessages: Boolean? = null,
    @SerializedName("wifi_only") val wifiOnly: Boolean? = null
)

/**
 * Request to create a credential backup.
 */
data class CreateCredentialBackupRequest(
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64 encoded
    val salt: String,  // Base64 encoded
    val nonce: String  // Base64 encoded
)

/**
 * Response for credential backup download.
 */
data class CredentialBackupResponse(
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64 encoded
    val salt: String,  // Base64 encoded
    val nonce: String  // Base64 encoded
)

/**
 * Request to recover credentials.
 */
data class RecoverCredentialsRequest(
    @SerializedName("encrypted_blob") val encryptedBlob: String,  // Base64 encoded (decrypted with phrase, re-encrypted for server)
    @SerializedName("device_id") val deviceId: String
)

// MARK: - Encrypted Credential Backup

/**
 * Locally encrypted credential backup data.
 */
data class EncryptedCredentialBackup(
    val ciphertext: ByteArray,
    val salt: ByteArray,
    val nonce: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedCredentialBackup

        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!salt.contentEquals(other.salt)) return false
        if (!nonce.contentEquals(other.nonce)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

// MARK: - Exceptions

class BackupApiException(message: String) : Exception(message)
class CredentialBackupException(message: String) : Exception(message)
class RecoveryPhraseException(message: String) : Exception(message)
