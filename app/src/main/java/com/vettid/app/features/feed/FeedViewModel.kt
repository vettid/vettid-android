package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private const val TAG = "FeedViewModel"

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FeedEffect>()
    val effects: SharedFlow<FeedEffect> = _effects.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadFeed()
    }

    fun loadFeed() {
        viewModelScope.launch {
            _state.value = FeedState.Loading
            try {
                // For now, show mock data to demonstrate the UI
                // In production, this would fetch from the API/local storage
                val mockEvents = generateMockEvents()

                if (mockEvents.isEmpty()) {
                    _state.value = FeedState.Empty
                } else {
                    _state.value = FeedState.Loaded(
                        events = mockEvents,
                        hasMore = false,
                        unreadCount = mockEvents.count { !it.isRead }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load feed", e)
                _state.value = FeedState.Error(e.message ?: "Failed to load feed")
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                loadFeed()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onEventClick(event: FeedEvent) {
        viewModelScope.launch {
            // Mark as read
            markAsRead(event.id)

            // Navigate based on event type
            when (event) {
                is FeedEvent.Message -> {
                    _effects.emit(FeedEffect.NavigateToConversation(event.senderId))
                }
                is FeedEvent.ConnectionRequest -> {
                    _effects.emit(FeedEffect.NavigateToConnectionRequest(event.fromId))
                }
                is FeedEvent.AuthRequest -> {
                    _effects.emit(FeedEffect.NavigateToAuthRequest(event.id))
                }
                is FeedEvent.HandlerComplete -> {
                    _effects.emit(FeedEffect.NavigateToHandler(event.handlerId))
                }
                is FeedEvent.BackupComplete -> {
                    _effects.emit(FeedEffect.NavigateToBackup(event.backupId))
                }
                is FeedEvent.VaultStatusChange -> {
                    // No navigation for vault status changes
                }
            }
        }
    }

    fun markAsRead(eventId: String) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is FeedState.Loaded) {
                val updatedEvents = currentState.events.map { event ->
                    if (event.id == eventId) {
                        when (event) {
                            is FeedEvent.Message -> event.copy(isRead = true)
                            is FeedEvent.ConnectionRequest -> event.copy(isRead = true)
                            is FeedEvent.AuthRequest -> event.copy(isRead = true)
                            is FeedEvent.HandlerComplete -> event.copy(isRead = true)
                            is FeedEvent.VaultStatusChange -> event.copy(isRead = true)
                            is FeedEvent.BackupComplete -> event.copy(isRead = true)
                        }
                    } else {
                        event
                    }
                }
                _state.value = currentState.copy(
                    events = updatedEvents,
                    unreadCount = updatedEvents.count { !it.isRead }
                )
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is FeedState.Loaded) {
                val updatedEvents = currentState.events.map { event ->
                    when (event) {
                        is FeedEvent.Message -> event.copy(isRead = true)
                        is FeedEvent.ConnectionRequest -> event.copy(isRead = true)
                        is FeedEvent.AuthRequest -> event.copy(isRead = true)
                        is FeedEvent.HandlerComplete -> event.copy(isRead = true)
                        is FeedEvent.VaultStatusChange -> event.copy(isRead = true)
                        is FeedEvent.BackupComplete -> event.copy(isRead = true)
                    }
                }
                _state.value = currentState.copy(
                    events = updatedEvents,
                    unreadCount = 0
                )
            }
        }
    }

    private fun generateMockEvents(): List<FeedEvent> {
        val now = Instant.now()
        return listOf(
            FeedEvent.Message(
                id = "msg-1",
                timestamp = now.minusSeconds(300),
                isRead = false,
                senderId = "conn-123",
                senderName = "Alice Johnson",
                preview = "Hey, did you get a chance to review the document I sent?"
            ),
            FeedEvent.ConnectionRequest(
                id = "req-1",
                timestamp = now.minusSeconds(3600),
                isRead = false,
                fromId = "user-456",
                fromName = "Bob Smith",
                fromEmail = "bob@example.com"
            ),
            FeedEvent.HandlerComplete(
                id = "handler-1",
                timestamp = now.minusSeconds(7200),
                isRead = true,
                handlerId = "handler-doc-sign",
                handlerName = "Document Signer",
                success = true,
                resultSummary = "Contract signed successfully"
            ),
            FeedEvent.VaultStatusChange(
                id = "vault-1",
                timestamp = now.minusSeconds(14400),
                isRead = true,
                previousStatus = "STOPPED",
                newStatus = "RUNNING"
            ),
            FeedEvent.BackupComplete(
                id = "backup-1",
                timestamp = now.minusSeconds(86400),
                isRead = true,
                backupId = "backup-abc123",
                success = true,
                sizeBytes = 1024 * 1024 * 5 // 5 MB
            )
        )
    }
}
