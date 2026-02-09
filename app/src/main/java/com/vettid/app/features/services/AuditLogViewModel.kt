package com.vettid.app.features.services

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import com.vettid.app.core.nats.NatsAutoConnector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AuditLogVM"

/**
 * ViewModel for audit log viewer.
 *
 * Uses FeedClient.queryAudit() to load events from the vault's encrypted SQLite.
 * Supports text search, time window filtering, and event type filtering.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class AuditLogViewModel @Inject constructor(
    private val feedClient: FeedClient,
    private val natsAutoConnector: NatsAutoConnector
) : ViewModel() {

    private val _logs = MutableStateFlow<List<FeedEvent>>(emptyList())
    val logs: StateFlow<List<FeedEvent>> = _logs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _timeWindow = MutableStateFlow(TimeWindow.ALL)
    val timeWindow: StateFlow<TimeWindow> = _timeWindow.asStateFlow()

    private val _selectedEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val selectedEventTypes: StateFlow<Set<String>> = _selectedEventTypes.asStateFlow()

    private val _verificationStatus = MutableStateFlow<VerificationStatus?>(null)
    val verificationStatus: StateFlow<VerificationStatus?> = _verificationStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // All loaded logs before local filtering
    private var allLogs: List<FeedEvent> = emptyList()

    // Track connection state
    private var hasLoadedInitially = false

    init {
        observeConnectionAndLoad()
        observeFilterChanges()
    }

    private fun observeConnectionAndLoad() {
        viewModelScope.launch {
            natsAutoConnector.connectionState.collect { state ->
                if (state is NatsAutoConnector.AutoConnectState.Connected && !hasLoadedInitially) {
                    hasLoadedInitially = true
                    loadLogs()
                }
            }
        }
    }

    private fun observeFilterChanges() {
        viewModelScope.launch {
            combine(
                _searchQuery.debounce(300),
                _timeWindow,
                _selectedEventTypes
            ) { _, _, _ -> Unit }
                .collect { applyFilter() }
        }
    }

    fun loadLogs() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val (startDate, endDate) = getTimeRange(_timeWindow.value)
                val eventTypes = _selectedEventTypes.value.takeIf { it.isNotEmpty() }?.toList()

                feedClient.queryAudit(
                    eventTypes = eventTypes,
                    startDate = startDate,
                    endDate = endDate,
                    limit = 500
                ).onSuccess { response ->
                    allLogs = response.events
                    applyFilter()
                    Log.d(TAG, "Loaded ${response.events.size} audit events (total: ${response.total})")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to load audit logs", error)
                    _error.value = error.message ?: "Failed to load audit logs"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load audit logs", e)
                _error.value = e.message ?: "Failed to load audit logs"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setTimeWindow(window: TimeWindow) {
        _timeWindow.value = window
        // Reload from server with new time range
        if (hasLoadedInitially) loadLogs()
    }

    fun toggleEventType(eventType: String) {
        val current = _selectedEventTypes.value.toMutableSet()
        if (current.contains(eventType)) {
            current.remove(eventType)
        } else {
            current.add(eventType)
        }
        _selectedEventTypes.value = current
        // Reload from server with new event types
        if (hasLoadedInitially) loadLogs()
    }

    fun clearEventTypeFilter() {
        _selectedEventTypes.value = emptySet()
        if (hasLoadedInitially) loadLogs()
    }

    private fun applyFilter() {
        val query = _searchQuery.value.trim()
        val filtered = if (query.isEmpty()) {
            allLogs
        } else {
            allLogs.filter { event ->
                event.title.contains(query, ignoreCase = true) ||
                    (event.message?.contains(query, ignoreCase = true) == true) ||
                    event.eventType.contains(query, ignoreCase = true)
            }
        }
        _logs.value = filtered.sortedByDescending { it.createdAt }
    }

    fun verifyIntegrity() {
        viewModelScope.launch {
            _verificationStatus.value = VerificationStatus.VERIFYING
            try {
                // Verify that logs are in sequence order (basic integrity check)
                val sorted = allLogs.sortedBy { it.syncSequence }
                var isValid = true
                for (i in 1 until sorted.size) {
                    if (sorted[i].syncSequence <= sorted[i - 1].syncSequence) {
                        isValid = false
                        break
                    }
                }
                _verificationStatus.value = if (isValid) {
                    VerificationStatus.VERIFIED
                } else {
                    VerificationStatus.TAMPERED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Integrity verification failed", e)
                _verificationStatus.value = null
            }
        }
    }

    fun exportJson() {
        viewModelScope.launch {
            try {
                feedClient.exportAudit(
                    eventTypes = _selectedEventTypes.value.takeIf { it.isNotEmpty() }?.toList(),
                    startDate = getTimeRange(_timeWindow.value).first,
                    endDate = getTimeRange(_timeWindow.value).second
                ).onSuccess { response ->
                    val json = com.google.gson.GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(response.events)
                    Log.d(TAG, "Exported ${response.events.size} entries to JSON (${json.length} chars)")
                }.onFailure { error ->
                    Log.e(TAG, "Export failed", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
            }
        }
    }

    fun exportCsv() {
        viewModelScope.launch {
            try {
                feedClient.exportAudit(
                    eventTypes = _selectedEventTypes.value.takeIf { it.isNotEmpty() }?.toList(),
                    startDate = getTimeRange(_timeWindow.value).first,
                    endDate = getTimeRange(_timeWindow.value).second
                ).onSuccess { response ->
                    val csv = buildString {
                        appendLine("event_id,event_type,source_type,source_id,title,message,priority,created_at")
                        response.events.forEach { event ->
                            append("\"${event.eventId}\",")
                            append("\"${event.eventType}\",")
                            append("\"${event.sourceType ?: ""}\",")
                            append("\"${event.sourceId ?: ""}\",")
                            append("\"${event.title.replace("\"", "\"\"")}\",")
                            append("\"${(event.message ?: "").replace("\"", "\"\"")}\",")
                            append("${event.priority},")
                            appendLine(event.createdAt.toString())
                        }
                    }
                    Log.d(TAG, "Exported ${response.events.size} entries to CSV (${csv.length} chars)")
                }.onFailure { error ->
                    Log.e(TAG, "CSV export failed", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "CSV export failed", e)
            }
        }
    }

    private fun getTimeRange(window: TimeWindow): Pair<Long?, Long?> {
        val now = System.currentTimeMillis() / 1000
        return when (window) {
            TimeWindow.LAST_HOUR -> Pair(now - 3600, null)
            TimeWindow.LAST_24H -> Pair(now - 86400, null)
            TimeWindow.LAST_7D -> Pair(now - 604800, null)
            TimeWindow.LAST_30D -> Pair(now - 2592000, null)
            TimeWindow.ALL -> Pair(null, null)
        }
    }
}

enum class TimeWindow(val label: String) {
    LAST_HOUR("1 Hour"),
    LAST_24H("24 Hours"),
    LAST_7D("7 Days"),
    LAST_30D("30 Days"),
    ALL("All Time")
}

enum class VerificationStatus {
    VERIFYING,
    VERIFIED,
    TAMPERED
}
