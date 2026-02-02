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
import com.vettid.app.core.attestation.PcrInitializationService
import com.vettid.app.core.attestation.VerifiedAttestation
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.CustomCategoryDto
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.PostEnrollmentAuthClient
import com.vettid.app.core.nats.RegistrationProfile
import com.vettid.app.core.nats.UtkInfo
import com.vettid.app.core.network.EnrollmentQRData
import com.vettid.app.core.network.NatsConnectionInfo
import com.vettid.app.core.network.VaultServiceClient
import com.vettid.app.core.storage.CategoryInfo
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.FieldType
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.features.enrollment.AttestationInfo
import com.vettid.app.features.enrollment.NatsBootstrapInfo
import com.vettid.app.features.enrollment.PasswordStrength
import com.vettid.app.worker.BackupWorker
import com.vettid.app.worker.PersonalDataSyncWorker
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
    private val pcrInitializationService: PcrInitializationService,
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

    // replay=1 ensures the last effect is replayed to new collectors (handles recomposition timing)
    // extraBufferCapacity=1 ensures emit() doesn't suspend if collector is briefly unavailable
    private val _effects = MutableSharedFlow<WizardEffect>(replay = 1, extraBufferCapacity = 1)
    val effects: SharedFlow<WizardEffect> = _effects.asSharedFlow()

    // Enrollment data storage
    private var passwordSalt: ByteArray? = null
    private var nitroVerifiedAttestation: VerifiedAttestation? = null
    private var nitroUtks: List<UtkInfo> = emptyList()
    private var userGuid: String? = null
    private var storedAttestationInfo: AttestationInfo? = null
    // Registration profile to send to vault during PIN setup
    private var pendingRegistrationProfile: RegistrationProfile? = null

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
            // Clear any leftover personal data from previous enrollment attempts
            // This ensures fresh enrollment starts with clean slate
            personalDataStore.clearAll()
            Log.d(TAG, "Cleared local personal data store for fresh enrollment")

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

                // Confirm identity phase events
                is WizardEvent.ConfirmIdentity -> confirmIdentityAndContinue()

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
                is WizardEvent.AddCustomField -> addCustomField(event.name, event.value, event.category, event.fieldType)
                is WizardEvent.UpdateCustomField -> updateCustomField(event.field)
                is WizardEvent.RemoveCustomField -> removeCustomField(event.fieldId)
                is WizardEvent.CreateCategory -> createCategory(event.name)
                is WizardEvent.SyncPersonalData -> syncPersonalData()
                is WizardEvent.ShowAddFieldDialog -> showAddFieldDialog()
                is WizardEvent.HideAddFieldDialog -> hideAddFieldDialog()
                is WizardEvent.ShowEditFieldDialog -> showEditFieldDialog(event.field)
                is WizardEvent.HideEditFieldDialog -> hideEditFieldDialog()
                is WizardEvent.DismissError -> dismissError()

                // Public profile phase events
                is WizardEvent.TogglePublicProfileField -> togglePublicProfileField(event.fieldNamespace)
                is WizardEvent.SelectAllPublicFields -> selectAllPublicFields()
                is WizardEvent.SelectNoPublicFields -> selectNoPublicFields()
                is WizardEvent.PublishProfile -> publishPublicProfile()
                is WizardEvent.SkipPublicProfile -> skipPublicProfile()

                // Complete phase events
                is WizardEvent.NavigateToMain -> navigateToMain()
            }
        }
    }

    // ============== NAVIGATION HANDLERS ==============

    private suspend fun handleNextStep() {
        when (val current = _state.value) {
            is WizardState.VerificationSuccess -> continueToPersonalData()
            is WizardState.PersonalData -> continueToPublicProfile()
            is WizardState.SetupPublicProfile -> completeWizard()
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
                // Skip personal data and go to public profile
                Log.i(TAG, "User skipped personal data")
                continueToPublicProfile()
            }
            is WizardState.SetupPublicProfile -> {
                // Skip public profile and complete
                Log.i(TAG, "User skipped public profile")
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
                WizardPhase.CONFIRM_IDENTITY -> {
                    // Return to confirm identity with stored data
                    val systemFields = personalDataStore.getSystemFields()
                    if (systemFields != null) {
                        _state.value = WizardState.ConfirmIdentity(
                            firstName = systemFields.firstName,
                            lastName = systemFields.lastName,
                            email = systemFields.email,
                            attestationInfo = storedAttestationInfo
                        )
                    } else {
                        _state.value = WizardState.SettingPin(attestationInfo = storedAttestationInfo)
                    }
                }
                WizardPhase.PIN_SETUP -> _state.value = WizardState.SettingPin(attestationInfo = storedAttestationInfo)
                WizardPhase.PASSWORD_SETUP -> _state.value = WizardState.SettingPassword(utks = nitroUtks)
                WizardPhase.VERIFY_CREDENTIAL -> _state.value = WizardState.VerifyingPassword()
                WizardPhase.PERSONAL_DATA -> loadPersonalData()
                WizardPhase.PUBLIC_PROFILE -> loadPublicProfile()
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

    // ============== CONFIRM IDENTITY PHASE ==============

    /**
     * User confirmed their identity - store registration info to vault and proceed to PIN setup.
     */
    private suspend fun confirmIdentityAndContinue() {
        val current = _state.value
        if (current !is WizardState.ConfirmIdentity) return

        // Store registration profile to send to vault during PIN setup
        // The vault receives this at the same time as the PIN, ensuring secure storage
        pendingRegistrationProfile = RegistrationProfile(
            firstName = current.firstName,
            lastName = current.lastName,
            email = current.email
        )
        Log.d(TAG, "Registration profile cached for PIN setup: ${current.firstName} ${current.email}")

        // Proceed to PIN setup
        _state.value = WizardState.SettingPin(attestationInfo = current.attestationInfo)
    }

    /**
     * Store registration info to vault with _system_ prefix.
     * These fields are read-only and cannot be edited by the user.
     */
    private suspend fun storeRegistrationInfoToVault(firstName: String, lastName: String, email: String) {
        if (!connectionManager.isConnected()) {
            Log.w(TAG, "Not connected to vault, skipping registration info sync")
            return
        }

        try {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("_system_first_name", firstName)
                addProperty("_system_last_name", lastName)
                addProperty("_system_email", email)
                addProperty("_system_stored_at", System.currentTimeMillis())
            }

            val result = ownerSpaceClient.sendToVault("profile.update", payload)
            result.fold(
                onSuccess = { requestId ->
                    Log.i(TAG, "Registration info stored to vault, requestId: $requestId")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to store registration info to vault: ${error.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error storing registration info to vault", e)
        }
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

        // Store registration profile if available (from NATS bootstrap response)
        if (bootstrap.registrationProfile != null) {
            val profile = bootstrap.registrationProfile
            Log.i(TAG, "Storing registration profile from bootstrap: ${profile.firstName} ${profile.lastName}")
            personalDataStore.storeSystemFields(
                com.vettid.app.core.storage.SystemPersonalData(
                    firstName = profile.firstName,
                    lastName = profile.lastName,
                    email = profile.email
                )
            )
        } else {
            // Fallback: fetch registration profile from API endpoint
            Log.d(TAG, "Registration profile not in bootstrap, fetching from API...")
            fetchRegistrationProfileFromApi()
        }

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

    /**
     * Fetch registration profile from the dedicated API endpoint.
     * This is used as a fallback when the bootstrap response doesn't include it.
     */
    private suspend fun fetchRegistrationProfileFromApi() {
        try {
            val result = vaultServiceClient.getRegistrationProfile()
            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Registration profile fetched from API: ${response.firstName} ${response.lastName}")
                    personalDataStore.storeSystemFields(
                        com.vettid.app.core.storage.SystemPersonalData(
                            firstName = response.firstName,
                            lastName = response.lastName,
                            email = response.email
                        )
                    )
                },
                onFailure = { error ->
                    Log.w(TAG, "Failed to fetch registration profile from API: ${error.message}")
                    // Continue without profile - user can still complete enrollment
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching registration profile from API", e)
        }
    }

    private suspend fun requestNitroAttestation() {
        _state.value = WizardState.RequestingAttestation(progress = 0.2f)

        // Ensure PCR values are fetched before attestation verification
        // This is critical - the async fetch started on app launch may not have completed yet
        Log.d(TAG, "Ensuring PCR values are fetched before attestation...")
        pcrInitializationService.initializeSync()
        Log.d(TAG, "PCR initialization complete, version: ${pcrConfigManager.getCurrentVersion()}")

        try {
            val result = nitroEnrollmentClient.requestAttestation()

            result.fold(
                onSuccess = { verifiedAttestation ->
                    Log.i(TAG, "Attestation verified: ${verifiedAttestation.moduleId}")
                    nitroVerifiedAttestation = verifiedAttestation

                    // NOTE: Attestation key is for session only, not for PIN unlock
                    // The ECIES public key for PIN unlock is returned in PIN setup response
                    Log.d(TAG, "Attestation verified - ECIES key will be returned in PIN setup")

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

                    // Move to Confirm Identity phase (if registration info is available)
                    val systemFields = personalDataStore.getSystemFields()
                    if (systemFields != null) {
                        _state.value = WizardState.ConfirmIdentity(
                            firstName = systemFields.firstName,
                            lastName = systemFields.lastName,
                            email = systemFields.email,
                            attestationInfo = attestationInfo
                        )
                    } else {
                        // Fallback to PIN setup if no registration info
                        Log.w(TAG, "No registration info available, skipping identity confirmation")
                        _state.value = WizardState.SettingPin(attestationInfo = attestationInfo)
                    }
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

            // Include registration profile in PIN setup so vault receives it during initialization
            // First try the cached profile, then fallback to loading from personalDataStore
            var profileToSend = pendingRegistrationProfile
            if (profileToSend == null) {
                // Fallback: try to get profile from personalDataStore
                val systemFields = personalDataStore.getSystemFields()
                if (systemFields != null) {
                    profileToSend = RegistrationProfile(
                        firstName = systemFields.firstName,
                        lastName = systemFields.lastName,
                        email = systemFields.email
                    )
                    Log.d(TAG, "Recovered registration profile from personalDataStore: ${profileToSend.firstName} ${profileToSend.email}")
                }
            }
            if (profileToSend != null) {
                Log.d(TAG, "Including registration profile in PIN setup: ${profileToSend.firstName} ${profileToSend.email}")
            } else {
                Log.w(TAG, "No registration profile available to send to vault")
            }

            val result = nitroEnrollmentClient.setupPin(current.pin, profileToSend)

            result.fold(
                onSuccess = { response ->
                    if (response.isSuccess) {
                        Log.i(TAG, "PIN setup complete, vault ready with ${response.utks?.size ?: 0} UTKs")
                        response.utks?.let { nitroUtks = it }

                        // Store ECIES public key for PIN unlock (different from attestation key!)
                        response.eciesPublicKey?.let { eciesKeyB64 ->
                            try {
                                val eciesKeyBytes = android.util.Base64.decode(eciesKeyB64, android.util.Base64.NO_WRAP)
                                credentialStore.storeEnclavePublicKey(eciesKeyBytes)
                                Log.i(TAG, "Stored ECIES public key for PIN unlock (${eciesKeyBytes.size} bytes)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode ECIES public key", e)
                            }
                        } ?: Log.w(TAG, "No ECIES public key in PIN setup response")

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

                    // Fetch registration info from vault via NATS (before disconnecting)
                    _state.value = WizardState.CreatingCredential(
                        message = "Finalizing setup...",
                        progress = 0.95f
                    )
                    val profileData = nitroEnrollmentClient.requestProfileData()
                    if (profileData != null) {
                        Log.i(TAG, "Profile data received: ${profileData.firstName} ${profileData.lastName}")
                        personalDataStore.storeSystemFields(
                            com.vettid.app.core.storage.SystemPersonalData(
                                firstName = profileData.firstName,
                                lastName = profileData.lastName,
                                email = profileData.email
                            )
                        )
                    } else {
                        Log.w(TAG, "Profile data not available from vault")
                    }

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

                    // NOTE: Keep enrollment client connected for personal data sync
                    // It will be disconnected in completeWizard() after sync

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
                        progress = 0.95f,
                        statusMessage = "Completing setup..."
                    )

                    // Finalize enrollment and store registration profile
                    fetchAndStoreRegistrationProfile()

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

    /**
     * Finalize enrollment and optionally fetch registration profile.
     *
     * IMPORTANT: This method MUST always call the finalize endpoint to mark the
     * enrollment session as COMPLETED in the backend. The profile data storage
     * is optional and only done if not already present.
     */
    private suspend fun fetchAndStoreRegistrationProfile() {
        val hasExistingProfile = personalDataStore.hasSystemFields()

        try {
            Log.d(TAG, "Calling finalize endpoint to complete enrollment session")
            val result = vaultServiceClient.finalizeEnrollmentForProfile()

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Enrollment finalized successfully")

                    // Only store profile data if we don't already have it
                    if (!hasExistingProfile) {
                        response.registrationProfile?.let { profile ->
                            Log.i(TAG, "Storing registration profile: ${profile.firstName} ${profile.lastName}")
                            personalDataStore.storeSystemFields(
                                com.vettid.app.core.storage.SystemPersonalData(
                                    firstName = profile.firstName,
                                    lastName = profile.lastName,
                                    email = profile.email
                                )
                            )
                        } ?: run {
                            Log.w(TAG, "Finalize response did not include registration profile")
                        }
                    } else {
                        Log.d(TAG, "Profile data already stored, skipping storage")
                    }
                },
                onFailure = { error ->
                    // Log but don't fail enrollment if finalize fails
                    // This can happen if session was already finalized (409 Conflict)
                    Log.w(TAG, "Failed to finalize enrollment: ${error.message}")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error finalizing enrollment", e)
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
                val customCategories = loadCustomCategoriesFromVault()
                val hasPendingSync = personalDataStore.hasPendingSync()

                _state.value = WizardState.PersonalData(
                    isLoading = false,
                    systemFields = systemFields,
                    optionalFields = optionalFields,
                    customFields = customFields,
                    customCategories = customCategories,
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

    private suspend fun loadCustomCategoriesFromVault(): List<CategoryInfo> {
        return try {
            // Use enrollment client during enrollment, otherwise use ownerSpaceClient
            val useEnrollmentClient = nitroEnrollmentClient.isConnected
            val result = if (useEnrollmentClient) {
                nitroEnrollmentClient.sendToVault("profile.categories.get", com.google.gson.JsonObject())
            } else {
                ownerSpaceClient.getCategories()
            }
            if (result.isSuccess) {
                val json = com.google.gson.JsonParser.parseString(result.getOrNull() ?: "{}").asJsonObject
                val customArray = json.getAsJsonArray("custom") ?: return emptyList()
                customArray.mapNotNull { element ->
                    val obj = element.asJsonObject
                    CategoryInfo(
                        id = obj.get("id")?.asString ?: return@mapNotNull null,
                        name = obj.get("name")?.asString ?: return@mapNotNull null,
                        icon = obj.get("icon")?.asString ?: "more",
                        createdAt = obj.get("created_at")?.asLong ?: 0
                    )
                }
            } else {
                Log.w(TAG, "Failed to load custom categories: ${result.exceptionOrNull()?.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom categories", e)
            emptyList()
        }
    }

    private fun createCategory(name: String) {
        val current = _state.value
        if (current !is WizardState.PersonalData) return

        viewModelScope.launch {
            try {
                // Create the new category
                val newCategory = CategoryInfo(
                    id = name.lowercase().replace(" ", "_").replace(Regex("[^a-z0-9_]"), ""),
                    name = name,
                    icon = "more",
                    createdAt = System.currentTimeMillis()
                )

                // Get existing custom categories and add new one
                val updatedCategories = current.customCategories + newCategory

                // Sync to vault - use enrollment client during enrollment
                val useEnrollmentClient = nitroEnrollmentClient.isConnected
                val payload = com.google.gson.JsonObject().apply {
                    val categoriesArray = com.google.gson.JsonArray()
                    updatedCategories.forEach { cat ->
                        val categoryObj = com.google.gson.JsonObject().apply {
                            addProperty("id", cat.id)
                            addProperty("name", cat.name)
                            addProperty("icon", cat.icon)
                            addProperty("created_at", cat.createdAt)
                        }
                        categoriesArray.add(categoryObj)
                    }
                    add("categories", categoriesArray)
                }
                val syncResult = if (useEnrollmentClient) {
                    nitroEnrollmentClient.sendToVault("profile.categories.update", payload)
                } else {
                    ownerSpaceClient.sendToVault("profile.categories.update", payload)
                }
                if (syncResult.isFailure) {
                    Log.w(TAG, "Failed to sync categories to vault: ${syncResult.exceptionOrNull()?.message}")
                }

                // Update state with new category
                _state.value = current.copy(customCategories = updatedCategories)

                Log.i(TAG, "Created custom category: ${newCategory.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create category", e)
                _state.value = current.copy(error = "Failed to create category: ${e.message}")
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

    private fun addCustomField(name: String, value: String, category: FieldCategory, fieldType: FieldType) {
        val current = _state.value
        if (current !is WizardState.PersonalData) return

        // Validate field name
        val validationError = personalDataStore.validateFieldName(name)
        if (validationError != null) {
            _state.value = current.copy(error = validationError)
            return
        }

        // Check for duplicate names
        if (personalDataStore.isFieldNameTaken(name)) {
            _state.value = current.copy(error = "A field with this name already exists")
            return
        }

        personalDataStore.addCustomField(name, value, category, fieldType)
        _state.value = current.copy(
            customFields = personalDataStore.getCustomFields(),
            hasPendingSync = true,
            showAddFieldDialog = false
        )
    }

    private fun updateCustomField(field: CustomField) {
        val current = _state.value
        if (current !is WizardState.PersonalData) return

        // Validate field name
        val validationError = personalDataStore.validateFieldName(field.name)
        if (validationError != null) {
            _state.value = current.copy(error = validationError)
            return
        }

        // Check for duplicate names (exclude this field's ID)
        if (personalDataStore.isFieldNameTaken(field.name, excludeId = field.id)) {
            _state.value = current.copy(error = "A field with this name already exists")
            return
        }

        // Normalize the field name before saving
        val normalizedField = field.copy(name = personalDataStore.normalizeFieldName(field.name))
        personalDataStore.updateCustomField(normalizedField)
        _state.value = current.copy(
            customFields = personalDataStore.getCustomFields(),
            hasPendingSync = true,
            editingField = null
        )
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

        // Check which connection is available
        val useEnrollmentClient = nitroEnrollmentClient.isConnected
        val useConnectionManager = connectionManager.isConnected()

        if (!useEnrollmentClient && !useConnectionManager) {
            _state.value = current.copy(error = "Not connected to vault. Changes will sync when connection is restored.")
            return
        }

        _state.value = current.copy(isSyncing = true)

        try {
            // Use exportFieldsMapForPersonalData() which excludes system fields
            val fieldsMap = personalDataStore.exportFieldsMapForPersonalData()

            if (fieldsMap.isEmpty()) {
                _state.update {
                    if (it is WizardState.PersonalData) {
                        it.copy(isSyncing = false, hasPendingSync = false)
                    } else it
                }
                _effects.emit(WizardEffect.ShowToast("No personal data to sync"))
                return
            }

            // Build payload for personal-data.update
            val fieldsObject = com.google.gson.JsonObject().apply {
                fieldsMap.forEach { (key, value) ->
                    addProperty(key, value)
                }
            }
            val payload = com.google.gson.JsonObject().apply {
                add("fields", fieldsObject)
            }

            Log.d(TAG, "Syncing personal data: ${fieldsMap.size} fields")
            fieldsMap.forEach { (key, value) ->
                Log.d(TAG, "  Field: $key = ${value.take(30)}${if (value.length > 30) "..." else ""}")
            }

            // Send to personal-data.update (not profile.update)
            val result = if (useEnrollmentClient) {
                nitroEnrollmentClient.sendToVault("personal-data.update", payload)
            } else {
                ownerSpaceClient.sendToVault("personal-data.update", payload)
            }

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
        when (current) {
            is WizardState.PersonalData -> _state.value = current.copy(error = null)
            is WizardState.SetupPublicProfile -> _state.value = current.copy(error = null)
            else -> { /* No-op */ }
        }
    }

    // ============== PUBLIC PROFILE PHASE ==============

    private suspend fun continueToPublicProfile() {
        // Always sync personal data to vault before moving to public profile
        Log.d(TAG, "continueToPublicProfile: hasPendingSync=${personalDataStore.hasPendingSync()}")

        val fieldsMap = personalDataStore.exportFieldsMapForProfileUpdate()
        Log.d(TAG, "continueToPublicProfile: ${fieldsMap.size} fields to sync")
        fieldsMap.forEach { (key, value) ->
            Log.d(TAG, "  Field: $key = ${value.take(20)}...")
        }

        // Force sync all personal data
        syncPersonalDataAndWait()

        loadPublicProfile()
    }

    private suspend fun syncPersonalDataAndWait() {
        // Check which NATS connection is available:
        // During enrollment, nitroEnrollmentClient is connected but connectionManager is not
        // After enrollment, connectionManager is used
        val useEnrollmentClient = nitroEnrollmentClient.isConnected
        val useConnectionManager = connectionManager.isConnected()

        if (!useEnrollmentClient && !useConnectionManager) {
            Log.w(TAG, "Cannot sync personal data - not connected to vault")
            return
        }

        try {
            // Use exportFieldsMapForPersonalData() which excludes system fields
            // System fields are already stored in profile/_system_* during PIN setup
            val fieldsMap = personalDataStore.exportFieldsMapForPersonalData()
            if (fieldsMap.isEmpty()) {
                Log.d(TAG, "No personal data fields to sync (system fields excluded)")
                return
            }

            val fieldsObject = com.google.gson.JsonObject().apply {
                fieldsMap.forEach { (key, value) ->
                    addProperty(key, value)
                }
            }
            val payload = com.google.gson.JsonObject().apply {
                add("fields", fieldsObject)
            }

            Log.i(TAG, "Syncing ${fieldsMap.size} personal data fields to vault via ${if (useEnrollmentClient) "enrollment client" else "connection manager"}")
            fieldsMap.forEach { (key, value) ->
                Log.d(TAG, "  Field: $key = ${value.take(30)}${if (value.length > 30) "..." else ""}")
            }

            // Use the enrollment client's connection during enrollment, otherwise use ownerSpaceClient
            val result = if (useEnrollmentClient) {
                nitroEnrollmentClient.sendToVault("personal-data.update", payload)
            } else {
                ownerSpaceClient.sendToVault("personal-data.update", payload)
            }

            result.fold(
                onSuccess = { requestId ->
                    Log.i(TAG, "Personal data sync sent: $requestId, waiting for vault to process...")

                    // Wait for vault to process the data before continuing
                    // The vault needs time to store the data before we can read it back
                    delay(1500)

                    Log.i(TAG, "Personal data sync complete after delay")
                    personalDataStore.markSyncComplete()
                },
                onFailure = { error ->
                    Log.e(TAG, "Personal data sync failed: ${error.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing personal data", e)
        }
    }

    private fun loadPublicProfile() {
        viewModelScope.launch {
            _state.value = WizardState.SetupPublicProfile(isLoading = true)

            try {
                val systemFields = personalDataStore.getSystemFields()
                val optionalFields = personalDataStore.getOptionalFields()
                val customFields = personalDataStore.getCustomFields()

                // Build list of available fields for public profile
                // Field naming convention: {category}.{subcategory}.{field_name}
                val availableFields = mutableListOf<PublicProfileField>()

                // Identity fields (personal.legal.* and personal.info.*)
                optionalFields.prefix?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "personal.legal.prefix",
                        displayName = "Name Prefix",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "identity"
                    ))
                }
                optionalFields.firstName?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "personal.legal.first_name",
                        displayName = "Legal First Name",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "identity"
                    ))
                }
                optionalFields.middleName?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "personal.legal.middle_name",
                        displayName = "Middle Name",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "identity"
                    ))
                }
                optionalFields.lastName?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "personal.legal.last_name",
                        displayName = "Legal Last Name",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "identity"
                    ))
                }
                optionalFields.suffix?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "personal.legal.suffix",
                        displayName = "Name Suffix",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "identity"
                    ))
                }
                optionalFields.birthday?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "personal.info.birthday",
                        displayName = "Birthday",
                        value = it,
                        fieldType = FieldType.DATE,
                        category = "identity"
                    ))
                }

                // Contact fields (contact.phone.*)
                optionalFields.phone?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "contact.phone.mobile",
                        displayName = "Mobile Phone",
                        value = it,
                        fieldType = FieldType.PHONE,
                        category = "contact"
                    ))
                }

                // Address fields (address.home.*)
                optionalFields.street?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "address.home.street",
                        displayName = "Street Address",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "address"
                    ))
                }
                optionalFields.street2?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "address.home.street2",
                        displayName = "Street Address 2",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "address"
                    ))
                }
                optionalFields.city?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "address.home.city",
                        displayName = "City",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "address"
                    ))
                }
                optionalFields.state?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "address.home.state",
                        displayName = "State",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "address"
                    ))
                }
                optionalFields.postalCode?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "address.home.postal_code",
                        displayName = "Postal Code",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "address"
                    ))
                }
                optionalFields.country?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "address.home.country",
                        displayName = "Country",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "address"
                    ))
                }

                // Social/Web fields (social.*.*)
                optionalFields.website?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "social.website.personal",
                        displayName = "Personal Website",
                        value = it,
                        fieldType = FieldType.URL,
                        category = "social"
                    ))
                }
                optionalFields.linkedin?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "social.linkedin.url",
                        displayName = "LinkedIn",
                        value = it,
                        fieldType = FieldType.URL,
                        category = "social"
                    ))
                }
                optionalFields.twitter?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "social.twitter.handle",
                        displayName = "X/Twitter",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "social"
                    ))
                }
                optionalFields.instagram?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "social.instagram.handle",
                        displayName = "Instagram",
                        value = it,
                        fieldType = FieldType.TEXT,
                        category = "social"
                    ))
                }
                optionalFields.github?.let {
                    availableFields.add(PublicProfileField(
                        namespace = "social.github.username",
                        displayName = "GitHub",
                        value = it,
                        fieldType = FieldType.URL,
                        category = "social"
                    ))
                }

                // Add custom fields
                customFields.forEach { field ->
                    val namespace = personalDataStore.generateNamespace(
                        field.category.name.lowercase(),
                        field.name
                    )
                    availableFields.add(PublicProfileField(
                        namespace = namespace,
                        displayName = field.name,
                        value = field.value,
                        fieldType = field.fieldType,
                        category = field.category.name.lowercase(),
                        isSensitive = field.fieldType == FieldType.PASSWORD
                    ))
                }

                // Load previously selected fields
                val selectedFields = personalDataStore.getPublicProfileFields().toSet()

                _state.value = WizardState.SetupPublicProfile(
                    isLoading = false,
                    systemFields = systemFields,
                    availableFields = availableFields,
                    selectedFields = selectedFields
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load public profile data", e)
                _state.value = WizardState.SetupPublicProfile(
                    isLoading = false,
                    error = "Failed to load profile data: ${e.message}"
                )
            }
        }
    }

    private fun togglePublicProfileField(namespace: String) {
        val current = _state.value
        if (current !is WizardState.SetupPublicProfile) return

        val newSelected = if (current.selectedFields.contains(namespace)) {
            current.selectedFields - namespace
        } else {
            current.selectedFields + namespace
        }

        _state.value = current.copy(selectedFields = newSelected)
    }

    private fun selectAllPublicFields() {
        val current = _state.value
        if (current !is WizardState.SetupPublicProfile) return

        val allNonSensitive = current.availableFields
            .filter { !it.isSensitive }
            .map { it.namespace }
            .toSet()

        _state.value = current.copy(selectedFields = allNonSensitive)
    }

    private fun selectNoPublicFields() {
        val current = _state.value
        if (current !is WizardState.SetupPublicProfile) return

        _state.value = current.copy(selectedFields = emptySet())
    }

    private suspend fun publishPublicProfile() {
        val current = _state.value
        if (current !is WizardState.SetupPublicProfile) return

        _state.value = current.copy(isPublishing = true, error = null)

        try {
            // Save selected fields locally
            personalDataStore.updatePublicProfileFields(current.selectedFields.toList())

            // Build payload with selected fields
            val payload = com.google.gson.JsonObject().apply {
                val fieldsArray = com.google.gson.JsonArray()
                current.selectedFields.forEach { fieldsArray.add(it) }
                add("fields", fieldsArray)
            }

            Log.d(TAG, "Publishing profile with ${current.selectedFields.size} selected fields: ${current.selectedFields}")

            // Use enrollment client if connected (during enrollment), otherwise use ownerSpaceClient
            val useEnrollmentClient = nitroEnrollmentClient.isConnected
            val useConnectionManager = connectionManager.isConnected()

            if (useEnrollmentClient) {
                Log.d(TAG, "Publishing via nitroEnrollmentClient")
                val result = nitroEnrollmentClient.sendToVault("profile.publish", payload)
                result.fold(
                    onSuccess = { requestId ->
                        Log.i(TAG, "Public profile publish request sent via enrollment client: $requestId")
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Failed to publish profile via enrollment client: ${error.message}")
                    }
                )
            } else if (useConnectionManager) {
                Log.d(TAG, "Publishing via ownerSpaceClient")
                val result = ownerSpaceClient.publishProfile(current.selectedFields.toList())
                result.fold(
                    onSuccess = { requestId ->
                        Log.i(TAG, "Public profile publish request sent via ownerSpaceClient: $requestId")
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Failed to publish profile via ownerSpaceClient: ${error.message}")
                    }
                )
            } else {
                Log.w(TAG, "Not connected to vault - profile settings saved locally only")
            }

            _effects.emit(WizardEffect.ShowToast("Public profile saved"))
            completeWizard()
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing profile", e)
            _state.value = current.copy(
                isPublishing = false,
                error = "Failed to save profile: ${e.message}"
            )
        }
    }

    private suspend fun skipPublicProfile() {
        Log.i(TAG, "User skipped public profile setup")
        completeWizard()
    }

    // ============== COMPLETE PHASE ==============

    private suspend fun completeWizard() {
        // Schedule background sync if there are pending personal data changes
        // This handles the case where personal data was entered during enrollment
        // but couldn't be synced because vault wasn't connected yet
        if (personalDataStore.hasPendingSync()) {
            Log.i(TAG, "Scheduling PersonalDataSyncWorker for pending changes")
            PersonalDataSyncWorker.scheduleImmediate(context)
        }

        _state.value = WizardState.Complete(userGuid = userGuid ?: "")
    }

    private suspend fun navigateToMain() {
        Log.i(TAG, "navigateToMain() called - emitting NavigateToMain effect")

        // Update state with shouldNavigate flag as backup for effect collection issues
        val current = _state.value
        if (current is WizardState.Complete) {
            _state.value = current.copy(shouldNavigate = true)
        }

        _effects.emit(WizardEffect.NavigateToMain)
        Log.i(TAG, "navigateToMain() - effect emitted and shouldNavigate set")
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
