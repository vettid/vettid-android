package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for managing peer-to-peer connections via vault handlers.
 *
 * Uses OwnerSpaceClient.sendAndAwaitResponse() for proper request-response
 * correlation by event_id, avoiding race conditions.
 *
 * Connection handlers enable secure peer messaging by:
 * 1. Creating invitation credentials for connecting to this vault
 * 2. Storing credentials received from peer invitations
 * 3. Managing credential lifecycle (rotation, revocation)
 *
 * All operations go through the vault-manager NATS handlers and require
 * the vault EC2 instance to be online.
 */
@Singleton
class ConnectionsClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    private val gson = Gson()

    /**
     * Create an invitation for a peer to connect to this vault.
     *
     * The invitation contains NATS credentials scoped to the owner's message space,
     * allowing the peer to publish messages that this vault can receive.
     *
     * @param peerGuid GUID of the peer being invited
     * @param label Human-readable label for this connection
     * @param expiresInMinutes How long the invitation is valid (default: 1440 minutes = 24 hours)
     * @return Connection invitation with credentials
     */
    suspend fun createInvite(
        peerGuid: String,
        label: String,
        expiresInMinutes: Int = 1440
    ): Result<ConnectionInvitation> {
        val payload = JsonObject().apply {
            addProperty("peer_guid", peerGuid)
            addProperty("label", label)
            // Send both formats for backward compatibility until enclave is updated
            addProperty("expires_in_minutes", expiresInMinutes)
            addProperty("expires_in_hours", (expiresInMinutes / 60).coerceAtLeast(1))
        }

        return sendAndAwait("connection.create-invite", payload) { result ->
            // Backend field names may differ from our model names:
            // - "credentials" or "nats_credentials" for the NATS credentials
            // - "owner_space" or "owner_space_id" for owner space
            // - "message_space" or "message_space_id" or "message_space_topic" for message space
            ConnectionInvitation(
                connectionId = result.get("connection_id")?.asString ?: "",
                peerGuid = peerGuid,
                label = label,
                natsCredentials = result.get("credentials")?.asString
                    ?: result.get("nats_credentials")?.asString ?: "",
                ownerSpaceId = result.get("owner_space")?.asString
                    ?: result.get("owner_space_id")?.asString ?: "",
                messageSpaceId = result.get("message_space")?.asString
                    ?: result.get("message_space_id")?.asString
                    ?: result.get("message_space_topic")?.asString ?: "",
                expiresAt = result.get("expires_at")?.asString ?: ""
            )
        }
    }

    /**
     * Store credentials received from a peer's invitation.
     *
     * Call this after receiving an invitation from another vault owner.
     * The credentials allow this vault to send messages to the peer's message space.
     *
     * @param connectionId Unique ID for this connection
     * @param peerGuid GUID of the peer who sent the invitation
     * @param label Human-readable label for this connection
     * @param natsCredentials NATS credentials from the invitation
     * @param peerOwnerSpaceId Peer's owner space ID (for receiving their messages)
     * @param peerMessageSpaceId Peer's message space ID (for sending to them)
     * @return Stored connection record
     */
    suspend fun storeCredentials(
        connectionId: String,
        peerGuid: String,
        label: String,
        natsCredentials: String,
        peerOwnerSpaceId: String,
        peerMessageSpaceId: String
    ): Result<ConnectionRecord> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("peer_guid", peerGuid)
            addProperty("label", label)
            addProperty("nats_credentials", natsCredentials)
            addProperty("peer_owner_space_id", peerOwnerSpaceId)
            addProperty("peer_message_space_id", peerMessageSpaceId)
        }

        android.util.Log.d(TAG, "storeCredentials payload: connection_id=$connectionId, peer_guid=$peerGuid")
        android.util.Log.d(TAG, "storeCredentials payload: nats_creds_len=${natsCredentials.length}, peer_owner_space=$peerOwnerSpaceId, peer_msg_space=$peerMessageSpaceId")

        return sendAndAwait("connection.store-credentials", payload) { result ->
            parseConnectionRecord(result)
        }
    }

    /**
     * Rotate credentials for a connection.
     *
     * Generates new NATS credentials for the peer, invalidating old ones.
     * Use this periodically or after suspected compromise.
     *
     * @param connectionId The connection to rotate
     * @return Updated connection with new credentials
     */
    suspend fun rotate(connectionId: String): Result<ConnectionRecord> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
        }

        return sendAndAwait("connection.rotate", payload) { result ->
            parseConnectionRecord(result)
        }
    }

    /**
     * Revoke a connection.
     *
     * Permanently invalidates the connection credentials. The peer will
     * no longer be able to send messages to this vault.
     *
     * @param connectionId The connection to revoke
     * @return Success confirmation
     */
    suspend fun revoke(connectionId: String): Result<Boolean> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
        }

        return sendAndAwait("connection.revoke", payload) { result ->
            result.get("success")?.asBoolean ?: false
        }
    }

    /**
     * List all connections.
     *
     * @param status Optional filter by status ("active", "revoked", "expired")
     * @param limit Maximum number of results (default: 50)
     * @param cursor Pagination cursor for next page
     * @return List of connection records
     */
    suspend fun list(
        status: String? = null,
        limit: Int = 50,
        cursor: String? = null
    ): Result<ConnectionListResult> {
        val payload = JsonObject().apply {
            status?.let { addProperty("status", it) }
            addProperty("limit", limit)
            cursor?.let { addProperty("cursor", it) }
        }

        return sendAndAwait("connection.list", payload) { result ->
            val items = (result.getAsJsonArray("connections") ?: result.getAsJsonArray("items"))?.map { item ->
                parseConnectionRecord(item.asJsonObject)
            } ?: emptyList()

            ConnectionListResult(
                items = items,
                nextCursor = result.get("next_cursor")?.asString
            )
        }
    }

    /**
     * Get credentials for a specific connection.
     *
     * Returns the NATS credentials needed to communicate with the peer.
     *
     * @param connectionId The connection ID
     * @return Connection credentials
     */
    suspend fun getCredentials(connectionId: String): Result<ConnectionCredentials> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
        }

        return sendAndAwait("connection.get-credentials", payload) { result ->
            ConnectionCredentials(
                connectionId = connectionId,
                natsCredentials = result.get("nats_credentials")?.asString ?: "",
                peerMessageSpaceId = result.get("peer_message_space_id")?.asString ?: "",
                expiresAt = result.get("expires_at")?.asString
            )
        }
    }

    /**
     * Send a request using OwnerSpaceClient.sendAndAwaitResponse() for proper
     * request-response correlation by event_id. This avoids race conditions
     * that occur when using JetStream's subject-based filtering.
     */
    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = 30_000,
        transform: (JsonObject) -> T
    ): Result<T> {
        Log.d(TAG, "Sending $messageType request via OwnerSpaceClient")

        return try {
            val response = ownerSpaceClient.sendAndAwaitResponse(messageType, payload, timeoutMs)

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        Log.d(TAG, "$messageType response received: ${response.result.toString().take(200)}")
                        try {
                            val parsed = transform(response.result)
                            Result.success(parsed)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse $messageType response", e)
                            Result.failure(e)
                        }
                    } else {
                        val error = response.error ?: "Request failed"
                        Log.w(TAG, "$messageType failed: $error")
                        Result.failure(NatsException(error))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "$messageType error: ${response.code} - ${response.message}")
                    Result.failure(NatsException(response.message))
                }
                null -> {
                    Log.e(TAG, "$messageType request timed out")
                    Result.failure(NatsException("Request timed out"))
                }
                else -> {
                    Log.w(TAG, "$messageType unexpected response type: ${response::class.simpleName}")
                    Result.failure(NatsException("Unexpected response type"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Rethrow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "$messageType failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseConnectionRecord(json: JsonObject): ConnectionRecord {
        return ConnectionRecord(
            connectionId = json.get("connection_id")?.asString ?: "",
            peerGuid = json.get("peer_guid")?.asString ?: "",
            label = json.get("label")?.asString ?: json.get("peer_alias")?.asString ?: "",
            status = json.get("status")?.asString ?: "unknown",
            direction = json.get("direction")?.asString ?: json.get("credentials_type")?.asString ?: "unknown",
            createdAt = json.get("created_at")?.asString ?: "",
            expiresAt = json.get("expires_at")?.asString,
            lastRotatedAt = json.get("last_rotated_at")?.asString
        )
    }

    companion object {
        private const val TAG = "ConnectionsClient"
    }
}

// MARK: - Data Models

/**
 * Invitation to connect with a peer.
 */
data class ConnectionInvitation(
    val connectionId: String,
    val peerGuid: String,
    val label: String,
    val natsCredentials: String,  // NATS .creds file content
    val ownerSpaceId: String,
    val messageSpaceId: String,
    val expiresAt: String
)

/**
 * Stored connection record.
 */
data class ConnectionRecord(
    val connectionId: String,
    val peerGuid: String,
    val label: String,
    val status: String,        // "active", "revoked", "expired"
    val direction: String,     // "outbound" (we invited) or "inbound" (they invited us)
    val createdAt: String,
    val expiresAt: String?,
    val lastRotatedAt: String?
)

/**
 * Connection list result with pagination.
 */
data class ConnectionListResult(
    val items: List<ConnectionRecord>,
    val nextCursor: String?
)

/**
 * Connection credentials for peer communication.
 */
data class ConnectionCredentials(
    val connectionId: String,
    val natsCredentials: String,  // NATS .creds file content
    val peerMessageSpaceId: String,
    val expiresAt: String?
)
