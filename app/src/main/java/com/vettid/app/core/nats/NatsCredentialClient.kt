package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for managing NATS credential lifecycle via vault handlers.
 *
 * Handles:
 * - `credentials.refresh` - Request new NATS credentials from vault
 * - `credentials.status` - Check current credential validity
 * - `forApp.credentials.rotate` - Handle proactive rotation from vault
 *
 * The vault-manager issues credentials with 7-day TTL and proactively
 * pushes new credentials 2 hours before expiry.
 */
@Singleton
class NatsCredentialClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "NatsCredentialClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    /**
     * Request fresh NATS credentials from the vault.
     *
     * @param currentCredentialId Current credential ID for tracking (optional)
     * @param deviceId Device identifier
     * @return New credentials with expiry info
     */
    suspend fun requestRefresh(
        currentCredentialId: String? = null,
        deviceId: String
    ): Result<CredentialRefreshResult> {
        val payload = JsonObject().apply {
            currentCredentialId?.let { addProperty("current_credential_id", it) }
            addProperty("device_id", deviceId)
        }

        Log.i(TAG, "Requesting credential refresh")

        return sendAndAwait("credentials.refresh", payload) { result ->
            CredentialRefreshResult(
                credentials = result.get("credentials")?.asString ?: "",
                expiresAt = result.get("expires_at")?.asString ?: "",
                ttlSeconds = result.get("ttl_seconds")?.asLong ?: 604800L, // 7 days default
                credentialId = result.get("credential_id")?.asString ?: ""
            )
        }
    }

    /**
     * Check the status of current credentials.
     *
     * @param credentialId Credential ID to check
     * @return Credential status with validity and expiry info
     */
    suspend fun checkStatus(credentialId: String): Result<CredentialStatus> {
        val payload = JsonObject().apply {
            addProperty("credential_id", credentialId)
        }

        Log.d(TAG, "Checking credential status: $credentialId")

        return sendAndAwait("credentials.status", payload) { result ->
            CredentialStatus(
                valid = result.get("valid")?.asBoolean ?: false,
                expiresAt = result.get("expires_at")?.asString ?: "",
                remainingSeconds = result.get("remaining_seconds")?.asLong ?: 0L
            )
        }
    }

    /**
     * Handle a credential rotation event from the vault.
     *
     * This is called when receiving a `forApp.credentials.rotate` message.
     * Stores the new credentials and returns them for reconnection.
     *
     * @param payload The rotation event payload
     * @return New credentials to use for reconnection
     */
    fun handleRotationEvent(payload: JsonObject): CredentialRotationEvent? {
        return try {
            val credentials = payload.get("credentials")?.asString ?: return null
            val expiresAt = payload.get("expires_at")?.asString ?: ""
            val ttlSeconds = payload.get("ttl_seconds")?.asLong ?: 604800L
            val credentialId = payload.get("credential_id")?.asString ?: ""
            val reason = payload.get("reason")?.asString ?: "unknown"
            val oldCredentialId = payload.get("old_credential_id")?.asString

            Log.i(TAG, "Received credential rotation: reason=$reason, newId=$credentialId")

            CredentialRotationEvent(
                credentials = credentials,
                expiresAt = expiresAt,
                ttlSeconds = ttlSeconds,
                credentialId = credentialId,
                reason = reason,
                oldCredentialId = oldCredentialId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rotation event", e)
            null
        }
    }

    /**
     * Store refreshed credentials in the credential store.
     *
     * @param credentials The new NATS credentials (.creds file content)
     * @param credentialId The credential ID for tracking
     */
    fun storeCredentials(credentials: String, credentialId: String? = null) {
        // Parse and store the credential file
        val endpoint = credentialStore.getNatsEndpoint() ?: return
        val ownerSpace = credentialStore.getNatsOwnerSpace()
        val messageSpace = credentialStore.getNatsConnection()?.messageSpace

        // Create NatsConnectionInfo with updated credentials
        val connectionInfo = com.vettid.app.core.network.NatsConnectionInfo(
            endpoint = endpoint,
            credentials = credentials,
            ownerSpace = ownerSpace ?: "",
            messageSpace = messageSpace ?: ""
        )

        credentialStore.storeNatsConnection(connectionInfo)
        Log.i(TAG, "Stored refreshed credentials: ${credentialId ?: "unknown"}")
    }

    /**
     * Check if credentials need refresh (within buffer period).
     *
     * @param bufferMinutes Minutes before expiry to consider credentials stale
     * @return true if credentials should be refreshed
     */
    fun needsRefresh(bufferMinutes: Long = 120): Boolean {
        val expiryTime = credentialStore.getNatsCredentialsExpiryTime() ?: return true
        val bufferMs = bufferMinutes * 60 * 1000
        val threshold = System.currentTimeMillis() + bufferMs
        return expiryTime < threshold
    }

    // Helper to send message and await response
    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        transform: (JsonObject) -> T
    ): Result<T> {
        val sendResult = ownerSpaceClient.sendToVault(messageType, payload)
        if (sendResult.isFailure) {
            return Result.failure(sendResult.exceptionOrNull() ?: NatsException("Send failed"))
        }

        val requestId = sendResult.getOrThrow()

        // Wait for matching response
        val response = withTimeoutOrNull(timeoutMs) {
            ownerSpaceClient.vaultResponses
                .filter { it.requestId == requestId }
                .first()
        }

        return when (response) {
            null -> Result.failure(NatsException("Request timed out"))
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    try {
                        Result.success(transform(response.result))
                    } catch (e: Exception) {
                        Result.failure(NatsException("Failed to parse response: ${e.message}"))
                    }
                } else {
                    Result.failure(NatsException(response.error ?: "Handler failed"))
                }
            }
            is VaultResponse.Error -> {
                Result.failure(NatsException("${response.code}: ${response.message}"))
            }
            else -> Result.failure(NatsException("Unexpected response type"))
        }
    }
}

// MARK: - Data Models

/**
 * Result of a credential refresh request.
 */
data class CredentialRefreshResult(
    /** NATS credentials (.creds file content) */
    val credentials: String,
    /** ISO 8601 expiration time */
    val expiresAt: String,
    /** Time to live in seconds */
    val ttlSeconds: Long,
    /** Unique credential ID for tracking */
    val credentialId: String
) {
    /**
     * Parse expiration time to Instant.
     */
    fun expiresAtInstant(): Instant? {
        return try {
            Instant.parse(expiresAt)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Status of current credentials.
 */
data class CredentialStatus(
    /** Whether credentials are currently valid */
    val valid: Boolean,
    /** ISO 8601 expiration time */
    val expiresAt: String,
    /** Seconds remaining until expiry */
    val remainingSeconds: Long
)

/**
 * Credential rotation event from vault.
 */
data class CredentialRotationEvent(
    /** New NATS credentials (.creds file content) */
    val credentials: String,
    /** ISO 8601 expiration time */
    val expiresAt: String,
    /** Time to live in seconds */
    val ttlSeconds: Long,
    /** Unique credential ID for tracking */
    val credentialId: String,
    /** Reason for rotation: "scheduled_rotation" or "expiry_imminent" */
    val reason: String,
    /** Previous credential ID that was rotated out */
    val oldCredentialId: String?
)
