package com.vettid.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.events.ProfilePhotoEvents
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AppViewModel"

data class AppState(
    val hasCredential: Boolean = false,
    val isAuthenticated: Boolean = false,
    val vaultStatus: VaultStatus? = null,
    val natsConnectionState: NatsConnectionState = NatsConnectionState.Idle,
    val natsError: String? = null,
    // Connection details for status dialog
    val natsEndpoint: String? = null,
    val natsOwnerSpaceId: String? = null,
    val natsMessageSpaceId: String? = null,
    val natsCredentialsExpiry: String? = null,
    // Profile photo (Base64 encoded)
    val profilePhoto: String? = null
)

enum class VaultStatus {
    PENDING_ENROLLMENT,
    PROVISIONING,
    RUNNING,
    STOPPED,
    TERMINATED
}

/**
 * NATS connection state for UI display.
 */
enum class NatsConnectionState {
    /** Not yet attempted */
    Idle,
    /** Checking credentials */
    Checking,
    /** Connecting to NATS */
    Connecting,
    /** Successfully connected */
    Connected,
    /** Credentials expired - needs re-authentication */
    CredentialsExpired,
    /** Connection failed */
    Failed
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val natsAutoConnector: NatsAutoConnector,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val profilePhotoEvents: ProfilePhotoEvents
) : ViewModel() {

    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    init {
        refreshCredentialStatus()
        observeNatsConnectionState()
        observeProfilePhotoUpdates()
    }

    fun refreshCredentialStatus() {
        viewModelScope.launch {
            val hasCredential = credentialStore.hasStoredCredential()
            _appState.update { it.copy(hasCredential = hasCredential) }
        }
    }

    /**
     * Set authentication state and trigger NATS auto-connect if authenticated.
     */
    fun setAuthenticated(authenticated: Boolean) {
        _appState.update { it.copy(isAuthenticated = authenticated) }

        if (authenticated) {
            // Attempt NATS auto-connect after successful authentication
            connectToNats()
        }
    }

    /**
     * Attempt to connect to NATS using stored credentials.
     * Called automatically after authentication succeeds.
     */
    fun connectToNats() {
        viewModelScope.launch {
            Log.i(TAG, "Attempting NATS auto-connect")
            _appState.update { it.copy(natsConnectionState = NatsConnectionState.Connecting) }

            when (val result = natsAutoConnector.autoConnect()) {
                is NatsAutoConnector.ConnectionResult.Success -> {
                    Log.i(TAG, "NATS connected successfully")
                    // Fetch connection details from credential store
                    val endpoint = credentialStore.getNatsEndpoint()
                    val ownerSpace = credentialStore.getNatsOwnerSpace()
                    val connection = credentialStore.getNatsConnection()
                    val expiryTime = credentialStore.getNatsCredentialsExpiryTime()
                    val expiryFormatted = expiryTime?.let { formatExpiryTime(it) }
                    _appState.update {
                        it.copy(
                            natsConnectionState = NatsConnectionState.Connected,
                            natsError = null,
                            natsEndpoint = endpoint,
                            natsOwnerSpaceId = ownerSpace,
                            natsMessageSpaceId = connection?.messageSpace,
                            natsCredentialsExpiry = expiryFormatted
                        )
                    }
                    // Fetch profile photo after successful connection
                    fetchProfilePhoto()
                }

                is NatsAutoConnector.ConnectionResult.NotEnrolled -> {
                    Log.w(TAG, "NATS auto-connect: Not enrolled")
                    _appState.update {
                        it.copy(
                            natsConnectionState = NatsConnectionState.Idle,
                            natsError = null
                        )
                    }
                    // Not an error - user just hasn't enrolled yet
                }

                is NatsAutoConnector.ConnectionResult.CredentialsExpired -> {
                    Log.w(TAG, "NATS credentials expired - re-authentication required")
                    _appState.update {
                        it.copy(
                            natsConnectionState = NatsConnectionState.CredentialsExpired,
                            natsError = "Session expired. Please authenticate again."
                        )
                    }
                    // In the future, this could trigger navigation to auth screen
                    // For now, we log it and the user can still use the app
                }

                is NatsAutoConnector.ConnectionResult.MissingData -> {
                    Log.w(TAG, "NATS auto-connect: Missing ${result.field}")
                    _appState.update {
                        it.copy(
                            natsConnectionState = NatsConnectionState.Failed,
                            natsError = "Missing connection data: ${result.field}"
                        )
                    }
                }

                is NatsAutoConnector.ConnectionResult.Error -> {
                    Log.e(TAG, "NATS auto-connect failed: ${result.message}", result.cause)
                    _appState.update {
                        it.copy(
                            natsConnectionState = NatsConnectionState.Failed,
                            natsError = result.message
                        )
                    }
                }
            }
        }
    }

    /**
     * Observe profile photo updates from PersonalDataViewModel.
     */
    private fun observeProfilePhotoUpdates() {
        viewModelScope.launch {
            profilePhotoEvents.photoUpdated.collect { photo ->
                Log.d(TAG, "Profile photo update received")
                _appState.update { it.copy(profilePhoto = photo) }
            }
        }
    }

    /**
     * Observe NatsAutoConnector's connection state for real-time updates.
     */
    private fun observeNatsConnectionState() {
        viewModelScope.launch {
            natsAutoConnector.connectionState.collect { autoConnectState ->
                val natsState = when (autoConnectState) {
                    is NatsAutoConnector.AutoConnectState.Idle -> NatsConnectionState.Idle
                    is NatsAutoConnector.AutoConnectState.Checking -> NatsConnectionState.Checking
                    is NatsAutoConnector.AutoConnectState.Connecting,
                    is NatsAutoConnector.AutoConnectState.Bootstrapping,
                    is NatsAutoConnector.AutoConnectState.Subscribing,
                    is NatsAutoConnector.AutoConnectState.StartingVault,
                    is NatsAutoConnector.AutoConnectState.WaitingForVault -> NatsConnectionState.Connecting
                    is NatsAutoConnector.AutoConnectState.Connected -> NatsConnectionState.Connected
                    is NatsAutoConnector.AutoConnectState.Failed -> {
                        when (autoConnectState.result) {
                            is NatsAutoConnector.ConnectionResult.CredentialsExpired ->
                                NatsConnectionState.CredentialsExpired
                            else -> NatsConnectionState.Failed
                        }
                    }
                }
                _appState.update { it.copy(natsConnectionState = natsState) }
            }
        }
    }

    /**
     * Retry NATS connection after a failure.
     */
    fun retryNatsConnection() {
        connectToNats()
    }

    /**
     * Refresh NATS credentials by reconnecting.
     * This disconnects and reconnects using stored credentials,
     * updating the connection state and expiry time.
     */
    fun refreshNatsCredentials() {
        viewModelScope.launch {
            Log.i(TAG, "Refreshing NATS credentials")

            // Disconnect first
            natsAutoConnector.disconnect()
            _appState.update {
                it.copy(
                    natsConnectionState = NatsConnectionState.Connecting,
                    natsError = null
                )
            }

            // Reconnect
            connectToNats()
        }
    }

    /**
     * Check if NATS is currently connected.
     */
    fun isNatsConnected(): Boolean = natsAutoConnector.isConnected()

    /**
     * Fetch profile photo from vault.
     */
    private fun fetchProfilePhoto() {
        viewModelScope.launch {
            ownerSpaceClient.getProfilePhoto()
                .onSuccess { photo ->
                    _appState.update { it.copy(profilePhoto = photo) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to fetch profile photo: ${error.message}")
                }
        }
    }

    /**
     * Update profile photo in app state (called after upload from other ViewModels).
     */
    fun updateProfilePhoto(base64Photo: String?) {
        _appState.update { it.copy(profilePhoto = base64Photo) }
    }

    /**
     * Refresh profile photo from vault.
     */
    fun refreshProfilePhoto() {
        if (isNatsConnected()) {
            fetchProfilePhoto()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            // Disconnect from NATS
            natsAutoConnector.disconnect()

            // Clear authentication state
            _appState.update {
                it.copy(
                    isAuthenticated = false,
                    natsConnectionState = NatsConnectionState.Idle,
                    natsError = null
                )
            }
            // Note: This just signs out of this session.
            // To clear credentials entirely, we would call credentialStore.clearAll()
        }
    }

    /**
     * Format expiry timestamp for display.
     * Shows relative time if within 24 hours, otherwise date/time.
     */
    private fun formatExpiryTime(expiryMillis: Long): String {
        val now = System.currentTimeMillis()
        val remainingMs = expiryMillis - now

        return when {
            remainingMs <= 0 -> "Expired"
            remainingMs < 60 * 1000 -> "< 1 minute"
            remainingMs < 60 * 60 * 1000 -> {
                val minutes = remainingMs / (60 * 1000)
                "$minutes min"
            }
            remainingMs < 24 * 60 * 60 * 1000 -> {
                val hours = remainingMs / (60 * 60 * 1000)
                val minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000)
                if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            }
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                sdf.format(java.util.Date(expiryMillis))
            }
        }
    }
}
