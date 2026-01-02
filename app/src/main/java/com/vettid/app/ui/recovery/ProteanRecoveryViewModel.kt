package com.vettid.app.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.RecoveryStatusResponse
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.ProteanCredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the Protean Credential recovery screen.
 */
sealed class ProteanRecoveryState {
    /** Initial state - entering email and backup PIN */
    data object EnteringCredentials : ProteanRecoveryState()

    /** Submitting recovery request */
    data object Submitting : ProteanRecoveryState()

    /** Recovery requested, waiting for 24-hour delay */
    data class Pending(
        val recoveryId: String,
        val availableAt: String,
        val remainingSeconds: Long
    ) : ProteanRecoveryState()

    /** Recovery is ready to download */
    data class Ready(val recoveryId: String) : ProteanRecoveryState()

    /** Downloading recovered credential */
    data object Downloading : ProteanRecoveryState()

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
 * Recovery Flow:
 * 1. User enters email and backup PIN
 * 2. Request sent to server, 24-hour countdown starts
 * 3. User can check status or cancel during the wait
 * 4. After 24 hours, credential can be downloaded
 * 5. Credential is imported into ProteanCredentialManager
 */
@HiltViewModel
class ProteanRecoveryViewModel @Inject constructor(
    private val vaultServiceClient: VaultServiceClient,
    private val proteanCredentialManager: ProteanCredentialManager
) : ViewModel() {

    private val _state = MutableStateFlow<ProteanRecoveryState>(ProteanRecoveryState.EnteringCredentials)
    val state: StateFlow<ProteanRecoveryState> = _state.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _backupPin = MutableStateFlow("")
    val backupPin: StateFlow<String> = _backupPin.asStateFlow()

    private val _isValidInput = MutableStateFlow(false)
    val isValidInput: StateFlow<Boolean> = _isValidInput.asStateFlow()

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
                _state.value = ProteanRecoveryState.Ready(response.recoveryId)
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
     * Download and import the recovered credential.
     */
    fun downloadCredential() {
        val recoveryId = currentRecoveryId ?: return

        viewModelScope.launch {
            _state.value = ProteanRecoveryState.Downloading

            vaultServiceClient.downloadRecoveredCredential(recoveryId).fold(
                onSuccess = { response ->
                    // Import the recovered credential
                    proteanCredentialManager.importRecoveredCredential(
                        credentialBlob = response.credentialBlob,
                        userGuid = response.userGuid,
                        version = response.version
                    )
                    currentRecoveryId = null
                    _state.value = ProteanRecoveryState.Complete
                },
                onFailure = { error ->
                    _state.value = ProteanRecoveryState.Error(
                        message = error.message ?: "Failed to download credential",
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
        _isValidInput.value = false
        _state.value = ProteanRecoveryState.EnteringCredentials
    }

    /**
     * Go back from cancelled/error state to try again.
     */
    fun tryAgain() {
        reset()
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
