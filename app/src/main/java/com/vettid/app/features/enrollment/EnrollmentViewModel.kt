package com.vettid.app.features.enrollment

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.attestation.AttestationVerificationException
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.attestation.NitroAttestationVerifier
import com.vettid.app.core.attestation.VerifiedAttestation
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.nats.UtkInfo
import com.vettid.app.core.network.EnclaveAttestation
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
 * Manages state transitions through two flows:
 *
 * Legacy Flow:
 * 1. QR Scanning → 2. Attestation → 3. Password Setup → 4. Finalization
 *
 * Nitro Enclave Flow (new):
 * 1. QR Scanning → 2. API Auth → 3. NATS Connect → 4. Attestation via NATS →
 * 5. PIN Setup → 6. Wait for Vault → 7. Password Setup → 8. Create Credential → 9. Verify
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultServiceClient: VaultServiceClient,
    private val cryptoManager: CryptoManager,
    private val attestationManager: HardwareAttestationManager,
    private val nitroAttestationVerifier: NitroAttestationVerifier,
    private val nitroEnrollmentClient: NitroEnrollmentClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    companion object {
        private const val TAG = "EnrollmentViewModel"
        const val MIN_PASSWORD_LENGTH = 12
        const val PIN_LENGTH = 6
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

    // Store transaction keys during enrollment (legacy flow)
    private var currentTransactionKeys: List<TransactionKeyPublic> = emptyList()
    private var passwordSalt: ByteArray? = null

    // Nitro Enclave attestation data (legacy API flow)
    private var enclaveAttestation: EnclaveAttestation? = null
    private var verifiedEnclavePublicKey: String? = null

    // Nitro Enclave flow data (new NATS flow)
    private var isNitroFlow: Boolean = false
    private var nitroVerifiedAttestation: VerifiedAttestation? = null
    private var nitroUtks: List<UtkInfo> = emptyList()
    private var userGuid: String? = null

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
                is EnrollmentEvent.PinChanged -> updatePin(event.pin)
                is EnrollmentEvent.ConfirmPinChanged -> updateConfirmPin(event.confirmPin)
                is EnrollmentEvent.SubmitPin -> submitPin()
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
     * Supports two flows:
     * 1. Production flow (session_token): authenticate → start → set-password → finalize
     * 2. Direct/test flow (invitation_code): start-direct → set-password → finalize
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

        // Use the appropriate flow based on QR data
        if (enrollmentData.isDirectEnrollment) {
            processDirectEnrollment(enrollmentData)
        } else if (enrollmentData.isNitroFlow) {
            // New Nitro Enclave enrollment flow
            processNitroEnrollment(enrollmentData)
        } else {
            // Legacy session token enrollment
            processSessionTokenEnrollment(enrollmentData)
        }
    }

    /**
     * Process enrollment using invitation_code (direct/test flow)
     * Uses /vault/enroll/start-direct endpoint
     *
     * Flow:
     * 1. Call start-direct to get enclave attestation and transaction keys
     * 2. Verify Nitro Enclave attestation (CRITICAL security step)
     * 3. Move to password setup with verified enclave public key
     */
    private suspend fun processDirectEnrollment(enrollmentData: EnrollmentQRData) {
        val invitationCode = enrollmentData.invitationCode
            ?: throw IllegalStateException("invitation_code required for direct enrollment")

        val result = vaultServiceClient.enrollStartDirect(
            invitationCode = invitationCode,
            deviceId = getDeviceId(),
            skipAttestation = enrollmentData.skipAttestation
        )

        result.fold(
            onSuccess = { startResponse ->
                currentTransactionKeys = startResponse.transactionKeys

                // Verify Nitro Enclave attestation (skip in test mode)
                val attestation = startResponse.enclaveAttestation

                if (enrollmentData.skipAttestation) {
                    // Test mode - skip attestation verification
                    Log.w(TAG, "Skipping attestation verification (test mode)")
                    enclaveAttestation = attestation
                    // Use enclave public key from attestation if available, otherwise from expected PCRs
                    verifiedEnclavePublicKey = attestation?.enclavePublicKey
                } else {
                    // Production mode - verify attestation
                    if (attestation == null) {
                        Log.e(TAG, "No enclave attestation in enrollment response")
                        _state.value = EnrollmentState.Error(
                            message = "Server did not provide enclave attestation. Enrollment cannot proceed securely.",
                            retryable = false
                        )
                        return@fold
                    }

                    // Verify attestation before proceeding
                    try {
                        Log.d(TAG, "Verifying Nitro Enclave attestation...")
                        val verified = nitroAttestationVerifier.verify(attestation)

                        // Store verified enclave public key for password encryption
                        enclaveAttestation = attestation
                        verifiedEnclavePublicKey = verified.enclavePublicKeyBase64()

                        Log.i(TAG, "Enclave attestation verified. Module: ${verified.moduleId}")
                    } catch (e: AttestationVerificationException) {
                        Log.e(TAG, "Enclave attestation verification failed", e)
                        _state.value = EnrollmentState.Error(
                            message = "Enclave security verification failed: ${e.message}",
                            retryable = false
                        )
                        return@fold
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected attestation error", e)
                        _state.value = EnrollmentState.Error(
                            message = "Attestation error: ${e.message}",
                            retryable = false
                        )
                        return@fold
                    }
                }

                // Generate password salt for later use
                passwordSalt = cryptoManager.generateSalt()

                // Move to password setup
                _state.value = EnrollmentState.SettingPassword(
                    sessionId = startResponse.enrollmentSessionId,
                    transactionKeys = startResponse.transactionKeys,
                    passwordKeyId = startResponse.passwordKeyId
                )
            },
            onFailure = { error ->
                val errorMessage = when {
                    error.message?.contains("401") == true ->
                        "Enrollment invitation has expired. Please request a new one."
                    error.message?.contains("404") == true ->
                        "Invalid invitation code. Please check and try again."
                    else ->
                        error.message ?: "Failed to start enrollment"
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
     * Process enrollment using session_token (production flow)
     * Uses /vault/enroll/authenticate then /vault/enroll/start
     *
     * Flow:
     * 1. Authenticate with session_token to get enrollment JWT
     * 2. Call enroll/start to get enclave attestation and transaction keys
     * 3. Verify Nitro Enclave attestation (CRITICAL security step)
     * 4. Move to password setup with verified enclave public key
     */
    private suspend fun processSessionTokenEnrollment(enrollmentData: EnrollmentQRData) {
        val sessionToken = enrollmentData.sessionToken
            ?: throw IllegalStateException("session_token required for session enrollment")

        // Step 1: Authenticate with session_token to get enrollment JWT
        val authResult = vaultServiceClient.enrollAuthenticate(
            sessionToken = sessionToken,
            deviceId = getDeviceId()
        )

        authResult.fold(
            onSuccess = { authResponse ->
                // Step 2: Call enroll/start to get transaction keys and attestation
                val startResult = vaultServiceClient.enrollStart(skipAttestation = true)

                startResult.fold(
                    onSuccess = { startResponse ->
                        currentTransactionKeys = startResponse.transactionKeys

                        // Verify Nitro Enclave attestation
                        val attestation = startResponse.enclaveAttestation
                        if (attestation == null) {
                            Log.e(TAG, "No enclave attestation in enrollment response")
                            _state.value = EnrollmentState.Error(
                                message = "Server did not provide enclave attestation. Enrollment cannot proceed securely.",
                                retryable = false
                            )
                            return@fold
                        }

                        // Verify attestation before proceeding
                        try {
                            Log.d(TAG, "Verifying Nitro Enclave attestation...")
                            val verified = nitroAttestationVerifier.verify(attestation)

                            // Store verified enclave public key for password encryption
                            enclaveAttestation = attestation
                            verifiedEnclavePublicKey = verified.enclavePublicKeyBase64()

                            Log.i(TAG, "Enclave attestation verified. Module: ${verified.moduleId}")
                        } catch (e: AttestationVerificationException) {
                            Log.e(TAG, "Enclave attestation verification failed", e)
                            _state.value = EnrollmentState.Error(
                                message = "Enclave security verification failed: ${e.message}",
                                retryable = false
                            )
                            return@fold
                        }

                        // Generate password salt for later use
                        passwordSalt = cryptoManager.generateSalt()

                        // Move to password setup
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

        // Use Nitro flow if applicable
        if (currentState.isNitroFlow) {
            submitPasswordNitro()
            return
        }

        // Legacy flow continues below

        // Validate password
        val validationError = validatePassword(currentState.password, currentState.confirmPassword)
        if (validationError != null) {
            _state.value = currentState.copy(error = validationError)
            return
        }

        // Find the transaction key for password encryption
        val passwordKey = currentState.transactionKeys.find { it.keyId == currentState.passwordKeyId }
        if (passwordKey == null) {
            _state.value = currentState.copy(
                error = "Security error: Password encryption key not found. Please restart enrollment."
            )
            return
        }

        _state.value = currentState.copy(isSubmitting = true, error = null)

        try {
            // Encrypt password with the transaction key (UTK)
            val salt = passwordSalt ?: cryptoManager.generateSalt().also { passwordSalt = it }
            val encryptedResult = cryptoManager.encryptPasswordForServer(
                password = currentState.password,
                salt = salt,
                utkPublicKeyBase64 = passwordKey.publicKey
            )

            Log.d(TAG, "Password encrypted with transaction key ${passwordKey.keyId}, submitting...")

            // Submit to server with all encryption parameters
            val result = vaultServiceClient.setPassword(
                encryptedPasswordHash = encryptedResult.encryptedPasswordHash,
                keyId = currentState.passwordKeyId,
                nonce = encryptedResult.nonce,
                ephemeralPublicKey = encryptedResult.ephemeralPublicKey
            )

            result.fold(
                onSuccess = { response ->
                    if (response.isSuccess) {
                        finalizeEnrollment(currentState.sessionId, salt)
                    } else {
                        _state.value = currentState.copy(
                            isSubmitting = false,
                            error = "Failed to set password: ${response.status}"
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
                    // Store credentials using new credential package format
                    credentialStore.storeCredentialPackage(
                        credentialPackage = response.credentialPackage,
                        passwordSalt = passwordSalt
                    )

                    // Store NATS connection credentials if present
                    response.natsConnection?.let { vaultBootstrap ->
                        credentialStore.storeNatsConnection(vaultBootstrap)
                        android.util.Log.i("EnrollmentViewModel", "NATS credentials stored for ${vaultBootstrap.endpoint}")
                    }

                    // Store enrollment token for member API calls
                    vaultServiceClient.getEnrollmentToken()?.let { token ->
                        credentialStore.setAuthToken(token)
                        android.util.Log.i("EnrollmentViewModel", "Auth token stored for member API calls")
                    }

                    updateFinalizingProgress(1.0f)
                    delay(300)

                    _state.value = EnrollmentState.Complete(
                        userGuid = response.credentialPackage.userGuid,
                        vaultStatus = response.vaultStatus
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

    // MARK: - PIN Methods (Nitro flow)

    private fun updatePin(pin: String) {
        val currentState = _state.value
        if (currentState is EnrollmentState.SettingPin) {
            // Only allow digits, max 6 characters
            val filteredPin = pin.filter { it.isDigit() }.take(PIN_LENGTH)
            _state.value = currentState.copy(pin = filteredPin, error = null)
        }
    }

    private fun updateConfirmPin(confirmPin: String) {
        val currentState = _state.value
        if (currentState is EnrollmentState.SettingPin) {
            val filteredPin = confirmPin.filter { it.isDigit() }.take(PIN_LENGTH)
            _state.value = currentState.copy(confirmPin = filteredPin, error = null)
        }
    }

    private suspend fun submitPin() {
        val currentState = _state.value
        if (currentState !is EnrollmentState.SettingPin) return

        // Validate PIN
        val validationError = validatePin(currentState.pin, currentState.confirmPin)
        if (validationError != null) {
            _state.value = currentState.copy(error = validationError)
            return
        }

        _state.value = currentState.copy(isSubmitting = true, error = null)

        try {
            Log.d(TAG, "Submitting PIN to enclave...")

            val result = nitroEnrollmentClient.setupPin(currentState.pin)

            result.fold(
                onSuccess = { response ->
                    if (response.success) {
                        Log.i(TAG, "PIN setup complete, waiting for vault ready")
                        waitForVaultReady()
                    } else {
                        _state.value = currentState.copy(
                            isSubmitting = false,
                            error = response.message ?: "PIN setup failed"
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "PIN setup failed", error)
                    _state.value = currentState.copy(
                        isSubmitting = false,
                        error = error.message ?: "PIN setup failed"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "PIN setup error", e)
            _state.value = currentState.copy(
                isSubmitting = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private fun validatePin(pin: String, confirmPin: String): String? {
        return when {
            pin.length != PIN_LENGTH -> "PIN must be exactly $PIN_LENGTH digits"
            !pin.all { it.isDigit() } -> "PIN must contain only digits"
            pin != confirmPin -> "PINs do not match"
            pin == "000000" || pin == "123456" || pin == "654321" ->
                "PIN is too simple. Please choose a different PIN."
            else -> null
        }
    }

    // MARK: - Nitro Enrollment Flow

    /**
     * Process enrollment using the new Nitro Enclave flow.
     * This is the main entry point for the new flow when QR contains session_token.
     */
    private suspend fun processNitroEnrollment(enrollmentData: EnrollmentQRData) {
        isNitroFlow = true
        userGuid = enrollmentData.userGuid

        Log.i(TAG, "Starting Nitro Enclave enrollment flow")

        // Step 1: Authenticate with session_token to get enrollment JWT
        val sessionToken = enrollmentData.sessionToken
            ?: throw IllegalStateException("session_token required for Nitro enrollment")

        val authResult = vaultServiceClient.enrollAuthenticate(
            sessionToken = sessionToken,
            deviceId = getDeviceId()
        )

        authResult.fold(
            onSuccess = { authResponse ->
                Log.d(TAG, "Authentication successful, getting NATS bootstrap")

                // Step 2: Call finalize to get NATS bootstrap credentials
                val finalizeResult = vaultServiceClient.enrollFinalizeForNats()

                finalizeResult.fold(
                    onSuccess = { bootstrapResponse ->
                        Log.d(TAG, "Got NATS bootstrap, connecting...")

                        // Store user GUID
                        userGuid = bootstrapResponse.userGuid

                        // Step 3: Connect to NATS
                        connectToNatsAndContinue(bootstrapResponse)
                    },
                    onFailure = { error ->
                        _state.value = EnrollmentState.Error(
                            message = "Failed to get vault connection: ${error.message}",
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
     * Connect to NATS with bootstrap credentials and continue the flow.
     */
    private suspend fun connectToNatsAndContinue(bootstrap: NatsBootstrapInfo) {
        _state.value = EnrollmentState.ConnectingToNats()

        val connectResult = nitroEnrollmentClient.connect(
            natsEndpoint = bootstrap.natsEndpoint,
            credentials = bootstrap.credentials
        )

        connectResult.fold(
            onSuccess = {
                nitroEnrollmentClient.setOwnerSpace(bootstrap.ownerSpace)
                Log.i(TAG, "Connected to NATS, requesting attestation")
                requestNitroAttestation()
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to connect to NATS", error)
                _state.value = EnrollmentState.Error(
                    message = "Failed to connect to secure vault: ${error.message}",
                    retryable = true,
                    previousState = EnrollmentState.ScanningQR()
                )
            }
        )
    }

    /**
     * Request attestation from the enclave supervisor via NATS.
     */
    private suspend fun requestNitroAttestation() {
        _state.value = EnrollmentState.RequestingAttestation(progress = 0.2f)

        try {
            val result = nitroEnrollmentClient.requestAttestation()

            result.fold(
                onSuccess = { verifiedAttestation ->
                    Log.i(TAG, "Attestation verified: ${verifiedAttestation.moduleId}")
                    nitroVerifiedAttestation = verifiedAttestation

                    _state.value = EnrollmentState.RequestingAttestation(progress = 1.0f)
                    delay(300)

                    // Move to PIN setup
                    _state.value = EnrollmentState.SettingPin()
                },
                onFailure = { error ->
                    Log.e(TAG, "Attestation verification failed", error)
                    _state.value = EnrollmentState.Error(
                        message = "Security verification failed: ${error.message}",
                        retryable = false
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Attestation error", e)
            _state.value = EnrollmentState.Error(
                message = "Attestation error: ${e.message}",
                retryable = false
            )
        }
    }

    /**
     * Wait for vault to be ready and receive UTKs.
     */
    private suspend fun waitForVaultReady() {
        _state.value = EnrollmentState.WaitingForVault(progress = 0.3f)

        try {
            val result = nitroEnrollmentClient.waitForVaultReady()

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Vault ready with ${response.utks.size} UTKs")
                    nitroUtks = response.utks

                    // Generate password salt
                    passwordSalt = cryptoManager.generateSalt()

                    _state.value = EnrollmentState.WaitingForVault(progress = 1.0f)
                    delay(300)

                    // Move to password setup
                    _state.value = EnrollmentState.SettingPassword(
                        sessionId = "",
                        transactionKeys = emptyList(),
                        passwordKeyId = response.utks.firstOrNull()?.keyId ?: "",
                        utks = response.utks,
                        isNitroFlow = true
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Waiting for vault failed", error)
                    _state.value = EnrollmentState.Error(
                        message = "Vault initialization failed: ${error.message}",
                        retryable = true
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Vault wait error", e)
            _state.value = EnrollmentState.Error(
                message = "Error: ${e.message}",
                retryable = true
            )
        }
    }

    /**
     * Submit password for Nitro flow - create credential via NATS.
     */
    private suspend fun submitPasswordNitro() {
        val currentState = _state.value
        if (currentState !is EnrollmentState.SettingPassword || !currentState.isNitroFlow) return

        // Validate password
        val validationError = validatePassword(currentState.password, currentState.confirmPassword)
        if (validationError != null) {
            _state.value = currentState.copy(error = validationError)
            return
        }

        // Get UTK for encryption
        val utk = currentState.utks.firstOrNull()
        if (utk == null) {
            _state.value = currentState.copy(error = "No transaction key available")
            return
        }

        _state.value = currentState.copy(isSubmitting = true, error = null)
        _state.value = EnrollmentState.CreatingCredential(progress = 0.2f)

        try {
            val salt = passwordSalt ?: cryptoManager.generateSalt().also { passwordSalt = it }

            val result = nitroEnrollmentClient.createCredential(
                password = currentState.password,
                passwordSalt = salt,
                utkPublicKey = utk.publicKey,
                utkKeyId = utk.keyId
            )

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Credential created: ${response.credentialGuid}")

                    // Update UTKs with new ones
                    nitroUtks = response.newUtks

                    // Store credential locally
                    credentialStore.storeNitroCredential(
                        encryptedCredential = response.encryptedCredential,
                        credentialGuid = response.credentialGuid,
                        userGuid = response.userGuid ?: userGuid ?: "",
                        passwordSalt = salt,
                        utks = response.newUtks
                    )

                    _state.value = EnrollmentState.CreatingCredential(progress = 0.8f)

                    // Verify enrollment
                    verifyNitroEnrollment(response.userGuid ?: userGuid ?: "")
                },
                onFailure = { error ->
                    Log.e(TAG, "Credential creation failed", error)
                    _state.value = EnrollmentState.Error(
                        message = "Failed to create credential: ${error.message}",
                        retryable = true
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Credential creation error", e)
            _state.value = EnrollmentState.Error(
                message = "Error: ${e.message}",
                retryable = true
            )
        }
    }

    /**
     * Verify enrollment with a test operation.
     */
    private suspend fun verifyNitroEnrollment(userGuid: String) {
        _state.value = EnrollmentState.VerifyingEnrollment(progress = 0.5f)

        try {
            val result = nitroEnrollmentClient.verifyEnrollment()

            result.fold(
                onSuccess = {
                    Log.i(TAG, "Enrollment verified successfully")

                    _state.value = EnrollmentState.VerifyingEnrollment(progress = 1.0f)
                    delay(300)

                    // Disconnect from NATS
                    nitroEnrollmentClient.disconnect()

                    // Complete enrollment
                    _state.value = EnrollmentState.Complete(
                        userGuid = userGuid,
                        vaultStatus = "enrolled"
                    )

                    _effects.emit(EnrollmentEffect.NavigateToMain)
                },
                onFailure = { error ->
                    Log.e(TAG, "Enrollment verification failed", error)
                    _state.value = EnrollmentState.Error(
                        message = "Enrollment verification failed: ${error.message}",
                        retryable = true
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Verification error", e)
            _state.value = EnrollmentState.Error(
                message = "Error: ${e.message}",
                retryable = true
            )
        }
    }

    private fun retry() {
        val currentState = _state.value
        if (currentState is EnrollmentState.Error && currentState.retryable) {
            _state.value = currentState.previousState ?: EnrollmentState.ScanningQR()
        }
    }

    private fun cancel() {
        viewModelScope.launch {
            // Disconnect from NATS if connected
            if (isNitroFlow) {
                try {
                    nitroEnrollmentClient.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting from NATS", e)
                }
            }
        }

        _state.value = EnrollmentState.Initial
        currentTransactionKeys = emptyList()
        passwordSalt = null
        enclaveAttestation = null
        verifiedEnclavePublicKey = null
        isNitroFlow = false
        nitroVerifiedAttestation = null
        nitroUtks = emptyList()
        userGuid = null
    }
}

/**
 * NATS bootstrap info from enrollment finalize.
 */
data class NatsBootstrapInfo(
    val credentials: String,
    val ownerSpace: String,
    val natsEndpoint: String,
    val userGuid: String?
)
