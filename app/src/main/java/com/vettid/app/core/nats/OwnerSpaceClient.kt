package com.vettid.app.core.nats

import android.util.Base64
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

        val message = VaultMessage(
            id = requestId,
            type = messageType,
            payload = payload,
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
