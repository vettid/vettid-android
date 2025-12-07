package com.vettid.app.features.auth

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.network.*
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.StoredCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for authentication flow
 *
 * Implements the Protean Credential authentication as per vault-services-api.yaml:
 * 1. POST /vault/auth/request - Request action token, get LAT for verification
 * 2. Verify LAT matches stored LAT (phishing protection)
 * 3. POST /vault/auth/execute - Submit encrypted credentials
 * 4. Handle credential rotation and key replenishment
 */
@HiltViewModel
class AuthenticationViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val cryptoManager: CryptoManager,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow<AuthenticationState>(AuthenticationState.Initial)
    val state: StateFlow<AuthenticationState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AuthenticationEffect>()
    val effects: SharedFlow<AuthenticationEffect> = _effects.asSharedFlow()

    // Store action for retry
    private var currentAction: String? = null

    fun onEvent(event: AuthenticationEvent) {
        when (event) {
            is AuthenticationEvent.StartAuth -> startAuthentication(event.action)
            is AuthenticationEvent.ConfirmLAT -> confirmLAT()
            is AuthenticationEvent.RejectLAT -> rejectLAT()
            is AuthenticationEvent.PasswordChanged -> updatePassword(event.password)
            is AuthenticationEvent.SubmitPassword -> submitPassword()
            is AuthenticationEvent.Retry -> retry()
            is AuthenticationEvent.Cancel -> cancel()
            is AuthenticationEvent.Proceed -> proceed()
        }
    }

    private fun startAuthentication(action: String) {
        currentAction = action

        viewModelScope.launch {
            _state.value = AuthenticationState.RequestingAction(action, 0.1f)

            // Get stored credential
            val credential = credentialStore.getStoredCredential()
            if (credential == null) {
                _state.value = AuthenticationState.Error(
                    message = "No stored credential found",
                    code = AuthErrorCode.CREDENTIAL_NOT_FOUND,
                    retryable = false
                )
                return@launch
            }

            _state.value = AuthenticationState.RequestingAction(action, 0.3f)

            // Request auth action
            val result = vaultServiceClient.authRequest(
                userGuid = credential.userGuid,
                action = action
            )

            result.fold(
                onSuccess = { response ->
                    _state.value = AuthenticationState.RequestingAction(action, 1.0f)

                    // Get stored LAT for comparison
                    val storedLatToken = credential.latToken

                    // Check if LAT matches
                    val latMatch = cryptoManager.verifyLat(
                        receivedLatHex = response.lat.token,
                        storedLatHex = storedLatToken
                    )

                    _state.value = AuthenticationState.VerifyingLAT(
                        authSessionId = response.authSessionId,
                        serverLat = response.lat,
                        storedLatToken = storedLatToken,
                        endpoint = response.endpoint,
                        latMatch = latMatch
                    )

                    if (!latMatch) {
                        // Emit phishing warning
                        _effects.emit(AuthenticationEffect.PhishingWarning)
                    }
                },
                onFailure = { error ->
                    val errorCode = when {
                        error is VaultServiceException && error.code == 409 ->
                            AuthErrorCode.CONCURRENT_SESSION
                        error is VaultServiceException && error.code == 404 ->
                            AuthErrorCode.CREDENTIAL_NOT_FOUND
                        else -> AuthErrorCode.NETWORK_ERROR
                    }

                    _state.value = AuthenticationState.Error(
                        message = error.message ?: "Failed to request authentication",
                        code = errorCode,
                        retryable = errorCode != AuthErrorCode.CREDENTIAL_NOT_FOUND,
                        previousState = AuthenticationState.RequestingAction(action)
                    )
                }
            )
        }
    }

    private fun confirmLAT() {
        val currentState = _state.value
        if (currentState !is AuthenticationState.VerifyingLAT) return

        if (!currentState.latMatch) {
            // User confirmed despite mismatch - this is dangerous
            viewModelScope.launch {
                _effects.emit(AuthenticationEffect.ShowError(
                    "LAT mismatch detected. This may be a phishing attack."
                ))
            }
            return
        }

        // LAT verified - proceed to password entry
        _state.value = AuthenticationState.EnteringPassword(
            authSessionId = currentState.authSessionId,
            endpoint = currentState.endpoint
        )
    }

    private fun rejectLAT() {
        val currentState = _state.value
        if (currentState !is AuthenticationState.VerifyingLAT) return

        viewModelScope.launch {
            _effects.emit(AuthenticationEffect.ShowError(
                "Authentication cancelled due to possible security concern"
            ))
            _effects.emit(AuthenticationEffect.NavigateBack)
        }

        _state.value = AuthenticationState.Initial
    }

    private fun updatePassword(password: String) {
        val currentState = _state.value
        if (currentState !is AuthenticationState.EnteringPassword) return

        _state.value = currentState.copy(password = password, error = null)
    }

    private fun submitPassword() {
        val currentState = _state.value
        if (currentState !is AuthenticationState.EnteringPassword) return
        if (currentState.isSubmitting) return

        val password = currentState.password

        // Validate password not empty
        if (password.isEmpty()) {
            _state.value = currentState.copy(error = "Please enter your password")
            return
        }

        viewModelScope.launch {
            _state.value = currentState.copy(isSubmitting = true, error = null)

            // Get stored credential
            val credential = credentialStore.getStoredCredential()
            if (credential == null) {
                _state.value = AuthenticationState.Error(
                    message = "Credential not found",
                    code = AuthErrorCode.CREDENTIAL_NOT_FOUND,
                    retryable = false
                )
                return@launch
            }

            // Get a transaction key from the pool
            val utkPool = credentialStore.getUtkPool()
            if (utkPool.isEmpty()) {
                _state.value = AuthenticationState.Error(
                    message = "No transaction keys available",
                    code = AuthErrorCode.NO_TRANSACTION_KEYS,
                    retryable = false
                )
                return@launch
            }

            val utk = utkPool.first()

            _state.value = AuthenticationState.Executing(
                authSessionId = currentState.authSessionId,
                progress = 0.2f,
                statusMessage = "Encrypting credentials..."
            )

            try {
                // Get password salt
                val salt = credentialStore.getPasswordSaltBytes()
                if (salt == null) {
                    _state.value = AuthenticationState.Error(
                        message = "Password salt not found",
                        code = AuthErrorCode.CREDENTIAL_NOT_FOUND,
                        retryable = false
                    )
                    return@launch
                }

                // Encrypt password hash with transaction key
                val encryptionResult = cryptoManager.encryptPasswordForServer(
                    password = password,
                    salt = salt,
                    utkPublicKeyBase64 = utk.publicKey
                )

                _state.value = AuthenticationState.Executing(
                    authSessionId = currentState.authSessionId,
                    progress = 0.5f,
                    statusMessage = "Sending to server..."
                )

                // Build credential blob for server
                val credentialBlob = CredentialBlob(
                    data = credential.encryptedBlob,
                    version = credential.latVersion,
                    cekVersion = credential.cekVersion
                )

                // Execute authentication
                val result = vaultServiceClient.authExecute(
                    authSessionId = currentState.authSessionId,
                    credentialBlob = credentialBlob,
                    encryptedPasswordHash = encryptionResult.encryptedPasswordHash,
                    transactionKeyId = utk.keyId
                )

                result.fold(
                    onSuccess = { response ->
                        _state.value = AuthenticationState.Executing(
                            authSessionId = currentState.authSessionId,
                            progress = 0.8f,
                            statusMessage = "Updating credentials..."
                        )

                        // Remove used transaction key
                        credentialStore.removeUtk(utk.keyId)

                        // Update credential blob with rotated CEK
                        credentialStore.updateCredentialBlob(
                            encryptedBlob = response.newCredentialBlob.data,
                            cekVersion = response.newCredentialBlob.cekVersion,
                            newLat = response.newLat,
                            newTransactionKeys = response.newTransactionKeys
                        )

                        _effects.emit(AuthenticationEffect.CredentialsRotated)

                        // Check if keys were replenished
                        val replenishedCount = response.newTransactionKeys?.size ?: 0
                        if (replenishedCount > 0) {
                            _effects.emit(AuthenticationEffect.KeysReplenished(replenishedCount))
                        }

                        _state.value = AuthenticationState.Success(
                            actionToken = response.actionToken,
                            message = "Authentication successful",
                            keysReplenished = replenishedCount
                        )
                    },
                    onFailure = { error ->
                        val errorCode = when {
                            error is VaultServiceException && error.code == 401 ->
                                AuthErrorCode.INVALID_CREDENTIALS
                            error is VaultServiceException && error.code == 409 ->
                                AuthErrorCode.SESSION_EXPIRED
                            else -> AuthErrorCode.NETWORK_ERROR
                        }

                        _state.value = AuthenticationState.Error(
                            message = when (errorCode) {
                                AuthErrorCode.INVALID_CREDENTIALS -> "Incorrect password"
                                AuthErrorCode.SESSION_EXPIRED -> "Session expired, please try again"
                                else -> error.message ?: "Authentication failed"
                            },
                            code = errorCode,
                            retryable = errorCode != AuthErrorCode.INVALID_CREDENTIALS,
                            previousState = currentState.copy(password = "", error = null)
                        )
                    }
                )

            } catch (e: Exception) {
                _state.value = AuthenticationState.Error(
                    message = "Encryption failed: ${e.message}",
                    code = AuthErrorCode.UNKNOWN,
                    retryable = true,
                    previousState = currentState.copy(password = "", error = null)
                )
            }
        }
    }

    private fun retry() {
        val currentState = _state.value
        if (currentState !is AuthenticationState.Error) return

        if (!currentState.retryable) return

        // Return to previous state or restart
        currentState.previousState?.let { previousState ->
            when (previousState) {
                is AuthenticationState.RequestingAction -> {
                    currentAction?.let { startAuthentication(it) }
                }
                is AuthenticationState.EnteringPassword -> {
                    _state.value = previousState
                }
                else -> {
                    currentAction?.let { startAuthentication(it) }
                }
            }
        } ?: currentAction?.let { startAuthentication(it) }
    }

    private fun cancel() {
        viewModelScope.launch {
            _effects.emit(AuthenticationEffect.NavigateBack)
        }
        _state.value = AuthenticationState.Initial
        currentAction = null
    }

    private fun proceed() {
        val currentState = _state.value
        if (currentState !is AuthenticationState.Success) return

        viewModelScope.launch {
            _effects.emit(AuthenticationEffect.AuthComplete(currentState.actionToken))
        }
    }

    /**
     * Check if transaction keys need replenishment
     */
    fun checkKeyPoolHealth(): KeyPoolHealth {
        val count = credentialStore.getUtkCount()
        return when {
            count == 0 -> KeyPoolHealth.EMPTY
            count < 5 -> KeyPoolHealth.LOW
            count < 10 -> KeyPoolHealth.MODERATE
            else -> KeyPoolHealth.HEALTHY
        }
    }
}

/**
 * Transaction key pool health status
 */
enum class KeyPoolHealth {
    EMPTY,
    LOW,
    MODERATE,
    HEALTHY
}
