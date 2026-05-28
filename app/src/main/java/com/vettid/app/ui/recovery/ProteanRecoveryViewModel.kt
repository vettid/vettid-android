package com.vettid.app.ui.recovery

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.recovery.QrRecoveryClient
import com.vettid.app.core.recovery.RecoveryQrCode
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.ProteanCredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State for the Protean Credential recovery screen.
 *
 * QR-only flow: the email + 24h delay + status polling + cancel/expire
 * branches were removed. The Account Portal issues a one-shot recovery
 * QR code after its own delay, and this screen exchanges it for fresh
 * NATS credentials.
 */
sealed class ProteanRecoveryState {
    /** Initial / landing state — shows the Scan QR Code CTA. */
    data object EnteringCredentials : ProteanRecoveryState()

    /** Scanning QR code from Account Portal */
    data object ScanningQrCode : ProteanRecoveryState()

    /** Processing scanned QR code */
    data object ProcessingQrCode : ProteanRecoveryState()

    /** Recovery complete */
    data object Complete : ProteanRecoveryState()

    /** Error occurred */
    data class Error(val message: String, val canRetry: Boolean = true) : ProteanRecoveryState()
}

/**
 * ViewModel for QR-based Protean Credential recovery.
 *
 * 1. User initiates recovery on Account Portal and waits the 24-hour delay there.
 * 2. After 24 hours, Account Portal displays a recovery QR code.
 * 3. User scans QR code containing recovery token and NATS credentials.
 * 4. App connects via NATS and exchanges token for new credentials.
 * 5. Credential is imported into ProteanCredentialManager.
 */
@HiltViewModel
class ProteanRecoveryViewModel @Inject constructor(
    private val proteanCredentialManager: ProteanCredentialManager,
    private val qrRecoveryClient: QrRecoveryClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    companion object {
        private const val TAG = "ProteanRecoveryVM"
    }

    private val _state = MutableStateFlow<ProteanRecoveryState>(ProteanRecoveryState.EnteringCredentials)
    val state: StateFlow<ProteanRecoveryState> = _state.asStateFlow()

    /**
     * Reset back to the QR landing state.
     */
    fun tryAgain() {
        _state.value = ProteanRecoveryState.EnteringCredentials
    }

    // MARK: - QR Code Recovery

    /**
     * Start QR code scanning mode.
     */
    fun startQrScanning() {
        _state.value = ProteanRecoveryState.ScanningQrCode
    }

    /**
     * Cancel QR code scanning and return to landing state.
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
}
