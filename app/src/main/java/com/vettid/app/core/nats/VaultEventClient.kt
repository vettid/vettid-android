package com.vettid.app.core.nats

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for submitting events to the vault for processing.
 *
 * Events are sent via NATS through the OwnerSpaceClient.
 * Responses are correlated by request_id.
 */
@Singleton
class VaultEventClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) {
    private val gson = Gson()

    /**
     * Submit an event to the vault for processing.
     *
     * @param event The event to submit
     * @return Result containing the request ID for correlation
     */
    suspend fun submitEvent(event: VaultSubmitEvent): Result<String> {
        val messageId = UUID.randomUUID().toString()
        val message = VaultEventMessage(
            id = messageId,
            eventType = event.type,
            payload = event.payload,
            timestamp = Instant.now().toString()
        )

        val payloadJson = JsonObject().apply {
            addProperty("id", message.id)
            addProperty("event_type", message.eventType)
            add("payload", message.payload)
            addProperty("timestamp", message.timestamp)
        }

        return ownerSpaceClient.sendToVault(
            messageType = event.type,  // No events. prefix
            payload = payloadJson
        ).map { messageId }
    }

    /**
     * Subscribe to event responses from vault.
     *
     * @return Flow of vault event responses
     */
    fun subscribeToResponses(): Flow<VaultEventResponse> {
        return ownerSpaceClient.vaultResponses.map { response ->
            when (response) {
                is VaultResponse.HandlerResult -> VaultEventResponse(
                    requestId = response.requestId,
                    status = if (response.success) "success" else "error",
                    result = response.result,
                    error = response.error,
                    processedAt = Instant.now().toString()
                )
                is VaultResponse.Error -> VaultEventResponse(
                    requestId = response.requestId,
                    status = "error",
                    result = null,
                    error = response.message,
                    processedAt = Instant.now().toString()
                )
                else -> VaultEventResponse(
                    requestId = response.requestId,
                    status = "unknown",
                    result = null,
                    error = null,
                    processedAt = Instant.now().toString()
                )
            }
        }
    }

    /**
     * Send a message to the vault.
     *
     * @param topic The topic/action
     * @param payload The message payload
     * @return Result containing the request ID
     */
    suspend fun sendToVault(topic: String, payload: JsonObject = JsonObject()): Result<String> {
        return ownerSpaceClient.sendToVault(
            messageType = topic,
            payload = payload
        )
    }

    companion object {
        private const val TAG = "VaultEventClient"
    }
}

// MARK: - Message Types

/**
 * Message sent to the vault.
 */
data class VaultEventMessage(
    val id: String,  // Changed from requestId to match vault-manager format
    val eventType: String,
    val payload: JsonObject,
    val timestamp: String  // ISO 8601 format
)

/**
 * Response from the vault for an event.
 */
data class VaultEventResponse(
    val requestId: String,
    val status: String, // success, error
    val result: JsonObject?,
    val error: String?,
    val processedAt: String
)

/**
 * Sealed class for vault events.
 */
sealed class VaultSubmitEvent(val type: String, val payload: JsonObject) {

    /**
     * Send a message to another user.
     */
    class SendMessage(recipient: String, content: String) : VaultSubmitEvent(
        type = "messaging.send",
        payload = JsonObject().apply {
            addProperty("recipient", recipient)
            addProperty("content", content)
        }
    )

    /**
     * Update user profile.
     */
    class UpdateProfile(updates: Map<String, Any>) : VaultSubmitEvent(
        type = "profile.update",
        payload = JsonObject().apply {
            updates.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                }
            }
        }
    )

    /**
     * Create a connection with another user.
     */
    class CreateConnection(inviteCode: String) : VaultSubmitEvent(
        type = "connection.create",
        payload = JsonObject().apply {
            addProperty("invite_code", inviteCode)
        }
    )

    /**
     * Accept a connection request.
     */
    class AcceptConnection(connectionId: String) : VaultSubmitEvent(
        type = "connection.accept",
        payload = JsonObject().apply {
            addProperty("connection_id", connectionId)
        }
    )

    /**
     * Custom event with arbitrary type and payload.
     */
    class Custom(eventType: String, eventPayload: JsonObject) : VaultSubmitEvent(
        type = eventType,
        payload = eventPayload
    )
}
