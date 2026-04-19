package com.vettid.app.features.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads the full interaction audit trail for a single connection.
 *
 * The connection card in the feed collapses activity into a single
 * preview + type icon; this screen restores the expanded timeline so
 * the user can see every message, call, key rotation, etc. tied to
 * the peer. Data source is FeedRepository — no new vault RPC needed.
 */
@HiltViewModel
class ConnectionHistoryViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    private val _state = MutableStateFlow<ConnectionHistoryState>(ConnectionHistoryState.Loading)
    val state: StateFlow<ConnectionHistoryState> = _state.asStateFlow()

    init {
        load(forceRefresh = false)
    }

    fun refresh() {
        load(forceRefresh = true)
    }

    private fun load(forceRefresh: Boolean) {
        viewModelScope.launch {
            _state.value = ConnectionHistoryState.Loading
            val result = feedRepository.getFeed(forceRefresh = forceRefresh)
            result.onSuccess { events ->
                val filtered = events
                    .filter { eventBelongsToConnection(it, connectionId) }
                    .sortedByDescending { it.createdAt }
                _state.value = if (filtered.isEmpty()) {
                    ConnectionHistoryState.Empty
                } else {
                    ConnectionHistoryState.Loaded(filtered)
                }
            }.onFailure { err ->
                _state.value = ConnectionHistoryState.Error(err.message ?: "Failed to load history")
            }
        }
    }

    /**
     * Match an event to a connection. The backend stores the connection id
     * in metadata["connection_id"] for conversation-bound events; lifecycle
     * events (connection.created, connection.accepted, etc.) use sourceId
     * instead. Accept either.
     */
    private fun eventBelongsToConnection(event: FeedEvent, connectionId: String): Boolean {
        val fromMeta = event.metadata?.get("connection_id")
        if (fromMeta == connectionId) return true
        if (event.sourceId == connectionId) return true
        return false
    }
}

sealed class ConnectionHistoryState {
    data object Loading : ConnectionHistoryState()
    data object Empty : ConnectionHistoryState()
    data class Loaded(val events: List<FeedEvent>) : ConnectionHistoryState()
    data class Error(val message: String) : ConnectionHistoryState()
}
