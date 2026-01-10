package com.vettid.app.ui.recovery

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.RestoreAuthenticateClient
import com.vettid.app.core.network.CredentialBackupInfo
import com.vettid.app.core.network.RecoveryStatusResponse
import com.vettid.app.core.network.RestoreVaultBootstrap
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.recovery.QrRecoveryClient
import com.vettid.app.core.recovery.RecoveryQrCode
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.ProteanCredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State for the Protean Credential recovery screen.
 */
sealed class ProteanRecoveryState {
    /** Initial state - entering email and backup PIN */
    data object EnteringCredentials : ProteanRecoveryState()

    /** Scanning QR code from Account Portal */
    data object ScanningQrCode : ProteanRecoveryState()

    /** Processing scanned QR code */
    data object ProcessingQrCode : ProteanRecoveryState()

    /** Submitting recovery request */
    data object Submitting : ProteanRecoveryState()

    /** Recovery requested, waiting for 24-hour delay */
    data class Pending(
        val recoveryId: String,
        val availableAt: String,
        val remainingSeconds: Long
    ) : ProteanRecoveryState()

    /** Recovery is ready - user needs to enter password to authenticate */
    data class ReadyForAuthentication(
        val recoveryId: String,
        val credentialBackup: CredentialBackupInfo,
        val vaultBootstrap: RestoreVaultBootstrap
    ) : ProteanRecoveryState()

    /** Authenticating with vault via NATS */
    data object Authenticating : ProteanRecoveryState()

    /** Recovery complete */
    data object Complete : ProteanRecoveryState()

    /** Recovery was cancelled */
    data object Cancelled : ProteanRecoveryState()

    /** Error occurred */
    data class Error(val message: String, val canRetry: Boolean = true) : ProteanRecoveryState()
}

/**
 * ViewModel for Protean Credential recovery with 24-hour security delay.
 *
 * Recovery Flow (Email/PIN):
 * 1. User enters email and backup PIN
 * 2. Request sent to server, 24-hour countdown starts
 * 3. User can check status or cancel during the wait
 * 4. After 24 hours, user confirms restore and gets bootstrap NATS credentials
 * 5. User enters password to authenticate via NATS
 * 6. Vault verifies password and issues full NATS credentials
 * 7. Credential is imported into ProteanCredentialManager
 *
 * Recovery Flow (QR Code):
 * 1. User initiates recovery on Account Portal
 * 2. After 24 hours, Account Portal displays QR code
 * 3. User scans QR code containing recovery token and NATS credentials
 * 4. App connects via NATS and exchanges token for new credentials
 * 5. Credential is imported into ProteanCredentialManager
 */
@HiltViewModel
class ProteanRecoveryViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val proteanCredentialManager: ProteanCredentialManager,
    private val restoreAuthenticateClient: RestoreAuthenticateClient,
    private val qrRecoveryClient: QrRecoveryClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    companion object {
        private const val TAG = "ProteanRecoveryVM"
    }

    private val _state = MutableStateFlow<ProteanRecoveryState>(ProteanRecoveryState.EnteringCredentials)
    val state: StateFlow<ProteanRecoveryState> = _state.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _backupPin = MutableStateFlow("")
    val backupPin: StateFlow<String> = _backupPin.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isValidInput = MutableStateFlow(false)
    val isValidInput: StateFlow<Boolean> = _isValidInput.asStateFlow()

    private val _isValidPassword = MutableStateFlow(false)
    val isValidPassword: StateFlow<Boolean> = _isValidPassword.asStateFlow()

    private var currentRecoveryId: String? = null
    private var statusPollingJob: Job? = null

    fun setEmail(value: String) {
        _email.value = value.trim()
        validateInput()
    }

    fun setBackupPin(value: String) {
        // Only allow digits, max 6 characters
        val filtered = value.filter { it.isDigit() }.take(6)
        _backupPin.value = filtered
        validateInput()
    }

    fun setPassword(value: String) {
        _password.value = value
        _isValidPassword.value = value.isNotEmpty()
    }

    private fun validateInput() {
        val emailValid = _email.value.contains("@") && _email.value.contains(".")
        val pinValid = _backupPin.value.length == 6
        _isValidInput.value = emailValid && pinValid
    }

    /**
     * Request credential recovery.
     * This initiates the 24-hour security delay.
     */
    fun requestRecovery() {
        if (!_isValidInput.value) return

        viewModelScope.launch {
            _state.value = ProteanRecoveryState.Submitting

            vaultServiceClient.requestRecovery(
                email = _email.value,
                backupPin = _backupPin.value
            ).fold(
                onSuccess = { response ->
                    currentRecoveryId = response.recoveryId
                    _state.value = ProteanRecoveryState.Pending(
                        recoveryId = response.recoveryId,
                        availableAt = response.availableAt,
                        remainingSeconds = calculateRemainingSeconds(response.availableAt)
                    )
                    startStatusPolling(response.recoveryId)
                },
                onFailure = { error ->
                    _state.value = ProteanRecoveryState.Error(
                        message = error.message ?: "Failed to request recovery",
                        canRetry = true
                    )
                }
            )
        }
    }

    /**
     * Check recovery status manually.
     */
    fun checkStatus() {
        val recoveryId = currentRecoveryId ?: return

        viewModelScope.launch {
            vaultServiceClient.getRecoveryStatus(recoveryId).fold(
                onSuccess = { response ->
                    handleStatusResponse(response)
                },
                onFailure = { error ->
                    // Don't change state for status check failures
                }
            )
        }
    }

    /**
     * Resume a pending recovery (e.g., after app restart).
     */
    fun resumeRecovery(recoveryId: String) {
        currentRecoveryId = recoveryId
        viewModelScope.launch {
            vaultServiceClient.getRecoveryStatus(recoveryId).fold(
                onSuccess = { response ->
                    handleStatusResponse(response)
                    if (response.status == "pending") {
                        startStatusPolling(recoveryId)
                    }
                },
                onFailure = { error ->
                    _state.value = ProteanRecoveryState.Error(
                        message = "Failed to resume recovery: ${error.message}",
                        canRetry = false
                    )
                }
            )
        }
    }

    private fun handleStatusResponse(response: RecoveryStatusResponse) {
        when (response.status) {
            "pending" -> {
                _state.value = ProteanRecoveryState.Pending(
                    recoveryId = response.recoveryId,
                    availableAt = response.availableAt,
                    remainingSeconds = response.remainingSeconds
                )
            }
            "ready" -> {
                stopStatusPolling()
                // When ready, call confirmRestore to get bootstrap credentials
                confirmRestore(response.recoveryId)
            }
            "cancelled" -> {
                stopStatusPolling()
                _state.value = ProteanRecoveryState.Cancelled
            }
            "expired" -> {
                stopStatusPolling()
                _state.value = ProteanRecoveryState.Error(
                    message = "Recovery request has expired. Please start a new recovery.",
                    canRetry = false
                )
            }
        }
    }

    /**
     * Confirm restore and get bootstrap credentials for NATS authentication.
     */
    private fun confirmRestore(recoveryId: String) {
        viewModelScope.launch {
            vaultServiceClient.confirmRestore(recoveryId).fold(
                onSuccess = { response ->
                    if (response.success) {
                        _state.value = ProteanRecoveryState.ReadyForAuthentication(
                            recoveryId = recoveryId,
                            credentialBackup = response.credentialBackup,
                            vaultBootstrap = response.vaultBootstrap
                        )
                    } else {
                        _state.value = ProteanRecoveryState.Error(
                            message = response.message,
                            canRetry = true
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = ProteanRecoveryState.Error(
                        message = error.message ?: "Failed to confirm restore",
                        canRetry = true
                    )
                }
            )
        }
    }

    /**
     * Cancel the pending recovery request.
     */
    fun cancelRecovery() {
        val recoveryId = currentRecoveryId ?: return

        viewModelScope.launch {
            vaultServiceClient.cancelRecovery(recoveryId).fold(
                onSuccess = {
                    stopStatusPolling()
                    currentRecoveryId = null
                    _state.value = ProteanRecoveryState.Cancelled
                },
                onFailure = { error ->
                    // Show error but don't change state
                }
            )
        }
    }

    /**
     * Authenticate with password via NATS and import the recovered credential.
     *
     * This method:
     * 1. Connects to NATS with bootstrap credentials
     * 2. Sends authenticate request with encrypted credential and password hash
     * 3. On success, stores the credential and full NATS credentials
     */
    fun authenticateWithPassword() {
        val currentState = _state.value
        if (currentState !is ProteanRecoveryState.ReadyForAuthentication) return
        if (!_isValidPassword.value) return

        viewModelScope.launch {
            _state.value = ProteanRecoveryState.Authenticating

            // Get password salt - this should be stored during initial enrollment
            // For recovery, we need to get it from the backup or use a default
            val passwordSalt = credentialStore.getPasswordSaltBytes()
                ?: run {
                    _state.value = ProteanRecoveryState.Error(
                        message = "Password salt not found. Please contact support.",
                        canRetry = false
                    )
                    return@launch
                }

            // Generate device ID for this restore session
            val deviceId = UUID.randomUUID().toString()

            restoreAuthenticateClient.authenticate(
                bootstrap = currentState.vaultBootstrap,
                encryptedCredential = currentState.credentialBackup.encryptedCredential,
                keyId = currentState.credentialBackup.keyId,
                password = _password.value,
                passwordSalt = passwordSalt,
                deviceId = deviceId
            ).fold(
                onSuccess = { response ->
                    if (response.success && response.credentials != null) {
                        // Import the recovered credential
                        proteanCredentialManager.importRecoveredCredential(
                            credentialBlob = currentState.credentialBackup.encryptedCredential,
                            userGuid = response.userGuid ?: "",
                            version = response.credentialVersion ?: 1
                        )

                        // Store full NATS credentials for future connections
                        credentialStore.storeFullNatsCredentials(
                            credentials = response.credentials,
                            ownerSpace = response.ownerSpace,
                            messageSpace = response.messageSpace,
                            credentialId = response.credentialId,
                            ttlSeconds = 604800L // 7 days default
                        )

                        currentRecoveryId = null
                        _password.value = ""
                        _state.value = ProteanRecoveryState.Complete
                    } else {
                        _state.value = ProteanRecoveryState.Error(
                            message = response.message.ifEmpty { "Authentication failed" },
                            canRetry = true
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = ProteanRecoveryState.Error(
                        message = error.message ?: "Failed to authenticate",
                        canRetry = true
                    )
                }
            )
        }
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        stopStatusPolling()
        currentRecoveryId = null
        _email.value = ""
        _backupPin.value = ""
        _password.value = ""
        _isValidInput.value = false
        _isValidPassword.value = false
        _state.value = ProteanRecoveryState.EnteringCredentials
    }

    /**
     * Go back to the authentication step from an error state.
     * Keeps the bootstrap info so user can retry with correct password.
     */
    fun retryAuthentication() {
        val currentState = _state.value
        if (currentState is ProteanRecoveryState.Error) {
            // Try to restore to ReadyForAuthentication if we have the info
            currentRecoveryId?.let { recoveryId ->
                confirmRestore(recoveryId)
            } ?: run {
                reset()
            }
        }
    }

    /**
     * Go back from cancelled/error state to try again.
     */
    fun tryAgain() {
        reset()
    }

    // MARK: - QR Code Recovery

    /**
     * Start QR code scanning mode.
     */
    fun startQrScanning() {
        _state.value = ProteanRecoveryState.ScanningQrCode
    }

    /**
     * Cancel QR code scanning and return to initial state.
     */
    fun cancelQrScanning() {
        _state.value = ProteanRecoveryState.EnteringCredentials
    }

    /**
     * Handle scanned QR code content.
     */
    fun onQrCodeScanned(content: String) {
        Log.d(TAG, "QR code scanned, validating...")

        // Validate QR code format
        if (!RecoveryQrCode.isRecoveryQrCode(content)) {
            Log.w(TAG, "Invalid QR code format")
            _state.value = ProteanRecoveryState.Error(
                message = "Invalid QR code. Please scan a VettID recovery QR code from the Account Portal.",
                canRetry = true
            )
            return
        }

        // Parse QR code
        val recoveryQr = RecoveryQrCode.parse(content)
        if (recoveryQr == null) {
            Log.w(TAG, "Failed to parse QR code")
            _state.value = ProteanRecoveryState.Error(
                message = "Could not parse QR code. Please try again.",
                canRetry = true
            )
            return
        }

        // Process the recovery
        processQrRecovery(recoveryQr)
    }

    /**
     * Handle QR code scanning error.
     */
    fun onQrScanError(error: String) {
        Log.e(TAG, "QR scan error: $error")
        _state.value = ProteanRecoveryState.Error(
            message = error,
            canRetry = true
        )
    }

    /**
     * Process QR code recovery - exchange token for credentials.
     */
    private fun processQrRecovery(recoveryQr: RecoveryQrCode) {
        viewModelScope.launch {
            _state.value = ProteanRecoveryState.ProcessingQrCode

            val deviceId = UUID.randomUUID().toString()

            qrRecoveryClient.exchangeRecoveryToken(
                recoveryQr = recoveryQr,
                deviceId = deviceId
            ).fold(
                onSuccess = { result ->
                    if (result.success && result.credentials != null) {
                        Log.i(TAG, "QR recovery successful")

                        // Import the recovered credential
                        result.sealedCredential?.let { sealedCredential ->
                            proteanCredentialManager.importRecoveredCredential(
                                credentialBlob = sealedCredential,
                                userGuid = result.userGuid ?: "",
                                version = result.credentialVersion ?: 1
                            )
                        }

                        // Store full NATS credentials
                        credentialStore.storeFullNatsCredentials(
                            credentials = result.credentials,
                            ownerSpace = result.ownerSpace,
                            messageSpace = result.messageSpace,
                            credentialId = result.credentialId,
                            ttlSeconds = 604800L // 7 days default
                        )

                        _state.value = ProteanRecoveryState.Complete
                    } else {
                        Log.w(TAG, "QR recovery failed: ${result.message}")
                        _state.value = ProteanRecoveryState.Error(
                            message = result.message.ifEmpty { "Recovery failed" },
                            canRetry = true
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "QR recovery error", error)
                    _state.value = ProteanRecoveryState.Error(
                        message = error.message ?: "Failed to recover credential",
                        canRetry = true
                    )
                }
            )
        }
    }

    private fun startStatusPolling(recoveryId: String) {
        stopStatusPolling()
        statusPollingJob = viewModelScope.launch {
            while (true) {
                delay(60_000) // Poll every minute
                vaultServiceClient.getRecoveryStatus(recoveryId).fold(
                    onSuccess = { response ->
                        handleStatusResponse(response)
                        if (response.status != "pending") {
                            return@launch
                        }
                    },
                    onFailure = { /* Continue polling */ }
                )
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun calculateRemainingSeconds(availableAt: String): Long {
        // Parse ISO 8601 timestamp and calculate remaining seconds
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val targetTime = format.parse(availableAt)?.time ?: return 0
            val remaining = (targetTime - System.currentTimeMillis()) / 1000
            maxOf(0, remaining)
        } catch (e: Exception) {
            0
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStatusPolling()
    }
}
