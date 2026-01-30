package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for managing peer-to-peer connections via vault handlers.
 *
 * Connection handlers enable secure peer messaging by:
 * 1. Creating invitation credentials for connecting to this vault
 * 2. Storing credentials received from peer invitations
 * 3. Managing credential lifecycle (rotation, revocation)
 *
 * All operations go through the vault-manager NATS handlers and require
 * the vault EC2 instance to be online.
 *
 * Uses JetStream for reliable message delivery since the enclave publishes
 * responses to the ENROLLMENT stream.
 */
@Singleton
class ConnectionsClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) {
    private val gson = Gson()
    private var jetStreamClient: JetStreamNatsClient? = null

    /**
     * Initialize JetStream client for reliable message delivery.
     */
    private fun ensureJetStreamInitialized(): Result<JetStreamNatsClient> {
        jetStreamClient?.let { client ->
            if (client.isConnected) return Result.success(client)
        }

        val natsClient = connectionManager.getNatsClient()
        if (!natsClient.isConnected) {
            return Result.failure(NatsException("NATS not connected"))
        }

        val androidClient = natsClient.getAndroidClient()
        val jsClient = JetStreamNatsClient()
        jsClient.initialize(androidClient)
        jetStreamClient = jsClient

        return Result.success(jsClient)
    }

    /**
     * Create an invitation for a peer to connect to this vault.
     *
     * The invitation contains NATS credentials scoped to the owner's message space,
     * allowing the peer to publish messages that this vault can receive.
     *
     * @param peerGuid GUID of the peer being invited
     * @param label Human-readable label for this connection
     * @param expiresInHours How long the invitation is valid (default: 24 hours)
     * @return Connection invitation with credentials
     */
    suspend fun createInvite(
        peerGuid: String,
        label: String,
        expiresInHours: Int = 24
    ): Result<ConnectionInvitation> {
        val payload = JsonObject().apply {
            addProperty("peer_guid", peerGuid)
            addProperty("label", label)
            addProperty("expires_in_hours", expiresInHours)
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
            val items = result.getAsJsonArray("items")?.map { item ->
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

    // Helper to send message and await response using JetStream
    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = 30_000,
        transform: (JsonObject) -> T
    ): Result<T> {
        val ownerSpace = connectionManager.getOwnerSpaceId()
            ?: return Result.failure(NatsException("No OwnerSpace ID available"))

        // Use JetStream for reliable delivery (enclave publishes to JetStream)
        val jsResult = ensureJetStreamInitialized()
        if (jsResult.isFailure) {
            Log.e(TAG, "JetStream not available: ${jsResult.exceptionOrNull()?.message}")
            return Result.failure(jsResult.exceptionOrNull() ?: NatsException("JetStream not available"))
        }

        val jsClient = jsResult.getOrThrow()
        Log.d(TAG, "Using JetStream for $messageType")

        val requestId = UUID.randomUUID().toString()
        val request = JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", messageType)
            addProperty("timestamp", java.time.Instant.now().toString())
            // Merge the payload fields
            payload.entrySet().forEach { (key, value) ->
                add(key, value)
            }
        }

        val topic = "OwnerSpace.$ownerSpace.forVault.$messageType"
        val responseTopic = "OwnerSpace.$ownerSpace.forApp.$messageType.response"

        return try {
            val response = jsClient.requestWithJetStream(
                requestSubject = topic,
                responseSubject = responseTopic,
                payload = gson.toJson(request).toByteArray(Charsets.UTF_8),
                timeoutMs = timeoutMs
            )

            response.fold(
                onSuccess = { msg ->
                    Log.d(TAG, "$messageType response received: ${msg.dataString.take(200)}")
                    val jsonResponse = gson.fromJson(msg.dataString, JsonObject::class.java)

                    // Check for error response
                    if (jsonResponse.has("error") && !jsonResponse.get("error").isJsonNull) {
                        val error = jsonResponse.get("error").asString
                        Log.w(TAG, "$messageType failed: $error")
                        Result.failure(NatsException(error))
                    } else if (jsonResponse.has("success") && jsonResponse.get("success").asBoolean == false) {
                        val error = jsonResponse.get("error")?.asString ?: "Request failed"
                        Log.w(TAG, "$messageType failed: $error")
                        Result.failure(NatsException(error))
                    } else {
                        // Extract result field or use whole response
                        val resultObj = if (jsonResponse.has("result")) {
                            jsonResponse.getAsJsonObject("result")
                        } else {
                            jsonResponse
                        }
                        try {
                            val parsed = transform(resultObj)
                            Result.success(parsed)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse $messageType response", e)
                            Result.failure(e)
                        }
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "$messageType request failed: ${e.message}", e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "JetStream $messageType failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseConnectionRecord(json: JsonObject): ConnectionRecord {
        return ConnectionRecord(
            connectionId = json.get("connection_id")?.asString ?: "",
            peerGuid = json.get("peer_guid")?.asString ?: "",
            label = json.get("label")?.asString ?: "",
            status = json.get("status")?.asString ?: "unknown",
            direction = json.get("direction")?.asString ?: "unknown",
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
