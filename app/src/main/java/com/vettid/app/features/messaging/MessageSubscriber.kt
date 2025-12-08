package com.vettid.app.features.messaging

import android.util.Log
import com.google.gson.Gson
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NatsSubscription
import com.vettid.app.core.network.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to real-time message and connection events via NATS.
 *
 * Event types:
 * - New messages
 * - Message read receipts
 * - Connection accepted/revoked
 * - Profile updates
 */
@Singleton
class MessageSubscriber @Inject constructor(
    private val connectionManager: NatsConnectionManager,
    private val connectionCryptoManager: ConnectionCryptoManager
) {
    private val gson = Gson()

    // Message events flow
    private val _messageEvents = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<MessageEvent> = _messageEvents.asSharedFlow()

    // Connection events flow
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    // Active subscriptions
    private var messagesSubscription: NatsSubscription? = null
    private var connectionsSubscription: NatsSubscription? = null

    /**
     * Start subscribing to message and connection events.
     * Call this after NATS is connected.
     */
    fun subscribe(): Result<Unit> {
        val ownerSpaceId = connectionManager.getOwnerSpaceId()
            ?: return Result.failure(MessagingException("No OwnerSpace ID available"))

        // Subscribe to message events
        val messagesSubject = "$ownerSpaceId.messages.>"
        val messagesResult = connectionManager.getNatsClient().subscribe(messagesSubject) { message ->
            handleMessageEvent(message.dataString)
        }

        messagesResult.onSuccess {
            messagesSubscription = it
            Log.d(TAG, "Subscribed to $messagesSubject")
        }.onFailure {
            Log.e(TAG, "Failed to subscribe to $messagesSubject", it)
            return Result.failure(it)
        }

        // Subscribe to connection events
        val connectionsSubject = "$ownerSpaceId.connections.>"
        val connectionsResult = connectionManager.getNatsClient().subscribe(connectionsSubject) { message ->
            handleConnectionEvent(message.dataString)
        }

        connectionsResult.onSuccess {
            connectionsSubscription = it
            Log.d(TAG, "Subscribed to $connectionsSubject")
        }.onFailure {
            Log.e(TAG, "Failed to subscribe to $connectionsSubject", it)
        }

        return Result.success(Unit)
    }

    /**
     * Stop subscribing to events.
     */
    fun unsubscribe() {
        messagesSubscription?.unsubscribe()
        messagesSubscription = null
        connectionsSubscription?.unsubscribe()
        connectionsSubscription = null
        Log.d(TAG, "Unsubscribed from all topics")
    }

    /**
     * Check if currently subscribed.
     */
    fun isSubscribed(): Boolean {
        return messagesSubscription != null
    }

    /**
     * Handle incoming message event.
     */
    private fun handleMessageEvent(json: String) {
        try {
            val eventJson = gson.fromJson(json, MessageEventJson::class.java)

            val event = when (eventJson.type) {
                "new_message" -> {
                    val message = eventJson.message ?: return
                    MessageEvent.NewMessage(
                        message = message,
                        connectionId = eventJson.connectionId ?: message.connectionId
                    )
                }
                "message_read" -> {
                    MessageEvent.MessageRead(
                        messageId = eventJson.messageId ?: return,
                        connectionId = eventJson.connectionId ?: return,
                        readAt = eventJson.readAt ?: System.currentTimeMillis()
                    )
                }
                "message_delivered" -> {
                    MessageEvent.MessageDelivered(
                        messageId = eventJson.messageId ?: return,
                        connectionId = eventJson.connectionId ?: return,
                        deliveredAt = eventJson.deliveredAt ?: System.currentTimeMillis()
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown message event type: ${eventJson.type}")
                    return
                }
            }

            _messageEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message event", e)
        }
    }

    /**
     * Handle incoming connection event.
     */
    private fun handleConnectionEvent(json: String) {
        try {
            val eventJson = gson.fromJson(json, ConnectionEventJson::class.java)

            val event = when (eventJson.type) {
                "invitation_accepted" -> {
                    val connection = eventJson.connection ?: return
                    val peerPublicKey = eventJson.peerPublicKey ?: return
                    ConnectionEvent.InvitationAccepted(
                        connection = connection,
                        peerPublicKey = peerPublicKey
                    )
                }
                "connection_revoked" -> {
                    ConnectionEvent.ConnectionRevoked(
                        connectionId = eventJson.connectionId ?: return
                    )
                }
                "profile_updated" -> {
                    val profile = eventJson.profile ?: return
                    ConnectionEvent.ProfileUpdated(
                        connectionId = eventJson.connectionId ?: return,
                        profile = profile
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown connection event type: ${eventJson.type}")
                    return
                }
            }

            _connectionEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse connection event", e)
        }
    }

    companion object {
        private const val TAG = "MessageSubscriber"
    }
}

// MARK: - Event Types

/**
 * Message-related events.
 */
sealed class MessageEvent {
    /**
     * New message received.
     */
    data class NewMessage(
        val message: Message,
        val connectionId: String
    ) : MessageEvent()

    /**
     * Message was read by recipient.
     */
    data class MessageRead(
        val messageId: String,
        val connectionId: String,
        val readAt: Long
    ) : MessageEvent()

    /**
     * Message was delivered to recipient's device.
     */
    data class MessageDelivered(
        val messageId: String,
        val connectionId: String,
        val deliveredAt: Long
    ) : MessageEvent()
}

// MARK: - JSON Models

private data class MessageEventJson(
    val type: String,
    val connectionId: String? = null,
    val messageId: String? = null,
    val message: Message? = null,
    val readAt: Long? = null,
    val deliveredAt: Long? = null
)

private data class ConnectionEventJson(
    val type: String,
    val connectionId: String? = null,
    val connection: Connection? = null,
    val peerPublicKey: String? = null,
    val profile: Profile? = null
)

// MARK: - Exceptions

class MessagingException(message: String) : Exception(message)
