package com.vettid.app.features.enrollmentwizard

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.attestation.AttestationVerificationException
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.attestation.NitroAttestationVerifier
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.attestation.VerifiedAttestation
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.PostEnrollmentAuthClient
import com.vettid.app.core.nats.UtkInfo
import com.vettid.app.core.network.EnrollmentQRData
import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.features.enrollment.AttestationInfo
import com.vettid.app.features.enrollment.NatsBootstrapInfo
import com.vettid.app.features.enrollment.PasswordStrength
import com.vettid.app.worker.BackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the unified enrollment wizard.
 *
 * Manages state transitions through all 7 phases:
 * 1. Start (QR/Manual) → 2. Attestation → 3. PIN Setup → 4. Password Setup →
 * 5. Verify Credential → 6. Personal Data → 7. Complete
 */
@HiltViewModel
class EnrollmentWizardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultServiceClient: VaultServiceClient,
    private val cryptoManager: CryptoManager,
    private val attestationManager: HardwareAttestationManager,
    private val nitroAttestationVerifier: NitroAttestationVerifier,
    private val nitroEnrollmentClient: NitroEnrollmentClient,
    private val credentialStore: CredentialStore,
    private val pcrConfigManager: PcrConfigManager,
    private val postEnrollmentAuthClient: PostEnrollmentAuthClient,
    private val personalDataStore: PersonalDataStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    companion object {
        private const val TAG = "EnrollmentWizardVM"
        const val MIN_PASSWORD_LENGTH = 12
        const val PIN_LENGTH = 6
    }

    private val _state = MutableStateFlow<WizardState>(WizardState.Loading)
    val state: StateFlow<WizardState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<WizardEffect>()
    val effects: SharedFlow<WizardEffect> = _effects.asSharedFlow()

    // Enrollment data storage
    private var passwordSalt: ByteArray? = null
    private var nitroVerifiedAttestation: VerifiedAttestation? = null
    private var nitroUtks: List<UtkInfo> = emptyList()
    private var userGuid: String? = null
    private var storedAttestationInfo: AttestationInfo? = null

    /**
     * Get unique device identifier for enrollment.
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: java.util.UUID.randomUUID().toString()
    }

    /**
     * Initialize with optional start parameters.
     */
    fun initialize(startWithManualEntry: Boolean = false, initialCode: String? = null) {
        viewModelScope.launch {
            when {
                initialCode != null -> {
                    // Process the code from deep link directly
                    processQRCode(initialCode)
                }
                startWithManualEntry -> {
                    _state.value = WizardState.ManualEntry()
                }
                else -> {
                    _state.value = WizardState.ScanningQR()
                }
            }
        }
    }

    /**
     * Process events from UI.
     */
    fun onEvent(event: WizardEvent) {
        viewModelScope.launch {
            when (event) {
                // Navigation events
                is WizardEvent.NextStep -> handleNextStep()
                is WizardEvent.PreviousStep -> handlePreviousStep()
                is WizardEvent.Skip -> handleSkip()
                is WizardEvent.Retry -> handleRetry()
                is WizardEvent.Cancel -> handleCancel()

                // Start phase events
                is WizardEvent.StartScanning -> _state.value = WizardState.ScanningQR()
                is WizardEvent.SwitchToManualEntry -> _state.value = WizardState.ManualEntry()
                is WizardEvent.SwitchToScanning -> _state.value = WizardState.ScanningQR()
                is WizardEvent.QRCodeScanned -> processQRCode(event.qrData)
                is WizardEvent.InviteCodeChanged -> updateInviteCode(event.inviteCode)
                is WizardEvent.SubmitInviteCode -> submitManualInviteCode()

                // PIN phase events
                is WizardEvent.PinChanged -> updatePin(event.pin)
                is WizardEvent.ConfirmPinChanged -> updateConfirmPin(event.confirmPin)
                is WizardEvent.SubmitPin -> submitPin()

                // Password phase events
                is WizardEvent.PasswordChanged -> updatePassword(event.password)
                is WizardEvent.ConfirmPasswordChanged -> updateConfirmPassword(event.confirmPassword)
                is WizardEvent.SubmitPassword -> submitPassword()

                // Verify phase events
                is WizardEvent.VerifyPasswordChanged -> updateVerifyPassword(event.password)
                is WizardEvent.TogglePasswordVisibility -> togglePasswordVisibility()
                is WizardEvent.SubmitVerifyPassword -> submitVerifyPassword()
                is WizardEvent.ContinueAfterVerification -> continueToPersonalData()

                // Personal data phase events
                is WizardEvent.UpdateOptionalField -> updateOptionalField(event.field, event.value)
                is WizardEvent.AddCustomField -> addCustomField(event.name, event.value, event.category)
                is WizardEvent.UpdateCustomField -> updateCustomField(event.field)
                is WizardEvent.RemoveCustomField -> removeCustomField(event.fieldId)
                is WizardEvent.SyncPersonalData -> syncPersonalData()
                is WizardEvent.ShowAddFieldDialog -> showAddFieldDialog()
                is WizardEvent.HideAddFieldDialog -> hideAddFieldDialog()
                is WizardEvent.ShowEditFieldDialog -> showEditFieldDialog(event.field)
                is WizardEvent.HideEditFieldDialog -> hideEditFieldDialog()
                is WizardEvent.DismissError -> dismissError()

                // Complete phase events
                is WizardEvent.NavigateToMain -> navigateToMain()
            }
        }
    }

    // ============== NAVIGATION HANDLERS ==============

    private suspend fun handleNextStep() {
        when (val current = _state.value) {
            is WizardState.VerificationSuccess -> continueToPersonalData()
            is WizardState.PersonalData -> completeWizard()
            is WizardState.Complete -> navigateToMain()
            else -> { /* No-op for states with specific submit actions */ }
        }
    }

    private fun handlePreviousStep() {
        when (val current = _state.value) {
            is WizardState.ManualEntry -> _state.value = WizardState.ScanningQR()
            is WizardState.SettingPin -> {
                // Can't go back from PIN setup - attestation must be re-done
            }
            is WizardState.SettingPassword -> {
                // Can go back to PIN but PIN must be re-entered
                _state.value = WizardState.SettingPin(attestationInfo = storedAttestationInfo)
            }
            is WizardState.VerifyingPassword -> {
                // Can't go back from verification - credential is already created
            }
            is WizardState.PersonalData -> {
                // Can't go back from personal data - credential verification is done
            }
            else -> { /* No-op */ }
        }
    }

    private suspend fun handleSkip() {
        when (val current = _state.value) {
            is WizardState.VerifyingPassword -> {
                // Skip verification and go directly to personal data
                Log.i(TAG, "User skipped credential verification")
                continueToPersonalData()
            }
            is WizardState.PersonalData -> {
                // Skip personal data and complete
                Log.i(TAG, "User skipped personal data")
                completeWizard()
            }
            else -> { /* No-op */ }
        }
    }

    private suspend fun handleRetry() {
        val current = _state.value
        if (current is WizardState.Error) {
            // Return to appropriate state based on previous phase
            when (current.previousPhase) {
                WizardPhase.START -> _state.value = WizardState.ScanningQR()
                WizardPhase.ATTESTATION -> _state.value = WizardState.ScanningQR()
                WizardPhase.PIN_SETUP -> _state.value = WizardState.SettingPin(attestationInfo = storedAttestationInfo)
                WizardPhase.PASSWORD_SETUP -> _state.value = WizardState.SettingPassword(utks = nitroUtks)
                WizardPhase.VERIFY_CREDENTIAL -> _state.value = WizardState.VerifyingPassword()
                WizardPhase.PERSONAL_DATA -> loadPersonalData()
                WizardPhase.COMPLETE -> completeWizard()
            }
        }
    }

    private suspend fun handleCancel() {
        // Disconnect from NATS if connected
        try {
            nitroEnrollmentClient.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from NATS", e)
        }

        // Reset state
        passwordSalt = null
        nitroVerifiedAttestation = null
        nitroUtks = emptyList()
        userGuid = null
        storedAttestationInfo = null

        _effects.emit(WizardEffect.CloseWizard)
    }

    // ============== START PHASE ==============

    private fun updateInviteCode(code: String) {
        val current = _state.value
        if (current is WizardState.ManualEntry) {
            _state.value = current.copy(inviteCode = code, error = null)
        }
    }

    private suspend fun submitManualInviteCode() {
        val current = _state.value
        if (current !is WizardState.ManualEntry) return

        val code = current.inviteCode.trim()
        if (code.isEmpty()) {
            _state.value = current.copy(error = "Please enter an invitation code")
            return
        }

        processQRCode(code)
    }

    private suspend fun processQRCode(qrData: String) {
        val enrollmentData = EnrollmentQRData.parse(qrData)

        if (enrollmentData == null) {
            _state.value = WizardState.Error(
                message = "Invalid QR code format. Please scan a valid VettID enrollment QR code.",
                canRetry = true,
                previousPhase = WizardPhase.START
            )
            return
        }

        _state.value = WizardState.ProcessingInvite("Validating your invitation code...")

        // Set the API URL from the QR code
        vaultServiceClient.setEnrollmentApiUrl(enrollmentData.apiUrl)

        // Use Nitro flow
        processNitroEnrollment(enrollmentData)
    }

    // ============== ATTESTATION PHASE ==============

    private suspend fun processNitroEnrollment(enrollmentData: EnrollmentQRData) {
        userGuid = enrollmentData.userGuid

        Log.i(TAG, "Starting Nitro Enclave enrollment flow")

        val sessionToken = enrollmentData.sessionToken
        if (sessionToken == null) {
            _state.value = WizardState.Error(
                message = "Invalid enrollment data: missing session token",
                canRetry = true,
                previousPhase = WizardPhase.START
            )
            return
        }

        // Step 1: Authenticate
        val authResult = vaultServiceClient.enrollAuthenticate(
            sessionToken = sessionToken,
            deviceId = getDeviceId()
        )

        authResult.fold(
            onSuccess = { authResponse ->
                Log.d(TAG, "Authentication successful, generating device attestation")

                // Step 2: Generate device attestation
                val deviceAttestation = try {
                    attestationManager.generateSessionAttestation(
                        sessionId = authResponse.enrollmentSessionId,
                        userGuid = authResponse.userGuid
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate device attestation", e)
                    _state.value = WizardState.Error(
                        message = "Device attestation failed: ${e.message}",
                        canRetry = true,
                        previousPhase = WizardPhase.START
                    )
                    return@fold
                }

                Log.d(TAG, "Device attestation generated (${deviceAttestation.securityLevel}), getting NATS bootstrap")

                // Step 3: Get NATS bootstrap credentials
                val bootstrapResult = vaultServiceClient.getNatsBootstrapCredentials(deviceAttestation)

                bootstrapResult.fold(
                    onSuccess = { bootstrapResponse ->
                        Log.d(TAG, "Got NATS bootstrap credentials, connecting...")
                        userGuid = bootstrapResponse.userGuid
                        connectToNatsAndContinue(bootstrapResponse)
                    },
                    onFailure = { error ->
                        _state.value = WizardState.Error(
                            message = "Failed to get vault connection: ${error.message}",
                            canRetry = true,
                            previousPhase = WizardPhase.START
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
                _state.value = WizardState.Error(
                    message = errorMessage,
                    canRetry = true,
                    previousPhase = WizardPhase.START
                )
            }
        )
    }

    private suspend fun connectToNatsAndContinue(bootstrap: NatsBootstrapInfo) {
        _state.value = WizardState.ConnectingToNats()

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
                _state.value = WizardState.Error(
                    message = "Failed to connect to secure vault: ${error.message}",
                    canRetry = true,
                    previousPhase = WizardPhase.START
                )
            }
        )
    }

    private suspend fun requestNitroAttestation() {
        _state.value = WizardState.RequestingAttestation(progress = 0.2f)

        try {
            val result = nitroEnrollmentClient.requestAttestation()

            result.fold(
                onSuccess = { verifiedAttestation ->
                    Log.i(TAG, "Attestation verified: ${verifiedAttestation.moduleId}")
                    nitroVerifiedAttestation = verifiedAttestation

                    _state.value = WizardState.RequestingAttestation(progress = 1.0f)
                    delay(300)

                    // Create attestation info for display
                    val pcr0Bytes = verifiedAttestation.pcrs[0]
                    val pcr0Full = pcr0Bytes?.joinToString("") { "%02x".format(it) } ?: "unknown"
                    val pcr0Short = if (pcr0Full.length > 16) {
                        pcr0Full.take(16) + "..." + pcr0Full.takeLast(8)
                    } else pcr0Full

                    val currentPcrs = pcrConfigManager.getCurrentPcrs()

                    val attestationInfo = AttestationInfo(
                        moduleId = verifiedAttestation.moduleId,
                        timestamp = verifiedAttestation.timestamp,
                        pcr0Short = pcr0Short,
                        pcrsVerified = true,
                        pcrVersion = currentPcrs.version,
                        pcr0Full = pcr0Full,
                        pcrDescription = currentPcrs.description
                    )

                    storedAttestationInfo = attestationInfo

                    // Show verified state briefly
                    _state.value = WizardState.AttestationVerified(attestationInfo = attestationInfo)
                    delay(2000)

                    // Move to PIN setup
                    _state.value = WizardState.SettingPin(attestationInfo = attestationInfo)
                },
                onFailure = { error ->
                    Log.e(TAG, "Attestation verification failed", error)
                    _state.value = WizardState.Error(
                        message = "Security verification failed: ${error.message}",
                        canRetry = false,
                        previousPhase = WizardPhase.ATTESTATION
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Attestation error", e)
            _state.value = WizardState.Error(
                message = "Attestation error: ${e.message}",
                canRetry = false,
                previousPhase = WizardPhase.ATTESTATION
            )
        }
    }

    // ============== PIN PHASE ==============

    private fun updatePin(pin: String) {
        val current = _state.value
        if (current is WizardState.SettingPin) {
            val filteredPin = pin.filter { it.isDigit() }.take(PIN_LENGTH)
            _state.value = current.copy(pin = filteredPin, error = null)
        }
    }

    private fun updateConfirmPin(confirmPin: String) {
        val current = _state.value
        if (current is WizardState.SettingPin) {
            val filteredPin = confirmPin.filter { it.isDigit() }.take(PIN_LENGTH)
            _state.value = current.copy(confirmPin = filteredPin, error = null)
        }
    }

    private suspend fun submitPin() {
        val current = _state.value
        if (current !is WizardState.SettingPin) return

        // Validate PIN
        val validationError = validatePin(current.pin, current.confirmPin)
        if (validationError != null) {
            _state.value = current.copy(error = validationError)
            return
        }

        _state.value = current.copy(isSubmitting = true, error = null)

        try {
            Log.d(TAG, "Submitting PIN to enclave...")

            val result = nitroEnrollmentClient.setupPin(current.pin)

            result.fold(
                onSuccess = { response ->
                    if (response.isSuccess) {
                        Log.i(TAG, "PIN setup complete, vault ready with ${response.utks?.size ?: 0} UTKs")
                        response.utks?.let { nitroUtks = it }

                        passwordSalt = cryptoManager.generateSalt()

                        _state.value = WizardState.SettingPassword(utks = nitroUtks)
                    } else {
                        _state.value = current.copy(
                            isSubmitting = false,
                            error = response.message ?: "PIN setup failed"
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "PIN setup failed", error)
                    _state.value = current.copy(
                        isSubmitting = false,
                        error = error.message ?: "PIN setup failed"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "PIN setup error", e)
            _state.value = current.copy(
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

    // ============== PASSWORD PHASE ==============

    private fun updatePassword(password: String) {
        val current = _state.value
        if (current is WizardState.SettingPassword) {
            _state.value = current.copy(
                password = password,
                strength = PasswordStrength.calculate(password),
                error = null
            )
        }
    }

    private fun updateConfirmPassword(confirmPassword: String) {
        val current = _state.value
        if (current is WizardState.SettingPassword) {
            _state.value = current.copy(confirmPassword = confirmPassword, error = null)
        }
    }

    private suspend fun submitPassword() {
        val current = _state.value
        if (current !is WizardState.SettingPassword) return

        // Validate password
        val validationError = validatePassword(current.password, current.confirmPassword)
        if (validationError != null) {
            _state.value = current.copy(error = validationError)
            return
        }

        // Get UTK for encryption
        val utk = current.utks.firstOrNull()
        if (utk == null) {
            _state.value = current.copy(error = "No transaction key available")
            return
        }

        _state.value = current.copy(isSubmitting = true, error = null)
        _state.value = WizardState.CreatingCredential(progress = 0.2f)

        try {
            val salt = passwordSalt ?: cryptoManager.generateSalt().also { passwordSalt = it }

            val result = nitroEnrollmentClient.createCredential(
                password = current.password,
                passwordSalt = salt,
                utk = utk
            )

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Credential created, status: ${response.status}")

                    val encryptedCred = response.encryptedCredential
                    if (encryptedCred == null) {
                        Log.e(TAG, "Credential response missing encrypted_credential")
                        _state.value = WizardState.Error(
                            message = "Invalid credential response from server",
                            canRetry = true,
                            previousPhase = WizardPhase.PASSWORD_SETUP
                        )
                        return@fold
                    }

                    val credGuid = response.credentialGuid ?: java.util.UUID.randomUUID().toString()
                    val utksToStore = response.newUtks ?: nitroUtks

                    if (response.newUtks != null) {
                        nitroUtks = response.newUtks
                    }

                    // Store credential
                    val currentPcrs = pcrConfigManager.getCurrentPcrs()
                    credentialStore.storeNitroCredential(
                        encryptedCredential = encryptedCred,
                        credentialGuid = credGuid,
                        userGuid = response.userGuid ?: userGuid ?: "",
                        passwordSalt = salt,
                        utks = utksToStore,
                        pcrVersion = currentPcrs.version,
                        pcr0Hash = currentPcrs.pcr0
                    )

                    _state.value = WizardState.CreatingCredential(progress = 0.8f)

                    // Verify enrollment
                    verifyNitroEnrollment(response.userGuid ?: userGuid ?: "")
                },
                onFailure = { error ->
                    Log.e(TAG, "Credential creation failed", error)
                    _state.value = WizardState.Error(
                        message = "Failed to create credential: ${error.message}",
                        canRetry = true,
                        previousPhase = WizardPhase.PASSWORD_SETUP
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Credential creation error", e)
            _state.value = WizardState.Error(
                message = "Error: ${e.message}",
                canRetry = true,
                previousPhase = WizardPhase.PASSWORD_SETUP
            )
        }
    }

    private suspend fun verifyNitroEnrollment(userGuid: String) {
        _state.value = WizardState.CreatingCredential(
            message = "Verifying enrollment...",
            progress = 0.9f
        )

        try {
            val result = nitroEnrollmentClient.verifyEnrollment()

            result.fold(
                onSuccess = {
                    Log.i(TAG, "Enrollment verified successfully")

                    // Store NATS credentials
                    val natsEndpoint = nitroEnrollmentClient.getNatsEndpoint()
                    val natsCredentials = nitroEnrollmentClient.getNatsCredentials()
                    val ownerSpace = nitroEnrollmentClient.getOwnerSpace()

                    if (natsEndpoint != null && natsCredentials != null && ownerSpace != null) {
                        val natsConnectionInfo = NatsConnectionInfo(
                            endpoint = natsEndpoint,
                            credentials = natsCredentials,
                            ownerSpace = ownerSpace,
                            messageSpace = ownerSpace.replace("OwnerSpace.", "MessageSpace.")
                        )
                        credentialStore.storeNatsConnection(natsConnectionInfo)
                        Log.i(TAG, "NATS credentials stored for post-enrollment auth: $natsEndpoint")
                    }

                    // Disconnect enrollment client
                    nitroEnrollmentClient.disconnect()

                    // Trigger backup
                    BackupWorker.triggerNow(context)

                    this.userGuid = userGuid

                    // Move to verification phase
                    _state.value = WizardState.VerifyingPassword()
                },
                onFailure = { error ->
                    Log.e(TAG, "Enrollment verification failed", error)
                    _state.value = WizardState.Error(
                        message = "Enrollment verification failed: ${error.message}",
                        canRetry = true,
                        previousPhase = WizardPhase.PASSWORD_SETUP
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Verification error", e)
            _state.value = WizardState.Error(
                message = "Error: ${e.message}",
                canRetry = true,
                previousPhase = WizardPhase.PASSWORD_SETUP
            )
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

    // ============== VERIFY PHASE ==============

    private fun updateVerifyPassword(password: String) {
        val current = _state.value
        if (current is WizardState.VerifyingPassword) {
            _state.value = current.copy(password = password, error = null)
        }
    }

    private fun togglePasswordVisibility() {
        val current = _state.value
        if (current is WizardState.VerifyingPassword) {
            _state.value = current.copy(isPasswordVisible = !current.isPasswordVisible)
        }
    }

    private suspend fun submitVerifyPassword() {
        val current = _state.value
        if (current !is WizardState.VerifyingPassword) return

        val password = current.password
        if (password.isBlank()) {
            _state.value = current.copy(error = "Please enter your password")
            return
        }

        _state.value = current.copy(isSubmitting = true, error = null)
        _state.value = WizardState.Authenticating(
            progress = 0.1f,
            statusMessage = "Connecting to vault..."
        )

        try {
            _state.value = WizardState.Authenticating(
                progress = 0.3f,
                statusMessage = "Verifying credential..."
            )

            val result = postEnrollmentAuthClient.authenticate(password)

            _state.value = WizardState.Authenticating(
                progress = 0.8f,
                statusMessage = "Processing response..."
            )

            result.fold(
                onSuccess = { authResult ->
                    Log.i(TAG, "Authentication successful for user: ${authResult.userGuid}")

                    if (authResult.natsCredentials != null) {
                        credentialStore.storeFullNatsCredentials(
                            credentials = authResult.natsCredentials,
                            ownerSpace = authResult.ownerSpace,
                            messageSpace = authResult.messageSpace
                        )
                    }

                    _state.value = WizardState.Authenticating(
                        progress = 1.0f,
                        statusMessage = "Credential verified!"
                    )

                    delay(500)

                    _state.value = WizardState.VerificationSuccess(
                        userGuid = authResult.userGuid,
                        message = "Your credential has been verified successfully"
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Authentication failed", error)

                    val errorMessage = when {
                        error.message?.contains("password", ignoreCase = true) == true ||
                        error.message?.contains("invalid", ignoreCase = true) == true ->
                            "Incorrect password. Please try again."
                        error.message?.contains("timeout", ignoreCase = true) == true ->
                            "Connection timed out. Please check your network and try again."
                        error.message?.contains("connect", ignoreCase = true) == true ->
                            "Could not connect to vault. Please check your network."
                        else -> error.message ?: "Authentication failed"
                    }

                    _state.value = WizardState.Error(
                        message = errorMessage,
                        canRetry = true,
                        previousPhase = WizardPhase.VERIFY_CREDENTIAL
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            _state.value = WizardState.Error(
                message = "An unexpected error occurred: ${e.message}",
                canRetry = true,
                previousPhase = WizardPhase.VERIFY_CREDENTIAL
            )
        }
    }

    private suspend fun continueToPersonalData() {
        loadPersonalData()
    }

    // ============== PERSONAL DATA PHASE ==============

    private fun loadPersonalData() {
        viewModelScope.launch {
            _state.value = WizardState.PersonalData(isLoading = true)

            try {
                val systemFields = personalDataStore.getSystemFields()
                val optionalFields = personalDataStore.getOptionalFields()
                val customFields = personalDataStore.getCustomFields()
                val hasPendingSync = personalDataStore.hasPendingSync()

                _state.value = WizardState.PersonalData(
                    isLoading = false,
                    systemFields = systemFields,
                    optionalFields = optionalFields,
                    customFields = customFields,
                    hasPendingSync = hasPendingSync
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal data", e)
                _state.value = WizardState.PersonalData(
                    isLoading = false,
                    error = "Failed to load personal data: ${e.message}"
                )
            }
        }
    }

    private fun updateOptionalField(field: OptionalField, value: String?) {
        personalDataStore.updateOptionalField(field, value?.takeIf { it.isNotBlank() })
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(
                optionalFields = personalDataStore.getOptionalFields(),
                hasPendingSync = true
            )
        }
    }

    private fun addCustomField(name: String, value: String, category: FieldCategory) {
        if (name.isBlank()) {
            val current = _state.value
            if (current is WizardState.PersonalData) {
                _state.value = current.copy(error = "Field name cannot be empty")
            }
            return
        }
        personalDataStore.addCustomField(name, value, category)
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(
                customFields = personalDataStore.getCustomFields(),
                hasPendingSync = true,
                showAddFieldDialog = false
            )
        }
    }

    private fun updateCustomField(field: CustomField) {
        personalDataStore.updateCustomField(field)
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(
                customFields = personalDataStore.getCustomFields(),
                hasPendingSync = true,
                editingField = null
            )
        }
    }

    private fun removeCustomField(fieldId: String) {
        personalDataStore.removeCustomField(fieldId)
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(
                customFields = personalDataStore.getCustomFields(),
                hasPendingSync = true,
                editingField = null
            )
        }
    }

    private suspend fun syncPersonalData() {
        val current = _state.value
        if (current !is WizardState.PersonalData) return

        if (!connectionManager.isConnected()) {
            _state.value = current.copy(error = "Not connected to vault. Changes will sync when connection is restored.")
            return
        }

        _state.value = current.copy(isSyncing = true)

        try {
            val data = personalDataStore.exportForSync()
            val payload = com.google.gson.JsonObject().apply {
                data.forEach { (key, value) ->
                    when (value) {
                        is String -> addProperty(key, value)
                        is Number -> addProperty(key, value)
                        is Boolean -> addProperty(key, value)
                        null -> {}
                        else -> addProperty(key, value.toString())
                    }
                }
            }

            val result = ownerSpaceClient.sendToVault("profile.update", payload)

            result.fold(
                onSuccess = {
                    personalDataStore.markSyncComplete()
                    _state.update {
                        if (it is WizardState.PersonalData) {
                            it.copy(
                                isSyncing = false,
                                hasPendingSync = false
                            )
                        } else it
                    }
                    _effects.emit(WizardEffect.ShowToast("Personal data synced successfully"))
                },
                onFailure = { error ->
                    _state.update {
                        if (it is WizardState.PersonalData) {
                            it.copy(
                                isSyncing = false,
                                error = "Sync failed: ${error.message}"
                            )
                        } else it
                    }
                }
            )
        } catch (e: Exception) {
            _state.update {
                if (it is WizardState.PersonalData) {
                    it.copy(
                        isSyncing = false,
                        error = "Sync error: ${e.message}"
                    )
                } else it
            }
        }
    }

    private fun showAddFieldDialog() {
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(showAddFieldDialog = true)
        }
    }

    private fun hideAddFieldDialog() {
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(showAddFieldDialog = false)
        }
    }

    private fun showEditFieldDialog(field: CustomField) {
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(editingField = field)
        }
    }

    private fun hideEditFieldDialog() {
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(editingField = null)
        }
    }

    private fun dismissError() {
        val current = _state.value
        if (current is WizardState.PersonalData) {
            _state.value = current.copy(error = null)
        }
    }

    // ============== COMPLETE PHASE ==============

    private suspend fun completeWizard() {
        // Schedule background sync if there are pending changes
        if (personalDataStore.hasPendingSync()) {
            Log.d(TAG, "Scheduling background sync for pending changes")
        }

        _state.value = WizardState.Complete(userGuid = userGuid ?: "")
    }

    private suspend fun navigateToMain() {
        _effects.emit(WizardEffect.NavigateToMain)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try {
                nitroEnrollmentClient.disconnect()
                postEnrollmentAuthClient.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting clients", e)
            }
        }
    }
}
