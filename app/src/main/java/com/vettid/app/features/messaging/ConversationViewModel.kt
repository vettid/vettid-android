package com.vettid.app.features.messaging

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
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
 * - Load message history with pagination
 * - Send encrypted messages
 * - Receive real-time messages
 * - Mark messages as read
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val messagingApiClient: MessagingApiClient,
    private val connectionApiClient: ConnectionApiClient,
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
    }

    /**
     * Load connection info.
     */
    private fun loadConnection() {
        viewModelScope.launch {
            connectionApiClient.getConnection(connectionId).fold(
                onSuccess = { connection ->
                    _connection.value = connection
                },
                onFailure = { /* Ignore, not critical */ }
            )
        }
    }

    /**
     * Load message history.
     */
    fun loadMessages() {
        viewModelScope.launch {
            if (_state.value !is ConversationState.Loaded) {
                _state.value = ConversationState.Loading
            }

            messagingApiClient.getMessageHistory(connectionId).fold(
                onSuccess = { response ->
                    // Decrypt messages
                    val decryptedMessages = response.messages.mapNotNull { message ->
                        decryptMessageContent(message)
                    }

                    allMessages = decryptedMessages.toMutableList()
                    hasMore = response.hasMore
                    nextCursor = response.nextCursor

                    _state.value = if (decryptedMessages.isEmpty()) {
                        ConversationState.Empty
                    } else {
                        ConversationState.Loaded(
                            messages = decryptedMessages,
                            hasMore = hasMore
                        )
                    }

                    // Mark as read
                    markAllAsRead()
                },
                onFailure = { error ->
                    _state.value = ConversationState.Error(
                        message = error.message ?: "Failed to load messages"
                    )
                }
            )
        }
    }

    /**
     * Load more messages (pagination).
     */
    fun loadMoreMessages() {
        if (!hasMore || nextCursor == null) return

        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ConversationState.Loaded && !currentState.isLoadingMore) {
                _state.value = currentState.copy(isLoadingMore = true)

                messagingApiClient.getMessageHistory(
                    connectionId = connectionId,
                    before = nextCursor
                ).fold(
                    onSuccess = { response ->
                        val decryptedMessages = response.messages.mapNotNull { message ->
                            decryptMessageContent(message)
                        }

                        allMessages.addAll(decryptedMessages)
                        hasMore = response.hasMore
                        nextCursor = response.nextCursor

                        _state.value = ConversationState.Loaded(
                            messages = allMessages.toList(),
                            hasMore = hasMore,
                            isLoadingMore = false
                        )
                    },
                    onFailure = {
                        _state.value = currentState.copy(isLoadingMore = false)
                    }
                )
            }
        }
    }

    /**
     * Update message text input.
     */
    fun onMessageTextChanged(text: String) {
        _messageText.value = text
    }

    /**
     * Send a message.
     */
    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            _isSending.value = true

            // Encrypt the message
            val encrypted = connectionCryptoManager.encryptMessageForConnection(text, connectionId)
            if (encrypted == null) {
                _effects.emit(ConversationEffect.ShowError("Encryption key not found"))
                _isSending.value = false
                return@launch
            }

            messagingApiClient.sendMessage(
                connectionId = connectionId,
                encryptedContent = encrypted.ciphertext,
                nonce = encrypted.nonce
            ).fold(
                onSuccess = { message ->
                    // Decrypt the sent message for display
                    val decryptedMessage = message.copy(content = text)

                    // Add to list
                    allMessages.add(0, decryptedMessage)

                    val currentState = _state.value
                    _state.value = ConversationState.Loaded(
                        messages = allMessages.toList(),
                        hasMore = hasMore
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
     * Mark all messages as read.
     */
    private fun markAllAsRead() {
        viewModelScope.launch {
            val firstUnread = allMessages.firstOrNull { msg ->
                msg.senderId != currentUserGuid && msg.readAt == null
            }

            firstUnread?.let { message ->
                messagingApiClient.markAsRead(message.messageId)
            }
        }
    }

    /**
     * Handle new message from real-time subscription.
     */
    fun onNewMessage(message: Message) {
        viewModelScope.launch {
            val decryptedMessage = decryptMessageContent(message) ?: return@launch

            // Add to list (at beginning)
            allMessages.add(0, decryptedMessage)

            _state.value = ConversationState.Loaded(
                messages = allMessages.toList(),
                hasMore = hasMore
            )

            // Mark as read if from other user
            if (message.senderId != currentUserGuid) {
                messagingApiClient.markAsRead(message.messageId)
            }
        }
    }

    /**
     * Decrypt message content.
     */
    private fun decryptMessageContent(message: Message): Message? {
        return try {
            // If content is already decrypted (e.g., from send), return as-is
            if (!message.content.startsWith("enc:")) {
                return message
            }

            // Parse encrypted content (format: "enc:<base64_ciphertext>:<base64_nonce>")
            val parts = message.content.removePrefix("enc:").split(":")
            if (parts.size != 2) return null

            val ciphertext = connectionCryptoManager.decodeBase64(parts[0])
            val nonce = connectionCryptoManager.decodeBase64(parts[1])

            val decrypted = connectionCryptoManager.decryptMessageFromConnection(
                ciphertext = ciphertext,
                nonce = nonce,
                connectionId = connectionId
            )

            if (decrypted != null) {
                message.copy(content = decrypted)
            } else {
                message.copy(content = "[Unable to decrypt message]")
            }
        } catch (e: Exception) {
            message.copy(content = "[Unable to decrypt message]")
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
