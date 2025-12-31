package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionStatus
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for scanning and accepting connection invitations.
 *
 * Flow (NATS-based):
 * 1. Scan QR code containing peer's NATS credentials and space IDs
 * 2. Store credentials via vault handler to enable messaging
 * 3. Connection is immediately available for communication
 *
 * Note: X25519 key exchange happens within vault JetStream, not during invitation acceptance.
 */
@HiltViewModel
class ScanInvitationViewModel @Inject constructor(
    private val connectionsClient: ConnectionsClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val connectionCryptoManager: ConnectionCryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow<ScanInvitationState>(ScanInvitationState.Scanning)
    val state: StateFlow<ScanInvitationState> = _state.asStateFlow()

    private val _manualCode = MutableStateFlow("")
    val manualCode: StateFlow<String> = _manualCode.asStateFlow()

    private val _effects = MutableSharedFlow<ScanInvitationEffect>()
    val effects: SharedFlow<ScanInvitationEffect> = _effects.asSharedFlow()

    private val gson = Gson()

    // Parsed invitation data (from QR)
    private var parsedInvitation: ConnectionInvitationData? = null

    /**
     * Called when QR code is scanned.
     */
    fun onQrCodeScanned(data: String) {
        // Parse the invitation from QR data
        val invitation = parseInvitationData(data)
        if (invitation != null) {
            parsedInvitation = invitation
            processInvitation(invitation)
        } else {
            viewModelScope.launch {
                _effects.emit(ScanInvitationEffect.ShowError("Invalid QR code"))
            }
        }
    }

    /**
     * Update manual code input.
     */
    fun onManualCodeChanged(code: String) {
        _manualCode.value = code
    }

    /**
     * Submit manually entered code.
     * Note: Manual entry is limited - full invitation data is in QR code.
     */
    fun onManualCodeEntered() {
        viewModelScope.launch {
            _effects.emit(ScanInvitationEffect.ShowError(
                "Please scan the QR code to accept an invitation"
            ))
        }
    }

    /**
     * Process the invitation by storing credentials via NATS vault handler.
     */
    private fun processInvitation(invitation: ConnectionInvitationData) {
        viewModelScope.launch {
            // Check NATS connection
            if (!natsAutoConnector.isConnected()) {
                _state.value = ScanInvitationState.Error(
                    message = "Not connected to vault"
                )
                return@launch
            }

            _state.value = ScanInvitationState.Processing

            // Store the peer's credentials via vault handler
            connectionsClient.storeCredentials(
                connectionId = invitation.connectionId,
                peerGuid = invitation.peerGuid,
                label = invitation.label,
                natsCredentials = invitation.natsCredentials,
                peerOwnerSpaceId = invitation.ownerSpaceId,
                peerMessageSpaceId = invitation.messageSpaceId
            ).fold(
                onSuccess = { connectionRecord ->
                    val connection = Connection(
                        connectionId = connectionRecord.connectionId,
                        peerGuid = connectionRecord.peerGuid,
                        peerDisplayName = connectionRecord.label,
                        peerAvatarUrl = null,
                        status = ConnectionStatus.ACTIVE,
                        createdAt = System.currentTimeMillis(),
                        lastMessageAt = null,
                        unreadCount = 0
                    )
                    _state.value = ScanInvitationState.Success(connection = connection)
                },
                onFailure = { error ->
                    _state.value = ScanInvitationState.Error(
                        message = parseError(error)
                    )
                }
            )
        }
    }

    /**
     * Accept the previewed invitation.
     */
    fun acceptInvitation() {
        val invitation = parsedInvitation
        if (invitation != null && _state.value is ScanInvitationState.Preview) {
            processInvitation(invitation)
        }
    }

    /**
     * Decline the previewed invitation.
     */
    fun declineInvitation() {
        parsedInvitation = null
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Retry after error.
     */
    fun retry() {
        parsedInvitation = null
        _manualCode.value = ""
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Switch to manual code entry.
     */
    fun switchToManualEntry() {
        _state.value = ScanInvitationState.ManualEntry
    }

    /**
     * Switch to scanner.
     */
    fun switchToScanner() {
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Parse invitation data from QR code.
     * Expected format: JSON with connection credentials
     */
    private fun parseInvitationData(data: String): ConnectionInvitationData? {
        return try {
            // Try parsing as JSON
            val json = gson.fromJson(data, JsonObject::class.java)
            ConnectionInvitationData(
                connectionId = json.get("connection_id")?.asString ?: return null,
                peerGuid = json.get("peer_guid")?.asString ?: return null,
                label = json.get("label")?.asString ?: "Unknown",
                natsCredentials = json.get("nats_credentials")?.asString ?: return null,
                ownerSpaceId = json.get("owner_space_id")?.asString ?: return null,
                messageSpaceId = json.get("message_space_id")?.asString ?: return null
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse error for user-friendly display.
     */
    private fun parseError(error: Throwable): String {
        val message = error.message ?: "Unknown error"

        return when {
            message.contains("expired", ignoreCase = true) -> "This invitation has expired"
            message.contains("already", ignoreCase = true) -> "This invitation has already been used"
            message.contains("not found", ignoreCase = true) -> "Invalid invitation code"
            message.contains("revoked", ignoreCase = true) -> "This invitation has been revoked"
            message.contains("self", ignoreCase = true) -> "You cannot connect with yourself"
            message.contains("not connected", ignoreCase = true) -> "Not connected to vault"
            else -> message
        }
    }
}

/**
 * Parsed connection invitation data from QR code.
 */
private data class ConnectionInvitationData(
    val connectionId: String,
    val peerGuid: String,
    val label: String,
    val natsCredentials: String,
    val ownerSpaceId: String,
    val messageSpaceId: String
)

// MARK: - State Types

/**
 * Scan invitation state.
 */
sealed class ScanInvitationState {
    object Scanning : ScanInvitationState()
    object ManualEntry : ScanInvitationState()
    object Processing : ScanInvitationState()

    data class Preview(
        val creatorName: String,
        val creatorAvatarUrl: String?
    ) : ScanInvitationState()

    data class Success(
        val connection: Connection
    ) : ScanInvitationState()

    data class Error(val message: String) : ScanInvitationState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ScanInvitationEffect {
    data class ShowError(val message: String) : ScanInvitationEffect()
    data class NavigateToConnection(val connectionId: String) : ScanInvitationEffect()
}
