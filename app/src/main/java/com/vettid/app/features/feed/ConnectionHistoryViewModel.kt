package com.vettid.app.features.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.audit.AuditChainVerifier
import com.vettid.app.core.nats.AuditCursor
import com.vettid.app.core.nats.AuditEntry
import com.vettid.app.core.nats.AuditListResult
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

    // Shared verifier instance — stateless, safe to keep as a field.
    // Used to stamp per-row chain-verification state on every page
    // fetched + to roll up the screen-level chainStatus pill (#125).
    private val chainVerifier = AuditChainVerifier()

    init {
        loadFirstPage()
        loadPeerName()
        loadPendingRequests()
    }

    /**
     * SECURITY (#125): run the audit-chain verifier over a fetched
     * page. The vault ships the (audit_pub, binding_sig, identity_pub)
     * anchor and per-row entry_hash / previous_hash / entry_sig — the
     * verifier checks the Ed25519 signature on every row and walks
     * the previous_hash linkage to catch tamper / insertion / reorder.
     *
     * Returns the page entries stamped with their RowState plus the
     * aggregated ChainStatus surfaced as the screen-level pill.
     */
    private fun verifyPage(page: AuditListResult): Pair<List<AuditEntry>, AuditChainVerifier.ChainStatus> {
        val identityPub = page.identityPubB64?.let { decodeB64Safely(it) }
        val (perRow, chainStatus) = chainVerifier.verifyChain(
            rows = page.entries,
            auditPubB64 = page.auditPubB64,
            bindingSigB64 = page.bindingSigB64,
            identityPub = identityPub,
            entryHashOf = { e -> Triple(e.entry_hash, e.previous_hash, e.entry_sig) },
        )
        val stamped = page.entries.mapIndexed { i, e ->
            e.copy(
                verification = perRow.getOrNull(i)?.state
                    ?: AuditChainVerifier.RowState.Unsigned,
            )
        }
        return stamped to chainStatus
    }

    // Mirror of MigrationClient.decodeBase64Safely. Vault encodes with
    // Go's base64.StdEncoding; try standard first then URL_SAFE.
    private fun decodeB64Safely(s: String): ByteArray? = try {
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
    } catch (_: Exception) {
        try {
            android.util.Base64.decode(s, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        } catch (_: Exception) {
            null
        }
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
                // #125: verify the new page on its own. Aggregate
                // chainStatus reflects the latest page — the verifier
                // is stateless across pages, so a per-page rollup is
                // the honest report. Per-row state on already-loaded
                // events stays as it was stamped on its own page.
                val (newRows, pageStatus) = verifyPage(page)
                _state.value = ConnectionHistoryState.Loaded(
                    events = prev.events + newRows,
                    totalEstimate = page.totalEstimate,
                    endReached = endReached,
                    chainStatus = pageStatus,
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
                    else -> {
                        val (rows, status) = verifyPage(page)
                        ConnectionHistoryState.Loaded(
                            events = rows,
                            totalEstimate = page.totalEstimate,
                            endReached = endReached,
                            chainStatus = status,
                        )
                    }
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
        // SECURITY (#125): aggregated chain-verification status for
        // the loaded page. Renders as a pill at the top of the screen
        // (Verified / Unsigned / Tampered).
        val chainStatus: AuditChainVerifier.ChainStatus =
            AuditChainVerifier.ChainStatus.Empty,
    ) : ConnectionHistoryState()
    data class Error(val message: String) : ConnectionHistoryState()
}
