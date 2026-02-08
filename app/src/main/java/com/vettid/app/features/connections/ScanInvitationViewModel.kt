package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.nats.ConnectionsClient
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
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
    private val ownerSpaceClient: OwnerSpaceClient,
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
        android.util.Log.d("ScanInvitationVM", "onQrCodeScanned called with data length: ${data.length}")
        // Parse the invitation from QR data
        val invitation = parseInvitationData(data)
        if (invitation != null) {
            android.util.Log.d("ScanInvitationVM", "Parsed invitation: connectionId=${invitation.connectionId}, peerGuid=${invitation.peerGuid}")
            parsedInvitation = invitation
            processInvitation(invitation)
        } else {
            android.util.Log.e("ScanInvitationVM", "Failed to parse invitation data")
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
        android.util.Log.d("ScanInvitationVM", "processInvitation called for ${invitation.connectionId}")
        viewModelScope.launch {
            _state.value = ScanInvitationState.Processing

            // Wait for NATS connection AND E2E session (with timeout)
            // E2E session is required because store-credentials needs full permissions
            android.util.Log.d("ScanInvitationVM", "Waiting for NATS connection and E2E session...")

            var attempts = 0
            val maxAttempts = 60 // 30 seconds (500ms each)
            while (attempts < maxAttempts) {
                val connected = natsAutoConnector.isConnected()
                val e2eEnabled = ownerSpaceClient.isE2EEnabled
                android.util.Log.d("ScanInvitationVM", "Check #$attempts: connected=$connected, e2e=$e2eEnabled")

                if (connected && e2eEnabled) {
                    break
                }
                kotlinx.coroutines.delay(500)
                attempts++
            }

            if (!natsAutoConnector.isConnected()) {
                android.util.Log.e("ScanInvitationVM", "NATS connection timeout")
                _state.value = ScanInvitationState.Error(
                    message = "Not connected to vault - please try again"
                )
                return@launch
            }

            if (!ownerSpaceClient.isE2EEnabled) {
                android.util.Log.e("ScanInvitationVM", "E2E session not established (timeout)")
                _state.value = ScanInvitationState.Error(
                    message = "Secure session not established - please try again"
                )
                return@launch
            }

            android.util.Log.d("ScanInvitationVM", "NATS connected and E2E enabled, storing credentials...")
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
     *
     * QR data format from create-invite:
     * {
     *   "type": "vettid_connection",
     *   "version": 1,
     *   "connection_id": "conn-xxx",
     *   "credentials": "-----BEGIN NATS USER JWT-----\n...",
     *   "owner_space": "OwnerSpace.userXXX",
     *   "message_space": "MessageSpace.userXXX.forOwner.>",
     *   "expires_at": "2026-01-31T..."
     * }
     */
    private fun parseInvitationData(data: String): ConnectionInvitationData? {
        return try {
            // Try parsing as JSON
            val json = gson.fromJson(data, JsonObject::class.java)

            // Validate type
            val type = json.get("type")?.asString
            if (type != null && type != "vettid_connection") {
                return null
            }

            // Parse with flexible field names (backend may use different conventions)
            val connectionId = json.get("connection_id")?.asString ?: return null
            val natsCredentials = json.get("credentials")?.asString
                ?: json.get("nats_credentials")?.asString ?: ""
            val ownerSpaceId = json.get("owner_space")?.asString
                ?: json.get("owner_space_id")?.asString ?: ""
            val messageSpaceId = json.get("message_space")?.asString
                ?: json.get("message_space_id")?.asString
                ?: json.get("message_space_topic")?.asString ?: return null

            // Derive peer_guid from message_space if not provided
            // Format: MessageSpace.userXXX.forOwner.> -> userXXX
            val peerGuid = json.get("peer_guid")?.asString
                ?: extractUserGuidFromMessageSpace(messageSpaceId)
                ?: "unknown"

            val label = json.get("label")?.asString ?: peerGuid.take(8)

            ConnectionInvitationData(
                connectionId = connectionId,
                peerGuid = peerGuid,
                label = label,
                natsCredentials = natsCredentials,
                ownerSpaceId = ownerSpaceId,
                messageSpaceId = messageSpaceId
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract user GUID from message space ID.
     * Format: MessageSpace.userXXX.forOwner.> -> user-XXX (with hyphen)
     */
    private fun extractUserGuidFromMessageSpace(messageSpace: String): String? {
        // Pattern: MessageSpace.userXXX.forOwner.>
        val regex = Regex("MessageSpace\\.(user[A-F0-9]+)\\.forOwner")
        val match = regex.find(messageSpace)
        return match?.groupValues?.get(1)?.let { userPart ->
            // Insert hyphen after "user" if not present
            if (userPart.startsWith("user") && !userPart.contains("-")) {
                "user-${userPart.substring(4)}"
            } else {
                userPart
            }
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
        val creatorAvatarUrl: String?,
        val creatorEmail: String? = null,
        val isEmailVerified: Boolean = false,
        val publicKeyFingerprint: String? = null,
        val trustLevel: String = "New",
        val capabilities: List<String> = emptyList(),
        val sharedDataCategories: List<String> = emptyList()
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
