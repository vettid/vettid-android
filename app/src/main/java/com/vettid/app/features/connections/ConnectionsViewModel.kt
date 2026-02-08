package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.network.ConnectionWithLastMessage
import com.vettid.app.core.network.NetworkMonitor
import com.vettid.app.features.connections.models.*
import com.vettid.app.features.connections.offline.OfflineQueueManager
import com.vettid.app.features.connections.offline.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel for the connections list screen.
 *
 * Features:
 * - Load connections via NATS vault handlers
 * - Pull-to-refresh
 * - Search/filter connections
 * - Offline support with operation queuing
 * - Status indicators and badges
 * - Connection organization (tags, favorites, archive)
 *
 * Note: Connections are managed vault-to-vault via NATS, not through HTTP APIs.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val networkMonitor: NetworkMonitor,
    private val offlineQueueManager: OfflineQueueManager
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionsState>(ConnectionsState.Loading)
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(ConnectionFilter())
    val filter: StateFlow<ConnectionFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.RECENT_ACTIVITY)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _effects = MutableSharedFlow<ConnectionsEffect>()
    val effects: SharedFlow<ConnectionsEffect> = _effects.asSharedFlow()

    // Network and sync state
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val syncStatus: StateFlow<SyncStatus> = offlineQueueManager.syncStatus
    val pendingOperationsCount: StateFlow<Int> = offlineQueueManager.pendingOperations
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // Badge counts for navigation
    private val _pendingReviewCount = MutableStateFlow(0)
    val pendingReviewCount: StateFlow<Int> = _pendingReviewCount.asStateFlow()

    private val _totalUnreadCount = MutableStateFlow(0)
    val totalUnreadCount: StateFlow<Int> = _totalUnreadCount.asStateFlow()

    // Available tags
    private val _availableTags = MutableStateFlow(ConnectionTag.DEFAULTS)
    val availableTags: StateFlow<List<ConnectionTag>> = _availableTags.asStateFlow()

    // Last sync time
    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()

    // Full list of connections (for filtering)
    private var allConnections: List<ConnectionWithLastMessage> = emptyList()
    private var allEnhancedConnections: List<ConnectionListItem> = emptyList()

    init {
        loadConnections()

        // Observe network changes for auto-sync
        viewModelScope.launch {
            networkMonitor.connectivityFlow.collect { online ->
                if (online && offlineQueueManager.hasPendingOperations()) {
                    processPendingOperations()
                }
            }
        }
    }

    /**
     * Load connections from the vault via NATS.
     */
    fun loadConnections() {
        viewModelScope.launch {
            if (_state.value !is ConnectionsState.Loaded) {
                _state.value = ConnectionsState.Loading
            }

            // Check if NATS is connected
            if (!natsAutoConnector.isConnected()) {
                // Not connected to vault - show empty state
                _state.value = ConnectionsState.Empty
                return@launch
            }

            connectionsClient.list(status = "active").fold(
                onSuccess = { listResult ->
                    // Map ConnectionRecord to ConnectionWithLastMessage for UI
                    val connectionsWithMessages = listResult.items.map { record ->
                        val status = when (record.status.lowercase()) {
                            "active" -> ConnectionStatus.ACTIVE
                            "pending" -> ConnectionStatus.PENDING
                            "revoked" -> ConnectionStatus.REVOKED
                            else -> ConnectionStatus.ACTIVE
                        }
                        // Parse ISO8601 timestamp to epoch millis
                        val createdAtMillis = try {
                            java.time.Instant.parse(record.createdAt).toEpochMilli()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                        ConnectionWithLastMessage(
                            connection = Connection(
                                connectionId = record.connectionId,
                                peerGuid = record.peerGuid,
                                peerDisplayName = record.label,
                                peerAvatarUrl = null,
                                status = status,
                                createdAt = createdAtMillis,
                                lastMessageAt = null,
                                unreadCount = 0
                            ),
                            lastMessage = null
                        )
                    }.sortedByDescending { it.connection.createdAt }

                    allConnections = connectionsWithMessages

                    // Check for stale key rotation (> 30 days)
                    val thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 60 * 60)
                    for (record in listResult.items) {
                        if (record.status.equals("active", ignoreCase = true)) {
                            val lastRotated = record.lastRotatedAt?.let {
                                try { Instant.parse(it) } catch (e: Exception) { null }
                            }
                            if (lastRotated == null || lastRotated.isBefore(thirtyDaysAgo)) {
                                android.util.Log.w("ConnectionsVM",
                                    "Connection ${record.connectionId} keys not rotated in 30+ days")
                            }
                        }
                    }

                    // Also populate enhanced connections
                    allEnhancedConnections = connectionsWithMessages.map { it.toEnhanced() }

                    // Update last sync time
                    _lastSyncTime.value = Instant.now()

                    // Apply filter and sort
                    applyFilterAndSort()
                },
                onFailure = { error ->
                    // If vault not connected or handler failed, show empty state
                    val isConnectionIssue = error.message?.contains("not connected", ignoreCase = true) == true ||
                            error.message?.contains("timeout", ignoreCase = true) == true
                    if (isConnectionIssue) {
                        _state.value = ConnectionsState.Empty
                    } else {
                        _state.value = ConnectionsState.Error(
                            message = error.message ?: "Failed to load connections"
                        )
                    }
                }
            )
        }
    }

    /**
     * Refresh connections (pull-to-refresh).
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadConnections()
            _isRefreshing.value = false
        }
    }

    /**
     * Update search query and filter connections.
     */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        val currentState = _state.value
        if (currentState is ConnectionsState.Loaded || allConnections.isNotEmpty()) {
            val filtered = filterConnections(allConnections, query)
            _state.value = if (filtered.isEmpty() && allConnections.isNotEmpty()) {
                ConnectionsState.Loaded(
                    connections = emptyList(),
                    totalUnread = (currentState as? ConnectionsState.Loaded)?.totalUnread ?: 0,
                    isSearchResult = true
                )
            } else {
                ConnectionsState.Loaded(
                    connections = filtered,
                    totalUnread = (currentState as? ConnectionsState.Loaded)?.totalUnread ?: 0,
                    isSearchResult = query.isNotBlank()
                )
            }
        }
    }

    /**
     * Navigate to connection detail.
     */
    fun onConnectionClick(connectionId: String) {
        viewModelScope.launch {
            _effects.emit(ConnectionsEffect.NavigateToConnection(connectionId))
        }
    }

    /**
     * Navigate to create invitation.
     */
    fun onCreateInvitation() {
        viewModelScope.launch {
            _effects.emit(ConnectionsEffect.NavigateToCreateInvitation)
        }
    }

    /**
     * Navigate to scan invitation.
     */
    fun onScanInvitation() {
        viewModelScope.launch {
            _effects.emit(ConnectionsEffect.NavigateToScanInvitation)
        }
    }

    /**
     * Update filter settings.
     */
    fun updateFilter(newFilter: ConnectionFilter) {
        _filter.value = newFilter
        applyFilterAndSort()
    }

    /**
     * Update sort order.
     */
    fun updateSortOrder(order: SortOrder) {
        _sortOrder.value = order
        _filter.value = _filter.value.copy(sortOrder = order)
        applyFilterAndSort()
    }

    /**
     * Toggle favorite status for a connection.
     */
    fun toggleFavorite(connectionId: String) {
        viewModelScope.launch {
            val connection = allEnhancedConnections.find { it.connectionId == connectionId } ?: return@launch
            val newFavorite = !connection.isFavorite

            // Update local state immediately
            allEnhancedConnections = allEnhancedConnections.map {
                if (it.connectionId == connectionId) it.copy(isFavorite = newFavorite) else it
            }
            applyFilterAndSort()

            // Queue for sync if offline, otherwise update immediately
            if (!networkMonitor.isOnline.value) {
                // For now, favorite is local-only; would need backend support
            }
        }
    }

    /**
     * Toggle archive status for a connection.
     */
    fun toggleArchive(connectionId: String) {
        viewModelScope.launch {
            val connection = allEnhancedConnections.find { it.connectionId == connectionId } ?: return@launch
            val newArchived = !connection.isArchived

            allEnhancedConnections = allEnhancedConnections.map {
                if (it.connectionId == connectionId) it.copy(isArchived = newArchived) else it
            }
            applyFilterAndSort()
        }
    }

    /**
     * Add a tag to a connection.
     */
    fun addTag(connectionId: String, tag: ConnectionTag) {
        viewModelScope.launch {
            allEnhancedConnections = allEnhancedConnections.map {
                if (it.connectionId == connectionId && tag !in it.tags) {
                    it.copy(tags = it.tags + tag)
                } else it
            }
            applyFilterAndSort()
        }
    }

    /**
     * Remove a tag from a connection.
     */
    fun removeTag(connectionId: String, tagId: String) {
        viewModelScope.launch {
            allEnhancedConnections = allEnhancedConnections.map {
                if (it.connectionId == connectionId) {
                    it.copy(tags = it.tags.filter { tag -> tag.id != tagId })
                } else it
            }
            applyFilterAndSort()
        }
    }

    /**
     * Create a new custom tag.
     */
    fun createTag(name: String, color: Long) {
        val newTag = ConnectionTag(
            id = name.lowercase().replace(" ", "_"),
            name = name,
            color = color
        )
        _availableTags.value = _availableTags.value + newTag
    }

    /**
     * Process pending operations when online.
     */
    private fun processPendingOperations() {
        viewModelScope.launch {
            offlineQueueManager.setSyncStatus(SyncStatus.SYNCING)

            val operations = offlineQueueManager.getProcessableOperations()
            var successCount = 0

            for (op in operations) {
                // Process based on operation type
                // For now, just mark as completed (actual implementation would call APIs)
                try {
                    // TODO: Implement actual operation processing
                    offlineQueueManager.markCompleted(op.id)
                    successCount++
                } catch (e: Exception) {
                    offlineQueueManager.markFailed(op.id, e.message ?: "Unknown error")
                }
            }

            offlineQueueManager.setSyncStatus(
                if (successCount == operations.size) SyncStatus.SUCCESS else SyncStatus.ERROR
            )

            if (successCount > 0) {
                _lastSyncTime.value = Instant.now()
                refresh()
            }
        }
    }

    /**
     * Retry syncing pending operations.
     */
    fun retrySyncOperations() {
        if (networkMonitor.isOnline.value) {
            processPendingOperations()
        }
    }

    /**
     * Show filter bottom sheet.
     */
    fun showFilterSheet() {
        viewModelScope.launch {
            _effects.emit(ConnectionsEffect.ShowFilterSheet)
        }
    }

    /**
     * Filter connections by search query.
     */
    private fun filterConnections(
        connections: List<ConnectionWithLastMessage>,
        query: String
    ): List<ConnectionWithLastMessage> {
        if (query.isBlank()) return connections

        val lowerQuery = query.lowercase()
        return connections.filter { connectionWithMessage ->
            connectionWithMessage.connection.peerDisplayName.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Apply current filter and sort to connections.
     */
    private fun applyFilterAndSort() {
        val currentFilter = _filter.value.copy(searchQuery = _searchQuery.value)

        var filtered = allEnhancedConnections.filter { currentFilter.matches(it) }

        // Sort
        filtered = when (currentFilter.sortOrder) {
            SortOrder.RECENT_ACTIVITY -> filtered.sortedByDescending { it.lastActiveAt ?: it.createdAt }
            SortOrder.ALPHABETICAL -> filtered.sortedBy { it.peerName.lowercase() }
            SortOrder.CONNECTION_DATE -> filtered.sortedByDescending { it.createdAt }
        }

        // Update pending review count
        _pendingReviewCount.value = allEnhancedConnections.count {
            it.status == com.vettid.app.features.connections.models.ConnectionStatus.PENDING_OUR_REVIEW ||
                    it.status == com.vettid.app.features.connections.models.ConnectionStatus.PENDING_OUR_ACCEPT
        }

        // Update total unread count
        _totalUnreadCount.value = allEnhancedConnections.sumOf { it.unreadCount }

        // Map back to ConnectionWithLastMessage for existing UI compatibility
        val mappedConnections = filtered.map { enhanced ->
            val legacyStatus = when (enhanced.status) {
                com.vettid.app.features.connections.models.ConnectionStatus.ACTIVE -> ConnectionStatus.ACTIVE
                com.vettid.app.features.connections.models.ConnectionStatus.REVOKED -> ConnectionStatus.REVOKED
                else -> ConnectionStatus.PENDING
            }
            ConnectionWithLastMessage(
                connection = Connection(
                    connectionId = enhanced.connectionId,
                    peerGuid = enhanced.peerGuid,
                    peerDisplayName = enhanced.peerName,
                    peerAvatarUrl = enhanced.peerAvatarUrl,
                    status = legacyStatus,
                    createdAt = enhanced.createdAt.toEpochMilli(),
                    lastMessageAt = enhanced.lastActiveAt?.toEpochMilli(),
                    unreadCount = enhanced.unreadCount
                ),
                lastMessage = null
            )
        }

        _state.value = if (mappedConnections.isEmpty() && allEnhancedConnections.isNotEmpty()) {
            ConnectionsState.Loaded(
                connections = emptyList(),
                totalUnread = _totalUnreadCount.value,
                isSearchResult = currentFilter.searchQuery.isNotBlank(),
                pendingReviewCount = _pendingReviewCount.value
            )
        } else if (mappedConnections.isEmpty()) {
            ConnectionsState.Empty
        } else {
            ConnectionsState.Loaded(
                connections = mappedConnections,
                totalUnread = _totalUnreadCount.value,
                isSearchResult = currentFilter.searchQuery.isNotBlank(),
                pendingReviewCount = _pendingReviewCount.value
            )
        }
    }

    /**
     * Convert legacy connection to enhanced model.
     */
    private fun ConnectionWithLastMessage.toEnhanced(): ConnectionListItem {
        val conn = this.connection
        return ConnectionListItem(
            connectionId = conn.connectionId,
            peerGuid = conn.peerGuid,
            peerName = conn.peerDisplayName,
            peerAvatarUrl = conn.peerAvatarUrl,
            status = when (conn.status) {
                ConnectionStatus.ACTIVE -> com.vettid.app.features.connections.models.ConnectionStatus.ACTIVE
                ConnectionStatus.PENDING -> com.vettid.app.features.connections.models.ConnectionStatus.PENDING_OUR_REVIEW
                ConnectionStatus.REVOKED -> com.vettid.app.features.connections.models.ConnectionStatus.REVOKED
            },
            direction = ConnectionDirection.OUTBOUND,
            lastActiveAt = conn.lastMessageAt?.let { Instant.ofEpochMilli(it) },
            unreadCount = conn.unreadCount,
            createdAt = Instant.ofEpochMilli(conn.createdAt)
        )
    }
}

// MARK: - State Types

/**
 * Connections list state.
 */
sealed class ConnectionsState {
    object Loading : ConnectionsState()
    object Empty : ConnectionsState()

    data class Loaded(
        val connections: List<ConnectionWithLastMessage>,
        val totalUnread: Int = 0,
        val isSearchResult: Boolean = false,
        val pendingReviewCount: Int = 0
    ) : ConnectionsState()

    data class Error(val message: String) : ConnectionsState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ConnectionsEffect {
    data class NavigateToConnection(val connectionId: String) : ConnectionsEffect()
    object NavigateToCreateInvitation : ConnectionsEffect()
    object NavigateToScanInvitation : ConnectionsEffect()
    object ShowFilterSheet : ConnectionsEffect()
    data class ShowSnackbar(val message: String) : ConnectionsEffect()
}
