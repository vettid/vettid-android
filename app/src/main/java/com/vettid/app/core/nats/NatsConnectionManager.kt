package com.vettid.app.core.nats

import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the NATS connection lifecycle including:
 * - Account creation and status
 * - Token generation and refresh
 * - Connection state management
 * - Automatic reconnection
 */
@Singleton
class NatsConnectionManager @Inject constructor(
    private val natsClient: NatsClient,
    private val natsApiClient: NatsApiClient,
    private val sharedPreferences: SharedPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val gson = Gson()

    private val _connectionState = MutableStateFlow<NatsConnectionState>(NatsConnectionState.Disconnected)
    val connectionState: StateFlow<NatsConnectionState> = _connectionState.asStateFlow()

    private val _account = MutableStateFlow<NatsAccount?>(null)
    val account: StateFlow<NatsAccount?> = _account.asStateFlow()

    private var currentCredentials: NatsCredentials? = null

    /**
     * Check if NATS account exists for the user.
     */
    suspend fun checkAccountStatus(authToken: String): Result<NatsStatus> {
        return natsApiClient.getNatsStatus(authToken).also { result ->
            result.getOrNull()?.let { status ->
                _account.value = status.account
            }
        }
    }

    /**
     * Create a new NATS account for the user.
     */
    suspend fun createAccount(authToken: String): Result<NatsAccount> {
        _connectionState.value = NatsConnectionState.CreatingAccount

        return natsApiClient.createNatsAccount(authToken)
            .onSuccess { account ->
                _account.value = account
                android.util.Log.i(TAG, "NATS account created: ${account.ownerSpaceId}")
            }
            .onFailure { error ->
                _connectionState.value = NatsConnectionState.Error(
                    "Failed to create NATS account: ${error.message}"
                )
            }
    }

    /**
     * Generate a new token and connect to NATS.
     */
    suspend fun connect(authToken: String, deviceId: String? = null): Result<Unit> = mutex.withLock {
        _connectionState.value = NatsConnectionState.Connecting

        // Check if we have valid cached credentials
        getCachedCredentials()?.let { cached ->
            if (!cached.needsRefresh()) {
                android.util.Log.d(TAG, "Using cached NATS credentials")
                return@withLock connectWithCredentials(cached)
            }
        }

        // Generate new token
        val tokenResult = natsApiClient.generateToken(
            authToken = authToken,
            clientType = NatsClientType.APP,
            deviceId = deviceId
        )

        return tokenResult.fold(
            onSuccess = { credentials ->
                cacheCredentials(credentials)
                connectWithCredentials(credentials)
            },
            onFailure = { error ->
                _connectionState.value = NatsConnectionState.Error(
                    "Failed to generate NATS token: ${error.message}"
                )
                Result.failure(error)
            }
        )
    }

    /**
     * Connect using existing credentials.
     */
    private suspend fun connectWithCredentials(credentials: NatsCredentials): Result<Unit> {
        return natsClient.connect(credentials)
            .onSuccess {
                currentCredentials = credentials
                _connectionState.value = NatsConnectionState.Connected(credentials)
                android.util.Log.i(TAG, "Connected to NATS")
            }
            .onFailure { error ->
                _connectionState.value = NatsConnectionState.Error(
                    "Failed to connect to NATS: ${error.message}"
                )
            }
    }

    /**
     * Disconnect from NATS.
     */
    suspend fun disconnect() = mutex.withLock {
        natsClient.disconnect()
        currentCredentials = null
        jetStreamClient = null  // Clear JetStream client on disconnect
        _connectionState.value = NatsConnectionState.Disconnected
        android.util.Log.i(TAG, "Disconnected from NATS")
    }

    /**
     * Refresh token if needed and reconnect.
     */
    suspend fun refreshTokenIfNeeded(authToken: String): Result<Unit> = mutex.withLock {
        val credentials = currentCredentials ?: return Result.failure(
            NatsException("No active NATS connection")
        )

        if (!credentials.needsRefresh()) {
            return Result.success(Unit)
        }

        android.util.Log.i(TAG, "Refreshing NATS token")
        _connectionState.value = NatsConnectionState.Refreshing

        // Disconnect current connection
        natsClient.disconnect()

        // Generate new token
        val tokenResult = natsApiClient.generateToken(
            authToken = authToken,
            clientType = NatsClientType.APP
        )

        return tokenResult.fold(
            onSuccess = { newCredentials ->
                cacheCredentials(newCredentials)
                connectWithCredentials(newCredentials)
            },
            onFailure = { error ->
                // Try to reconnect with old credentials
                _connectionState.value = NatsConnectionState.Error(
                    "Failed to refresh token: ${error.message}"
                )
                Result.failure(error)
            }
        )
    }

    /**
     * Revoke current token.
     */
    suspend fun revokeCurrentToken(authToken: String): Result<Unit> {
        val tokenId = currentCredentials?.tokenId ?: return Result.success(Unit)

        return natsApiClient.revokeToken(authToken, tokenId)
            .onSuccess {
                clearCachedCredentials()
                disconnect()
            }
    }

    /**
     * Get current connection status.
     */
    fun isConnected(): Boolean = natsClient.isConnected

    /**
     * Get OwnerSpace ID for pub/sub.
     */
    fun getOwnerSpaceId(): String? = _account.value?.ownerSpaceId

    /**
     * Get MessageSpace ID for pub/sub.
     */
    fun getMessageSpaceId(): String? = _account.value?.messageSpaceId

    /**
     * Get the underlying NATS client for pub/sub operations.
     */
    fun getNatsClient(): NatsClient = natsClient

    // Shared JetStream client for all components
    private var jetStreamClient: JetStreamNatsClient? = null

    /**
     * Get or create a shared JetStreamNatsClient.
     * Uses the existing NATS connection to avoid race conditions.
     * All components should use this instead of creating their own JetStreamNatsClient.
     */
    fun getJetStreamClient(): JetStreamNatsClient? {
        if (!natsClient.isConnected) {
            return null
        }

        // Lazy initialize JetStream client using the shared AndroidNatsClient
        return jetStreamClient ?: run {
            val jsClient = JetStreamNatsClient()
            jsClient.initialize(natsClient.getAndroidClient())
            jetStreamClient = jsClient
            jsClient
        }
    }

    /**
     * Set account info from stored credentials.
     * Used by NatsAutoConnector when connecting with stored bootstrap credentials.
     *
     * @param ownerSpaceId The owner space ID from stored credentials
     * @param messageSpaceId The message space ID from stored credentials
     * @param endpoint The NATS endpoint
     */
    fun setAccountFromStored(ownerSpaceId: String, messageSpaceId: String?, endpoint: String) {
        _account.value = NatsAccount(
            ownerSpaceId = ownerSpaceId,
            messageSpaceId = messageSpaceId ?: "",
            natsEndpoint = endpoint,
            status = NatsAccountStatus.ACTIVE
        )
        android.util.Log.i(TAG, "Account set from stored credentials: $ownerSpaceId")
    }

    /**
     * Update connection state after external connection (e.g., from NatsAutoConnector).
     *
     * @param credentials The credentials used for connection
     */
    fun setConnectedState(credentials: NatsCredentials) {
        currentCredentials = credentials
        _connectionState.value = NatsConnectionState.Connected(credentials)
    }

    // MARK: - Credential Caching

    private fun cacheCredentials(credentials: NatsCredentials) {
        val json = gson.toJson(credentials.toCacheModel())
        sharedPreferences.edit()
            .putString(KEY_NATS_CREDENTIALS, json)
            .apply()
    }

    private fun getCachedCredentials(): NatsCredentials? {
        val json = sharedPreferences.getString(KEY_NATS_CREDENTIALS, null) ?: return null
        return try {
            val model = gson.fromJson(json, NatsCredentialsCacheModel::class.java)
            model.toCredentials()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse cached credentials", e)
            null
        }
    }

    private fun clearCachedCredentials() {
        sharedPreferences.edit()
            .remove(KEY_NATS_CREDENTIALS)
            .apply()
    }

    companion object {
        private const val TAG = "NatsConnectionManager"
        private const val KEY_NATS_CREDENTIALS = "nats_credentials"
    }
}

/**
 * NATS connection state.
 */
sealed class NatsConnectionState {
    object Disconnected : NatsConnectionState()
    object CreatingAccount : NatsConnectionState()
    object Connecting : NatsConnectionState()
    object Refreshing : NatsConnectionState()
    data class Connected(val credentials: NatsCredentials) : NatsConnectionState()
    data class Error(val message: String) : NatsConnectionState()
}

/**
 * Cache model for NATS credentials (for Gson serialization).
 */
private data class NatsCredentialsCacheModel(
    val tokenId: String,
    val jwt: String,
    val seed: String,
    val endpoint: String,
    val expiresAtEpoch: Long,
    val publishPerms: List<String>,
    val subscribePerms: List<String>
)

private fun NatsCredentials.toCacheModel() = NatsCredentialsCacheModel(
    tokenId = tokenId,
    jwt = jwt,
    seed = seed,
    endpoint = endpoint,
    expiresAtEpoch = expiresAt.epochSecond,
    publishPerms = permissions.publish,
    subscribePerms = permissions.subscribe
)

private fun NatsCredentialsCacheModel.toCredentials() = NatsCredentials(
    tokenId = tokenId,
    jwt = jwt,
    seed = seed,
    endpoint = endpoint,
    expiresAt = Instant.ofEpochSecond(expiresAtEpoch),
    permissions = NatsPermissions(
        publish = publishPerms,
        subscribe = subscribePerms
    )
)
