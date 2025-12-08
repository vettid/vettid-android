package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.ConnectionCryptoManager
import com.vettid.app.core.crypto.ConnectionKeyPair
import com.vettid.app.core.network.AcceptInvitationResponse
import com.vettid.app.core.network.Connection
import com.vettid.app.core.network.ConnectionApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for scanning and accepting connection invitations.
 *
 * Flow:
 * 1. Scan QR code or enter invitation code manually
 * 2. Generate key pair for this connection
 * 3. Accept invitation via API (sends public key)
 * 4. Receive peer's public key and derive shared secret
 * 5. Store connection encryption key
 */
@HiltViewModel
class ScanInvitationViewModel @Inject constructor(
    private val connectionApiClient: ConnectionApiClient,
    private val connectionCryptoManager: ConnectionCryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow<ScanInvitationState>(ScanInvitationState.Scanning)
    val state: StateFlow<ScanInvitationState> = _state.asStateFlow()

    private val _manualCode = MutableStateFlow("")
    val manualCode: StateFlow<String> = _manualCode.asStateFlow()

    private val _effects = MutableSharedFlow<ScanInvitationEffect>()
    val effects: SharedFlow<ScanInvitationEffect> = _effects.asSharedFlow()

    // Key pair generated for this connection attempt
    private var connectionKeyPair: ConnectionKeyPair? = null

    // Parsed invitation code (from QR or manual entry)
    private var parsedInvitationCode: String? = null

    /**
     * Called when QR code is scanned.
     */
    fun onQrCodeScanned(data: String) {
        // Parse the invitation code from QR data
        // Could be a deep link URL or raw code
        val invitationCode = parseInvitationCode(data)
        if (invitationCode != null) {
            parsedInvitationCode = invitationCode
            processInvitation(invitationCode)
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
     */
    fun onManualCodeEntered() {
        val code = _manualCode.value.trim()
        if (code.isNotEmpty()) {
            parsedInvitationCode = code
            processInvitation(code)
        }
    }

    /**
     * Process the invitation code.
     */
    private fun processInvitation(invitationCode: String) {
        viewModelScope.launch {
            _state.value = ScanInvitationState.Processing

            // Generate our key pair
            connectionKeyPair = connectionCryptoManager.generateConnectionKeyPair()
            val keyPair = connectionKeyPair!!

            // Accept the invitation
            connectionApiClient.acceptInvitation(invitationCode, keyPair.publicKey).fold(
                onSuccess = { response ->
                    // Derive and store the connection key
                    val connectionKey = connectionCryptoManager.deriveAndStoreConnectionKey(
                        privateKey = keyPair.privateKey,
                        peerPublicKey = connectionCryptoManager.decodeBase64(response.peerPublicKey),
                        connectionId = response.connection.connectionId
                    )

                    // Clear private key from memory
                    keyPair.clearPrivateKey()
                    connectionKeyPair = null

                    _state.value = ScanInvitationState.Success(
                        connection = response.connection
                    )
                },
                onFailure = { error ->
                    keyPair.clearPrivateKey()
                    connectionKeyPair = null
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
        val code = parsedInvitationCode
        if (code != null && _state.value is ScanInvitationState.Preview) {
            processInvitation(code)
        }
    }

    /**
     * Decline the previewed invitation.
     */
    fun declineInvitation() {
        connectionKeyPair?.clearPrivateKey()
        connectionKeyPair = null
        parsedInvitationCode = null
        _state.value = ScanInvitationState.Scanning
    }

    /**
     * Retry after error.
     */
    fun retry() {
        connectionKeyPair?.clearPrivateKey()
        connectionKeyPair = null
        parsedInvitationCode = null
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
     * Parse invitation code from various formats.
     */
    private fun parseInvitationCode(data: String): String? {
        // Try deep link format: vettid://connect/CODE
        val deepLinkRegex = """vettid://connect/([A-Za-z0-9-]+)""".toRegex()
        deepLinkRegex.find(data)?.let {
            return it.groupValues[1]
        }

        // Try HTTPS URL format: https://vettid.dev/connect/CODE
        val urlRegex = """https?://vettid\.dev/connect/([A-Za-z0-9-]+)""".toRegex()
        urlRegex.find(data)?.let {
            return it.groupValues[1]
        }

        // Try raw code (alphanumeric with dashes)
        if (data.matches("""[A-Za-z0-9-]{8,64}""".toRegex())) {
            return data
        }

        return null
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
            else -> message
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionKeyPair?.clearPrivateKey()
        connectionKeyPair = null
    }
}

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
