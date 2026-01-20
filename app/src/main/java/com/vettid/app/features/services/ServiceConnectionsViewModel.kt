package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.NetworkMonitor
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel for the service connections list screen.
 *
 * Features:
 * - Load service connections via NATS vault handlers
 * - Pull-to-refresh
 * - Search/filter services
 * - Offline support with operation queuing
 * - Status indicators and badges
 * - Service organization (tags, favorites, archive)
 * - Contract update tracking
 * - Activity monitoring
 *
 * Note: Service connections are managed vault-to-vault via NATS.
 * Services are business/organization vaults with rich profiles.
 */
@HiltViewModel
class ServiceConnectionsViewModel @Inject constructor(
    private val natsAutoConnector: NatsAutoConnector,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _state = MutableStateFlow<ServiceConnectionsState>(ServiceConnectionsState.Loading)
    val state: StateFlow<ServiceConnectionsState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(ServiceConnectionFilter())
    val filter: StateFlow<ServiceConnectionFilter> = _filter.asStateFlow()

    private val _effects = MutableSharedFlow<ServiceConnectionsEffect>()
    val effects: SharedFlow<ServiceConnectionsEffect> = _effects.asSharedFlow()

    // Network and sync state
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    // Offline queue state
    private val _pendingActionsCount = MutableStateFlow(0)
    val pendingActionsCount: StateFlow<Int> = _pendingActionsCount.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    // Badge counts
    private val _pendingContractUpdates = MutableStateFlow(0)
    val pendingContractUpdates: StateFlow<Int> = _pendingContractUpdates.asStateFlow()

    private val _pendingRequests = MutableStateFlow(0)
    val pendingRequests: StateFlow<Int> = _pendingRequests.asStateFlow()

    // Available categories and tags
    private val _availableCategories = MutableStateFlow(ServiceCategory.entries.toList())
    val availableCategories: StateFlow<List<ServiceCategory>> = _availableCategories.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    // Last sync time
    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()

    // Full list of service connections
    private var allConnections: List<ServiceConnectionRecord> = emptyList()
    private var offlineActions: List<OfflineServiceAction> = emptyList()

    init {
        loadServiceConnections()

        // Observe network changes for auto-sync
        viewModelScope.launch {
            networkMonitor.connectivityFlow.collect { online ->
                if (online && offlineActions.isNotEmpty()) {
                    processPendingActions()
                }
            }
        }
    }

    /**
     * Load service connections from the vault via NATS.
     */
    fun loadServiceConnections() {
        viewModelScope.launch {
            if (_state.value !is ServiceConnectionsState.Loaded) {
                _state.value = ServiceConnectionsState.Loading
            }

            // Check if NATS is connected
            if (!natsAutoConnector.isConnected()) {
                _state.value = ServiceConnectionsState.Empty
                return@launch
            }

            // TODO: Implement actual NATS call to fetch service connections
            // For now, simulate empty state until backend handlers are ready
            // connectionsClient.listServiceConnections().fold(...)

            // Placeholder: Show empty state until backend is ready
            _state.value = ServiceConnectionsState.Empty
            _lastSyncTime.value = Instant.now()
        }
    }

    /**
     * Refresh service connections (pull-to-refresh).
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadServiceConnections()
            _isRefreshing.value = false
        }
    }

    /**
     * Update search query and filter services.
     */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        applyFilterAndSort()
    }

    /**
     * Navigate to service connection detail.
     */
    fun onServiceClick(connectionId: String) {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.NavigateToServiceDetail(connectionId))
        }
    }

    /**
     * Navigate to service discovery/browse.
     */
    fun onBrowseServices() {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.NavigateToServiceDiscovery)
        }
    }

    /**
     * Navigate to scan service QR code.
     */
    fun onScanServiceQR() {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.NavigateToScanServiceQR)
        }
    }

    /**
     * Update filter settings.
     */
    fun updateFilter(newFilter: ServiceConnectionFilter) {
        _filter.value = newFilter
        applyFilterAndSort()
    }

    /**
     * Update sort order.
     */
    fun updateSortOrder(order: ServiceSortOrder) {
        _filter.value = _filter.value.copy(sortOrder = order)
        applyFilterAndSort()
    }

    /**
     * Toggle favorite status for a service connection.
     */
    fun toggleFavorite(connectionId: String) {
        viewModelScope.launch {
            val connection = allConnections.find { it.connectionId == connectionId } ?: return@launch
            val newFavorite = !connection.isFavorite

            // Update local state immediately
            allConnections = allConnections.map {
                if (it.connectionId == connectionId) it.copy(isFavorite = newFavorite) else it
            }
            applyFilterAndSort()

            // Queue update for sync
            if (!networkMonitor.isOnline.value) {
                queueOfflineAction(connectionId, OfflineActionType.REQUEST_RESPONSE, byteArrayOf())
            }
        }
    }

    /**
     * Toggle archive status for a service connection.
     */
    fun toggleArchive(connectionId: String) {
        viewModelScope.launch {
            val connection = allConnections.find { it.connectionId == connectionId } ?: return@launch
            val newArchived = !connection.isArchived

            allConnections = allConnections.map {
                if (it.connectionId == connectionId) it.copy(isArchived = newArchived) else it
            }
            applyFilterAndSort()
        }
    }

    /**
     * Toggle mute status for a service connection.
     */
    fun toggleMute(connectionId: String) {
        viewModelScope.launch {
            val connection = allConnections.find { it.connectionId == connectionId } ?: return@launch
            val newMuted = !connection.isMuted

            allConnections = allConnections.map {
                if (it.connectionId == connectionId) it.copy(isMuted = newMuted) else it
            }
            applyFilterAndSort()
        }
    }

    /**
     * Add a tag to a service connection.
     */
    fun addTag(connectionId: String, tag: String) {
        viewModelScope.launch {
            allConnections = allConnections.map {
                if (it.connectionId == connectionId && tag !in it.tags) {
                    it.copy(tags = it.tags + tag)
                } else it
            }

            // Update available tags
            val allTags = allConnections.flatMap { it.tags }.toSet()
            _availableTags.value = allTags.toList()

            applyFilterAndSort()
        }
    }

    /**
     * Remove a tag from a service connection.
     */
    fun removeTag(connectionId: String, tag: String) {
        viewModelScope.launch {
            allConnections = allConnections.map {
                if (it.connectionId == connectionId) {
                    it.copy(tags = it.tags.filter { t -> t != tag })
                } else it
            }
            applyFilterAndSort()
        }
    }

    /**
     * Review a pending contract update.
     */
    fun reviewContractUpdate(connectionId: String) {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.NavigateToContractReview(connectionId))
        }
    }

    /**
     * View service activity dashboard.
     */
    fun viewActivityDashboard(connectionId: String) {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.NavigateToActivityDashboard(connectionId))
        }
    }

    /**
     * View data transparency screen for a service.
     */
    fun viewDataTransparency(connectionId: String) {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.NavigateToDataTransparency(connectionId))
        }
    }

    /**
     * Revoke connection to a service.
     */
    fun revokeConnection(connectionId: String) {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.ShowRevokeConfirmation(connectionId))
        }
    }

    /**
     * Confirm revocation of a service connection.
     */
    fun confirmRevoke(connectionId: String) {
        viewModelScope.launch {
            val connection = allConnections.find { it.connectionId == connectionId } ?: return@launch

            // Update local state immediately
            allConnections = allConnections.map {
                if (it.connectionId == connectionId) {
                    it.copy(status = ServiceConnectionStatus.REVOKED)
                } else it
            }
            applyFilterAndSort()

            // Queue for sync if offline
            if (!networkMonitor.isOnline.value) {
                queueOfflineAction(connectionId, OfflineActionType.REVOKE, byteArrayOf())
                _effects.emit(ServiceConnectionsEffect.ShowSnackbar("Connection will be revoked when online"))
            } else {
                // TODO: Call NATS to revoke
                _effects.emit(ServiceConnectionsEffect.ShowSnackbar("Connection to ${connection.serviceProfile.serviceName} revoked"))
            }
        }
    }

    /**
     * Show filter bottom sheet.
     */
    fun showFilterSheet() {
        viewModelScope.launch {
            _effects.emit(ServiceConnectionsEffect.ShowFilterSheet)
        }
    }

    /**
     * Process pending offline actions when online.
     */
    private fun processPendingActions() {
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.PENDING

            var successCount = 0
            val actionsToProcess = offlineActions.filter { it.syncStatus == SyncStatus.PENDING }

            for (action in actionsToProcess) {
                try {
                    // TODO: Process each action type
                    when (action.actionType) {
                        OfflineActionType.REQUEST_RESPONSE -> {
                            // Process request response
                        }
                        OfflineActionType.REVOKE -> {
                            // Process revocation
                        }
                        OfflineActionType.CONTRACT_ACCEPT -> {
                            // Process contract acceptance
                        }
                        OfflineActionType.CONTRACT_REJECT -> {
                            // Process contract rejection
                        }
                    }

                    // Mark as synced
                    offlineActions = offlineActions.map {
                        if (it.actionId == action.actionId) {
                            it.copy(syncStatus = SyncStatus.SYNCED, syncedAt = Instant.now())
                        } else it
                    }
                    successCount++
                } catch (e: Exception) {
                    offlineActions = offlineActions.map {
                        if (it.actionId == action.actionId) {
                            it.copy(syncStatus = SyncStatus.FAILED, error = e.message)
                        } else it
                    }
                }
            }

            _pendingActionsCount.value = offlineActions.count { it.syncStatus == SyncStatus.PENDING }
            _syncStatus.value = if (successCount == actionsToProcess.size) SyncStatus.SYNCED else SyncStatus.FAILED

            if (successCount > 0) {
                _lastSyncTime.value = Instant.now()
                refresh()
            }
        }
    }

    /**
     * Queue an offline action.
     */
    private fun queueOfflineAction(connectionId: String, type: OfflineActionType, payload: ByteArray) {
        val action = OfflineServiceAction(
            actionId = java.util.UUID.randomUUID().toString(),
            connectionId = connectionId,
            actionType = type,
            payload = payload,
            createdAt = Instant.now(),
            syncStatus = SyncStatus.PENDING
        )
        offlineActions = offlineActions + action
        _pendingActionsCount.value = offlineActions.count { it.syncStatus == SyncStatus.PENDING }
    }

    /**
     * Retry syncing pending actions.
     */
    fun retrySyncActions() {
        if (networkMonitor.isOnline.value) {
            processPendingActions()
        }
    }

    /**
     * Apply current filter and sort to connections.
     */
    private fun applyFilterAndSort() {
        val currentFilter = _filter.value.copy(searchQuery = _searchQuery.value)

        var filtered = allConnections.filter { currentFilter.matches(it) }

        // Sort
        filtered = when (currentFilter.sortOrder) {
            ServiceSortOrder.RECENT_ACTIVITY -> filtered.sortedByDescending { it.lastActiveAt ?: it.createdAt }
            ServiceSortOrder.ALPHABETICAL -> filtered.sortedBy { it.serviceProfile.serviceName.lowercase() }
            ServiceSortOrder.CONNECTION_DATE -> filtered.sortedByDescending { it.createdAt }
            ServiceSortOrder.CATEGORY -> filtered.sortedWith(
                compareBy({ it.serviceProfile.serviceCategory.ordinal }, { it.serviceProfile.serviceName.lowercase() })
            )
        }

        // Update badge counts
        _pendingContractUpdates.value = allConnections.count {
            it.status == ServiceConnectionStatus.PENDING_CONTRACT_UPDATE
        }
        _pendingRequests.value = 0 // TODO: Count pending requests when data is available

        _state.value = if (filtered.isEmpty() && allConnections.isNotEmpty()) {
            ServiceConnectionsState.Loaded(
                connections = emptyList(),
                isSearchResult = currentFilter.searchQuery.isNotBlank()
            )
        } else if (filtered.isEmpty()) {
            ServiceConnectionsState.Empty
        } else {
            ServiceConnectionsState.Loaded(
                connections = filtered,
                isSearchResult = currentFilter.searchQuery.isNotBlank()
            )
        }
    }
}

// MARK: - State Types

/**
 * Service connections list state.
 */
sealed class ServiceConnectionsState {
    object Loading : ServiceConnectionsState()
    object Empty : ServiceConnectionsState()

    data class Loaded(
        val connections: List<ServiceConnectionRecord>,
        val isSearchResult: Boolean = false
    ) : ServiceConnectionsState()

    data class Error(val message: String) : ServiceConnectionsState()
}

// MARK: - Filter Types

/**
 * Service connection filter.
 */
data class ServiceConnectionFilter(
    val searchQuery: String = "",
    val statusFilter: Set<ServiceConnectionStatus> = ServiceConnectionStatus.entries.toSet(),
    val categoryFilter: Set<ServiceCategory> = emptySet(),
    val showFavoritesOnly: Boolean = false,
    val showArchivedOnly: Boolean = false,
    val selectedTags: Set<String> = emptySet(),
    val sortOrder: ServiceSortOrder = ServiceSortOrder.RECENT_ACTIVITY
) {
    val isDefault: Boolean
        get() = searchQuery.isEmpty() &&
                statusFilter == ServiceConnectionStatus.entries.toSet() &&
                categoryFilter.isEmpty() &&
                !showFavoritesOnly &&
                !showArchivedOnly &&
                selectedTags.isEmpty() &&
                sortOrder == ServiceSortOrder.RECENT_ACTIVITY

    fun matches(connection: ServiceConnectionRecord): Boolean {
        // Search query match
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            val nameMatch = connection.serviceProfile.serviceName.lowercase().contains(query)
            val descMatch = connection.serviceProfile.serviceDescription.lowercase().contains(query)
            val orgMatch = connection.serviceProfile.organization.name.lowercase().contains(query)
            if (!nameMatch && !descMatch && !orgMatch) return false
        }

        // Status filter
        if (connection.status !in statusFilter) return false

        // Category filter (if any selected)
        if (categoryFilter.isNotEmpty() && connection.serviceProfile.serviceCategory !in categoryFilter) return false

        // Favorites
        if (showFavoritesOnly && !connection.isFavorite) return false

        // Archived
        if (!showArchivedOnly && connection.isArchived) return false
        if (showArchivedOnly && !connection.isArchived) return false

        // Tags
        if (selectedTags.isNotEmpty() && !connection.tags.any { it in selectedTags }) return false

        return true
    }
}

/**
 * Service connection sort order.
 */
enum class ServiceSortOrder(val displayName: String, val icon: String) {
    RECENT_ACTIVITY("Recent Activity", "schedule"),
    ALPHABETICAL("Alphabetical", "sort_by_alpha"),
    CONNECTION_DATE("Connection Date", "calendar_today"),
    CATEGORY("Category", "category")
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ServiceConnectionsEffect {
    data class NavigateToServiceDetail(val connectionId: String) : ServiceConnectionsEffect()
    data class NavigateToContractReview(val connectionId: String) : ServiceConnectionsEffect()
    data class NavigateToActivityDashboard(val connectionId: String) : ServiceConnectionsEffect()
    data class NavigateToDataTransparency(val connectionId: String) : ServiceConnectionsEffect()
    object NavigateToServiceDiscovery : ServiceConnectionsEffect()
    object NavigateToScanServiceQR : ServiceConnectionsEffect()
    object ShowFilterSheet : ServiceConnectionsEffect()
    data class ShowRevokeConfirmation(val connectionId: String) : ServiceConnectionsEffect()
    data class ShowSnackbar(val message: String) : ServiceConnectionsEffect()
}
