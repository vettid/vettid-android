package com.vettid.app.core.nats

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 */
@Singleton
class OwnerSpaceClient @Inject constructor(
    private val connectionManager: NatsConnectionManager
) {
    private val gson = Gson()
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
     * @param messageType The message type/action
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
        val subject = "$ownerSpaceId.forVault.$messageType"

        val message = VaultMessage(
            id = requestId,
            type = messageType,
            payload = payload,
            timestamp = java.time.Instant.now().toString()  // ISO 8601
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

    private fun handleVaultResponse(message: NatsMessage) {
        try {
            val response = gson.fromJson(message.dataString, VaultResponseJson::class.java)

            val vaultResponse = when (response.type) {
                "handlerResult" -> VaultResponse.HandlerResult(
                    requestId = response.id,
                    handlerId = response.handlerId,
                    success = response.success ?: false,
                    result = response.result,
                    error = response.error
                )
                "status" -> VaultResponse.StatusResponse(
                    requestId = response.id,
                    status = parseVaultStatus(response.result)
                )
                "eventTypes" -> VaultResponse.EventTypesResponse(
                    requestId = response.id,
                    eventTypes = parseEventTypes(response.result)
                )
                "pong" -> VaultResponse.Pong(
                    requestId = response.id,
                    timestamp = response.timestamp ?: java.time.Instant.now().toString()
                )
                "error" -> VaultResponse.Error(
                    requestId = response.id,
                    code = response.errorCode ?: "UNKNOWN",
                    message = response.error ?: "Unknown error"
                )
                else -> {
                    android.util.Log.w(TAG, "Unknown response type: ${response.type}")
                    return
                }
            }

            _vaultResponses.tryEmit(vaultResponse)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse vault response", e)
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
 */
data class VaultMessage(
    val id: String,
    val type: String,
    val payload: JsonObject,
    val timestamp: String  // ISO 8601 format
)

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
        val timestamp: String  // ISO 8601 format
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

private data class VaultResponseJson(
    val id: String,  // Changed from requestId to match vault-manager format
    val type: String,
    val handlerId: String? = null,
    val success: Boolean? = null,
    val result: JsonObject? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val timestamp: String? = null  // ISO 8601 format
)

private data class EventTypesJson(
    val types: List<EventTypeJson>
)

private data class EventTypeJson(
    val id: String,
    val name: String,
    val description: String? = null,
    val parameters: List<String>? = null
)
