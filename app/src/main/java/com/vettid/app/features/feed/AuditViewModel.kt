package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AuditViewModel"

/**
 * ViewModel for the full audit log viewer.
 *
 * Supports:
 * - Filtering by event types
 * - Date range filtering
 * - Pagination
 * - Export to JSON
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val feedClient: FeedClient,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<AuditState>(AuditState.Loading)
    val state: StateFlow<AuditState> = _state.asStateFlow()

    private val _filters = MutableStateFlow(AuditFilters())
    val filters: StateFlow<AuditFilters> = _filters.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    // Pagination state
    private var currentOffset = 0
    private var hasMore = false

    // Raw events as loaded from the vault (date + event-type filtered
    // server-side). The connection filter is applied client-side on top
    // of this — see emitFilteredState(). A server-side connection_id
    // param on queryAudit would make this exact under pagination; that
    // lands with the grant.list-my-requests enclave pass.
    private var allLoadedEvents: List<FeedEvent> = emptyList()

    /**
     * Connections available for the audit "connection" filter — each
     * peer the user can scope the log to. Sourced from the feed cache.
     */
    val connectionOptions: List<AuditConnectionOption>
        get() = feedRepository.getCachedConnections()
            .filter { it.connectionType != "system" }
            .map { conn ->
                val first = conn.peerProfile?.firstName.orEmpty().trim()
                val last = conn.peerProfile?.lastName.orEmpty().trim()
                val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
                AuditConnectionOption(
                    connectionId = conn.connectionId,
                    displayName = full.ifEmpty { conn.label.ifEmpty { conn.connectionId.take(8) } },
                )
            }
            .sortedBy { it.displayName.lowercase() }

    init {
        loadAuditLog()
    }

    fun loadAuditLog() {
        viewModelScope.launch {
            _state.value = AuditState.Loading
            currentOffset = 0

            val currentFilters = _filters.value
            feedClient.queryAudit(
                eventTypes = currentFilters.selectedEventTypes.takeIf { it.isNotEmpty() }?.toList(),
                startDate = currentFilters.startDate,
                endDate = currentFilters.endDate,
                limit = PAGE_SIZE
            ).onSuccess { response ->
                hasMore = response.events.size == PAGE_SIZE
                allLoadedEvents = response.events
                emitFilteredState()
            }.onFailure { error ->
                Log.e(TAG, "Failed to load audit log", error)
                _state.value = AuditState.Error(error.message ?: "Failed to load audit log")
            }
        }
    }

    /**
     * Apply the client-side connection filter to the loaded events and
     * publish the resulting state. Date + event-type filtering already
     * happened server-side in the query.
     */
    private fun emitFilteredState() {
        val connId = _filters.value.connectionId
        val visible = if (connId.isNullOrEmpty()) {
            allLoadedEvents
        } else {
            allLoadedEvents.filter { it.sourceId == connId }
        }
        _state.value = if (visible.isEmpty()) {
            AuditState.Empty
        } else {
            AuditState.Success(events = visible, hasMore = hasMore, totalCount = visible.size)
        }
    }

    fun loadMore() {
        if (_state.value !is AuditState.Success) return
        if (!hasMore) return

        viewModelScope.launch {
            currentOffset += PAGE_SIZE

            val currentFilters = _filters.value
            feedClient.queryAudit(
                eventTypes = currentFilters.selectedEventTypes.takeIf { it.isNotEmpty() }?.toList(),
                startDate = currentFilters.startDate,
                endDate = currentFilters.endDate,
                limit = PAGE_SIZE
            ).onSuccess { response ->
                hasMore = response.events.size == PAGE_SIZE
                allLoadedEvents = allLoadedEvents + response.events
                emitFilteredState()
            }.onFailure { error ->
                Log.e(TAG, "Failed to load more audit entries", error)
            }
        }
    }

    fun updateFilters(newFilters: AuditFilters) {
        _filters.value = newFilters
        loadAuditLog()
    }

    fun toggleEventTypeFilter(eventType: String) {
        val currentFilters = _filters.value
        val newTypes = if (eventType in currentFilters.selectedEventTypes) {
            currentFilters.selectedEventTypes - eventType
        } else {
            currentFilters.selectedEventTypes + eventType
        }
        _filters.value = currentFilters.copy(selectedEventTypes = newTypes)
        loadAuditLog()
    }

    fun setDateRange(startDate: Long?, endDate: Long?) {
        _filters.value = _filters.value.copy(
            startDate = startDate,
            endDate = endDate
        )
        loadAuditLog()
    }

    /**
     * Scope the log to a single connection (or null for all). Applied
     * client-side over already-loaded events — no re-query needed.
     */
    fun setConnectionFilter(connectionId: String?) {
        _filters.value = _filters.value.copy(connectionId = connectionId)
        emitFilteredState()
    }

    fun clearFilters() {
        _filters.value = AuditFilters()
        loadAuditLog()
    }

    fun exportAudit(onComplete: (Result<String>) -> Unit) {
        viewModelScope.launch {
            _isExporting.value = true

            val currentFilters = _filters.value
            feedClient.exportAudit(
                eventTypes = currentFilters.selectedEventTypes.takeIf { it.isNotEmpty() }?.toList(),
                startDate = currentFilters.startDate,
                endDate = currentFilters.endDate
            ).onSuccess { response ->
                // Format as JSON for export
                val json = buildString {
                    appendLine("{")
                    appendLine("  \"exported_at\": ${response.exportedAt},")
                    appendLine("  \"event_count\": ${response.events.size},")
                    appendLine("  \"events\": [")
                    response.events.forEachIndexed { index, event ->
                        append("    ")
                        append(eventToJson(event))
                        if (index < response.events.size - 1) append(",")
                        appendLine()
                    }
                    appendLine("  ]")
                    append("}")
                }
                _isExporting.value = false
                onComplete(Result.success(json))
            }.onFailure { error ->
                Log.e(TAG, "Failed to export audit log", error)
                _isExporting.value = false
                onComplete(Result.failure(error))
            }
        }
    }

    private fun eventToJson(event: FeedEvent): String {
        return """{"event_id":"${event.eventId}","event_type":"${event.eventType}","title":"${event.title.replace("\"", "\\\"")}","created_at":${event.createdAt}}"""
    }

    fun refresh() {
        loadAuditLog()
    }

    companion object {
        private const val PAGE_SIZE = 50

        /**
         * All supported event types for filtering.
         */
        val ALL_EVENT_TYPES = listOf(
            EventTypeFilter("call.incoming", "Incoming Calls"),
            EventTypeFilter("call.missed", "Missed Calls"),
            EventTypeFilter("call.completed", "Completed Calls"),
            EventTypeFilter("message.received", "Messages"),
            EventTypeFilter("connection.request", "Connection Requests"),
            EventTypeFilter("connection.accepted", "Connections Accepted"),
            EventTypeFilter("connection.revoked", "Connections Revoked"),
            EventTypeFilter("connection.verified", "Identity Verifications"),
            EventTypeFilter("connection.verify.denied", "Verifications Denied"),
            EventTypeFilter("identity.key.used", "Identity-Key Signings"),
            EventTypeFilter("critical_secret.used", "Critical Secret Use"),
            EventTypeFilter("security.alert", "Security Alerts"),
            EventTypeFilter("security.migration", "Security Migrations"),
            EventTypeFilter("transfer.request", "Transfer Requests"),
            EventTypeFilter("backup.complete", "Backups"),
            EventTypeFilter("handler.complete", "Handler Completions")
        )
    }
}

/**
 * UI state for the audit log viewer.
 */
sealed class AuditState {
    object Loading : AuditState()
    object Empty : AuditState()
    data class Success(
        val events: List<FeedEvent>,
        val hasMore: Boolean,
        val totalCount: Int
    ) : AuditState()
    data class Error(val message: String) : AuditState()
}

/**
 * Audit log filters. Connection + date range are the primary scoping
 * controls; event types are a secondary refinement.
 */
data class AuditFilters(
    val connectionId: String? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val selectedEventTypes: Set<String> = emptySet(),
) {
    val hasActiveFilters: Boolean
        get() = connectionId != null ||
            startDate != null ||
            endDate != null ||
            selectedEventTypes.isNotEmpty()
}

/**
 * Event type for filtering.
 */
data class EventTypeFilter(
    val type: String,
    val displayName: String
)

/** A connection the audit log can be scoped to. */
data class AuditConnectionOption(
    val connectionId: String,
    val displayName: String,
)
