package com.vettid.app.core.nats

import android.content.Context
import android.util.Log
import com.vettid.app.core.network.VaultLifecycleClient
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.location.LocationCollectionWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 6. If bootstrap credentials: execute app.bootstrap to get full credentials
 * 7. Subscribe to vault topics
 * 8. Restore or bootstrap E2E session for encrypted communication
 *
 * If credentials are expired, returns [ConnectionResult.CredentialsExpired]
 * and the caller should trigger Vault Services authentication to get fresh credentials.
 */
@Singleton
class NatsAutoConnector @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val natsClient: NatsClient,
    private val connectionManager: NatsConnectionManager,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val credentialClient: NatsCredentialClient,
    private val bootstrapClient: BootstrapClient,
    private val vaultLifecycleClient: VaultLifecycleClient,
    private val appPreferencesStore: AppPreferencesStore
) {
    companion object {
        private const val TAG = "NatsAutoConnector"
        private const val VAULT_START_WAIT_MS = 30_000L // Wait 30s for vault to start
        private const val VAULT_POLL_INTERVAL_MS = 5_000L // Poll every 5s
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
        /** Starting vault EC2 instance */
        object StartingVault : AutoConnectState()
        /** Waiting for vault to become ready */
        object WaitingForVault : AutoConnectState()
        /** Exchanging bootstrap credentials for full credentials */
        object Bootstrapping : AutoConnectState()
        object Subscribing : AutoConnectState()
        object Connected : AutoConnectState()
        data class Failed(val result: ConnectionResult) : AutoConnectState()
    }

    /**
     * Attempt to auto-connect using stored credentials.
     *
     * Call this on app startup after the user has been enrolled.
     *
     * @param autoStartVault If true, attempt to start the vault if connection fails due to auth
     * @return [ConnectionResult] indicating success or the type of failure
     */
    suspend fun autoConnect(autoStartVault: Boolean = true): ConnectionResult {
        // Quick check: if already connected, just return success
        // This prevents reconnection when called multiple times (e.g., from both PinUnlockViewModel and AppViewModel)
        if (natsClient.isConnected) {
            Log.i(TAG, "Already connected to NATS, skipping reconnect")
            _connectionState.value = AutoConnectState.Connected
            return ConnectionResult.Success
        }

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

            // Check if this is an auth error that might be due to vault not running
            val errorMessage = error?.message ?: ""
            val isAuthError = errorMessage.contains("Authentication", ignoreCase = true) ||
                              errorMessage.contains("auth", ignoreCase = true) ||
                              errorMessage.contains("timeout", ignoreCase = true)

            // Attempt to start vault if this looks like an auth error and auto-start is enabled
            if (autoStartVault && isAuthError) {
                Log.i(TAG, "Auth error detected - attempting to start vault")
                val vaultStarted = attemptVaultStart()
                if (vaultStarted) {
                    Log.i(TAG, "Vault started - retrying connection")
                    // Retry connection without auto-start to avoid infinite loop
                    return autoConnect(autoStartVault = false)
                }
            }

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

        // Step 8: Check if we have bootstrap credentials and need to exchange for full credentials
        if (credentialStore.hasBootstrapCredentials() && ownerSpaceId != null) {
            _connectionState.value = AutoConnectState.Bootstrapping
            Log.i(TAG, "Have bootstrap credentials - executing app.bootstrap to get full credentials")

            val bootstrapResult = executeBootstrapExchange(ownerSpaceId)
            if (bootstrapResult.isFailure) {
                val error = bootstrapResult.exceptionOrNull()
                Log.e(TAG, "Bootstrap credential exchange failed: ${error?.message}")
                // Continue with bootstrap credentials - may have limited functionality
                // Don't fail completely as we are connected
            } else {
                val fullCredentialsResult = bootstrapResult.getOrThrow()
                Log.i(TAG, "Got full credentials from bootstrap - reconnecting")

                // Store E2E session if established (needed for credential rotation)
                fullCredentialsResult.sessionCrypto?.let { session ->
                    credentialStore.storeSession(
                        sessionId = session.sessionId,
                        sessionKey = session.getSessionKeyForStorage(),
                        publicKey = session.publicKey,
                        expiresAt = fullCredentialsResult.sessionExpiresAt?.let {
                            try { java.time.Instant.parse(it).toEpochMilli() }
                            catch (e: Exception) { System.currentTimeMillis() + 24 * 60 * 60 * 1000L }
                        } ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                    )
                    // Enable E2E in ownerSpaceClient for credential rotation
                    ownerSpaceClient.setSession(session)
                }

                // Check if immediate credential rotation is required (short-lived bootstrap creds)
                var finalCredentials = fullCredentialsResult.natsCredentials
                var finalCredentialId = fullCredentialsResult.credentialId
                var finalTtlSeconds = fullCredentialsResult.ttlSeconds

                if (fullCredentialsResult.requiresImmediateRotation && fullCredentialsResult.sessionCrypto != null) {
                    Log.i(TAG, "Bootstrap credentials require immediate rotation (TTL: ${fullCredentialsResult.ttlSeconds}s)")

                    // Rotate credentials over E2E encrypted channel
                    val deviceId = credentialStore.getUserGuid() ?: "unknown"
                    val rotationResult = credentialClient.requestRefresh(
                        currentCredentialId = fullCredentialsResult.credentialId,
                        deviceId = deviceId
                    )

                    rotationResult.fold(
                        onSuccess = { refreshResult ->
                            Log.i(TAG, "Credential rotation successful. New TTL: ${refreshResult.ttlSeconds}s")
                            finalCredentials = refreshResult.credentials
                            finalCredentialId = refreshResult.credentialId
                            finalTtlSeconds = refreshResult.ttlSeconds
                        },
                        onFailure = { error ->
                            Log.w(TAG, "Credential rotation failed: ${error.message}. Using short-lived bootstrap credentials.")
                            // Continue with short-lived credentials - they'll work for 5 minutes
                        }
                    )
                }

                // Store full credentials (either rotated or original bootstrap)
                credentialStore.storeFullNatsCredentials(
                    credentials = finalCredentials,
                    ownerSpace = fullCredentialsResult.ownerSpace,
                    messageSpace = fullCredentialsResult.messageSpace,
                    credentialId = finalCredentialId,
                    ttlSeconds = finalTtlSeconds
                )

                // Disconnect and reconnect with full credentials
                natsClient.disconnect()

                // Recurse to reconnect with full credentials
                return autoConnect()
            }
        }

        // Step 9: Subscribe to vault topics (if ownerSpaceId available)
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

        // Step 10: Restore or bootstrap E2E session
        if (!ownerSpaceClient.restoreSession()) {
            Log.i(TAG, "No valid E2E session - will bootstrap on first vault message")
            // Session will be bootstrapped when needed (e.g., on first sendToVault call)
            // For now, we just log that there's no session
        } else {
            Log.i(TAG, "E2E session restored: ${ownerSpaceClient.currentSessionId}")
        }

        // Step 11: Start listening for credential rotation events
        startRotationListener()

        // Step 12: Re-schedule location worker if tracking was enabled
        ensureLocationWorkerScheduled()

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
     * Re-schedule the location collection worker if location tracking is enabled.
     * Called after successful NATS connection to recover from worker death.
     *
     * WorkManager's ExistingPeriodicWorkPolicy.KEEP means this is a no-op
     * if the worker is already running — it only reschedules if the worker was lost.
     */
    private fun ensureLocationWorkerScheduled() {
        if (!appPreferencesStore.isLocationTrackingEnabled()) return

        val frequency = appPreferencesStore.getLocationFrequency()
        Log.i(TAG, "Ensuring location worker is scheduled (frequency=${frequency.minutes}min)")
        LocationCollectionWorker.ensureScheduled(appContext, frequency.minutes)
    }

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
     * Execute the bootstrap credential exchange to get full NATS credentials.
     *
     * @param ownerSpaceId The OwnerSpace ID for topic construction
     * @return BootstrapResult with full credentials on success
     */
    private suspend fun executeBootstrapExchange(ownerSpaceId: String): Result<BootstrapResult> {
        val deviceId = credentialStore.getUserGuid() ?: "unknown"
        return bootstrapClient.executeBootstrap(
            natsClient = natsClient,
            ownerSpaceId = ownerSpaceId,
            deviceId = deviceId
        )
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

    /**
     * Attempt to start the vault EC2 instance and wait for it to become ready.
     *
     * @return true if vault was started successfully and is running
     */
    private suspend fun attemptVaultStart(): Boolean {
        _connectionState.value = AutoConnectState.StartingVault

        // Try to start the vault
        val startResult = vaultLifecycleClient.startVault()
        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()
            Log.e(TAG, "Failed to start vault: ${error?.message}")
            return false
        }

        val startResponse = startResult.getOrThrow()
        Log.i(TAG, "Vault start response: ${startResponse.status}")

        // If already running, we're good
        if (startResponse.isAlreadyRunning) {
            Log.i(TAG, "Vault is already running")
            return true
        }

        // If starting, wait for it to become ready
        if (startResponse.isStarting) {
            Log.i(TAG, "Vault is starting - waiting for it to become ready")
            return waitForVaultReady()
        }

        // Unknown status
        Log.w(TAG, "Unexpected vault start status: ${startResponse.status}")
        return false
    }

    /**
     * Poll vault status until it's running or timeout.
     *
     * @return true if vault became ready within timeout
     */
    private suspend fun waitForVaultReady(): Boolean {
        _connectionState.value = AutoConnectState.WaitingForVault

        val startTime = System.currentTimeMillis()
        val deadline = startTime + VAULT_START_WAIT_MS

        while (System.currentTimeMillis() < deadline) {
            delay(VAULT_POLL_INTERVAL_MS)

            val statusResult = vaultLifecycleClient.getVaultStatus()
            if (statusResult.isFailure) {
                Log.w(TAG, "Failed to get vault status: ${statusResult.exceptionOrNull()?.message}")
                continue // Keep polling
            }

            val status = statusResult.getOrThrow()
            Log.d(TAG, "Vault status: ${status.instanceStatus}")

            if (status.isVaultRunning) {
                Log.i(TAG, "Vault is now running")
                // Give the vault-manager a moment to fully initialize
                delay(2000)
                return true
            }

            if (status.isVaultStopped) {
                Log.w(TAG, "Vault stopped unexpectedly")
                return false
            }

            // Still pending/starting, keep waiting
        }

        Log.w(TAG, "Timeout waiting for vault to start")
        return false
    }
}
