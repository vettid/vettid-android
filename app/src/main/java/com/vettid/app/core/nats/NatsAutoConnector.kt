package com.vettid.app.core.nats

import android.util.Log
import com.vettid.app.core.storage.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles automatic NATS connection on app startup using stored credentials.
 *
 * Flow:
 * 1. Check if stored NATS credentials exist
 * 2. Verify credentials are not expired
 * 3. Parse JWT/seed from credential file
 * 4. Set account info on NatsConnectionManager
 * 5. Connect to NATS
 * 6. Subscribe to vault topics
 * 7. Restore or bootstrap E2E session for encrypted communication
 *
 * If credentials are expired, returns [ConnectionResult.CredentialsExpired]
 * and the caller should trigger Vault Services authentication to get fresh credentials.
 */
@Singleton
class NatsAutoConnector @Inject constructor(
    private val natsClient: NatsClient,
    private val connectionManager: NatsConnectionManager,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val credentialClient: NatsCredentialClient
) {
    companion object {
        private const val TAG = "NatsAutoConnector"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rotationListenerJob: Job? = null

    private val _connectionState = MutableStateFlow<AutoConnectState>(AutoConnectState.Idle)
    val connectionState: StateFlow<AutoConnectState> = _connectionState.asStateFlow()

    /**
     * Result of auto-connection attempt.
     */
    sealed class ConnectionResult {
        /** Successfully connected to NATS and subscribed to vault topics */
        object Success : ConnectionResult()

        /** No stored credentials - user needs to enroll */
        object NotEnrolled : ConnectionResult()

        /** Credentials expired - user needs to authenticate with Vault Services */
        object CredentialsExpired : ConnectionResult()

        /** Missing required data (ownerSpaceId, etc.) */
        data class MissingData(val field: String) : ConnectionResult()

        /** Connection failed with error */
        data class Error(val message: String, val cause: Throwable? = null) : ConnectionResult()
    }

    /**
     * Auto-connect state for UI observation.
     */
    sealed class AutoConnectState {
        object Idle : AutoConnectState()
        object Checking : AutoConnectState()
        object Connecting : AutoConnectState()
        object Subscribing : AutoConnectState()
        object Connected : AutoConnectState()
        data class Failed(val result: ConnectionResult) : AutoConnectState()
    }

    /**
     * Attempt to auto-connect using stored credentials.
     *
     * Call this on app startup after the user has been enrolled.
     *
     * @return [ConnectionResult] indicating success or the type of failure
     */
    suspend fun autoConnect(): ConnectionResult {
        _connectionState.value = AutoConnectState.Checking

        // Step 1: Check if credentials exist
        if (!credentialStore.hasNatsConnection()) {
            Log.i(TAG, "No stored NATS credentials - not enrolled")
            val result = ConnectionResult.NotEnrolled
            _connectionState.value = AutoConnectState.Failed(result)
            return result
        }

        // Step 2: Check if credentials are valid (not expired)
        if (!credentialStore.areNatsCredentialsValid()) {
            Log.i(TAG, "NATS credentials expired - authentication required")
            val result = ConnectionResult.CredentialsExpired
            _connectionState.value = AutoConnectState.Failed(result)
            return result
        }

        // Step 3: Get required connection data
        val endpoint = credentialStore.getNatsEndpoint()
        if (endpoint == null) {
            Log.e(TAG, "Missing NATS endpoint")
            val result = ConnectionResult.MissingData("endpoint")
            _connectionState.value = AutoConnectState.Failed(result)
            return result
        }

        val ownerSpaceId = credentialStore.getNatsOwnerSpace()
        if (ownerSpaceId == null) {
            Log.w(TAG, "Missing ownerSpaceId - bootstrap credentials may be incomplete")
            // Continue anyway - ownerSpaceId might be obtained after bootstrap
        }

        // Step 4: Parse JWT and seed from credential file
        val parsedCredentials = credentialStore.getParsedNatsCredentials()
        if (parsedCredentials == null) {
            Log.e(TAG, "Failed to parse NATS credential file")
            val result = ConnectionResult.MissingData("credentials")
            _connectionState.value = AutoConnectState.Failed(result)
            return result
        }

        val (jwt, seed) = parsedCredentials

        // Step 5: Build NatsCredentials object
        val credentials = NatsCredentials(
            tokenId = "stored", // We don't have tokenId for stored credentials
            jwt = jwt,
            seed = seed,
            endpoint = endpoint,
            expiresAt = calculateExpirationTime(),
            permissions = NatsPermissions(
                publish = listOf("OwnerSpace.>"),
                subscribe = listOf("OwnerSpace.>")
            ),
            caCertificate = credentialStore.getNatsCaCertificate()
        )

        // Step 6: Set account info on NatsConnectionManager (before connecting)
        // This allows OwnerSpaceClient.subscribeToVault() to get the ownerSpaceId
        if (ownerSpaceId != null) {
            val messageSpaceId = credentialStore.getNatsConnection()?.messageSpace
            connectionManager.setAccountFromStored(ownerSpaceId, messageSpaceId, endpoint)
        }

        // Step 7: Connect to NATS
        _connectionState.value = AutoConnectState.Connecting
        Log.i(TAG, "Connecting to NATS at $endpoint")

        val connectResult = natsClient.connect(credentials)
        if (connectResult.isFailure) {
            val error = connectResult.exceptionOrNull()
            Log.e(TAG, "Failed to connect to NATS", error)
            val result = ConnectionResult.Error(
                message = error?.message ?: "Connection failed",
                cause = error
            )
            _connectionState.value = AutoConnectState.Failed(result)
            return result
        }

        // Update connection manager state
        connectionManager.setConnectedState(credentials)
        Log.i(TAG, "Connected to NATS successfully")

        // Step 8: Subscribe to vault topics (if ownerSpaceId available)
        if (ownerSpaceId != null) {
            _connectionState.value = AutoConnectState.Subscribing

            val subscribeResult = ownerSpaceClient.subscribeToVault()
            if (subscribeResult.isFailure) {
                val error = subscribeResult.exceptionOrNull()
                Log.w(TAG, "Failed to subscribe to vault topics: ${error?.message}")
                // Don't fail completely - we're connected, just can't subscribe yet
            } else {
                Log.i(TAG, "Subscribed to vault topics")
            }
        }

        // Step 9: Restore or bootstrap E2E session
        if (!ownerSpaceClient.restoreSession()) {
            Log.i(TAG, "No valid E2E session - will bootstrap on first vault message")
            // Session will be bootstrapped when needed (e.g., on first sendToVault call)
            // For now, we just log that there's no session
        } else {
            Log.i(TAG, "E2E session restored: ${ownerSpaceClient.currentSessionId}")
        }

        // Step 10: Start listening for credential rotation events
        startRotationListener()

        _connectionState.value = AutoConnectState.Connected
        Log.i(TAG, "Auto-connect completed successfully")
        return ConnectionResult.Success
    }

    /**
     * Check connection state without connecting.
     *
     * @return Current credential state
     */
    fun checkCredentialState(): CredentialState {
        if (!credentialStore.hasNatsConnection()) {
            return CredentialState.NotEnrolled
        }

        return if (credentialStore.areNatsCredentialsValid()) {
            CredentialState.Valid
        } else {
            CredentialState.Expired
        }
    }

    /**
     * Credential state enum.
     */
    enum class CredentialState {
        /** No credentials stored - user needs to enroll */
        NotEnrolled,
        /** Credentials exist and are valid */
        Valid,
        /** Credentials exist but are expired */
        Expired
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = natsClient.isConnected

    /**
     * Disconnect from NATS.
     */
    suspend fun disconnect() {
        stopRotationListener()
        ownerSpaceClient.unsubscribeFromVault()
        natsClient.disconnect()
        _connectionState.value = AutoConnectState.Idle
        Log.i(TAG, "Disconnected from NATS")
    }

    /**
     * Start listening for credential rotation events from the vault.
     */
    private fun startRotationListener() {
        rotationListenerJob?.cancel()
        rotationListenerJob = scope.launch {
            ownerSpaceClient.credentialRotation.collect { rotation ->
                handleCredentialRotation(rotation)
            }
        }
        Log.d(TAG, "Started credential rotation listener")
    }

    /**
     * Stop listening for credential rotation events.
     */
    private fun stopRotationListener() {
        rotationListenerJob?.cancel()
        rotationListenerJob = null
    }

    /**
     * Handle a credential rotation event from the vault.
     *
     * Stores the new credentials and reconnects to NATS.
     */
    private suspend fun handleCredentialRotation(rotation: CredentialRotationMessage) {
        Log.i(TAG, "Handling credential rotation: reason=${rotation.reason}, credentialId=${rotation.credentialId}")

        try {
            // Store new credentials
            credentialClient.storeCredentials(rotation.credentials, rotation.credentialId)
            Log.i(TAG, "Stored rotated credentials")

            // Reconnect with new credentials
            Log.i(TAG, "Reconnecting with new credentials...")

            // First disconnect (but keep rotation listener until reconnected)
            ownerSpaceClient.unsubscribeFromVault()
            natsClient.disconnect()

            // Reconnect using the new credentials
            val result = autoConnect()

            when (result) {
                is ConnectionResult.Success -> {
                    Log.i(TAG, "Successfully reconnected after credential rotation")
                }
                else -> {
                    Log.e(TAG, "Failed to reconnect after credential rotation: $result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling credential rotation", e)
        }
    }

    /**
     * Request credential refresh from the vault.
     *
     * Use this to proactively refresh credentials before they expire.
     *
     * @param deviceId Device identifier
     * @return New credentials on success
     */
    suspend fun requestCredentialRefresh(deviceId: String): Result<CredentialRefreshResult> {
        if (!isConnected()) {
            return Result.failure(NatsException("Not connected to NATS"))
        }

        val result = credentialClient.requestRefresh(
            currentCredentialId = null, // We don't track credential IDs yet
            deviceId = deviceId
        )

        result.onSuccess { refreshResult ->
            // Store the refreshed credentials
            credentialClient.storeCredentials(refreshResult.credentials, refreshResult.credentialId)
            Log.i(TAG, "Credentials refreshed successfully, expires: ${refreshResult.expiresAt}")
        }

        return result
    }

    /**
     * Check if credentials need refresh (within buffer period).
     *
     * @param bufferMinutes Minutes before expiry to consider credentials stale
     * @return true if credentials should be refreshed
     */
    fun needsCredentialRefresh(bufferMinutes: Long = 120): Boolean {
        return credentialClient.needsRefresh(bufferMinutes)
    }

    /**
     * Calculate expiration time based on stored timestamp.
     * Credentials are valid for 24 hours from storage time.
     */
    private fun calculateExpirationTime(): Instant {
        // CredentialStore stores at KEY_NATS_STORED_AT
        // Valid for 24 hours
        val storedAtMs = System.currentTimeMillis() // Approximate - actual stored time not exposed
        val expiresAtMs = storedAtMs + (24 * 60 * 60 * 1000L)
        return Instant.ofEpochMilli(expiresAtMs)
    }
}
