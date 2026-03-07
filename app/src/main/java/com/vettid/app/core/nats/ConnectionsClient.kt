package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            addProperty("expires_in_minutes", expiresInMinutes)
        }

        return sendAndAwait("connection.create-invite", payload) { result ->
            // Backend field names may differ from our model names:
            // - "credentials" or "nats_credentials" for the NATS credentials
            // - "owner_space" or "owner_space_id" for owner space
            // - "message_space" or "message_space_id" or "message_space_topic" for message space
            val inviterProfile = mutableMapOf<String, String>()
            result.getAsJsonObject("inviter_profile")?.entrySet()?.forEach { entry ->
                entry.value?.asString?.let { inviterProfile[entry.key] = it }
            }
            // Use vault-returned label (which may be richer) or fallback to what we sent
            val vaultLabel = result.get("label")?.asString ?: label
            ConnectionInvitation(
                connectionId = result.get("connection_id")?.asString ?: "",
                peerGuid = peerGuid,
                label = vaultLabel,
                natsCredentials = result.get("credentials")?.asString
                    ?: result.get("nats_credentials")?.asString ?: "",
                ownerSpaceId = result.get("owner_space")?.asString
                    ?: result.get("owner_space_id")?.asString ?: "",
                messageSpaceId = result.get("message_space")?.asString
                    ?: result.get("message_space_id")?.asString
                    ?: result.get("message_space_topic")?.asString ?: "",
                expiresAt = result.get("expires_at")?.asString ?: "",
                inviterProfile = inviterProfile
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
     * Fetch a peer's published profile by connecting to NATS with invitation credentials.
     *
     * Creates a temporary NATS connection using the scoped credentials from the QR code,
     * then reads the peer's retained profile from JetStream.
     *
     * @param natsCredentials NATS .creds file content from QR code
     * @param natsEndpoint NATS server endpoint (tls://host:port)
     * @param ownerSpace Peer's owner space ID
     * @return Map of profile fields (e.g. _system_first_name, _system_last_name, _system_email)
     */
    suspend fun fetchPeerProfile(
        natsCredentials: String,
        natsEndpoint: String,
        ownerSpace: String
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        val client = AndroidNatsClient()
        try {
            // Parse JWT and seed from .creds file format
            val (jwt, seed) = parseCredsFile(natsCredentials)
                ?: return@withContext Result.failure(NatsException("Invalid NATS credentials format"))

            Log.d(TAG, "Connecting to peer NATS for profile fetch: $natsEndpoint")

            // Connect with the scoped invitation credentials
            val connectResult = client.connect(natsEndpoint, jwt, seed)
            if (connectResult.isFailure) {
                return@withContext Result.failure(
                    NatsException("Failed to connect to peer NATS: ${connectResult.exceptionOrNull()?.message}")
                )
            }

            // Create a JetStream consumer to read the retained profile
            val jsClient = JetStreamNatsClient()
            jsClient.initialize(client)

            val profileSubject = "OwnerSpace.$ownerSpace.forApp.profile.public"

            // Use a direct JetStream consumer to fetch the last retained profile message
            val consumerName = "profile-${System.currentTimeMillis()}"
            val createRequest = JsonObject().apply {
                addProperty("stream_name", "ENROLLMENT")
                add("config", JsonObject().apply {
                    addProperty("name", consumerName)
                    addProperty("filter_subject", profileSubject)
                    addProperty("deliver_policy", "last")
                    addProperty("ack_policy", "none")
                    addProperty("max_deliver", 1)
                    addProperty("num_replicas", 1)
                    addProperty("mem_storage", true)
                })
            }

            val createSubject = "\$JS.API.CONSUMER.CREATE.ENROLLMENT.$consumerName"
            val createResult = client.request(
                createSubject,
                gson.toJson(createRequest).toByteArray(),
                timeoutMs = 10_000
            )

            if (createResult.isFailure) {
                Log.e(TAG, "Failed to create profile consumer: ${createResult.exceptionOrNull()?.message}")
                return@withContext Result.failure(
                    NatsException("Failed to read peer profile: ${createResult.exceptionOrNull()?.message}")
                )
            }

            // Check for JetStream errors
            val createResponse = gson.fromJson(createResult.getOrThrow().dataString, JsonObject::class.java)
            if (createResponse.has("error")) {
                val errMsg = createResponse.getAsJsonObject("error")?.get("description")?.asString ?: "Unknown error"
                Log.e(TAG, "JetStream error creating profile consumer: $errMsg")
                return@withContext Result.failure(NatsException("Failed to read peer profile: $errMsg"))
            }

            // Fetch the profile message
            val fetchSubject = "\$JS.API.CONSUMER.MSG.NEXT.ENROLLMENT.$consumerName"
            val fetchRequest = JsonObject().apply {
                addProperty("batch", 1)
                addProperty("expires", 10_000_000_000L) // 10s in nanoseconds
            }

            val fetchResult = client.request(
                fetchSubject,
                gson.toJson(fetchRequest).toByteArray(),
                timeoutMs = 15_000
            )

            // Clean up consumer
            try {
                client.publish("\$JS.API.CONSUMER.DELETE.ENROLLMENT.$consumerName", "{}".toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete profile consumer: ${e.message}")
            }

            if (fetchResult.isFailure || fetchResult.getOrNull()?.data?.isEmpty() == true) {
                Log.w(TAG, "No retained profile found for $ownerSpace")
                return@withContext Result.success(emptyMap())
            }

            // Parse profile JSON (PublishedProfile struct from vault-manager)
            val profileData = fetchResult.getOrThrow().dataString
            Log.d(TAG, "Fetched peer profile: ${profileData.take(200)}")

            val profileJson = gson.fromJson(profileData, JsonObject::class.java)
            val profile = mutableMapOf<String, String>()

            // Extract top-level fields (first_name, last_name, email)
            profileJson.get("first_name")?.asString?.let { profile["_system_first_name"] = it }
            profileJson.get("last_name")?.asString?.let { profile["_system_last_name"] = it }
            profileJson.get("email")?.asString?.let { profile["_system_email"] = it }
            profileJson.get("user_guid")?.asString?.let { profile["user_guid"] = it }
            profileJson.get("public_key")?.asString?.let { profile["public_key"] = it }

            // Extract fields map (key -> {display_name, value, field_type})
            profileJson.getAsJsonObject("fields")?.entrySet()?.forEach { entry ->
                val fieldObj = entry.value?.asJsonObject
                val value = fieldObj?.get("value")?.asString
                if (value != null) {
                    profile[entry.key] = value
                }
            }

            Log.i(TAG, "Fetched peer profile with ${profile.size} fields")
            Result.success(profile.toMap())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch peer profile: ${e.message}", e)
            Result.failure(NatsException("Failed to fetch peer profile: ${e.message}"))
        } finally {
            try {
                client.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting peer profile client: ${e.message}")
            }
        }
    }

    /**
     * Parse a NATS .creds file into JWT and seed components.
     *
     * Format:
     * -----BEGIN NATS USER JWT-----
     * <jwt>
     * ------END NATS USER JWT------
     * ...
     * -----BEGIN USER NKEY SEED-----
     * <seed>
     * ------END USER NKEY SEED------
     */
    private fun parseCredsFile(creds: String): Pair<String, String>? {
        val jwtMatch = Regex("-----BEGIN NATS USER JWT-----\\s*([^\\s-]+)\\s*------END NATS USER JWT------")
            .find(creds) ?: return null
        val seedMatch = Regex("-----BEGIN USER NKEY SEED-----\\s*([^\\s-]+)\\s*------END USER NKEY SEED------")
            .find(creds) ?: return null
        return Pair(jwtMatch.groupValues[1].trim(), seedMatch.groupValues[1].trim())
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
    val expiresAt: String,
    val inviterProfile: Map<String, String> = emptyMap()
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
