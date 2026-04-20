package com.vettid.app.features.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.AuditCursor
import com.vettid.app.core.nats.AuditEntry
import com.vettid.app.core.nats.ConnectionAuditClient
import com.vettid.app.core.nats.ConnectionsClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads the full interaction audit trail for a single connection.
 *
 * Backed by connection.audit.{list,search}. Search runs server-side so
 * agent connections with thousands of entries stay responsive.
 * Pagination uses the (created_at, entry_id) cursor returned by the
 * vault — no loading "everything up front."
 */
@HiltViewModel
class ConnectionHistoryViewModel @Inject constructor(
    private val auditClient: ConnectionAuditClient,
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

    private val _isPaginating = MutableStateFlow(false)
    val isPaginating: StateFlow<Boolean> = _isPaginating.asStateFlow()

    // Connection peer name for the screen header. Loaded once from
    // connection.list on init; the audit trail itself doesn't carry it.
    private val _peerName = MutableStateFlow("")
    val peerName: StateFlow<String> = _peerName.asStateFlow()

    // Most recent loaded page — used for the "load next page" request
    // and to suppress duplicate inflight requests.
    private var nextCursor: AuditCursor? = null
    private var endReached: Boolean = false

    // Debounce coroutine for search-as-you-type so we don't hammer the
    // vault with one RPC per keystroke.
    private var searchJob: Job? = null

    init {
        loadFirstPage()
        loadPeerName()
    }

    private fun loadPeerName() {
        viewModelScope.launch {
            connectionsClient.list().onSuccess { result ->
                val match = result.items.firstOrNull { it.connectionId == connectionId }
                if (match != null) {
                    val name = listOfNotNull(
                        match.peerProfile?.firstName, match.peerProfile?.lastName,
                    ).joinToString(" ").trim().ifEmpty { match.label }
                    _peerName.value = name
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadFirstPage(pushState = false)
            _isRefreshing.value = false
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // ~250 ms feels snappy but avoids firing on every character
            // for users who type fast.
            delay(250)
            loadFirstPage(pushState = true)
        }
    }

    fun loadNextPage() {
        if (endReached || _isPaginating.value) return
        val cursor = nextCursor ?: return
        viewModelScope.launch {
            _isPaginating.value = true
            val q = _searchQuery.value.trim()
            val result = if (q.isEmpty()) {
                auditClient.list(connectionId, cursor = cursor)
            } else {
                auditClient.search(connectionId, query = q, cursor = cursor)
            }
            _isPaginating.value = false

            val prev = _state.value
            if (prev !is ConnectionHistoryState.Loaded) return@launch
            result.onSuccess { page ->
                nextCursor = page.nextCursor
                endReached = page.nextCursor == null
                _state.value = ConnectionHistoryState.Loaded(
                    events = prev.events + page.entries,
                    totalEstimate = page.totalEstimate,
                    endReached = endReached,
                )
            }
        }
    }

    private fun loadFirstPage(pushState: Boolean = true) {
        viewModelScope.launch {
            if (pushState) _state.value = ConnectionHistoryState.Loading
            nextCursor = null
            endReached = false

            val q = _searchQuery.value.trim()
            val result = if (q.isEmpty()) {
                auditClient.list(connectionId)
            } else {
                auditClient.search(connectionId, query = q)
            }

            result.onSuccess { page ->
                nextCursor = page.nextCursor
                endReached = page.nextCursor == null
                _state.value = when {
                    page.entries.isEmpty() && q.isNotEmpty() ->
                        ConnectionHistoryState.NoMatches(q)
                    page.entries.isEmpty() ->
                        ConnectionHistoryState.Empty
                    else -> ConnectionHistoryState.Loaded(
                        events = page.entries,
                        totalEstimate = page.totalEstimate,
                        endReached = endReached,
                    )
                }
            }.onFailure { err ->
                _state.value = ConnectionHistoryState.Error(err.message ?: "Failed to load history")
            }
        }
    }
}

sealed class ConnectionHistoryState {
    data object Loading : ConnectionHistoryState()
    data object Empty : ConnectionHistoryState()
    data class NoMatches(val query: String) : ConnectionHistoryState()
    data class Loaded(
        val events: List<AuditEntry>,
        val totalEstimate: Int,
        val endReached: Boolean,
    ) : ConnectionHistoryState()
    data class Error(val message: String) : ConnectionHistoryState()
}
