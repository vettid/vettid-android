package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.JsonObject
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for enclave migration operations.
 *
 * Handles:
 * - Checking migration status on app startup
 * - Acknowledging migration notifications
 * - Emergency recovery (when both enclaves are unavailable)
 *
 * Migration happens server-side without user action. The app:
 * 1. Checks status on startup
 * 2. Shows notification banner if migration completed
 * 3. Handles emergency recovery if both enclaves are down
 */
@Singleton
class MigrationClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore
) {
    companion object {
        private const val TAG = "MigrationClient"
        private const val TIMEOUT_MS = 10_000L
    }

    private val _migrationEvents = MutableSharedFlow<MigrationEvent>(extraBufferCapacity = 8)
    val migrationEvents: SharedFlow<MigrationEvent> = _migrationEvents.asSharedFlow()

    /**
     * Check migration status on app startup.
     *
     * @return MigrationStatus indicating current state
     */
    suspend fun checkMigrationStatus(): MigrationStatus {
        Log.d(TAG, "Checking migration status")

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.status", JsonObject(), TIMEOUT_MS
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    parseMigrationStatus(response.result)
                } else {
                    Log.w(TAG, "Migration status check failed: ${response.error}")
                    MigrationStatus.Unknown
                }
            }
            is VaultResponse.Error -> {
                Log.d(TAG, "Migration status: ${response.code}")
                MigrationStatus.None
            }
            else -> MigrationStatus.Unknown
        }
    }

    /**
     * Mark user as notified about migration completion.
     * Call this when user dismisses the migration notification banner.
     */
    suspend fun acknowledgeNotification(): Result<Unit> {
        Log.d(TAG, "Acknowledging migration notification")

        val payload = JsonObject().apply {
            addProperty("acknowledged", true)
            addProperty("acknowledged_at", System.currentTimeMillis())
        }

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.acknowledge", payload, TIMEOUT_MS
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success) {
                    Log.i(TAG, "Migration notification acknowledged")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.error ?: "Acknowledgment failed"))
                }
            }
            is VaultResponse.Error -> {
                Result.failure(Exception("${response.code}: ${response.message}"))
            }
            else -> {
                Result.failure(Exception("Timeout waiting for acknowledgment response"))
            }
        }
    }

    /**
     * Perform emergency recovery when both enclaves are unavailable.
     *
     * This is only used in disaster scenarios where the user must provide
     * their PIN to re-derive the DEK.
     *
     * @param encryptedPinHash Base64-encoded encrypted PIN hash
     * @param ephemeralPublicKey Base64-encoded ephemeral public key
     * @param nonce Base64-encoded nonce
     */
    suspend fun performEmergencyRecovery(
        encryptedPinHash: String,
        ephemeralPublicKey: String,
        nonce: String
    ): Result<Unit> {
        Log.i(TAG, "Performing emergency recovery")

        val payload = JsonObject().apply {
            addProperty("encrypted_pin_hash", encryptedPinHash)
            addProperty("ephemeral_public_key", ephemeralPublicKey)
            addProperty("nonce", nonce)
        }

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.emergency_recovery", payload, 30_000L
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success) {
                    Log.i(TAG, "Emergency recovery successful")
                    _migrationEvents.tryEmit(MigrationEvent.RecoveryCompleted)
                    Result.success(Unit)
                } else {
                    val error = response.error ?: "Recovery failed"
                    Log.e(TAG, "Emergency recovery failed: $error")
                    _migrationEvents.tryEmit(MigrationEvent.RecoveryFailed(error))
                    Result.failure(Exception(error))
                }
            }
            is VaultResponse.Error -> {
                val error = "${response.code}: ${response.message}"
                Log.e(TAG, "Emergency recovery error: $error")
                _migrationEvents.tryEmit(MigrationEvent.RecoveryFailed(error))
                Result.failure(Exception(error))
            }
            else -> {
                val error = "Timeout waiting for recovery response"
                Log.e(TAG, error)
                _migrationEvents.tryEmit(MigrationEvent.RecoveryFailed(error))
                Result.failure(Exception(error))
            }
        }
    }

    /**
     * Get audit log entries for migrations.
     *
     * @param limit Maximum number of entries to return
     * @return List of audit log entries
     */
    suspend fun getAuditLog(limit: Int = 50): Result<List<AuditLogEntry>> {
        Log.d(TAG, "Fetching audit log")

        val payload = JsonObject().apply {
            addProperty("limit", limit)
            addProperty("filter", "migration")
        }

        val response = ownerSpaceClient.sendAndAwaitResponse("audit.query", payload, TIMEOUT_MS)

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    val entries = parseAuditLogEntries(response.result)
                    Result.success(entries)
                } else {
                    Result.failure(Exception(response.error ?: "Failed to fetch audit log"))
                }
            }
            is VaultResponse.Error -> {
                if (response.code == "TIMEOUT") {
                    // No audit logs yet is normal
                    Result.success(emptyList())
                } else {
                    Result.failure(Exception("${response.code}: ${response.message}"))
                }
            }
            else -> {
                Result.failure(Exception("Timeout waiting for audit log response"))
            }
        }
    }

    /**
     * Check if a vault security update is available.
     * Sends credential.migration.config to the vault.
     *
     * @return MigrationConfig if update is available, null otherwise
     */
    suspend fun getMigrationConfig(): MigrationConfig? {
        Log.d(TAG, "Checking for migration config")

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.config", JsonObject(), TIMEOUT_MS
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    parseMigrationConfig(response.result)
                } else {
                    Log.w(TAG, "Migration config check failed: ${response.error}")
                    null
                }
            }
            else -> {
                Log.d(TAG, "Migration config: no update available")
                null
            }
        }
    }

    /**
     * Start the migration (re-seal vault for new enclave).
     * Called when user taps "Update Now".
     *
     * @return Result<String> with the migration version on success
     */
    suspend fun startMigration(): Result<String> {
        Log.i(TAG, "Starting vault migration (re-seal)")

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.start", JsonObject(), 30_000L
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success) {
                    val version = response.result?.get("version")?.asString ?: ""
                    Log.i(TAG, "Migration completed successfully: $version")
                    _migrationEvents.tryEmit(MigrationEvent.MigrationCompleted(version))
                    Result.success(version)
                } else {
                    val error = response.error ?: "Migration failed"
                    Log.e(TAG, "Migration failed: $error")
                    _migrationEvents.tryEmit(MigrationEvent.MigrationFailed(error))
                    Result.failure(Exception(error))
                }
            }
            is VaultResponse.Error -> {
                val error = "${response.code}: ${response.message}"
                _migrationEvents.tryEmit(MigrationEvent.MigrationFailed(error))
                Result.failure(Exception(error))
            }
            else -> {
                val error = "Timeout waiting for migration response"
                _migrationEvents.tryEmit(MigrationEvent.MigrationFailed(error))
                Result.failure(Exception(error))
            }
        }
    }

    private fun parseMigrationConfig(json: JsonObject): MigrationConfig? {
        val available = json.get("available")?.asBoolean ?: false
        if (!available) return null

        // Extract new PCR0 from new_pcrs object
        val newPcr0 = json.getAsJsonObject("new_pcrs")?.get("pcr0")?.asString

        return MigrationConfig(
            version = json.get("version")?.asString ?: "",
            summary = json.get("summary")?.asString ?: "",
            detailsUrl = json.get("details_url")?.asString,
            publishedAt = json.get("published_at")?.asString,
            mandatoryAfter = json.get("mandatory_after")?.asString,
            newPcr0 = newPcr0
        )
    }

    private fun parseMigrationStatus(json: JsonObject): MigrationStatus {
        val status = json.get("status")?.asString

        return when (status) {
            "none", null -> MigrationStatus.None
            "in_progress" -> MigrationStatus.InProgress(
                progress = json.get("progress")?.asFloat ?: 0f
            )
            "complete" -> MigrationStatus.Complete(
                migratedAt = json.get("migrated_at")?.asString,
                userNotified = json.get("user_notified")?.asBoolean ?: false,
                fromPcrVersion = json.get("from_pcr_version")?.asString,
                toPcrVersion = json.get("to_pcr_version")?.asString
            )
            "emergency_recovery_required" -> MigrationStatus.EmergencyRecoveryRequired
            else -> {
                Log.w(TAG, "Unknown migration status: $status")
                MigrationStatus.Unknown
            }
        }
    }

    private fun parseAuditLogEntries(json: JsonObject): List<AuditLogEntry> {
        val entriesArray = json.getAsJsonArray("entries") ?: return emptyList()

        return entriesArray.mapNotNull { element ->
            try {
                val obj = element.asJsonObject
                AuditLogEntry(
                    id = obj.get("id")?.asString ?: return@mapNotNull null,
                    type = obj.get("type")?.asString ?: return@mapNotNull null,
                    title = getAuditLogTitle(obj.get("type")?.asString ?: ""),
                    description = obj.get("description")?.asString ?: "",
                    timestamp = obj.get("timestamp")?.asLong ?: System.currentTimeMillis(),
                    metadata = obj.getAsJsonObject("metadata")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse audit log entry", e)
                null
            }
        }
    }

    private fun getAuditLogTitle(type: String): String {
        return when (type) {
            "migration_sealed_material" -> "Credential Migration Started"
            "migration_verified" -> "Credential Migration Completed"
            "migration_old_version_deleted" -> "Old Credential Version Removed"
            "emergency_recovery" -> "Emergency Recovery Performed"
            else -> type.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Migration status states.
 */
sealed class MigrationStatus {
    /** No migration has occurred */
    object None : MigrationStatus()

    /** Migration completed successfully */
    data class Complete(
        val migratedAt: String?,
        val userNotified: Boolean,
        val fromPcrVersion: String?,
        val toPcrVersion: String?
    ) : MigrationStatus()

    /** Migration is currently in progress (server-side) */
    data class InProgress(val progress: Float) : MigrationStatus()

    /** Both enclaves unavailable - user must provide PIN for recovery */
    object EmergencyRecoveryRequired : MigrationStatus()

    /** Could not determine migration status */
    object Unknown : MigrationStatus()
}

/**
 * Migration config returned by the vault when an update is available.
 */
data class MigrationConfig(
    val version: String,
    val summary: String,
    val detailsUrl: String?,
    val publishedAt: String?,
    val mandatoryAfter: String?,
    val newPcr0: String? = null
)

/**
 * Migration events for UI updates.
 */
sealed class MigrationEvent {
    data class MigrationCompleted(val version: String) : MigrationEvent()
    data class MigrationFailed(val error: String) : MigrationEvent()
    object RecoveryCompleted : MigrationEvent()
    data class RecoveryFailed(val error: String) : MigrationEvent()
}

/**
 * Audit log entry for security events.
 */
data class AuditLogEntry(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val timestamp: Long,
    val metadata: JsonObject?
)
