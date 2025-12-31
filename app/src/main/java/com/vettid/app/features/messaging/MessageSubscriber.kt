package com.vettid.app.features.messaging

import android.util.Log
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionRevoked as NatsConnectionRevoked
import com.vettid.app.core.nats.IncomingMessage
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.ProfileUpdate as NatsProfileUpdate
import com.vettid.app.core.nats.ReadReceipt
import com.vettid.app.core.network.ConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to real-time message and connection events via NATS.
 *
 * Uses OwnerSpaceClient's forApp topic flows for receiving:
 * - New messages (forApp.new-message)
 * - Message read receipts (forApp.read-receipt)
 * - Profile updates (forApp.profile-update)
 * - Connection revocations (forApp.connection-revoked)
 */
@Singleton
class MessageSubscriber @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionCryptoManager: ConnectionCryptoManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Message events flow
    private val _messageEvents = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<MessageEvent> = _messageEvents.asSharedFlow()

    // Connection events flow
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    // Active collection jobs
    private var messageCollectionJob: Job? = null
    private var readReceiptCollectionJob: Job? = null
    private var profileUpdateCollectionJob: Job? = null
    private var revocationCollectionJob: Job? = null

    /**
     * Start subscribing to message and connection events.
     * Collects from OwnerSpaceClient's forApp topic flows.
     */
    fun subscribe(): Result<Unit> {
        // Collect incoming messages
        messageCollectionJob = scope.launch {
            ownerSpaceClient.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Collect read receipts
        readReceiptCollectionJob = scope.launch {
            ownerSpaceClient.readReceipts.collect { receipt ->
                handleReadReceipt(receipt)
            }
        }

        // Collect profile updates
        profileUpdateCollectionJob = scope.launch {
            ownerSpaceClient.profileUpdates.collect { update ->
                handleProfileUpdate(update)
            }
        }

        // Collect connection revocations
        revocationCollectionJob = scope.launch {
            ownerSpaceClient.connectionRevocations.collect { revocation ->
                handleConnectionRevoked(revocation)
            }
        }

        Log.d(TAG, "Started collecting from OwnerSpaceClient flows")
        return Result.success(Unit)
    }

    /**
     * Stop subscribing to events.
     */
    fun unsubscribe() {
        messageCollectionJob?.cancel()
        messageCollectionJob = null
        readReceiptCollectionJob?.cancel()
        readReceiptCollectionJob = null
        profileUpdateCollectionJob?.cancel()
        profileUpdateCollectionJob = null
        revocationCollectionJob?.cancel()
        revocationCollectionJob = null
        Log.d(TAG, "Stopped collecting from OwnerSpaceClient flows")
    }

    /**
     * Check if currently subscribed.
     */
    fun isSubscribed(): Boolean {
        return messageCollectionJob?.isActive == true
    }

    /**
     * Handle incoming message from OwnerSpaceClient.
     * Messages arrive encrypted and must be decrypted by the consumer.
     */
    private fun handleIncomingMessage(message: IncomingMessage) {
        try {
            // Pass through the encrypted message directly
            // Downstream consumers are responsible for decryption using connectionCryptoManager
            val event = MessageEvent.NewMessage(
                incomingMessage = message
            )

            Log.d(TAG, "New message from ${message.connectionId}: ${message.messageId}")
            _messageEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message", e)
        }
    }

    /**
     * Handle read receipt from OwnerSpaceClient.
     */
    private fun handleReadReceipt(receipt: ReadReceipt) {
        try {
            val event = MessageEvent.MessageRead(
                messageId = receipt.messageId,
                connectionId = receipt.connectionId,
                readAt = parseTimestamp(receipt.readAt)
            )

            Log.d(TAG, "Read receipt for ${receipt.messageId}")
            _messageEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle read receipt", e)
        }
    }

    /**
     * Handle profile update from OwnerSpaceClient.
     */
    private fun handleProfileUpdate(update: NatsProfileUpdate) {
        try {
            val event = ConnectionEvent.ProfileUpdated(
                connectionId = update.connectionId,
                profile = PartialProfile(
                    displayName = update.displayName,
                    avatarUrl = update.avatarUrl,
                    status = update.status
                )
            )

            Log.d(TAG, "Profile update from ${update.connectionId}")
            _connectionEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle profile update", e)
        }
    }

    /**
     * Handle connection revocation from OwnerSpaceClient.
     */
    private fun handleConnectionRevoked(revocation: NatsConnectionRevoked) {
        try {
            val event = ConnectionEvent.ConnectionRevoked(
                connectionId = revocation.connectionId,
                reason = revocation.reason
            )

            Log.i(TAG, "Connection revoked: ${revocation.connectionId}")
            _connectionEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle connection revocation", e)
        }
    }

    /**
     * Parse ISO 8601 timestamp to epoch milliseconds.
     */
    private fun parseTimestamp(isoTimestamp: String): Long {
        return try {
            if (isoTimestamp.isNotEmpty()) {
                Instant.parse(isoTimestamp).toEpochMilli()
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
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
     * New encrypted message received.
     * The message content is encrypted and must be decrypted by the consumer
     * using ConnectionCryptoManager with the connection's shared secret.
     */
    data class NewMessage(
        val incomingMessage: IncomingMessage
    ) : MessageEvent() {
        val messageId: String get() = incomingMessage.messageId
        val connectionId: String get() = incomingMessage.connectionId
    }

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

// MARK: - Profile Update Model

/**
 * Partial profile update from a peer.
 * Only contains fields that changed, all fields are optional.
 */
data class PartialProfile(
    val displayName: String?,
    val avatarUrl: String?,
    val status: String?
)

// MARK: - Exceptions

class MessagingException(message: String) : Exception(message)
