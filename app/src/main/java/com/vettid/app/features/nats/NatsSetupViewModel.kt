package com.vettid.app.features.nats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.*
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for NATS setup and connection management.
 *
 * Handles the flow:
 * 1. Check if NATS account exists
 * 2. Create account if needed
 * 3. Generate token
 * 4. Connect to NATS
 * 5. Subscribe to vault topics
 */
@HiltViewModel
class NatsSetupViewModel @Inject constructor(
    private val connectionManager: NatsConnectionManager,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow<NatsSetupState>(NatsSetupState.Initial)
    val state: StateFlow<NatsSetupState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<NatsSetupEffect>()
    val effects: SharedFlow<NatsSetupEffect> = _effects.asSharedFlow()

    private var lastAuthToken: String? = null

    init {
        // Observe connection state changes
        viewModelScope.launch {
            connectionManager.connectionState.collect { connectionState ->
                handleConnectionStateChange(connectionState)
            }
        }
    }

    fun onEvent(event: NatsSetupEvent) {
        when (event) {
            is NatsSetupEvent.SetupNats -> setupNats(event.authToken)
            is NatsSetupEvent.Disconnect -> disconnect()
            is NatsSetupEvent.RefreshToken -> refreshToken(event.authToken)
            is NatsSetupEvent.Retry -> retry()
            is NatsSetupEvent.DismissError -> dismissError()
        }
    }

    private fun setupNats(authToken: String) {
        lastAuthToken = authToken

        viewModelScope.launch {
            _state.value = NatsSetupState.CheckingAccount

            // Step 1: Check account status
            val statusResult = connectionManager.checkAccountStatus(authToken)
            val status = statusResult.getOrNull()

            if (status == null) {
                handleSetupError(statusResult.exceptionOrNull(), NatsErrorCode.NETWORK_ERROR)
                return@launch
            }

            // Step 2: Create account if needed
            val account = if (!status.hasAccount || status.account == null) {
                _state.value = NatsSetupState.CreatingAccount

                val createResult = connectionManager.createAccount(authToken)
                val created = createResult.getOrNull()

                if (created == null) {
                    handleSetupError(createResult.exceptionOrNull(), NatsErrorCode.ACCOUNT_CREATION_FAILED)
                    return@launch
                }

                created
            } else {
                status.account
            }

            // Step 3: Connect (generates token internally)
            _state.value = NatsSetupState.Connecting

            val connectResult = connectionManager.connect(
                authToken = authToken,
                deviceId = credentialStore.getUserGuid()
            )

            if (connectResult.isFailure) {
                handleSetupError(connectResult.exceptionOrNull(), NatsErrorCode.CONNECTION_FAILED)
                return@launch
            }

            // Step 4: Subscribe to vault topics
            val subscribeResult = ownerSpaceClient.subscribeToVault()
            if (subscribeResult.isFailure) {
                android.util.Log.w(TAG, "Failed to subscribe to vault topics: ${subscribeResult.exceptionOrNull()?.message}")
                // Continue anyway - subscriptions can be retried
            }

            // Success!
            _effects.emit(NatsSetupEffect.ConnectionEstablished(account))
        }
    }

    private fun disconnect() {
        viewModelScope.launch {
            ownerSpaceClient.unsubscribeFromVault()
            connectionManager.disconnect()
            _state.value = NatsSetupState.Disconnected(
                account = connectionManager.account.value,
                reason = "User disconnected"
            )
            _effects.emit(NatsSetupEffect.Disconnected)
        }
    }

    private fun refreshToken(authToken: String) {
        lastAuthToken = authToken

        viewModelScope.launch {
            val result = connectionManager.refreshTokenIfNeeded(authToken)
            if (result.isFailure) {
                _effects.emit(NatsSetupEffect.ShowError(
                    "Failed to refresh token: ${result.exceptionOrNull()?.message}"
                ))
            }
        }
    }

    private fun retry() {
        val token = lastAuthToken
        if (token != null) {
            setupNats(token)
        } else {
            viewModelScope.launch {
                _effects.emit(NatsSetupEffect.RequireAuth)
            }
        }
    }

    private fun dismissError() {
        val currentAccount = connectionManager.account.value
        _state.value = NatsSetupState.Disconnected(account = currentAccount)
    }

    private fun handleSetupError(error: Throwable?, code: NatsErrorCode) {
        val message = error?.message ?: "Unknown error"
        _state.value = NatsSetupState.Error(
            message = message,
            code = code,
            retryable = code != NatsErrorCode.UNAUTHORIZED
        )
    }

    private fun handleConnectionStateChange(connectionState: NatsConnectionState) {
        when (connectionState) {
            is NatsConnectionState.Connected -> {
                val account = connectionManager.account.value
                if (account != null) {
                    _state.value = NatsSetupState.Connected(
                        account = account,
                        tokenExpiresAt = connectionState.credentials.expiresAt.toString(),
                        permissions = connectionState.credentials.permissions
                    )
                }
            }
            is NatsConnectionState.Disconnected -> {
                if (_state.value is NatsSetupState.Connected) {
                    _state.value = NatsSetupState.Disconnected(
                        account = connectionManager.account.value,
                        reason = "Connection lost"
                    )
                }
            }
            is NatsConnectionState.Error -> {
                _state.value = NatsSetupState.Error(
                    message = connectionState.message,
                    code = NatsErrorCode.CONNECTION_FAILED,
                    retryable = true
                )
            }
            is NatsConnectionState.Refreshing -> {
                // Keep current state, just update internally
            }
            is NatsConnectionState.Connecting -> {
                // Already handled in setupNats
            }
            is NatsConnectionState.CreatingAccount -> {
                // Already handled in setupNats
            }
        }
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = connectionManager.isConnected()

    /**
     * Get the OwnerSpace client for pub/sub operations.
     */
    fun getOwnerSpaceClient(): OwnerSpaceClient = ownerSpaceClient

    /**
     * Get connection summary for display.
     */
    fun getConnectionSummary(): NatsConnectionSummary {
        val state = _state.value
        return when (state) {
            is NatsSetupState.Connected -> NatsConnectionSummary(
                statusText = "Connected",
                icon = NatsStatusIcon.CONNECTED,
                ownerSpaceId = state.account.ownerSpaceId,
                messageSpaceId = state.account.messageSpaceId
            )
            is NatsSetupState.Connecting,
            is NatsSetupState.GeneratingToken,
            is NatsSetupState.CreatingAccount,
            is NatsSetupState.CheckingAccount -> NatsConnectionSummary(
                statusText = "Connecting...",
                icon = NatsStatusIcon.CONNECTING
            )
            is NatsSetupState.Error -> NatsConnectionSummary(
                statusText = "Error",
                icon = NatsStatusIcon.ERROR
            )
            is NatsSetupState.Disconnected -> NatsConnectionSummary(
                statusText = "Disconnected",
                icon = NatsStatusIcon.DISCONNECTED,
                ownerSpaceId = state.account?.ownerSpaceId,
                messageSpaceId = state.account?.messageSpaceId
            )
            is NatsSetupState.Initial -> NatsConnectionSummary(
                statusText = "Not Set Up",
                icon = NatsStatusIcon.NOT_SETUP
            )
        }
    }

    companion object {
        private const val TAG = "NatsSetupViewModel"
    }
}

/**
 * Summary of NATS connection status for display.
 */
data class NatsConnectionSummary(
    val statusText: String,
    val icon: NatsStatusIcon,
    val ownerSpaceId: String? = null,
    val messageSpaceId: String? = null
)

/**
 * Icon for NATS status.
 */
enum class NatsStatusIcon {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
    NOT_SETUP
}
