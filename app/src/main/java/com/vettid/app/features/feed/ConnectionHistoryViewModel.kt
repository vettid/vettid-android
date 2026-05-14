package com.vettid.app.features.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.AuditCursor
import com.vettid.app.core.nats.AuditEntry
import com.vettid.app.core.nats.ConnectionAuditClient
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.features.grants.GrantsRepository
import com.vettid.app.features.grants.PendingRequestSummary
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
    private val grantsRepository: GrantsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    private val _state = MutableStateFlow<ConnectionHistoryState>(ConnectionHistoryState.Loading)
    val state: StateFlow<ConnectionHistoryState> = _state.asStateFlow()

    // Incoming data requests for this connection that are still awaiting
    // the user's decision, keyed by request_id. Lets the history screen
    // turn a `data.request.received` row that's still open into a
    // tappable "Respond" affordance — the permanent fallback path back
    // to a request that needs an answer.
    private val _pendingRequests = MutableStateFlow<Map<String, PendingRequestSummary>>(emptyMap())
    val pendingRequests: StateFlow<Map<String, PendingRequestSummary>> = _pendingRequests.asStateFlow()

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

    // Time-range filter for the history list. Null = all time.
    private val _timeFilter = MutableStateFlow<TimeRangeFilter?>(null)
    val timeFilter: StateFlow<TimeRangeFilter?> = _timeFilter.asStateFlow()

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
        loadPendingRequests()
    }

    /**
     * Refresh the set of still-open incoming requests for this
     * connection. Cheap RPC; runs on init + every refresh so the
     * "Respond" affordance disappears once a request is actioned.
     */
    private fun loadPendingRequests() {
        viewModelScope.launch {
            grantsRepository.listPending().onSuccess { list ->
                _pendingRequests.value = list
                    .filter { it.connectionId == connectionId }
                    .associateBy { it.requestId }
            }
        }
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
        loadPendingRequests()
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

    /** Set or clear (null) the time-range filter. Triggers a reload. */
    fun onTimeFilterChanged(range: TimeRangeFilter?) {
        _timeFilter.value = range
        loadFirstPage(pushState = true)
    }

    fun loadNextPage() {
        if (endReached || _isPaginating.value) return
        val cursor = nextCursor ?: return
        viewModelScope.launch {
            _isPaginating.value = true
            val q = _searchQuery.value.trim()
            val range = _timeFilter.value
            val result = if (q.isEmpty()) {
                auditClient.list(
                    connectionId,
                    cursor = cursor,
                    sinceEpoch = range?.sinceEpoch,
                    untilEpoch = range?.untilEpoch,
                )
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
            val range = _timeFilter.value
            val result = if (q.isEmpty()) {
                auditClient.list(
                    connectionId,
                    sinceEpoch = range?.sinceEpoch,
                    untilEpoch = range?.untilEpoch,
                )
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

/**
 * Time window applied to the history list. Epochs are seconds so they
 * can be passed straight to the vault's since_epoch / until_epoch fields.
 */
data class TimeRangeFilter(
    val label: String,            // "Today", "Last 7 days", "Custom (…)"
    val sinceEpoch: Long,
    val untilEpoch: Long,          // exclusive upper bound; 0 = open
)

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
