package com.vettid.app.features.messaging

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.NatsMessagingClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.*
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for conversation (messaging) screen.
 *
 * Features:
 * - Send encrypted messages via NATS vault handlers
 * - Receive real-time messages via NATS subscriptions
 * - Mark messages as read via NATS
 *
 * Note: Message history is stored locally and in vault JetStream.
 * Messages flow vault-to-vault via NATS, not through HTTP APIs.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val messagingClient: NatsMessagingClient,
    private val connectionsClient: ConnectionsClient,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val connectionCryptoManager: ConnectionCryptoManager,
    private val credentialStore: CredentialStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Loading)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _effects = MutableSharedFlow<ConversationEffect>()
    val effects: SharedFlow<ConversationEffect> = _effects.asSharedFlow()

    // Current user's GUID
    private val currentUserGuid: String? by lazy { credentialStore.getUserGuid() }

    // Pagination cursor
    private var nextCursor: String? = null
    private var hasMore: Boolean = true

    // All loaded messages
    private var allMessages: MutableList<Message> = mutableListOf()

    init {
        loadConnection()
        loadMessages()
        observeIncomingMessages()
    }

    /**
     * Load connection info from vault via NATS.
     */
    private fun loadConnection() {
        viewModelScope.launch {
            if (!natsAutoConnector.isConnected()) return@launch

            connectionsClient.list().fold(
                onSuccess = { listResult ->
                    val record = listResult.items.find { it.connectionId == connectionId }
                    if (record != null) {
                        val status = when (record.status.lowercase()) {
                            "active" -> ConnectionStatus.ACTIVE
                            "pending" -> ConnectionStatus.PENDING
                            "revoked" -> ConnectionStatus.REVOKED
                            else -> ConnectionStatus.ACTIVE
                        }
                        val createdAtMillis = try {
                            java.time.Instant.parse(record.createdAt).toEpochMilli()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                        _connection.value = Connection(
                            connectionId = record.connectionId,
                            peerGuid = record.peerGuid,
                            peerDisplayName = record.label,
                            peerAvatarUrl = null,
                            status = status,
                            createdAt = createdAtMillis,
                            lastMessageAt = null,
                            unreadCount = 0
                        )
                    }
                },
                onFailure = { /* Ignore, not critical */ }
            )
        }
    }

    /**
     * Load message history.
     * Note: Messages are stored locally and synced via vault JetStream.
     * For now, start with empty state - messages arrive via real-time subscription.
     */
    fun loadMessages() {
        viewModelScope.launch {
            // TODO: Load from local storage or vault JetStream
            // For now, start with empty state
            _state.value = ConversationState.Empty
        }
    }

    /**
     * Observe incoming messages via NATS subscription.
     */
    private fun observeIncomingMessages() {
        viewModelScope.launch {
            ownerSpaceClient.incomingMessages
                .filter { it.connectionId == connectionId }
                .collect { incomingMessage ->
                    // Decrypt the message content
                    val decrypted = connectionCryptoManager.decryptMessageFromConnection(
                        ciphertext = connectionCryptoManager.decodeBase64(incomingMessage.encryptedContent),
                        nonce = connectionCryptoManager.decodeBase64(incomingMessage.nonce),
                        connectionId = connectionId
                    )

                    val sentAtMillis = try {
                        java.time.Instant.parse(incomingMessage.sentAt).toEpochMilli()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    val message = Message(
                        messageId = incomingMessage.messageId,
                        connectionId = connectionId,
                        senderId = incomingMessage.senderGuid,
                        content = decrypted ?: "[Unable to decrypt]",
                        contentType = when (incomingMessage.contentType) {
                            "image" -> MessageContentType.IMAGE
                            "file" -> MessageContentType.FILE
                            else -> MessageContentType.TEXT
                        },
                        sentAt = sentAtMillis,
                        receivedAt = System.currentTimeMillis(),
                        readAt = null,
                        status = MessageStatus.DELIVERED
                    )

                    // Add to list
                    allMessages.add(0, message)
                    _state.value = ConversationState.Loaded(
                        messages = allMessages.toList(),
                        hasMore = false
                    )

                    // Send read receipt
                    messagingClient.sendReadReceipt(connectionId, message.messageId)
                }
        }
    }

    /**
     * Load more messages (pagination).
     * Note: Not implemented for NATS-based messaging yet.
     */
    fun loadMoreMessages() {
        // TODO: Implement pagination from local storage or vault JetStream
    }

    /**
     * Update message text input.
     */
    fun onMessageTextChanged(text: String) {
        _messageText.value = text
    }

    /**
     * Send a message via NATS vault handler.
     */
    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            if (!natsAutoConnector.isConnected()) {
                _effects.emit(ConversationEffect.ShowError("Not connected to vault"))
                return@launch
            }

            _isSending.value = true

            // Encrypt the message
            val encrypted = connectionCryptoManager.encryptMessageForConnection(text, connectionId)
            if (encrypted == null) {
                _effects.emit(ConversationEffect.ShowError("Encryption key not found"))
                _isSending.value = false
                return@launch
            }

            messagingClient.sendMessage(
                connectionId = connectionId,
                encryptedContent = android.util.Base64.encodeToString(encrypted.ciphertext, android.util.Base64.NO_WRAP),
                nonce = android.util.Base64.encodeToString(encrypted.nonce, android.util.Base64.NO_WRAP),
                contentType = "text"
            ).fold(
                onSuccess = { sentMessage ->
                    // Create message for display
                    val sentAtMillis = try {
                        java.time.Instant.parse(sentMessage.timestamp).toEpochMilli()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    val message = Message(
                        messageId = sentMessage.messageId,
                        connectionId = connectionId,
                        senderId = currentUserGuid ?: "",
                        content = text,
                        contentType = MessageContentType.TEXT,
                        sentAt = sentAtMillis,
                        receivedAt = null,
                        readAt = null,
                        status = when (sentMessage.status) {
                            "delivered" -> MessageStatus.DELIVERED
                            "read" -> MessageStatus.READ
                            else -> MessageStatus.SENT
                        }
                    )

                    // Add to list
                    allMessages.add(0, message)
                    _state.value = ConversationState.Loaded(
                        messages = allMessages.toList(),
                        hasMore = false
                    )

                    // Clear input
                    _messageText.value = ""
                },
                onFailure = { error ->
                    _effects.emit(ConversationEffect.ShowError(
                        error.message ?: "Failed to send message"
                    ))
                }
            )

            _isSending.value = false
        }
    }

    /**
     * Handle new message from real-time subscription (legacy).
     * Note: Real-time messages now come via observeIncomingMessages().
     */
    fun onNewMessage(message: Message) {
        viewModelScope.launch {
            // Add to list (at beginning)
            allMessages.add(0, message)

            _state.value = ConversationState.Loaded(
                messages = allMessages.toList(),
                hasMore = false
            )

            // Mark as read if from other user
            if (message.senderId != currentUserGuid) {
                messagingClient.sendReadReceipt(connectionId, message.messageId)
            }
        }
    }

    /**
     * Check if message is from current user.
     */
    fun isFromCurrentUser(message: Message): Boolean {
        return message.senderId == currentUserGuid
    }

    /**
     * Navigate to connection details.
     */
    fun onConnectionDetailClick() {
        viewModelScope.launch {
            _effects.emit(ConversationEffect.NavigateToConnectionDetail(connectionId))
        }
    }
}

// MARK: - State Types

/**
 * Conversation state.
 */
sealed class ConversationState {
    object Loading : ConversationState()
    object Empty : ConversationState()

    data class Loaded(
        val messages: List<Message>,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false
    ) : ConversationState()

    data class Error(val message: String) : ConversationState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ConversationEffect {
    data class ShowError(val message: String) : ConversationEffect()
    data class NavigateToConnectionDetail(val connectionId: String) : ConversationEffect()
}
