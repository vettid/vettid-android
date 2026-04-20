package com.vettid.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.events.ProfilePhotoEvents
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.NatsMessagingClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.PersonalDataStore
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
    // User profile info
    val firstName: String = "",
    val lastName: String = "",
    val userEmail: String = "",
    // Profile photo (Base64 encoded)
    val profilePhoto: String? = null
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

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
    private val natsMessagingClient: NatsMessagingClient,
    private val profilePhotoEvents: ProfilePhotoEvents,
    private val personalDataStore: PersonalDataStore,
    private val appLifecycleObserver: com.vettid.app.core.nats.AppLifecycleObserver,
) : ViewModel() {

    private val _appState = MutableStateFlow(AppState(
        // Initialize synchronously to avoid Welcome screen flash on relaunch
        hasCredential = credentialStore.hasStoredCredential()
    ))
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    init {
        observeNatsConnectionState()
        observeProfilePhotoUpdates()
        observeSessionExpiry()
        observeVaultLock()
        loadUserProfile()
    }

    private fun observeSessionExpiry() {
        viewModelScope.launch {
            appLifecycleObserver.sessionExpired.collect {
                Log.i(TAG, "Session TTL expired, forcing re-authentication")
                _appState.update { it.copy(isAuthenticated = false) }
                natsAutoConnector.disconnect()
            }
        }
    }

    /**
     * Observe vault-locked events from OwnerSpaceClient.
     * When the vault reports "vault_locked" (e.g., after enclave instance refresh
     * where the DEK is lost), force the user back to PIN entry so the DEK can
     * be re-derived from PIN + sealed material.
     *
     * Grace window: during a migration the ASG runs two enclaves and NATS may
     * load-balance a request to the "cold" instance that doesn't hold our
     * just-derived DEK. Treating a single vault_locked response as a real
     * lockout would kick the user back to the PIN screen seconds after they
     * typed it. Ignore vault_locked responses within VAULT_LOCK_GRACE_MS of
     * the last successful authentication and instead just log them.
     */
    private fun observeVaultLock() {
        viewModelScope.launch {
            ownerSpaceClient.vaultLocked.collect {
                if (!_appState.value.isAuthenticated) return@collect
                val sinceAuth = System.currentTimeMillis() - lastAuthenticatedAtMillis
                if (sinceAuth < VAULT_LOCK_GRACE_MS) {
                    Log.w(TAG, "vault_locked within grace window (${sinceAuth}ms since auth) — likely migration routing, ignoring")
                    return@collect
                }
                Log.i(TAG, "Vault locked — requiring PIN re-entry")
                _appState.update { it.copy(isAuthenticated = false) }
            }
        }
    }

    // Tracked so observeVaultLock can ignore transient vault_locked responses
    // that arrive right after a successful unlock (migration-time routing race).
    private var lastAuthenticatedAtMillis: Long = 0L

    private companion object {
        /**
         * Window during which a vault_locked response is ignored rather than
         * forcing re-auth. Sized to cover a migration-window routing blip —
         * during migration, NATS may deliver a few consecutive requests to a
         * cold enclave before the warm one takes over. 60s is long enough to
         * ride out the handful of background requests the app fires after
         * unlock (feed.sync, profile.broadcast, profile.get, photo fetch)
         * without masking a genuine lockout caused by enclave restart.
         */
        private const val VAULT_LOCK_GRACE_MS = 60_000L
    }

    /**
     * Load user profile info from local storage.
     */
    private fun loadUserProfile() {
        viewModelScope.launch {
            val systemFields = personalDataStore.getSystemFields()
            val existingPhoto = _appState.value.profilePhoto
            _appState.update {
                it.copy(
                    firstName = systemFields?.firstName ?: "",
                    lastName = systemFields?.lastName ?: "",
                    userEmail = systemFields?.email ?: ""
                )
            }
            Log.d(TAG, "Loaded user profile: ${systemFields?.firstName ?: ""} ${systemFields?.lastName ?: ""}, photo preserved: ${_appState.value.profilePhoto?.length ?: 0} (was: ${existingPhoto?.length ?: 0})")
        }
    }

    /**
     * Refresh user profile info (called after enrollment or profile update).
     */
    fun refreshUserProfile() {
        loadUserProfile()
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
        if (authenticated) {
            lastAuthenticatedAtMillis = System.currentTimeMillis()
        }
        _appState.update { it.copy(isAuthenticated = authenticated) }

        if (authenticated && !credentialStore.getOfflineMode()) {
            // Attempt NATS auto-connect after successful authentication (unless offline)
            connectToNats()
        }
    }

    /**
     * Set offline mode preference and update connection state accordingly.
     */
    fun setOfflineMode(enabled: Boolean) {
        credentialStore.setOfflineMode(enabled)
        if (enabled) {
            // Disconnect from NATS when going offline
            _appState.update {
                it.copy(
                    natsConnectionState = NatsConnectionState.Idle,
                    natsError = null
                )
            }
            viewModelScope.launch {
                natsAutoConnector.disconnect()
            }
        } else {
            // Attempt to reconnect when coming online
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
                    // Fetch profile photo after successful connection.
                    // Profile broadcast to peers is triggered by the shared
                    // observeNatsConnectionState flow on the
                    // disconnected→connected transition — covers this path
                    // plus lifecycle reconnects.
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
                    // Credential reissue was already attempted in NatsAutoConnector.
                    // If we still get CredentialsExpired, the REST reissue also failed.
                    Log.w(TAG, "NATS credentials expired and reissue failed")
                    _appState.update {
                        it.copy(
                            natsConnectionState = NatsConnectionState.CredentialsExpired,
                            natsError = "Unable to reconnect. Check your internet connection and try again."
                        )
                    }
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
            var wasConnected = false
            natsAutoConnector.connectionState.collect { autoConnectState ->
                val natsState = when (autoConnectState) {
                    is NatsAutoConnector.AutoConnectState.Idle -> NatsConnectionState.Idle
                    is NatsAutoConnector.AutoConnectState.Checking -> NatsConnectionState.Checking
                    is NatsAutoConnector.AutoConnectState.Connecting,
                    is NatsAutoConnector.AutoConnectState.Bootstrapping,
                    is NatsAutoConnector.AutoConnectState.Subscribing,
                    is NatsAutoConnector.AutoConnectState.StartingVault,
                    is NatsAutoConnector.AutoConnectState.WaitingForVault,
                    is NatsAutoConnector.AutoConnectState.ReissuingCredentials -> NatsConnectionState.Connecting
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

                // Fire the profile broadcast on every disconnected → connected
                // transition, not just the first time connectToNats is invoked.
                // AppLifecycleObserver auto-reconnects on foreground without
                // going through AppViewModel.connectToNats, so without this
                // the snapshot wouldn't reach peers when the user just resumes
                // the app instead of doing a fresh PIN unlock.
                val nowConnected = natsState == NatsConnectionState.Connected
                if (nowConnected && !wasConnected) {
                    syncProfileToPeers()
                }
                wasConnected = nowConnected
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
     * Broadcast the current published profile to all active peers so their
     * cached _peer_profile stays in sync — picks up wallets/fields added
     * since the last broadcast. Called once per successful auto-connect.
     */
    private fun syncProfileToPeers() {
        viewModelScope.launch {
            natsMessagingClient.broadcastProfileUpdate()
                .onSuccess { count ->
                    if (count > 0) {
                        Log.d(TAG, "Broadcast profile to $count peers")
                    }
                }
                .onFailure { error ->
                    Log.d(TAG, "Profile broadcast skipped: ${error.message}")
                }
        }
    }

    /**
     * Fetch profile photo from vault.
     */
    private fun fetchProfilePhoto() {
        viewModelScope.launch {
            Log.d(TAG, "Fetching profile photo...")
            ownerSpaceClient.getProfilePhoto()
                .onSuccess { photo ->
                    if (!photo.isNullOrEmpty()) {
                        Log.d(TAG, "Profile photo fetched from vault: ${photo.length} chars")
                        _appState.update { it.copy(profilePhoto = photo) }
                        personalDataStore.saveProfilePhoto(photo)
                    } else {
                        Log.d(TAG, "Vault has no photo, loading from local cache")
                        loadPhotoFromLocalCache()
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to fetch profile photo: ${error.message}")
                    loadPhotoFromLocalCache()
                }
        }
    }

    private fun loadPhotoFromLocalCache() {
        val localPhoto = personalDataStore.getProfilePhoto()
        if (!localPhoto.isNullOrEmpty()) {
            Log.d(TAG, "Profile photo loaded from local cache: ${localPhoto.length} chars")
            _appState.update { it.copy(profilePhoto = localPhoto) }
        } else {
            Log.d(TAG, "No profile photo in local cache")
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
