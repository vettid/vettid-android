package com.vettid.app.features.messaging

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.NatsMessagingClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.network.*
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.feed.FeedRepository
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
    private val feedClient: FeedClient,
    private val feedRepository: FeedRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Loading)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    private val _peerPhotoBase64 = MutableStateFlow<String?>(null)
    val peerPhotoBase64: StateFlow<String?> = _peerPhotoBase64.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isAgentConnection = MutableStateFlow(false)
    val isAgentConnection: StateFlow<Boolean> = _isAgentConnection.asStateFlow()

    private val _agentName = MutableStateFlow<String?>(null)
    val agentName: StateFlow<String?> = _agentName.asStateFlow()

    private val _agentType = MutableStateFlow<String?>(null)
    val agentType: StateFlow<String?> = _agentType.asStateFlow()

    private val _effects = MutableSharedFlow<ConversationEffect>()
    val effects: SharedFlow<ConversationEffect> = _effects.asSharedFlow()

    // Current user's GUID
    private val currentUserGuid: String? by lazy { credentialStore.getUserGuid() }

    // Pagination cursor
    private var nextCursor: String? = null
    private var hasMore: Boolean = true

    // All loaded messages
    private var allMessages: MutableList<Message> = mutableListOf()

    // Transport key for app-vault encrypted messaging
    private var transportKey: ByteArray? = null

    init {
        loadConnection()
        fetchTransportKey()
        loadMessages()
        observeIncomingMessages()
        observeReadReceipts()
        markConnectionEventsAsRead()
        sendPendingReadReceipts()
    }

    /**
     * Mark all unread message/call feed events for this connection as read.
     * Updates both the vault (via FeedClient) and the local cache (via FeedRepository)
     * so the feed badge updates immediately when the user navigates back.
     */
    private fun markConnectionEventsAsRead() {
        viewModelScope.launch {
            val messageEventTypes = setOf(
                "message.received", "message.sent",
                "call.incoming", "call.completed", "call.missed",
                "transfer.request", "connection.accepted"
            )
            val unreadEventIds = feedRepository.getCachedEvents()
                .filter { event ->
                    event.isUnread &&
                        event.eventType in messageEventTypes &&
                        (event.metadata?.get("connection_id") ?: event.sourceId) == connectionId
                }
                .map { it.eventId }

            if (unreadEventIds.isEmpty()) return@launch

            feedClient.markMultipleRead(unreadEventIds)
                .onSuccess {
                    val now = System.currentTimeMillis() / 1000
                    unreadEventIds.forEach { id ->
                        feedRepository.updateEventLocally(id) {
                            it.copy(feedStatus = "read", readAt = now)
                        }
                    }
                    android.util.Log.d("ConversationVM", "Marked ${unreadEventIds.size} events as read for $connectionId")
                }
                .onFailure { error ->
                    android.util.Log.w("ConversationVM", "Failed to mark events as read: ${error.message}")
                }
        }
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
                        // Store peer photo for header avatar
                        _peerPhotoBase64.value = record.peerProfile?.photo

                        // Detect agent connections for reply routing
                        if (record.connectionType == "agent") {
                            _isAgentConnection.value = true
                            _agentName.value = record.label
                        }
                    }
                },
                onFailure = { /* Ignore, not critical */ }
            )
        }
    }

    /**
     * Fetch transport key from vault for encrypted messaging.
     */
    private fun fetchTransportKey() {
        viewModelScope.launch {
            messagingClient.getTransportKey(connectionId).fold(
                onSuccess = { key ->
                    transportKey = key
                    android.util.Log.d("ConversationVM", "Transport key fetched for $connectionId")
                },
                onFailure = { error ->
                    android.util.Log.w("ConversationVM", "Failed to get transport key: ${error.message}")
                    // Non-fatal — will fall back to plaintext if key unavailable
                }
            )
        }
    }

    /**
     * Load message history from the vault.
     */
    fun loadMessages() {
        viewModelScope.launch {
            messagingClient.listMessages(connectionId).fold(
                onSuccess = { storedMessages ->
                    android.util.Log.i("ConversationVM", "Loaded ${storedMessages.size} messages for $connectionId")
                    storedMessages.forEachIndexed { i, msg ->
                        android.util.Log.d("ConversationVM", "  msg[$i]: id=${msg.messageId}, dir=${msg.direction}, status=${msg.status}, content=${msg.content.take(50)}")
                    }
                    if (storedMessages.isEmpty()) {
                        _state.value = ConversationState.Empty
                    } else {
                        val messages = storedMessages.map { stored ->
                            val sentAtMillis = try {
                                java.time.Instant.parse(stored.sentAt).toEpochMilli()
                            } catch (e: Exception) { System.currentTimeMillis() }

                            Message(
                                messageId = stored.messageId,
                                connectionId = stored.connectionId,
                                senderId = stored.senderGuid,
                                content = stored.content,
                                contentType = when (stored.contentType) {
                                    "image" -> MessageContentType.IMAGE
                                    "file" -> MessageContentType.FILE
                                    else -> MessageContentType.TEXT
                                },
                                sentAt = sentAtMillis,
                                receivedAt = null,
                                readAt = null,
                                status = when (stored.status) {
                                    "sent" -> MessageStatus.SENT
                                    "delivered" -> MessageStatus.DELIVERED
                                    "read" -> MessageStatus.READ
                                    else -> MessageStatus.SENT
                                }
                            )
                        }
                        // Sort newest first — reverseLayout shows index 0 at the bottom
                        val sorted = messages.sortedByDescending { it.sentAt }
                        allMessages = sorted.toMutableList()
                        _state.value = ConversationState.Loaded(messages = sorted, hasMore = false)
                        android.util.Log.i("ConversationVM", "State set to Loaded with ${sorted.size} messages, newest: ${sorted.firstOrNull()?.content?.take(30)}")
                    }
                },
                onFailure = { error ->
                    android.util.Log.e("ConversationVM", "Failed to load messages for $connectionId: ${error.message}", error)
                    _state.value = ConversationState.Empty
                }
            )
        }
    }

    /**
     * Observe read receipts from the peer — update message status to READ (✓✓).
     */
    private fun observeReadReceipts() {
        viewModelScope.launch {
            ownerSpaceClient.readReceipts
                .filter { it.connectionId == connectionId }
                .collect { receipt ->
                    android.util.Log.d("ConversationVM", "Read receipt: ${receipt.messageId}")
                    // Update message in the list
                    val updated = allMessages.map { msg ->
                        if (msg.messageId == receipt.messageId) {
                            msg.copy(status = MessageStatus.READ, readAt = System.currentTimeMillis())
                        } else msg
                    }
                    if (updated != allMessages.toList()) {
                        allMessages = updated.toMutableList()
                        _state.value = ConversationState.Loaded(
                            messages = allMessages.toList(),
                            hasMore = false
                        )
                    }
                }
        }
    }

    /**
     * Send read receipts for all unread incoming messages in this conversation.
     * Called when conversation opens to catch messages that arrived while closed.
     */
    private fun sendPendingReadReceipts() {
        viewModelScope.launch {
            // Wait for messages to load first
            kotlinx.coroutines.delay(1000)
            val loaded = _state.value as? ConversationState.Loaded ?: return@launch
            val unreadFromPeer = loaded.messages.filter { msg ->
                msg.senderId != currentUserGuid &&
                msg.status != MessageStatus.READ
            }
            if (unreadFromPeer.isEmpty()) return@launch

            android.util.Log.d("ConversationVM", "Sending ${unreadFromPeer.size} pending read receipts")
            unreadFromPeer.forEach { msg ->
                try {
                    messagingClient.sendReadReceipt(connectionId, msg.messageId)
                } catch (e: Exception) {
                    android.util.Log.w("ConversationVM", "Read receipt failed for ${msg.messageId}: ${e.message}")
                }
            }
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
                    // Use decrypted content from vault (vault decrypts with shared secret)
                    val decrypted = incomingMessage.content
                        ?: incomingMessage.encryptedContent // fallback to encrypted if no plaintext

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

            // Build a Message for display regardless of send path
            var sentMessageForDisplay: Message? = null

            val success = if (_isAgentConnection.value) {
                // Agent connection: route reply through agent message-reply path
                val payload = com.google.gson.JsonObject().apply {
                    addProperty("connection_id", connectionId)
                    addProperty("content", text)
                }
                try {
                    val agentResponse = ownerSpaceClient.sendAndAwaitResponse("agent.message-reply", payload, 15000L)
                    if (agentResponse is com.vettid.app.core.nats.VaultResponse.HandlerResult && agentResponse.success) {
                        val msgId = agentResponse.result?.get("message_id")?.asString ?: "msg-${System.currentTimeMillis()}"
                        sentMessageForDisplay = Message(
                            messageId = msgId,
                            connectionId = connectionId,
                            senderId = currentUserGuid ?: "",
                            content = text,
                            contentType = MessageContentType.TEXT,
                            sentAt = System.currentTimeMillis(),
                            receivedAt = null,
                            readAt = null,
                            status = MessageStatus.SENT
                        )
                        true
                    } else false
                } catch (e: Exception) {
                    android.util.Log.e("ConversationVM", "Agent send failed", e)
                    false
                }
            } else {
                // Peer connection: encrypt with transport key (XChaCha20-Poly1305)
                val key = transportKey
                val result = if (key != null) {
                    val encrypted = connectionCryptoManager.encryptXChaCha20(text, key)
                    messagingClient.sendMessage(
                        connectionId = connectionId,
                        encryptedContent = android.util.Base64.encodeToString(encrypted.ciphertext, android.util.Base64.NO_WRAP),
                        nonce = android.util.Base64.encodeToString(encrypted.nonce, android.util.Base64.NO_WRAP),
                        contentType = "text"
                    )
                } else {
                    android.util.Log.w("ConversationVM", "No transport key — sending plaintext to vault")
                    messagingClient.sendMessage(
                        connectionId = connectionId,
                        content = text,
                        contentType = "text"
                    )
                }
                result.fold(
                    onSuccess = { sent ->
                        val sentAtMillis = try {
                            java.time.Instant.parse(sent.timestamp).toEpochMilli()
                        } catch (e: Exception) { System.currentTimeMillis() }
                        sentMessageForDisplay = Message(
                            messageId = sent.messageId,
                            connectionId = connectionId,
                            senderId = currentUserGuid ?: "",
                            content = text,
                            contentType = MessageContentType.TEXT,
                            sentAt = sentAtMillis,
                            receivedAt = null,
                            readAt = null,
                            status = MessageStatus.SENT
                        )
                        true
                    },
                    onFailure = { false }
                )
            }

            if (success && sentMessageForDisplay != null) {
                val message = sentMessageForDisplay!!

                // Add to list
                allMessages.add(0, message)
                _state.value = ConversationState.Loaded(
                    messages = allMessages.toList(),
                    hasMore = false
                )

                // Clear input
                _messageText.value = ""
            } else if (!success) {
                _effects.emit(ConversationEffect.ShowError("Failed to send message"))
            }

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
