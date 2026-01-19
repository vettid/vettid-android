package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.JsonObject
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

        val requestIdResult = ownerSpaceClient.sendToVault("credential.migration.status", JsonObject())
        if (requestIdResult.isFailure) {
            Log.e(TAG, "Failed to send migration status request", requestIdResult.exceptionOrNull())
            return MigrationStatus.Unknown
        }

        val requestId = requestIdResult.getOrThrow()

        // Wait for response
        val response = withTimeoutOrNull(TIMEOUT_MS) {
            ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
        }

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
                Log.e(TAG, "Migration status error: ${response.code} - ${response.message}")
                MigrationStatus.Unknown
            }
            else -> {
                Log.w(TAG, "Unexpected response type or timeout")
                MigrationStatus.Unknown
            }
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

        val requestIdResult = ownerSpaceClient.sendToVault("credential.migration.acknowledge", payload)
        if (requestIdResult.isFailure) {
            return Result.failure(requestIdResult.exceptionOrNull() ?: Exception("Failed to send acknowledgment"))
        }

        val requestId = requestIdResult.getOrThrow()

        val response = withTimeoutOrNull(TIMEOUT_MS) {
            ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
        }

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

        val requestIdResult = ownerSpaceClient.sendToVault("credential.emergency_recovery", payload)
        if (requestIdResult.isFailure) {
            return Result.failure(requestIdResult.exceptionOrNull() ?: Exception("Failed to send recovery request"))
        }

        val requestId = requestIdResult.getOrThrow()

        // Longer timeout for recovery operation
        val response = withTimeoutOrNull(30_000L) {
            ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
        }

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

        val requestIdResult = ownerSpaceClient.sendToVault("audit.log.get", payload)
        if (requestIdResult.isFailure) {
            return Result.failure(requestIdResult.exceptionOrNull() ?: Exception("Failed to fetch audit log"))
        }

        val requestId = requestIdResult.getOrThrow()

        val response = withTimeoutOrNull(TIMEOUT_MS) {
            ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
        }

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
                Result.failure(Exception("${response.code}: ${response.message}"))
            }
            else -> {
                Result.failure(Exception("Timeout waiting for audit log response"))
            }
        }
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
 * Migration events for UI updates.
 */
sealed class MigrationEvent {
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
