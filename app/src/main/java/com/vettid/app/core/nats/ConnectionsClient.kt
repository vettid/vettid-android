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
            val inviterWallets = mutableListOf<Map<String, String>>()
            result.getAsJsonObject("inviter_profile")?.let { profileObj ->
                profileObj.entrySet().forEach { entry ->
                    if (entry.key == "wallets" && entry.value?.isJsonArray == true) {
                        entry.value.asJsonArray.forEach { walletEl ->
                            val w = walletEl?.asJsonObject ?: return@forEach
                            inviterWallets.add(mapOf(
                                "wallet_id" to (w.get("wallet_id")?.asString ?: ""),
                                "label" to (w.get("label")?.asString ?: "Wallet"),
                                "address" to (w.get("address")?.asString ?: ""),
                                "network" to (w.get("network")?.asString ?: "mainnet")
                            ))
                        }
                    } else {
                        try { entry.value?.asString?.let { inviterProfile[entry.key] = it } }
                        catch (_: Exception) { /* skip non-string values */ }
                    }
                }
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
                inviterProfile = inviterProfile,
                inviteCode = result.get("invite_code")?.asString ?: "",
                inviterWallets = inviterWallets
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
        peerMessageSpaceId: String,
        peerProfile: Map<String, String>? = null
    ): Result<ConnectionRecord> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("peer_guid", peerGuid)
            addProperty("label", label)
            addProperty("nats_credentials", natsCredentials)
            addProperty("peer_owner_space_id", peerOwnerSpaceId)
            addProperty("peer_message_space_id", peerMessageSpaceId)
            if (peerProfile != null) {
                val profileObj = JsonObject()
                peerProfile.forEach { (key, value) -> profileObj.addProperty(key, value) }
                add("peer_profile", profileObj)
            }
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

            // Extract top-level fields (first_name, last_name, email, photo)
            profileJson.get("first_name")?.asString?.let { profile["_system_first_name"] = it }
            profileJson.get("last_name")?.asString?.let { profile["_system_last_name"] = it }
            profileJson.get("email")?.asString?.let { profile["_system_email"] = it }
            profileJson.get("user_guid")?.asString?.let { profile["user_guid"] = it }
            profileJson.get("public_key")?.asString?.let { profile["public_key"] = it }
            profileJson.get("photo")?.takeIf { !it.isJsonNull }?.asString?.let { profile["photo"] = it }

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
     * Notify the inviter's vault that we accepted the connection.
     *
     * Creates a temporary NATS connection using the invitation credentials
     * and publishes an acceptance notification to the inviter's message space.
     * This is fire-and-forget - failure is non-fatal.
     *
     * @param natsCredentials NATS .creds file content from QR code
     * @param natsEndpoint NATS server endpoint
     * @param peerOwnerSpace Inviter's owner space ID
     * @param connectionId Connection ID from the invitation
     * @param accepterGuid Our user GUID
     * @param e2ePublicKey Our E2E public key (hex) from storeCredentials response
     */
    suspend fun notifyPeerOfAcceptance(
        natsCredentials: String,
        natsEndpoint: String,
        peerOwnerSpace: String,
        connectionId: String,
        accepterGuid: String,
        e2ePublicKey: String? = null,
        accepterProfile: Map<String, String>? = null
    ) = withContext(Dispatchers.IO) {
        val client = AndroidNatsClient()
        try {
            val (jwt, seed) = parseCredsFile(natsCredentials)
                ?: return@withContext

            Log.d(TAG, "Connecting to peer NATS to send acceptance notification")

            val connectResult = client.connect(natsEndpoint, jwt, seed)
            if (connectResult.isFailure) {
                Log.w(TAG, "Failed to connect for acceptance notification: ${connectResult.exceptionOrNull()?.message}")
                return@withContext
            }

            // Build acceptance notification
            val notification = JsonObject().apply {
                addProperty("connection_id", connectionId)
                addProperty("peer_guid", accepterGuid)
                e2ePublicKey?.let { addProperty("e2e_public_key", it) }
            }

            // Include our profile so the inviter can see our name/email
            if (accepterProfile != null && accepterProfile.isNotEmpty()) {
                val profileObj = JsonObject()
                accepterProfile.forEach { (key, value) -> profileObj.addProperty(key, value) }
                notification.add("peer_profile", profileObj)
            }

            // Publish to the inviter's connection notification topic
            val subject = "MessageSpace.$peerOwnerSpace.forOwner.connection.accepted"
            val payload = gson.toJson(notification).toByteArray()

            client.publish(subject, payload)
            Log.i(TAG, "Published acceptance notification to $subject")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send acceptance notification: ${e.message}")
        } finally {
            try {
                client.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting notification client: ${e.message}")
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
        val jwtMatch = Regex("-----BEGIN NATS USER JWT-----\\s*(.+?)\\s*------END NATS USER JWT------", RegexOption.DOT_MATCHES_ALL)
            .find(creds) ?: return null
        val seedMatch = Regex("-----BEGIN USER NKEY SEED-----\\s*(.+?)\\s*------END USER NKEY SEED------", RegexOption.DOT_MATCHES_ALL)
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
        val peerProfile = json.getAsJsonObject("peer_profile")?.let { profileObj ->
            // Parse nested fields object if present
            val nestedFields = profileObj.getAsJsonObject("fields")?.let { fieldsObj ->
                val map = mutableMapOf<String, Map<String, String>>()
                fieldsObj.entrySet().forEach { (key, value) ->
                    val fieldObj = value?.asJsonObject
                    if (fieldObj != null) {
                        map[key] = mapOf(
                            "display_name" to (fieldObj.get("display_name")?.asString ?: key),
                            "value" to (fieldObj.get("value")?.asString ?: "")
                        )
                    }
                }
                map
            }

            // If no nested fields, build fields from top-level system entries
            val fields = if (nestedFields.isNullOrEmpty()) {
                val fallback = mutableMapOf<String, Map<String, String>>()
                profileObj.entrySet().forEach { (key, value) ->
                    if (key.startsWith("_system_") && value.isJsonPrimitive && value.asString.isNotBlank()) {
                        val displayName = key.removePrefix("_system_")
                            .replace("_", " ")
                            .replaceFirstChar { it.uppercase() }
                        fallback[key] = mapOf(
                            "display_name" to displayName,
                            "value" to value.asString
                        )
                    }
                }
                fallback.ifEmpty { null }
            } else {
                nestedFields
            }

            // Parse wallets array if present
            val wallets = profileObj.getAsJsonArray("wallets")?.mapNotNull { element ->
                val walletObj = element?.asJsonObject ?: return@mapNotNull null
                PeerWalletInfo(
                    walletId = walletObj.get("wallet_id")?.asString ?: "",
                    label = walletObj.get("label")?.asString ?: "Wallet",
                    address = walletObj.get("address")?.asString ?: "",
                    network = walletObj.get("network")?.asString ?: "mainnet"
                )
            }?.filter { it.address.isNotBlank() }

            // Handler catalog and public-secret metadata were added
            // later; tolerate their absence so older vaults keep
            // working without an upgrade prompt.
            val handlers = profileObj.getAsJsonArray("handlers")?.mapNotNull { element ->
                val obj = element?.asJsonObject ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                PeerHandlerInfo(
                    id = obj.get("id")?.asString ?: name,
                    name = name,
                    description = obj.get("description")?.asString ?: "",
                    operations = obj.getAsJsonArray("operations")
                        ?.mapNotNull { it?.asString }
                        ?: emptyList(),
                    category = obj.get("category")?.asString ?: "",
                    required = obj.get("required")?.asBoolean ?: false,
                    shareable = obj.get("shareable")?.asBoolean ?: false,
                )
            }.orEmpty()

            val publicSecrets = profileObj.getAsJsonArray("public_secrets")?.mapNotNull { element ->
                val obj = element?.asJsonObject ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                PeerPublicSecretMetadata(
                    name = name,
                    type = obj.get("type")?.asString ?: "",
                    category = obj.get("category")?.asString ?: "",
                    alias = obj.get("alias")?.asString ?: "",
                )
            }.orEmpty()

            val dataCatalog = profileObj.getAsJsonArray("data_catalog")?.mapNotNull { element ->
                val obj = element?.asJsonObject ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                PeerDataCatalogEntry(
                    name = name,
                    displayName = obj.get("display_name")?.asString ?: name,
                    fieldType = obj.get("field_type")?.asString ?: "",
                    category = obj.get("category")?.asString ?: "",
                    alias = obj.get("alias")?.asString ?: "",
                )
            }.orEmpty()

            val secretCatalog = profileObj.getAsJsonArray("secret_catalog")?.mapNotNull { element ->
                val obj = element?.asJsonObject ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                PeerPublicSecretMetadata(
                    name = name,
                    type = obj.get("type")?.asString ?: "",
                    category = obj.get("category")?.asString ?: "",
                    alias = obj.get("alias")?.asString ?: "",
                )
            }.orEmpty()

            // M3 / 2026-05-09: vault emits field_order so the user's
            // drag-to-reorder propagates to peer rendering. Falls back
            // to null if the published profile predates the change —
            // callers must then iterate fields' insertion order.
            val fieldOrder = profileObj.getAsJsonArray("field_order")?.mapNotNull { it?.asString }

            val profileData = PeerProfileData(
                firstName = profileObj.get("_system_first_name")?.asString,
                lastName = profileObj.get("_system_last_name")?.asString,
                email = profileObj.get("_system_email")?.asString,
                photo = profileObj.get("photo")?.takeIf { !it.isJsonNull }?.asString,
                fields = fields,
                fieldOrder = fieldOrder?.takeIf { it.isNotEmpty() },
                publicKey = profileObj.get("public_key")?.takeIf { !it.isJsonNull }?.asString,
                userGuid = profileObj.get("user_guid")?.takeIf { !it.isJsonNull }?.asString,
                wallets = wallets?.takeIf { it.isNotEmpty() },
                handlers = handlers.takeIf { it.isNotEmpty() },
                publicSecrets = publicSecrets.takeIf { it.isNotEmpty() },
                dataCatalog = dataCatalog.takeIf { it.isNotEmpty() },
                secretCatalog = secretCatalog.takeIf { it.isNotEmpty() },
            )
            // Diagnostic (2026-05-10): single-line summary of what we
            // got from the cached peer_profile, so we can see whether
            // the broadcast carried the expected keys without fighting
            // logcat truncation on the full connection.list response.
            android.util.Log.i(
                TAG,
                "PeerProfileData parsed: peer=${profileData.userGuid?.take(8)} pk=${profileData.publicKey?.take(8) ?: "<null>"} fields=${profileData.fields?.keys ?: "<null>"} catalog=${profileData.dataCatalog?.size ?: 0} fieldOrder=${profileData.fieldOrder ?: "<null>"}"
            )
            profileData
        }

        return ConnectionRecord(
            connectionId = json.get("connection_id")?.asString ?: "",
            peerGuid = json.get("peer_guid")?.asString ?: "",
            label = json.get("label")?.asString ?: json.get("peer_alias")?.asString ?: "",
            status = json.get("status")?.asString ?: "unknown",
            direction = json.get("direction")?.asString ?: json.get("credentials_type")?.asString ?: "unknown",
            connectionType = json.get("connection_type")?.asString ?: "peer",
            createdAt = json.get("created_at")?.asString ?: "",
            expiresAt = json.get("expires_at")?.asString,
            lastRotatedAt = json.get("last_rotated_at")?.asString,
            e2ePublicKey = json.get("e2e_public_key")?.asString,
            e2eReady = json.get("e2e_ready")?.asBoolean ?: false,
            needsAttention = json.get("needs_attention")?.asBoolean ?: false,
            lastMessagePreview = json.get("last_message_preview")?.takeIf { !it.isJsonNull }?.asString,
            lastMessageAt = json.get("last_message_at")?.takeIf { !it.isJsonNull }?.asString,
            lastMessageDirection = json.get("last_message_direction")?.takeIf { !it.isJsonNull }?.asString,
            unreadMessageCount = json.get("unread_count")?.asInt ?: 0,
            lastActivityType = json.get("last_activity_type")?.takeIf { !it.isJsonNull }?.asString,
            lastActivityAt = json.get("last_activity_at")?.takeIf { !it.isJsonNull }?.asString,
            lastActivityDirection = json.get("last_activity_direction")?.takeIf { !it.isJsonNull }?.asString,
            lastActivityTitle = json.get("last_activity_title")?.takeIf { !it.isJsonNull }?.asString,
            lastActivitySubtype = json.get("last_activity_subtype")?.takeIf { !it.isJsonNull }?.asString,
            lastActivityOutcome = json.get("last_activity_outcome")?.takeIf { !it.isJsonNull }?.asString,
            missedCallCount = json.get("missed_call_count")?.asInt ?: 0,
            peerProfile = peerProfile,
            presenceShareOverride = json.get("presence_share_override")?.takeIf { !it.isJsonNull }?.asBoolean,
        )
    }

    /**
     * Respond to a pending connection (accept or reject).
     * Used by the inviter to review and approve/decline the peer.
     *
     * Idempotent server-side (re-decisioning the same way is a
     * no-op), so on a JetStream timeout we retry — early test runs
     * surfaced "Connection failed" to the user when the underlying
     * vault op had actually succeeded but the response was stuck on
     * the parent reconnect. Two retries with exponential backoff
     * cover the typical reconnect window.
     */
    suspend fun respond(connectionId: String, response: String): Result<ConnectionRecord> {
        val payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
            addProperty("response", response)
        }

        var lastFailure: Throwable? = null
        val backoffsMs = longArrayOf(0L, 1_000L, 3_000L)
        for (attempt in backoffsMs.indices) {
            if (backoffsMs[attempt] > 0L) {
                kotlinx.coroutines.delay(backoffsMs[attempt])
            }
            val result = sendAndAwait("connection.respond", payload) { json ->
                parseConnectionRecord(json)
            }
            if (result.isSuccess) return result
            val err = result.exceptionOrNull()
            lastFailure = err
            val msg = err?.message.orEmpty()
            // Only retry the transient-looking failures. A
            // protocol-level error from the vault (e.g. "connection
            // is already terminal", "invalid response") shouldn't
            // get hammered.
            val isTransient = msg.contains("timed out", ignoreCase = true) ||
                msg.contains("TIMEOUT", ignoreCase = true) ||
                msg.contains("Failed to create consumer", ignoreCase = true) ||
                msg.contains("Failed to fetch", ignoreCase = true)
            if (!isTransient) return result
            android.util.Log.w(
                "ConnectionsClient",
                "connection.respond attempt ${attempt + 1} timed out; retrying. err=$msg",
            )
        }
        return Result.failure(lastFailure ?: Exception("connection.respond failed after retries"))
    }

    /**
     * Resolve an invite code via the vault's broker.
     * The vault fetches the invitation data from the NATS INVITATIONS stream.
     */
    suspend fun resolveInvite(inviteCode: String): Result<ResolvedInvitation> {
        val payload = JsonObject().apply {
            addProperty("invite_code", inviteCode)
        }

        return sendAndAwait("connection.resolve-invite", payload) { result ->
            val jwt = result.get("jwt")?.asString ?: ""
            val seed = result.get("seed")?.asString ?: ""
            // Reconstruct .creds format
            val natsCredentials = if (jwt.isNotEmpty() && seed.isNotEmpty()) {
                "-----BEGIN NATS USER JWT-----\n$jwt\n------END NATS USER JWT------\n\n-----BEGIN USER NKEY SEED-----\n$seed\n------END USER NKEY SEED------\n"
            } else ""

            // Extract inviter profile from broker payload
            val inviterProfile = mutableMapOf<String, String>()
            var inviterFields: Map<String, Map<String, String>>? = null
            var inviterFieldOrder: List<String>? = null
            val inviterWallets = mutableListOf<Map<String, String>>()
            val inviterHandlers = mutableListOf<PeerHandlerInfo>()
            val inviterPublicSecrets = mutableListOf<PeerPublicSecretMetadata>()
            val inviterDataCatalog = mutableListOf<PeerDataCatalogEntry>()
            val inviterSecretCatalog = mutableListOf<PeerPublicSecretMetadata>()
            result.getAsJsonObject("inviter_profile")?.let { profileObj ->
                profileObj.entrySet().forEach { (key, value) ->
                    when {
                        key == "fields" && value.isJsonObject -> {
                            // Parse nested profile fields
                            val fieldsMap = mutableMapOf<String, Map<String, String>>()
                            value.asJsonObject.entrySet().forEach { (fieldKey, fieldValue) ->
                                if (fieldValue.isJsonObject) {
                                    val fieldData = mutableMapOf<String, String>()
                                    fieldValue.asJsonObject.entrySet().forEach { (k, v) ->
                                        if (v.isJsonPrimitive) fieldData[k] = v.asString
                                    }
                                    fieldsMap[fieldKey] = fieldData
                                }
                            }
                            inviterFields = fieldsMap.ifEmpty { null }
                        }
                        key == "field_order" && value.isJsonArray -> {
                            // M3 / 2026-05-09: render fields in user-intended order.
                            inviterFieldOrder = value.asJsonArray.mapNotNull { it?.asString }
                                .takeIf { it.isNotEmpty() }
                        }
                        key == "wallets" && value.isJsonArray -> {
                            value.asJsonArray.forEach { walletEl ->
                                val w = walletEl?.asJsonObject ?: return@forEach
                                inviterWallets.add(mapOf(
                                    "wallet_id" to (w.get("wallet_id")?.asString ?: ""),
                                    "label" to (w.get("label")?.asString ?: "Wallet"),
                                    "address" to (w.get("address")?.asString ?: ""),
                                    "network" to (w.get("network")?.asString ?: "mainnet")
                                ))
                            }
                        }
                        key == "handlers" && value.isJsonArray -> {
                            value.asJsonArray.forEach { el ->
                                val obj = el?.asJsonObject ?: return@forEach
                                val name = obj.get("name")?.asString ?: return@forEach
                                inviterHandlers.add(
                                    PeerHandlerInfo(
                                        id = obj.get("id")?.asString ?: name,
                                        name = name,
                                        description = obj.get("description")?.asString ?: "",
                                        operations = obj.getAsJsonArray("operations")
                                            ?.mapNotNull { it?.asString }
                                            ?: emptyList(),
                                        category = obj.get("category")?.asString ?: "",
                                        required = obj.get("required")?.asBoolean ?: false,
                                        shareable = obj.get("shareable")?.asBoolean ?: false,
                                    )
                                )
                            }
                        }
                        key == "public_secrets" && value.isJsonArray -> {
                            value.asJsonArray.forEach { el ->
                                val obj = el?.asJsonObject ?: return@forEach
                                val name = obj.get("name")?.asString ?: return@forEach
                                inviterPublicSecrets.add(
                                    PeerPublicSecretMetadata(
                                        name = name,
                                        type = obj.get("type")?.asString ?: "",
                                        category = obj.get("category")?.asString ?: "",
                                        alias = obj.get("alias")?.asString ?: "",
                                    )
                                )
                            }
                        }
                        key == "data_catalog" && value.isJsonArray -> {
                            value.asJsonArray.forEach { el ->
                                val obj = el?.asJsonObject ?: return@forEach
                                val name = obj.get("name")?.asString ?: return@forEach
                                inviterDataCatalog.add(
                                    PeerDataCatalogEntry(
                                        name = name,
                                        displayName = obj.get("display_name")?.asString ?: name,
                                        fieldType = obj.get("field_type")?.asString ?: "",
                                        category = obj.get("category")?.asString ?: "",
                                        alias = obj.get("alias")?.asString ?: "",
                                    )
                                )
                            }
                        }
                        key == "secret_catalog" && value.isJsonArray -> {
                            value.asJsonArray.forEach { el ->
                                val obj = el?.asJsonObject ?: return@forEach
                                val name = obj.get("name")?.asString ?: return@forEach
                                inviterSecretCatalog.add(
                                    PeerPublicSecretMetadata(
                                        name = name,
                                        type = obj.get("type")?.asString ?: "",
                                        category = obj.get("category")?.asString ?: "",
                                        alias = obj.get("alias")?.asString ?: "",
                                    )
                                )
                            }
                        }
                        !value.isJsonNull && value.isJsonPrimitive -> {
                            inviterProfile[key] = value.asString
                        }
                    }
                }
            }

            ResolvedInvitation(
                connectionId = result.get("connection_id")?.asString ?: "",
                natsCredentials = natsCredentials,
                ownerSpaceId = result.get("owner_space")?.asString ?: "",
                messageSpaceId = result.get("message_space")?.asString ?: "",
                expiresAt = result.get("expires_at")?.asString ?: "",
                label = result.get("label")?.asString ?: "",
                inviterProfile = inviterProfile.ifEmpty { null },
                inviterFields = inviterFields,
                inviterFieldOrder = inviterFieldOrder,
                inviterWallets = inviterWallets,
                inviterHandlers = inviterHandlers.takeIf { it.isNotEmpty() },
                inviterPublicSecrets = inviterPublicSecrets.takeIf { it.isNotEmpty() },
                inviterDataCatalog = inviterDataCatalog.takeIf { it.isNotEmpty() },
                inviterSecretCatalog = inviterSecretCatalog.takeIf { it.isNotEmpty() },
            )
        }
    }

    companion object {
        private const val TAG = "ConnectionsClient"
    }
}

/**
 * Resolved invitation data from the broker.
 */
data class ResolvedInvitation(
    val connectionId: String,
    val natsCredentials: String,
    val ownerSpaceId: String,
    val messageSpaceId: String,
    val expiresAt: String,
    val label: String,
    val inviterProfile: Map<String, String>? = null,
    val inviterFields: Map<String, Map<String, String>>? = null,
    /** User-intended display order of [inviterFields]; may be null on older vaults. */
    val inviterFieldOrder: List<String>? = null,
    val inviterWallets: List<Map<String, String>> = emptyList(),
    val inviterHandlers: List<PeerHandlerInfo>? = null,
    val inviterPublicSecrets: List<PeerPublicSecretMetadata>? = null,
    val inviterDataCatalog: List<PeerDataCatalogEntry>? = null,
    val inviterSecretCatalog: List<PeerPublicSecretMetadata>? = null,
)

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
    val inviterProfile: Map<String, String> = emptyMap(),
    val inviteCode: String = "",  // Short code for QR broker lookup
    val inviterWallets: List<Map<String, String>> = emptyList()
)

/**
 * Stored connection record.
 */
data class ConnectionRecord(
    val connectionId: String,
    val peerGuid: String,
    val label: String,
    val status: String,        // "active", "pending", "revoked", "expired", "rejected"
    val direction: String,     // "outbound" (we invited) or "inbound" (they invited us)
    val connectionType: String = "peer", // "peer", "agent", "device"
    val createdAt: String,
    val expiresAt: String?,
    val lastRotatedAt: String?,
    val e2ePublicKey: String? = null,
    val e2eReady: Boolean = false,
    val needsAttention: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageDirection: String? = null, // "incoming" | "outgoing" | null
    val unreadMessageCount: Int = 0,
    // Merged interaction-history + call last-activity (vault picks the
    // newer one). "activity" carries lastActivityTitle (the AuditLog
    // entry title — same text the history screen's top row shows);
    // "call" carries subtype + outcome.
    val lastActivityType: String? = null,       // "activity" | "call"
    val lastActivityAt: String? = null,
    val lastActivityDirection: String? = null,  // "incoming" | "outgoing"
    val lastActivityTitle: String? = null,      // activity: AuditLog entry title
    val lastActivitySubtype: String? = null,    // calls: "voice" | "video"
    val lastActivityOutcome: String? = null,    // calls: "completed" | "missed" | "rejected"
    val missedCallCount: Int = 0,
    val peerProfile: PeerProfileData? = null,
    /**
     * Per-connection presence override. null = follow user-wide
     * default; true/false = explicit override. Surfaced on the
     * connection-detail screen so the user can override their
     * global presence setting per peer.
     */
    val presenceShareOverride: Boolean? = null,
)

/**
 * Cached peer profile data from the vault.
 */
data class PeerProfileData(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val photo: String? = null,
    val fields: Map<String, Map<String, String>>? = null,
    /**
     * User-intended display order of [fields]. Backend emits this
     * when present so peer + own preview render in the same order
     * the user established via drag-to-reorder. Absent on older
     * vaults — callers must fall back to fields' insertion order.
     */
    val fieldOrder: List<String>? = null,
    val publicKey: String? = null,
    val userGuid: String? = null,
    val wallets: List<PeerWalletInfo>? = null,
    // Vault capability catalog from the peer's published profile.
    val handlers: List<PeerHandlerInfo>? = null,
    /**
     * Legacy: secrets the peer flagged as public via profile.publish.
     * Newer enclaves carry the same content (and more) in [secretCatalog];
     * kept for backwards compat with older vault builds.
     */
    val publicSecrets: List<PeerPublicSecretMetadata>? = null,
    /**
     * Full data catalog — every personal-data entry the peer has
     * cataloged or made public. Metadata only — peers can see "Al
     * has a Mobile Phone" without seeing the value, then request
     * access through the capability flow.
     */
    val dataCatalog: List<PeerDataCatalogEntry>? = null,
    /**
     * Full secret catalog — every credential secret the peer has
     * cataloged or made public. Same metadata-only model as
     * [dataCatalog].
     */
    val secretCatalog: List<PeerPublicSecretMetadata>? = null,
)

/**
 * One personal-data catalog entry on a peer's published profile.
 * Metadata only — never the value.
 */
data class PeerDataCatalogEntry(
    val name: String,
    val displayName: String,
    val fieldType: String,
    val category: String,
    val alias: String = "",
)

/**
 * One entry in a peer's published handler catalog (what operations
 * their vault supports). Mirrors PublishedHandler on the vault side.
 */
data class PeerHandlerInfo(
    val id: String,
    val name: String,
    val description: String,
    val operations: List<String>,
    // Classification badges shown alongside the handler row. Empty
    // strings / false defaults preserve compatibility with vaults
    // running pre-classification enclave versions.
    val category: String = "",
    val required: Boolean = false,
    val shareable: Boolean = false,
)

/**
 * Metadata about a public secret the peer has opted to share
 * (value is never transmitted, only name/type/category).
 */
data class PeerPublicSecretMetadata(
    val name: String,
    val type: String,
    val category: String,
    // Optional grouping alias (e.g. "BTC · MyWallet"). Carried through
    // so the peer-side catalog dialog can collapse a wallet, its seed
    // phrase, and its signing key into one card the same way the own-
    // profile preview does.
    val alias: String = "",
)

/**
 * Public wallet info from a peer's published profile.
 */
data class PeerWalletInfo(
    val walletId: String,
    val label: String,
    val address: String,
    val network: String
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
