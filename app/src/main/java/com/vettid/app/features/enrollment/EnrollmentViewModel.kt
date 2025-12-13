package com.vettid.app.features.enrollment

import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.network.DeviceInfo
import com.vettid.app.core.network.EnrollmentQRData
import com.vettid.app.core.network.TransactionKeyPublic
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the enrollment flow
 *
 * Manages state transitions through:
 * 1. QR Scanning → 2. Attestation → 3. Password Setup → 4. Finalization
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultServiceClient: VaultServiceClient,
    private val cryptoManager: CryptoManager,
    private val attestationManager: HardwareAttestationManager,
    private val credentialStore: CredentialStore
) : ViewModel() {

    companion object {
        const val MIN_PASSWORD_LENGTH = 12
    }

    /**
     * Get unique device identifier for enrollment
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: java.util.UUID.randomUUID().toString()
    }

    private val _state = MutableStateFlow<EnrollmentState>(EnrollmentState.Initial)
    val state: StateFlow<EnrollmentState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<EnrollmentEffect>()
    val effects: SharedFlow<EnrollmentEffect> = _effects.asSharedFlow()

    // Store transaction keys during enrollment
    private var currentTransactionKeys: List<TransactionKeyPublic> = emptyList()
    private var passwordSalt: ByteArray? = null

    /**
     * Process enrollment events
     */
    fun onEvent(event: EnrollmentEvent) {
        viewModelScope.launch {
            when (event) {
                is EnrollmentEvent.StartScanning -> startScanning()
                is EnrollmentEvent.SwitchToManualEntry -> switchToManualEntry()
                is EnrollmentEvent.SwitchToScanning -> startScanning()
                is EnrollmentEvent.InviteCodeChanged -> updateInviteCode(event.inviteCode)
                is EnrollmentEvent.SubmitInviteCode -> submitManualInviteCode()
                is EnrollmentEvent.QRCodeScanned -> processQRCode(event.qrData)
                is EnrollmentEvent.AttestationComplete -> handleAttestationResult(event.success)
                is EnrollmentEvent.PasswordChanged -> updatePassword(event.password)
                is EnrollmentEvent.ConfirmPasswordChanged -> updateConfirmPassword(event.confirmPassword)
                is EnrollmentEvent.SubmitPassword -> submitPassword()
                is EnrollmentEvent.Retry -> retry()
                is EnrollmentEvent.Cancel -> cancel()
            }
        }
    }

    private fun startScanning() {
        _state.value = EnrollmentState.ScanningQR()
    }

    private fun switchToManualEntry() {
        _state.value = EnrollmentState.ManualEntry()
    }

    private fun updateInviteCode(inviteCode: String) {
        val currentState = _state.value
        if (currentState is EnrollmentState.ManualEntry) {
            _state.value = currentState.copy(inviteCode = inviteCode, error = null)
        }
    }

    private suspend fun submitManualInviteCode() {
        val currentState = _state.value
        if (currentState !is EnrollmentState.ManualEntry) return

        val code = currentState.inviteCode.trim()
        if (code.isEmpty()) {
            _state.value = currentState.copy(error = "Please enter an invitation code")
            return
        }

        // Process the manually entered code (could be JSON or URL)
        processQRCode(code)
    }

    /**
     * Process scanned or manually entered QR code data
     * Parses JSON, extracts API URL, and starts enrollment
     *
     * Flow:
     * 1. Parse QR code JSON
     * 2. Call /vault/enroll/authenticate to get JWT
     * 3. Call /vault/enroll/start to get transaction keys
     * 4. Move to password setup (skipping attestation for now)
     */
    private suspend fun processQRCode(qrData: String) {
        // Try to parse as JSON enrollment data
        val enrollmentData = EnrollmentQRData.parse(qrData)

        if (enrollmentData == null) {
            _state.value = EnrollmentState.Error(
                message = "Invalid QR code format. Please scan a valid VettID enrollment QR code.",
                retryable = true,
                previousState = EnrollmentState.ScanningQR()
            )
            return
        }

        _state.value = EnrollmentState.ProcessingInvite(enrollmentData)

        // Set the API URL from the QR code
        vaultServiceClient.setEnrollmentApiUrl(enrollmentData.apiUrl)

        // Step 1: Authenticate with session_token to get enrollment JWT
        val authResult = vaultServiceClient.enrollAuthenticate(
            sessionToken = enrollmentData.sessionToken,
            deviceId = getDeviceId()
        )

        authResult.fold(
            onSuccess = { authResponse ->
                // Step 2: Call enroll/start to get transaction keys
                val startResult = vaultServiceClient.enrollStart(skipAttestation = true)

                startResult.fold(
                    onSuccess = { startResponse ->
                        currentTransactionKeys = startResponse.transactionKeys

                        // Generate password salt for later use
                        passwordSalt = cryptoManager.generateSalt()

                        // Move directly to password setup (skipping attestation)
                        _state.value = EnrollmentState.SettingPassword(
                            sessionId = authResponse.enrollmentSessionId,
                            transactionKeys = startResponse.transactionKeys,
                            passwordKeyId = startResponse.passwordKeyId
                        )
                    },
                    onFailure = { error ->
                        _state.value = EnrollmentState.Error(
                            message = error.message ?: "Failed to start enrollment",
                            retryable = true,
                            previousState = EnrollmentState.ScanningQR()
                        )
                    }
                )
            },
            onFailure = { error ->
                val errorMessage = if (error.message?.contains("401") == true) {
                    "Enrollment QR code has expired. Please request a new one."
                } else {
                    error.message ?: "Failed to authenticate enrollment"
                }
                _state.value = EnrollmentState.Error(
                    message = errorMessage,
                    retryable = true,
                    previousState = EnrollmentState.ScanningQR()
                )
            }
        )
    }

    /**
     * Perform hardware attestation (optional - can be skipped with skip_attestation=true)
     * Note: Currently not used as we skip attestation during enrollment
     */
    private suspend fun performAttestation(sessionId: String, challenge: ByteArray) {
        try {
            // Update progress
            updateAttestationProgress(0.2f)

            // Generate attestation
            val attestationResult = attestationManager.generateAttestationKey(challenge)
            updateAttestationProgress(0.5f)

            // Submit to server
            val certChain = attestationResult.certificateChain.map { it.encoded }
            updateAttestationProgress(0.7f)

            // Submit attestation (uses JWT token from authenticate step)
            val result = vaultServiceClient.submitAttestation(
                certificateChain = certChain
            )

            result.fold(
                onSuccess = { response ->
                    updateAttestationProgress(1.0f)
                    delay(300) // Brief delay for UI feedback

                    if (response.verified) {
                        // Generate password salt for later use
                        passwordSalt = cryptoManager.generateSalt()

                        // Get passwordKeyId from Attesting state
                        val attestingState = _state.value as? EnrollmentState.Attesting

                        // Move to password setup
                        _state.value = EnrollmentState.SettingPassword(
                            sessionId = sessionId,
                            transactionKeys = currentTransactionKeys,
                            passwordKeyId = attestingState?.passwordKeyId ?: ""
                        )
                    } else {
                        _state.value = EnrollmentState.Error(
                            message = "Device attestation failed. Security level: ${response.securityLevel}",
                            retryable = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = EnrollmentState.Error(
                        message = "Attestation submission failed: ${error.message}",
                        retryable = true
                    )
                }
            )
        } catch (e: Exception) {
            _state.value = EnrollmentState.Error(
                message = "Attestation error: ${e.message}",
                retryable = true
            )
        }
    }

    private fun updateAttestationProgress(progress: Float) {
        val currentState = _state.value
        if (currentState is EnrollmentState.Attesting) {
            _state.value = currentState.copy(progress = progress)
        }
    }

    private fun handleAttestationResult(success: Boolean) {
        // This is called if we need manual attestation handling
        val currentState = _state.value
        if (currentState is EnrollmentState.Attesting && success) {
            passwordSalt = cryptoManager.generateSalt()
            _state.value = EnrollmentState.SettingPassword(
                sessionId = currentState.sessionId,
                transactionKeys = currentState.transactionKeys,
                passwordKeyId = currentState.passwordKeyId
            )
        }
    }

    private fun updatePassword(password: String) {
        val currentState = _state.value
        if (currentState is EnrollmentState.SettingPassword) {
            _state.value = currentState.copy(
                password = password,
                strength = PasswordStrength.calculate(password),
                error = null
            )
        }
    }

    private fun updateConfirmPassword(confirmPassword: String) {
        val currentState = _state.value
        if (currentState is EnrollmentState.SettingPassword) {
            _state.value = currentState.copy(
                confirmPassword = confirmPassword,
                error = null
            )
        }
    }

    private suspend fun submitPassword() {
        val currentState = _state.value
        if (currentState !is EnrollmentState.SettingPassword) return

        // Validate password
        val validationError = validatePassword(currentState.password, currentState.confirmPassword)
        if (validationError != null) {
            _state.value = currentState.copy(error = validationError)
            return
        }

        _state.value = currentState.copy(isSubmitting = true, error = null)

        try {
            // Find the transaction key specified by passwordKeyId
            val transactionKey = currentState.transactionKeys.find { it.keyId == currentState.passwordKeyId }
                ?: throw IllegalStateException("Password key not found: ${currentState.passwordKeyId}")

            // Encrypt password with the designated transaction key
            val salt = passwordSalt ?: cryptoManager.generateSalt().also { passwordSalt = it }
            val encryptedResult = cryptoManager.encryptPasswordForServer(
                password = currentState.password,
                salt = salt,
                utkPublicKeyBase64 = transactionKey.publicKey
            )

            // Submit to server using passwordKeyId (sessionId is in the JWT now)
            val result = vaultServiceClient.setPassword(
                encryptedPassword = encryptedResult.encryptedPasswordHash,
                transactionKeyId = currentState.passwordKeyId
            )

            result.fold(
                onSuccess = { response ->
                    if (response.success) {
                        finalizeEnrollment(currentState.sessionId, salt)
                    } else {
                        _state.value = currentState.copy(
                            isSubmitting = false,
                            error = "Failed to set password"
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = currentState.copy(
                        isSubmitting = false,
                        error = error.message ?: "Password submission failed"
                    )
                }
            )
        } catch (e: Exception) {
            _state.value = currentState.copy(
                isSubmitting = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private suspend fun finalizeEnrollment(sessionId: String, passwordSalt: ByteArray) {
        _state.value = EnrollmentState.Finalizing(sessionId = sessionId, progress = 0f)

        try {
            updateFinalizingProgress(0.3f)

            // finalize() uses the JWT token, sessionId not needed in request
            val result = vaultServiceClient.finalize()

            updateFinalizingProgress(0.7f)

            result.fold(
                onSuccess = { response ->
                    // Store credentials
                    credentialStore.storeCredentialBlob(
                        userGuid = response.userGuid,
                        encryptedBlob = response.credentialBlob.data,
                        cekVersion = response.credentialBlob.cekVersion,
                        lat = response.lat,
                        transactionKeys = currentTransactionKeys,
                        passwordSalt = passwordSalt
                    )

                    updateFinalizingProgress(1.0f)
                    delay(300)

                    _state.value = EnrollmentState.Complete(
                        userGuid = response.userGuid
                    )

                    _effects.emit(EnrollmentEffect.NavigateToMain)
                },
                onFailure = { error ->
                    _state.value = EnrollmentState.Error(
                        message = "Finalization failed: ${error.message}",
                        retryable = true
                    )
                }
            )
        } catch (e: Exception) {
            _state.value = EnrollmentState.Error(
                message = "Finalization error: ${e.message}",
                retryable = true
            )
        }
    }

    private fun updateFinalizingProgress(progress: Float) {
        val currentState = _state.value
        if (currentState is EnrollmentState.Finalizing) {
            _state.value = currentState.copy(progress = progress)
        }
    }

    private fun validatePassword(password: String, confirmPassword: String): String? {
        return when {
            password.length < MIN_PASSWORD_LENGTH ->
                "Password must be at least $MIN_PASSWORD_LENGTH characters"
            password != confirmPassword ->
                "Passwords do not match"
            PasswordStrength.calculate(password) == PasswordStrength.WEAK ->
                "Password is too weak. Use a mix of uppercase, lowercase, numbers, and symbols."
            else -> null
        }
    }

    private fun retry() {
        val currentState = _state.value
        if (currentState is EnrollmentState.Error && currentState.retryable) {
            _state.value = currentState.previousState ?: EnrollmentState.ScanningQR()
        }
    }

    private fun cancel() {
        _state.value = EnrollmentState.Initial
        currentTransactionKeys = emptyList()
        passwordSalt = null
    }
}
