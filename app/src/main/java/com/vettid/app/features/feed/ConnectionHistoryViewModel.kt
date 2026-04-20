package com.vettid.app.features.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
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
 * The connection card in the feed collapses activity into a compact
 * summary; this screen restores the expanded timeline so the user can
 * see every message, call, key rotation, etc. tied to the peer. Data
 * source is FeedRepository — no new vault RPC needed.
 */
@HiltViewModel
class ConnectionHistoryViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val connectionsClient: ConnectionsClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    private val _state = MutableStateFlow<ConnectionHistoryState>(ConnectionHistoryState.Loading)
    val state: StateFlow<ConnectionHistoryState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Peer GUID for broader event matching — some call/transfer events
    // carry peer_guid in metadata instead of connection_id.
    private var peerGuid: String? = null

    // Cached unfiltered list so search just re-filters in-memory rather
    // than re-hitting the repository on every keystroke.
    private var allEvents: List<FeedEvent> = emptyList()

    init {
        // Resolve the peer's GUID so we can match events that only carry
        // peer_guid (e.g. some historical call events). Best-effort — on
        // failure we fall back to connection_id/sourceId-only matching.
        viewModelScope.launch {
            connectionsClient.list().onSuccess { result ->
                peerGuid = result.items.firstOrNull { it.connectionId == connectionId }?.peerGuid
            }
            load(forceRefresh = false)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            load(forceRefresh = true)
            _isRefreshing.value = false
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        applyFilter()
    }

    private suspend fun load(forceRefresh: Boolean) {
        if (!forceRefresh) _state.value = ConnectionHistoryState.Loading
        val result = feedRepository.getFeed(forceRefresh = forceRefresh)
        result.onSuccess { events ->
            allEvents = events
                .filter { eventBelongsToConnection(it) }
                .sortedByDescending { it.createdAt }
            applyFilter()
        }.onFailure { err ->
            _state.value = ConnectionHistoryState.Error(err.message ?: "Failed to load history")
        }
    }

    private fun applyFilter() {
        val query = _searchQuery.value.trim()
        val filtered = if (query.isEmpty()) {
            allEvents
        } else {
            allEvents.filter { event ->
                event.title.contains(query, ignoreCase = true) ||
                    (event.message?.contains(query, ignoreCase = true) == true)
            }
        }
        _state.value = if (filtered.isEmpty()) {
            if (allEvents.isEmpty()) ConnectionHistoryState.Empty
            else ConnectionHistoryState.NoMatches(query)
        } else {
            ConnectionHistoryState.Loaded(filtered)
        }
    }

    /**
     * Match an event to this connection. The backend stores the
     * connection id in metadata["connection_id"] for conversation-bound
     * events; lifecycle events (connection.created, connection.accepted,
     * …) use sourceId; call events sometimes carry the peer's user_guid
     * in metadata instead. Accept any of them so the audit trail is
     * actually complete.
     */
    private fun eventBelongsToConnection(event: FeedEvent): Boolean {
        val meta = event.metadata ?: emptyMap()
        if (meta["connection_id"] == connectionId) return true
        if (event.sourceId == connectionId) return true
        val peer = peerGuid
        if (peer != null) {
            if (meta["peer_guid"] == peer) return true
            if (meta["peer_user_guid"] == peer) return true
            if (meta["sender_guid"] == peer) return true
            if (event.sourceId == peer) return true
        }
        return false
    }
}

sealed class ConnectionHistoryState {
    data object Loading : ConnectionHistoryState()
    data object Empty : ConnectionHistoryState()
    data class NoMatches(val query: String) : ConnectionHistoryState()
    data class Loaded(val events: List<com.vettid.app.core.nats.FeedEvent>) : ConnectionHistoryState()
    data class Error(val message: String) : ConnectionHistoryState()
}
