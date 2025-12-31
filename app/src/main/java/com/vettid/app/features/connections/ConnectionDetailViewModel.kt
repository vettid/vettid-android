package com.vettid.app.features.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.vettid.app.core.network.Profile
import com.vettid.app.features.calling.CallManager
import com.vettid.app.features.calling.CallType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for connection detail screen.
 *
 * Features:
 * - Display connection details and profile
 * - Revoke connection via NATS vault handler
 * - Navigate to messaging
 *
 * Note: Connection data comes from vault via NATS, not HTTP API.
 */
@HiltViewModel
class ConnectionDetailViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val connectionCryptoManager: ConnectionCryptoManager,
    private val callManager: CallManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val connectionId: String = savedStateHandle["connectionId"]
        ?: throw IllegalArgumentException("connectionId is required")

    private val _state = MutableStateFlow<ConnectionDetailState>(ConnectionDetailState.Loading)
    val state: StateFlow<ConnectionDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ConnectionDetailEffect>()
    val effects: SharedFlow<ConnectionDetailEffect> = _effects.asSharedFlow()

    // Dialog state for revoke confirmation
    private val _showRevokeDialog = MutableStateFlow(false)
    val showRevokeDialog: StateFlow<Boolean> = _showRevokeDialog.asStateFlow()

    init {
        loadConnection()
    }

    /**
     * Load connection details from vault via NATS.
     */
    fun loadConnection() {
        viewModelScope.launch {
            _state.value = ConnectionDetailState.Loading

            // Check if NATS is connected
            if (!natsAutoConnector.isConnected()) {
                _state.value = ConnectionDetailState.Error(
                    message = "Not connected to vault"
                )
                return@launch
            }

            // Load connection list and find the matching one
            connectionsClient.list().fold(
                onSuccess = { listResult ->
                    val record = listResult.items.find { it.connectionId == connectionId }
                    if (record != null) {
                        val status = when (record.status.lowercase()) {
                            "active" -> ConnectionStatus.ACTIVE
                            "pending" -> ConnectionStatus.PENDING
                            "revoked" -> ConnectionStatus.REVOKED
                            else -> ConnectionStatus.ACTIVE
                        }
                        val createdAtMillis = try {
                            java.time.Instant.parse(record.createdAt).toEpochMilli()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                        val connection = Connection(
                            connectionId = record.connectionId,
                            peerGuid = record.peerGuid,
                            peerDisplayName = record.label,
                            peerAvatarUrl = null,
                            status = status,
                            createdAt = createdAtMillis,
                            lastMessageAt = null,
                            unreadCount = 0
                        )
                        // Profile comes from peer via NATS, not available here
                        _state.value = ConnectionDetailState.Loaded(
                            connection = connection,
                            profile = null
                        )
                    } else {
                        _state.value = ConnectionDetailState.Error(
                            message = "Connection not found"
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = ConnectionDetailState.Error(
                        message = error.message ?: "Failed to load connection"
                    )
                }
            )
        }
    }

    /**
     * Navigate to messaging.
     */
    fun onMessageClick() {
        viewModelScope.launch {
            _effects.emit(ConnectionDetailEffect.NavigateToMessages(connectionId))
        }
    }

    /**
     * Show revoke confirmation dialog.
     */
    fun onRevokeClick() {
        _showRevokeDialog.value = true
    }

    /**
     * Dismiss revoke confirmation dialog.
     */
    fun dismissRevokeDialog() {
        _showRevokeDialog.value = false
    }

    /**
     * Confirm and execute revocation via NATS vault handler.
     */
    fun confirmRevoke() {
        _showRevokeDialog.value = false

        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ConnectionDetailState.Loaded) {
                _state.value = currentState.copy(isRevoking = true)
            }

            connectionsClient.revoke(connectionId).fold(
                onSuccess = {
                    // Delete the connection key
                    connectionCryptoManager.deleteConnectionKey(connectionId)

                    _effects.emit(ConnectionDetailEffect.ShowSuccess("Connection revoked"))
                    _effects.emit(ConnectionDetailEffect.NavigateBack)
                },
                onFailure = { error ->
                    if (currentState is ConnectionDetailState.Loaded) {
                        _state.value = currentState.copy(isRevoking = false)
                    }
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to revoke connection"
                    ))
                }
            )
        }
    }

    /**
     * View profile details.
     */
    fun onViewProfileClick() {
        viewModelScope.launch {
            _effects.emit(ConnectionDetailEffect.NavigateToProfile(connectionId))
        }
    }

    /**
     * Start a voice call with the connection.
     */
    fun onVoiceCallClick() {
        viewModelScope.launch {
            callManager.startCall(connectionId, CallType.VOICE).fold(
                onSuccess = {
                    // CallManager will emit showCallUI event which navigates to call screen
                },
                onFailure = { error ->
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to start call"
                    ))
                }
            )
        }
    }

    /**
     * Start a video call with the connection.
     */
    fun onVideoCallClick() {
        viewModelScope.launch {
            callManager.startCall(connectionId, CallType.VIDEO).fold(
                onSuccess = {
                    // CallManager will emit showCallUI event which navigates to call screen
                },
                onFailure = { error ->
                    _effects.emit(ConnectionDetailEffect.ShowError(
                        error.message ?: "Failed to start video call"
                    ))
                }
            )
        }
    }
}

// MARK: - State Types

/**
 * Connection detail state.
 */
sealed class ConnectionDetailState {
    object Loading : ConnectionDetailState()

    data class Loaded(
        val connection: Connection,
        val profile: Profile?,
        val isRevoking: Boolean = false
    ) : ConnectionDetailState()

    data class Error(val message: String) : ConnectionDetailState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ConnectionDetailEffect {
    data class NavigateToMessages(val connectionId: String) : ConnectionDetailEffect()
    data class NavigateToProfile(val connectionId: String) : ConnectionDetailEffect()
    object NavigateBack : ConnectionDetailEffect()
    data class ShowSuccess(val message: String) : ConnectionDetailEffect()
    data class ShowError(val message: String) : ConnectionDetailEffect()
}
