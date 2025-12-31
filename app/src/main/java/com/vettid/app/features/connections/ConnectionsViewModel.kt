package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.network.ConnectionWithLastMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the connections list screen.
 *
 * Features:
 * - Load connections via NATS vault handlers
 * - Pull-to-refresh
 * - Search/filter connections
 *
 * Note: Connections are managed vault-to-vault via NATS, not through HTTP APIs.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val natsAutoConnector: NatsAutoConnector
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionsState>(ConnectionsState.Loading)
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _effects = MutableSharedFlow<ConnectionsEffect>()
    val effects: SharedFlow<ConnectionsEffect> = _effects.asSharedFlow()

    // Full list of connections (for filtering)
    private var allConnections: List<ConnectionWithLastMessage> = emptyList()

    init {
        loadConnections()
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

                    _state.value = if (connectionsWithMessages.isEmpty()) {
                        ConnectionsState.Empty
                    } else {
                        ConnectionsState.Loaded(
                            connections = filterConnections(connectionsWithMessages, _searchQuery.value),
                            totalUnread = 0
                        )
                    }
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
        val isSearchResult: Boolean = false
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
}
