package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for vault-to-vault messaging via NATS.
 *
 * Messages flow: App → Vault (OwnerSpace.forVault) → Peer Vault (MessageSpace) → Peer App
 *
 * Handlers:
 * - `message.send` - Send encrypted message to peer vault
 * - `message.read-receipt` - Send read receipt to sender vault
 * - `profile.broadcast` - Broadcast profile updates to all connections
 * - `connection.notify-revoke` - Notify peer of connection revocation
 */
@Singleton
class NatsMessagingClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "NatsMessagingClient"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    /**
     * Send an encrypted message to a peer via their vault.
     *
     * @param connectionId The connection ID (peer relationship)
     * @param encryptedContent Base64-encoded encrypted message content
     * @param nonce Base64-encoded encryption nonce
     * @param contentType Message content type (default: "text")
     * @return Sent message info with server-assigned ID and timestamp
     */
    suspend fun sendMessage(
        connectionId: String,
        encryptedContent: String,
        nonce: String,
        contentType: String = "text"
    ): Result<SentMessage> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("encrypted_content", encryptedContent)
            addProperty("nonce", nonce)
            addProperty("content_type", contentType)
        }

        Log.d(TAG, "Sending message to connection: $connectionId")

        return sendAndAwait("message.send", payload) { result ->
            SentMessage(
                messageId = result.get("message_id")?.asString ?: "",
                connectionId = connectionId,
                timestamp = result.get("timestamp")?.asString ?: "",
                status = result.get("status")?.asString ?: "sent"
            )
        }
    }

    /**
     * Send a read receipt to the sender vault.
     *
     * @param connectionId The connection ID
     * @param messageId The message ID that was read
     * @return Success indicator
     */
    suspend fun sendReadReceipt(
        connectionId: String,
        messageId: String
    ): Result<Boolean> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("message_id", messageId)
        }

        Log.d(TAG, "Sending read receipt for message: $messageId")

        return sendAndAwait("message.read-receipt", payload) { result ->
            result.get("success")?.asBoolean ?: true
        }
    }

    /**
     * Broadcast a profile update to all active connections.
     *
     * @param displayName Updated display name
     * @param avatarUrl Updated avatar URL (optional)
     * @param status Updated status message (optional)
     * @return Number of connections notified
     */
    suspend fun broadcastProfileUpdate(
        displayName: String? = null,
        avatarUrl: String? = null,
        status: String? = null
    ): Result<Int> {
        val profileUpdates = JsonObject().apply {
            displayName?.let { addProperty("display_name", it) }
            avatarUrl?.let { addProperty("avatar_url", it) }
            status?.let { addProperty("status", it) }
        }

        val payload = JsonObject().apply {
            add("profile", profileUpdates)
        }

        Log.d(TAG, "Broadcasting profile update")

        return sendAndAwait("profile.broadcast", payload) { result ->
            result.get("notified_count")?.asInt ?: 0
        }
    }

    /**
     * Notify a peer that the connection has been revoked.
     *
     * @param connectionId The connection ID being revoked
     * @param reason Reason for revocation (optional)
     * @return Success indicator
     */
    suspend fun notifyConnectionRevoked(
        connectionId: String,
        reason: String? = null
    ): Result<Boolean> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            reason?.let { addProperty("reason", it) }
        }

        Log.d(TAG, "Notifying connection revocation: $connectionId")

        return sendAndAwait("connection.notify-revoke", payload) { result ->
            result.get("success")?.asBoolean ?: true
        }
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
 * Result of sending a message.
 */
data class SentMessage(
    /** Server-assigned message ID */
    val messageId: String,
    /** Connection ID the message was sent to */
    val connectionId: String,
    /** ISO 8601 timestamp when message was sent */
    val timestamp: String,
    /** Message status (sent, delivered, etc.) */
    val status: String
)

/**
 * Incoming message from a peer.
 */
data class IncomingMessage(
    /** Unique message ID */
    val messageId: String,
    /** Connection ID this message is from */
    val connectionId: String,
    /** Sender's user GUID */
    val senderGuid: String,
    /** Base64-encoded encrypted content */
    val encryptedContent: String,
    /** Base64-encoded nonce */
    val nonce: String,
    /** Content type (text, image, file, etc.) */
    val contentType: String,
    /** ISO 8601 timestamp */
    val sentAt: String
)

/**
 * Read receipt from a peer.
 */
data class ReadReceipt(
    /** The message ID that was read */
    val messageId: String,
    /** Connection ID */
    val connectionId: String,
    /** Reader's user GUID */
    val readerGuid: String,
    /** ISO 8601 timestamp when message was read */
    val readAt: String
)

/**
 * Profile update from a peer.
 */
data class ProfileUpdate(
    /** Connection ID */
    val connectionId: String,
    /** Peer's user GUID */
    val peerGuid: String,
    /** Updated display name (if changed) */
    val displayName: String?,
    /** Updated avatar URL (if changed) */
    val avatarUrl: String?,
    /** Updated status (if changed) */
    val status: String?,
    /** ISO 8601 timestamp of update */
    val updatedAt: String
)

/**
 * Connection revocation notice from a peer.
 */
data class ConnectionRevoked(
    /** The connection ID that was revoked */
    val connectionId: String,
    /** Peer's user GUID */
    val peerGuid: String,
    /** Reason for revocation (if provided) */
    val reason: String?,
    /** ISO 8601 timestamp */
    val revokedAt: String
)
