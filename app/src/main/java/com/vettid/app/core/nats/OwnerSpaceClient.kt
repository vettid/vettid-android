package com.vettid.app.core.nats

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.crypto.EncryptedSessionMessage
import com.vettid.app.core.crypto.SessionCrypto
import com.vettid.app.core.crypto.SessionInfo
import com.vettid.app.core.crypto.SessionKeyPair
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for OwnerSpace pub/sub communication.
 *
 * OwnerSpace is the private namespace for owner-vault communication.
 * Mobile app topics:
 * - Publish: OwnerSpace.{guid}.forVault.> (send commands to vault)
 * - Subscribe: OwnerSpace.{guid}.forApp.> (receive responses from vault)
 * - Subscribe: OwnerSpace.{guid}.eventTypes (read handler definitions)
 *
 * Supports E2E encryption via session key exchange with vault.
 */
@Singleton
class OwnerSpaceClient @Inject constructor(
    private val connectionManager: NatsConnectionManager,
    private val credentialStore: CredentialStore
) {
    private val gson = Gson()

    // E2E session crypto (established via bootstrap)
    private var sessionCrypto: SessionCrypto? = null
    private var pendingKeyPair: SessionKeyPair? = null
    private val natsClient: NatsClient
        get() = connectionManager.getNatsClient()

    private val _vaultResponses = MutableSharedFlow<VaultResponse>(extraBufferCapacity = 64)
    val vaultResponses: SharedFlow<VaultResponse> = _vaultResponses.asSharedFlow()

    private val _vaultEvents = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 64)
    val vaultEvents: SharedFlow<VaultEvent> = _vaultEvents.asSharedFlow()

    private val _credentialRotation = MutableSharedFlow<CredentialRotationMessage>(extraBufferCapacity = 8)
    /** Flow of credential rotation events from vault. Subscribe to handle proactive credential rotation. */
    val credentialRotation: SharedFlow<CredentialRotationMessage> = _credentialRotation.asSharedFlow()

    // Messaging events
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    /** Flow of new messages from peers. */
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private val _readReceipts = MutableSharedFlow<ReadReceipt>(extraBufferCapacity = 64)
    /** Flow of read receipts from peers. */
    val readReceipts: SharedFlow<ReadReceipt> = _readReceipts.asSharedFlow()

    private val _profileUpdates = MutableSharedFlow<ProfileUpdate>(extraBufferCapacity = 16)
    /** Flow of profile updates from peers. */
    val profileUpdates: SharedFlow<ProfileUpdate> = _profileUpdates.asSharedFlow()

    private val _connectionRevocations = MutableSharedFlow<ConnectionRevoked>(extraBufferCapacity = 16)
    /** Flow of connection revocation notices from peers. */
    val connectionRevocations: SharedFlow<ConnectionRevoked> = _connectionRevocations.asSharedFlow()

    private val _callEvents = MutableSharedFlow<CallSignalEvent>(extraBufferCapacity = 64)
    /** Flow of call signaling events (vault-routed). */
    val callEvents: SharedFlow<CallSignalEvent> = _callEvents.asSharedFlow()

    private var appSubscription: NatsSubscription? = null
    private var eventTypesSubscription: NatsSubscription? = null

    /**
     * Subscribe to vault responses and events.
     * Call this after connecting to NATS.
     */
    fun subscribeToVault(): Result<Unit> {
        val ownerSpaceId = connectionManager.getOwnerSpaceId()
            ?: return Result.failure(NatsException("No OwnerSpace ID available"))

        // Subscribe to responses from vault
        val forAppSubject = "$ownerSpaceId.forApp.>"
        val forAppResult = natsClient.subscribe(forAppSubject) { message ->
            handleVaultResponse(message)
        }

        forAppResult.onSuccess {
            appSubscription = it
            android.util.Log.d(TAG, "Subscribed to $forAppSubject")
        }.onFailure {
            android.util.Log.e(TAG, "Failed to subscribe to $forAppSubject", it)
            return Result.failure(it)
        }

        // Subscribe to event types
        val eventTypesSubject = "$ownerSpaceId.eventTypes"
        val eventTypesResult = natsClient.subscribe(eventTypesSubject) { message ->
            handleEventTypes(message)
        }

        eventTypesResult.onSuccess {
            eventTypesSubscription = it
            android.util.Log.d(TAG, "Subscribed to $eventTypesSubject")
        }.onFailure {
            android.util.Log.e(TAG, "Failed to subscribe to $eventTypesSubject", it)
        }

        return Result.success(Unit)
    }

    /**
     * Unsubscribe from vault topics.
     */
    fun unsubscribeFromVault() {
        appSubscription?.unsubscribe()
        appSubscription = null
        eventTypesSubscription?.unsubscribe()
        eventTypesSubscription = null
    }

    /**
     * Send a message to the vault.
     *
     * If E2E encryption is enabled, the payload is encrypted before sending.
     * The message envelope (id, type, timestamp) remains in plaintext for routing.
     *
     * @param messageType The message type/action (e.g., "profile.get", "secrets.datastore.add")
     * @param payload The message payload
     * @return Request ID for correlating response
     */
    suspend fun sendToVault(
        messageType: String,
        payload: JsonObject = JsonObject()
    ): Result<String> {
        val ownerSpaceId = connectionManager.getOwnerSpaceId()
            ?: return Result.failure(NatsException("No OwnerSpace ID available"))

        val requestId = UUID.randomUUID().toString()
        // Subject includes the message type for routing
        val subject = "$ownerSpaceId.forVault.$messageType"

        // Encrypt payload if E2E session is established (skip for bootstrap)
        val effectivePayload = if (isE2EEnabled && messageType != "app.bootstrap") {
            android.util.Log.d(TAG, "sendToVault: encrypting payload for $messageType")
            android.util.Log.d(TAG, "sendToVault: original payload keys: ${payload.keySet()}")
            encryptPayload(payload)
        } else {
            android.util.Log.d(TAG, "sendToVault: NOT encrypting (E2E=${isE2EEnabled}, type=$messageType)")
            payload
        }

        val message = VaultMessage(
            id = requestId,
            type = messageType,
            payload = effectivePayload,
            timestamp = Instant.now().toString()  // ISO 8601 format
        )

        val json = gson.toJson(message)
        return natsClient.publish(subject, json).map { requestId }
    }

    /**
     * Execute a vault handler.
     *
     * @param handlerId The handler to execute
     * @param payload Handler parameters
     * @return Request ID
     */
    suspend fun executeHandler(
        handlerId: String,
        payload: JsonObject = JsonObject()
    ): Result<String> {
        val requestPayload = JsonObject().apply {
            addProperty("handlerId", handlerId)
            add("params", payload)
        }
        return sendToVault("execute", requestPayload)
    }

    /**
     * Send a message to another user's vault.
     *
     * Used for vault-routed signaling where messages go to the TARGET user's vault
     * instead of our own vault. The target vault will verify, log, and relay the message.
     *
     * @param targetUserGuid The target user's GUID
     * @param messageType The message type/action (e.g., "call.initiate")
     * @param payload The message payload
     * @return Request ID for correlating response
     */
    suspend fun sendToTargetVault(
        targetUserGuid: String,
        messageType: String,
        payload: JsonObject = JsonObject()
    ): Result<String> {
        val requestId = UUID.randomUUID().toString()
        val subject = "OwnerSpace.$targetUserGuid.forVault.$messageType"

        // Add our user GUID as caller_id for identification
        val enrichedPayload = payload.deepCopy().apply {
            val ownGuid = credentialStore.getUserGuid()
            if (ownGuid != null && !has("caller_id")) {
                addProperty("caller_id", ownGuid)
            }
        }

        val message = VaultMessage(
            id = requestId,
            type = messageType,
            payload = enrichedPayload,
            timestamp = Instant.now().toString()
        )

        val json = gson.toJson(message)
        android.util.Log.d(TAG, "Sending to target vault: $subject")
        return natsClient.publish(subject, json).map { requestId }
    }

    /**
     * Request vault status.
     *
     * @return Request ID
     */
    suspend fun requestVaultStatus(): Result<String> {
        return sendToVault("status")
    }

    /**
     * Request event types/handlers from vault.
     *
     * @return Request ID
     */
    suspend fun requestEventTypes(): Result<String> {
        return sendToVault("getEventTypes")
    }

    /**
     * Send a ping to vault to check connectivity.
     *
     * @return Request ID
     */
    suspend fun ping(): Result<String> {
        return sendToVault("ping")
    }

    // MARK: - Credential Operations (KMS-Sealed Protean Credential)

    /**
     * Create a new Protean Credential inside the Nitro Enclave.
     *
     * The credential is sealed using KMS envelope encryption bound to PCR0,
     * meaning only the exact enclave code can unseal it.
     *
     * @param encryptedPin Base64-encoded encrypted PIN/password
     * @param authType Authentication type ("pin" or "password")
     * @return Request ID for correlating the response
     */
    suspend fun createCredential(
        encryptedPin: String,
        authType: String = "pin"
    ): Result<String> {
        val payload = JsonObject().apply {
            add("credential_request", JsonObject().apply {
                addProperty("encrypted_pin", encryptedPin)
                addProperty("auth_type", authType)
            })
        }
        return sendToVault("credential.create", payload)
    }

    /**
     * Unseal a previously created Protean Credential.
     *
     * Requires the sealed credential blob and correct PIN/password.
     * KMS validates PCR0 before decrypting the DEK.
     *
     * @param sealedCredential Base64-encoded sealed credential blob
     * @param challengeId Challenge identifier
     * @param challengeResponse PIN/password response
     * @return Request ID for correlating the response
     */
    suspend fun unsealCredential(
        sealedCredential: String,
        challengeId: String,
        challengeResponse: String
    ): Result<String> {
        val payload = JsonObject().apply {
            addProperty("sealed_credential", sealedCredential)
            add("challenge", JsonObject().apply {
                addProperty("challenge_id", challengeId)
                addProperty("response", challengeResponse)
            })
        }
        return sendToVault("credential.unseal", payload)
    }

    // MARK: - Block List Management

    /**
     * Block a user from contacting you.
     *
     * @param targetGuid The user GUID to block
     * @param reason Optional reason for blocking
     * @param durationSecs Duration in seconds (0 = permanent)
     * @return Request ID
     */
    suspend fun blockUser(
        targetGuid: String,
        reason: String? = null,
        durationSecs: Long = 0
    ): Result<String> {
        val payload = JsonObject().apply {
            addProperty("target_id", targetGuid)
            reason?.let { addProperty("reason", it) }
            addProperty("duration_secs", durationSecs)
        }
        return sendToVault("block.add", payload)
    }

    /**
     * Unblock a user.
     *
     * @param targetGuid The user GUID to unblock
     * @return Request ID
     */
    suspend fun unblockUser(targetGuid: String): Result<String> {
        val payload = JsonObject().apply {
            addProperty("target_id", targetGuid)
        }
        return sendToVault("block.remove", payload)
    }

    /**
     * Get list of blocked users.
     *
     * @return Request ID
     */
    suspend fun getBlockedUsers(): Result<String> {
        return sendToVault("block.list")
    }

    // MARK: - E2E Session Management

    /**
     * Bootstrap the vault connection with E2E session key exchange.
     *
     * This establishes an encrypted session for app-vault communication:
     * 1. Generate X25519 keypair
     * 2. Send public key to vault via app.bootstrap
     * 3. Receive vault's public key in response
     * 4. Derive shared session key via ECDH + HKDF
     *
     * @param deviceId Device identifier
     * @param appVersion App version string
     * @return Result with session info on success
     */
    suspend fun bootstrap(
        deviceId: String,
        appVersion: String = "1.0.0"
    ): Result<BootstrapResponse> {
        // Generate session keypair
        val keyPair = SessionCrypto.generateKeyPair()
        pendingKeyPair = keyPair

        // Build bootstrap request
        val payload = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("device_type", "android")
            addProperty("app_version", appVersion)
            addProperty("app_session_public_key", keyPair.publicKeyBase64())
        }

        android.util.Log.i(TAG, "Sending bootstrap with session public key")

        // Send bootstrap request
        val requestIdResult = sendToVault("app.bootstrap", payload)
        if (requestIdResult.isFailure) {
            pendingKeyPair?.clear()
            pendingKeyPair = null
            return Result.failure(requestIdResult.exceptionOrNull() ?: NatsException("Bootstrap failed"))
        }

        return Result.success(BootstrapResponse(
            requestId = requestIdResult.getOrThrow(),
            pendingKeyPair = keyPair
        ))
    }

    /**
     * Complete session establishment from bootstrap response.
     * Call this when you receive the bootstrap response with session_info.
     *
     * @param sessionInfo Session info from vault response
     * @return SessionCrypto instance for encrypting messages
     */
    fun establishSession(sessionInfo: SessionInfo): Result<SessionCrypto> {
        val keyPair = pendingKeyPair
            ?: return Result.failure(NatsException("No pending key exchange"))

        return try {
            val vaultPublicKey = Base64.decode(sessionInfo.vaultSessionPublicKey, Base64.NO_WRAP)
            val expiresAt = sessionInfo.expiresAtMillis()

            val session = SessionCrypto.fromKeyExchange(
                sessionId = sessionInfo.sessionId,
                appPrivateKey = keyPair.privateKey,
                appPublicKey = keyPair.publicKey,
                vaultPublicKey = vaultPublicKey,
                expiresAt = expiresAt
            )

            // Store session
            credentialStore.storeSession(
                sessionId = session.sessionId,
                sessionKey = session.getSessionKeyForStorage(),
                publicKey = session.publicKey,
                expiresAt = expiresAt
            )

            sessionCrypto = session
            pendingKeyPair?.clear()
            pendingKeyPair = null

            android.util.Log.i(TAG, "E2E session established: ${session.sessionId}")
            Result.success(session)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to establish session", e)
            pendingKeyPair?.clear()
            pendingKeyPair = null
            Result.failure(e)
        }
    }

    /**
     * Restore session from storage if available.
     *
     * @return true if a valid session was restored
     */
    fun restoreSession(): Boolean {
        if (!credentialStore.hasValidSession()) {
            return false
        }

        val sessionId = credentialStore.getSessionId() ?: return false
        val sessionKey = credentialStore.getSessionKey() ?: return false
        val publicKey = credentialStore.getSessionPublicKey() ?: return false
        val expiresAt = credentialStore.getSessionExpiresAt()

        sessionCrypto = SessionCrypto.fromStored(
            sessionId = sessionId,
            sessionKey = sessionKey,
            publicKey = publicKey,
            expiresAt = expiresAt
        )

        android.util.Log.i(TAG, "E2E session restored: $sessionId")
        return true
    }

    /**
     * Check if E2E encryption is enabled.
     */
    val isE2EEnabled: Boolean
        get() = sessionCrypto?.isValid == true

    /**
     * Get current session ID if E2E is enabled.
     */
    val currentSessionId: String?
        get() = sessionCrypto?.sessionId

    /**
     * Rotate the session key.
     * Call this periodically or after a certain message count.
     *
     * @param reason Reason for rotation (scheduled, message_count, explicit)
     * @return Request ID
     */
    suspend fun rotateSession(reason: String = "scheduled"): Result<String> {
        val keyPair = SessionCrypto.generateKeyPair()
        pendingKeyPair = keyPair

        val payload = JsonObject().apply {
            addProperty("device_id", credentialStore.getUserGuid() ?: "")
            addProperty("new_app_public_key", keyPair.publicKeyBase64())
            addProperty("reason", reason)
        }

        return sendToVault("session.rotate", payload)
    }

    /**
     * Clear the current session.
     */
    fun clearSession() {
        sessionCrypto?.clear()
        sessionCrypto = null
        pendingKeyPair?.clear()
        pendingKeyPair = null
        credentialStore.clearSession()
    }

    /**
     * Set the session directly (used during bootstrap credential rotation).
     * This enables E2E encryption for immediate credential refresh requests.
     */
    fun setSession(session: SessionCrypto) {
        sessionCrypto = session
        Log.d(TAG, "Session set directly: ${session.sessionId}")
    }

    /**
     * Encrypt a message payload if E2E is enabled.
     *
     * @param payload The message payload
     * @return Encrypted payload as JsonObject or original if E2E not enabled
     */
    fun encryptPayload(payload: JsonObject): JsonObject {
        val session = sessionCrypto
        if (session == null || !session.isValid) {
            return payload
        }

        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val encrypted = session.encrypt(plaintext)

        // Convert to Gson JsonObject
        return JsonObject().apply {
            addProperty("session_id", encrypted.sessionId)
            addProperty("ciphertext", Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP))
            addProperty("nonce", Base64.encodeToString(encrypted.nonce, Base64.NO_WRAP))
        }
    }

    /**
     * Decrypt a message if it's encrypted.
     *
     * @param data Raw message data
     * @return Decrypted data or original if not encrypted
     */
    fun decryptPayload(data: ByteArray): ByteArray {
        val session = sessionCrypto ?: return data

        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.has("session_id") && json.has("ciphertext") && json.has("nonce")) {
                val encrypted = EncryptedSessionMessage.fromJson(json)
                session.decrypt(encrypted)
            } else {
                data
            }
        } catch (e: Exception) {
            // Not encrypted or decryption failed - return original
            data
        }
    }

    private fun handleVaultResponse(message: NatsMessage) {
        try {
            // Route based on subject suffix
            when {
                message.subject.endsWith(".forApp.credentials.rotate") -> {
                    handleCredentialRotation(message)
                    return
                }
                message.subject.endsWith(".forApp.new-message") -> {
                    handleNewMessage(message)
                    return
                }
                message.subject.endsWith(".forApp.read-receipt") -> {
                    handleReadReceipt(message)
                    return
                }
                message.subject.endsWith(".forApp.profile-update") -> {
                    handleProfileUpdate(message)
                    return
                }
                message.subject.endsWith(".forApp.connection-revoked") -> {
                    handleConnectionRevoked(message)
                    return
                }
                // Call events (vault-routed signaling)
                message.subject.contains(".forApp.call.") -> {
                    handleCallEvent(message)
                    return
                }
            }

            // Decrypt if E2E is enabled
            val data = decryptPayload(message.data)
            val responseString = String(data, Charsets.UTF_8)

            val response = gson.fromJson(responseString, VaultResponseJson::class.java)
            val correlationId = response.getCorrelationId()

            // Handle standard vault-manager response format (success/error with result)
            if (response.success != null) {
                val vaultResponse = if (response.success) {
                    VaultResponse.HandlerResult(
                        requestId = correlationId,
                        handlerId = response.handlerId,
                        success = true,
                        result = response.result,
                        error = null
                    )
                } else {
                    VaultResponse.Error(
                        requestId = correlationId,
                        code = response.errorCode ?: "HANDLER_ERROR",
                        message = response.error ?: "Unknown error"
                    )
                }
                _vaultResponses.tryEmit(vaultResponse)
                return
            }

            // Handle legacy typed responses
            val vaultResponse = when (response.type) {
                "handlerResult" -> VaultResponse.HandlerResult(
                    requestId = correlationId,
                    handlerId = response.handlerId,
                    success = response.success ?: false,
                    result = response.result,
                    error = response.error
                )
                "status" -> VaultResponse.StatusResponse(
                    requestId = correlationId,
                    status = parseVaultStatus(response.result)
                )
                "eventTypes" -> VaultResponse.EventTypesResponse(
                    requestId = correlationId,
                    eventTypes = parseEventTypes(response.result)
                )
                "pong" -> VaultResponse.Pong(
                    requestId = correlationId,
                    timestamp = parseTimestamp(response.timestamp)
                )
                "error" -> VaultResponse.Error(
                    requestId = correlationId,
                    code = response.errorCode ?: "UNKNOWN",
                    message = response.error ?: "Unknown error"
                )
                else -> {
                    // Treat as generic handler result
                    VaultResponse.HandlerResult(
                        requestId = correlationId,
                        handlerId = null,
                        success = response.error == null,
                        result = response.result,
                        error = response.error
                    )
                }
            }

            _vaultResponses.tryEmit(vaultResponse)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse vault response", e)
        }
    }

    private fun parseTimestamp(timestamp: String?): Long {
        return try {
            timestamp?.let { Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun handleEventTypes(message: NatsMessage) {
        try {
            val eventTypes = gson.fromJson(message.dataString, EventTypesJson::class.java)
            val event = VaultEvent.EventTypesUpdated(
                eventTypes = eventTypes.types.map { type ->
                    EventType(
                        id = type.id,
                        name = type.name,
                        description = type.description,
                        parameters = type.parameters
                    )
                }
            )
            _vaultEvents.tryEmit(event)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse event types", e)
        }
    }

    private fun parseVaultStatus(json: JsonObject?): VaultRuntimeStatus {
        return VaultRuntimeStatus(
            isOnline = json?.get("isOnline")?.asBoolean ?: false,
            connectedAt = json?.get("connectedAt")?.asString,
            handlers = json?.getAsJsonArray("handlers")?.map { it.asString } ?: emptyList()
        )
    }

    private fun parseEventTypes(json: JsonObject?): List<EventType> {
        val array = json?.getAsJsonArray("eventTypes") ?: return emptyList()
        return array.map { element ->
            val obj = element.asJsonObject
            EventType(
                id = obj.get("id")?.asString ?: "",
                name = obj.get("name")?.asString ?: "",
                description = obj.get("description")?.asString,
                parameters = obj.getAsJsonArray("parameters")?.map { it.asString }
            )
        }
    }

    /**
     * Handle credential rotation event from vault.
     * These are proactive push messages from the vault with new credentials.
     */
    private fun handleCredentialRotation(message: NatsMessage) {
        try {
            // Credential rotation messages are NOT encrypted (sent before session)
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) {
                json.getJSONObject("payload")
            } else {
                json
            }

            val rotationMessage = CredentialRotationMessage(
                credentials = payload.getString("credentials"),
                expiresAt = payload.optString("expires_at", ""),
                ttlSeconds = payload.optLong("ttl_seconds", 604800L),
                credentialId = payload.optString("credential_id", ""),
                reason = payload.optString("reason", "unknown"),
                oldCredentialId = if (payload.has("old_credential_id")) payload.optString("old_credential_id") else null
            )

            android.util.Log.i(TAG, "Received credential rotation: reason=${rotationMessage.reason}")
            _credentialRotation.tryEmit(rotationMessage)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse credential rotation event", e)
        }
    }

    /**
     * Handle new message event from peer vault.
     */
    private fun handleNewMessage(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val incomingMessage = IncomingMessage(
                messageId = payload.getString("message_id"),
                connectionId = payload.getString("connection_id"),
                senderGuid = payload.getString("sender_guid"),
                encryptedContent = payload.getString("encrypted_content"),
                nonce = payload.getString("nonce"),
                contentType = payload.optString("content_type", "text"),
                sentAt = payload.optString("sent_at", "")
            )

            android.util.Log.d(TAG, "Received new message: ${incomingMessage.messageId}")
            _incomingMessages.tryEmit(incomingMessage)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse new-message event", e)
        }
    }

    /**
     * Handle read receipt from peer vault.
     */
    private fun handleReadReceipt(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val readReceipt = ReadReceipt(
                messageId = payload.getString("message_id"),
                connectionId = payload.getString("connection_id"),
                readerGuid = payload.getString("reader_guid"),
                readAt = payload.optString("read_at", "")
            )

            android.util.Log.d(TAG, "Received read receipt: ${readReceipt.messageId}")
            _readReceipts.tryEmit(readReceipt)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse read-receipt event", e)
        }
    }

    /**
     * Handle profile update from peer vault.
     */
    private fun handleProfileUpdate(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val profileUpdate = ProfileUpdate(
                connectionId = payload.getString("connection_id"),
                peerGuid = payload.getString("peer_guid"),
                displayName = if (payload.has("display_name")) payload.getString("display_name") else null,
                avatarUrl = if (payload.has("avatar_url")) payload.getString("avatar_url") else null,
                status = if (payload.has("status")) payload.getString("status") else null,
                updatedAt = payload.optString("updated_at", "")
            )

            android.util.Log.d(TAG, "Received profile update from: ${profileUpdate.peerGuid}")
            _profileUpdates.tryEmit(profileUpdate)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse profile-update event", e)
        }
    }

    /**
     * Handle connection revocation notice from peer vault.
     */
    private fun handleConnectionRevoked(message: NatsMessage) {
        try {
            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val connectionRevoked = ConnectionRevoked(
                connectionId = payload.getString("connection_id"),
                peerGuid = payload.getString("peer_guid"),
                reason = if (payload.has("reason")) payload.getString("reason") else null,
                revokedAt = payload.optString("revoked_at", "")
            )

            android.util.Log.i(TAG, "Connection revoked: ${connectionRevoked.connectionId}")
            _connectionRevocations.tryEmit(connectionRevoked)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse connection-revoked event", e)
        }
    }

    /**
     * Handle call signaling events from vault (vault-routed calls).
     *
     * Events:
     * - call.incoming: Incoming call notification
     * - call.offer: WebRTC SDP offer
     * - call.answer: WebRTC SDP answer
     * - call.candidate: ICE candidate
     * - call.accepted: Call was accepted
     * - call.rejected: Call was rejected
     * - call.ended: Call ended
     * - call.missed: Call was not answered
     * - call.blocked: Caller is blocked
     * - call.busy: Callee is busy
     */
    private fun handleCallEvent(message: NatsMessage) {
        try {
            // Extract event type from subject (e.g., "OwnerSpace.guid.forApp.call.incoming" -> "incoming")
            val eventType = message.subject.substringAfterLast(".forApp.call.")

            val json = JSONObject(String(message.data, Charsets.UTF_8))
            val payload = if (json.has("payload")) json.getJSONObject("payload") else json

            val callEvent = when (eventType) {
                "incoming" -> CallSignalEvent.Incoming(
                    callId = payload.getString("call_id"),
                    callerGuid = payload.getString("caller_id"),
                    callerDisplayName = payload.optString("caller_display_name", "Unknown"),
                    callType = payload.optString("call_type", "voice"),
                    sdpOffer = if (payload.has("sdp_offer")) payload.getString("sdp_offer") else null,
                    timestamp = payload.optLong("timestamp", System.currentTimeMillis())
                )
                "offer" -> CallSignalEvent.Offer(
                    callId = payload.getString("call_id"),
                    callerGuid = payload.getString("caller_id"),
                    sdpOffer = payload.getString("sdp_offer")
                )
                "answer" -> CallSignalEvent.Answer(
                    callId = payload.getString("call_id"),
                    sdpAnswer = payload.getString("sdp_answer")
                )
                "candidate" -> CallSignalEvent.IceCandidate(
                    callId = payload.getString("call_id"),
                    candidate = payload.getString("candidate"),
                    sdpMid = if (payload.has("sdp_mid")) payload.getString("sdp_mid") else null,
                    sdpMLineIndex = if (payload.has("sdp_m_line_index")) payload.getInt("sdp_m_line_index") else null
                )
                "accepted" -> CallSignalEvent.Accepted(
                    callId = payload.getString("call_id"),
                    sdpAnswer = if (payload.has("sdp_answer")) payload.getString("sdp_answer") else null
                )
                "rejected" -> CallSignalEvent.Rejected(
                    callId = payload.getString("call_id"),
                    reason = if (payload.has("reason")) payload.getString("reason") else null
                )
                "ended" -> CallSignalEvent.Ended(
                    callId = payload.getString("call_id"),
                    reason = payload.optString("reason", "completed"),
                    duration = payload.optLong("duration", 0)
                )
                "missed" -> CallSignalEvent.Missed(
                    callId = payload.getString("call_id"),
                    callerGuid = payload.optString("caller_id", ""),
                    callerDisplayName = payload.optString("caller_display_name", "Unknown"),
                    timestamp = payload.optLong("timestamp", System.currentTimeMillis())
                )
                "blocked" -> CallSignalEvent.Blocked(
                    callId = payload.getString("call_id"),
                    targetGuid = payload.optString("target_id", "")
                )
                "busy" -> CallSignalEvent.Busy(
                    callId = payload.getString("call_id")
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown call event type: $eventType")
                    null
                }
            }

            callEvent?.let { event ->
                android.util.Log.d(TAG, "Received call event: $eventType (callId=${event.callId})")
                _callEvents.tryEmit(event)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse call event", e)
        }
    }

    companion object {
        private const val TAG = "OwnerSpaceClient"
    }
}

// MARK: - Message Types

/**
 * Message sent TO the vault.
 *
 * Field names must match vault-manager expectations:
 * - id: Unique request ID for correlation (not "requestId")
 * - type: Handler/event type (e.g., "profile.get", "secrets.datastore.add")
 * - timestamp: ISO 8601 string (not epoch milliseconds)
 * - payload: Handler-specific data
 */
data class VaultMessage(
    val id: String,
    val type: String,
    val payload: JsonObject,
    val timestamp: String  // ISO 8601 format
) {
    companion object {
        fun create(type: String, payload: JsonObject = JsonObject()): VaultMessage {
            return VaultMessage(
                id = UUID.randomUUID().toString(),
                type = type,
                payload = payload,
                timestamp = Instant.now().toString()
            )
        }
    }
}

/**
 * Response FROM the vault.
 */
sealed class VaultResponse {
    abstract val requestId: String

    data class HandlerResult(
        override val requestId: String,
        val handlerId: String?,
        val success: Boolean,
        val result: JsonObject?,
        val error: String?
    ) : VaultResponse()

    data class StatusResponse(
        override val requestId: String,
        val status: VaultRuntimeStatus
    ) : VaultResponse()

    data class EventTypesResponse(
        override val requestId: String,
        val eventTypes: List<EventType>
    ) : VaultResponse()

    data class Pong(
        override val requestId: String,
        val timestamp: Long
    ) : VaultResponse()

    data class Error(
        override val requestId: String,
        val code: String,
        val message: String
    ) : VaultResponse()
}

/**
 * Events from the vault (not request-response).
 */
sealed class VaultEvent {
    data class EventTypesUpdated(val eventTypes: List<EventType>) : VaultEvent()
    data class HandlerTriggered(val handlerId: String, val data: JsonObject) : VaultEvent()

    // Security events
    data class RecoveryRequested(val requestId: String, val email: String?) : VaultEvent()
    data class RecoveryCancelled(val requestId: String) : VaultEvent()
    data class RecoveryCompleted(val requestId: String) : VaultEvent()
}

/**
 * Vault runtime status.
 */
data class VaultRuntimeStatus(
    val isOnline: Boolean,
    val connectedAt: String?,
    val handlers: List<String>
)

/**
 * Event type/handler definition.
 */
data class EventType(
    val id: String,
    val name: String,
    val description: String?,
    val parameters: List<String>?
)

// MARK: - JSON Models

/**
 * JSON model for vault responses.
 *
 * Vault-manager returns:
 * - event_id: Matches the 'id' from the request
 * - success: Whether the operation succeeded
 * - timestamp: ISO 8601 when response was generated
 * - result: Handler result (if success=true)
 * - error: Error message (if success=false)
 */
private data class VaultResponseJson(
    val event_id: String? = null,      // Matches request 'id'
    val id: String? = null,            // Alternative field name
    val requestId: String? = null,     // Legacy fallback
    val type: String? = null,
    val handlerId: String? = null,
    val success: Boolean? = null,
    val result: JsonObject? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val timestamp: String? = null      // ISO 8601 format
) {
    /** Get the correlation ID, preferring event_id over id over legacy requestId */
    fun getCorrelationId(): String = event_id ?: id ?: requestId ?: ""
}

private data class EventTypesJson(
    val types: List<EventTypeJson>
)

private data class EventTypeJson(
    val id: String,
    val name: String,
    val description: String? = null,
    val parameters: List<String>? = null
)

// MARK: - Bootstrap Response

/**
 * Response from bootstrap request.
 * Contains the pending keypair for session establishment.
 */
data class BootstrapResponse(
    val requestId: String,
    val pendingKeyPair: SessionKeyPair
)

// MARK: - Credential Operations (KMS-Sealed)

/**
 * Response from credential.create operation.
 * Contains the sealed credential blob encrypted with KMS.
 */
data class CreateCredentialResponse(
    /** Base64-encoded sealed credential blob */
    val credential: String,
    /** Algorithm used for sealing */
    val algorithm: String = "nitro-kms-aes256-gcm"
) {
    companion object {
        fun fromJson(json: JsonObject): CreateCredentialResponse? {
            return try {
                CreateCredentialResponse(
                    credential = json.get("credential")?.asString ?: return null,
                    algorithm = json.get("algorithm")?.asString ?: "nitro-kms-aes256-gcm"
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Response from credential.unseal operation.
 * Contains session token for subsequent operations.
 */
data class UnsealCredentialResponse(
    /** Session token for authenticated operations */
    val sessionToken: String,
    /** Session expiration (epoch seconds) */
    val expiresAt: Long
) {
    companion object {
        fun fromJson(json: JsonObject): UnsealCredentialResponse? {
            return try {
                val unsealResult = json.getAsJsonObject("unseal_result") ?: return null
                UnsealCredentialResponse(
                    sessionToken = unsealResult.get("session_token")?.asString ?: return null,
                    expiresAt = unsealResult.get("expires_at")?.asLong ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Sealed credential blob structure.
 * This is what gets stored locally after credential.create.
 */
data class SealedCredentialBlob(
    val version: Int,
    val algorithm: String,
    val encryptedDek: String,
    val nonce: String,
    val ciphertext: String,
    val pcrBound: Boolean
)

// MARK: - Credential Rotation

/**
 * Credential rotation message from vault.
 *
 * The vault proactively pushes new credentials before expiry.
 * Reason values:
 * - "scheduled_rotation" - Normal rotation (2+ hours remaining)
 * - "expiry_imminent" - Urgent rotation (<30 minutes remaining)
 */
data class CredentialRotationMessage(
    /** New NATS credentials (.creds file content) */
    val credentials: String,
    /** ISO 8601 expiration time */
    val expiresAt: String,
    /** Time to live in seconds (default: 604800 = 7 days) */
    val ttlSeconds: Long,
    /** Unique credential ID for tracking */
    val credentialId: String,
    /** Reason for rotation */
    val reason: String,
    /** Previous credential ID that was rotated out */
    val oldCredentialId: String?
)

// MARK: - Call Signaling Events (Vault-Routed)

/**
 * Call signaling events received from vault.
 *
 * These events are routed through the user's vault for:
 * - Security verification of caller identity
 * - Block list enforcement
 * - Audit logging
 */
sealed class CallSignalEvent {
    abstract val callId: String

    /** Incoming call notification */
    data class Incoming(
        override val callId: String,
        val callerGuid: String,
        val callerDisplayName: String,
        val callType: String,  // "voice" or "video"
        val sdpOffer: String?,
        val timestamp: Long
    ) : CallSignalEvent()

    /** WebRTC SDP offer */
    data class Offer(
        override val callId: String,
        val callerGuid: String,
        val sdpOffer: String
    ) : CallSignalEvent()

    /** WebRTC SDP answer */
    data class Answer(
        override val callId: String,
        val sdpAnswer: String
    ) : CallSignalEvent()

    /** ICE candidate for WebRTC */
    data class IceCandidate(
        override val callId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : CallSignalEvent()

    /** Call was accepted by callee */
    data class Accepted(
        override val callId: String,
        val sdpAnswer: String?
    ) : CallSignalEvent()

    /** Call was rejected by callee */
    data class Rejected(
        override val callId: String,
        val reason: String?
    ) : CallSignalEvent()

    /** Call ended */
    data class Ended(
        override val callId: String,
        val reason: String,
        val duration: Long
    ) : CallSignalEvent()

    /** Call was not answered (timeout) */
    data class Missed(
        override val callId: String,
        val callerGuid: String,
        val callerDisplayName: String,
        val timestamp: Long
    ) : CallSignalEvent()

    /** Caller is blocked by callee */
    data class Blocked(
        override val callId: String,
        val targetGuid: String
    ) : CallSignalEvent()

    /** Callee is busy on another call */
    data class Busy(
        override val callId: String
    ) : CallSignalEvent()
}
