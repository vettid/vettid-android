package com.vettid.app.features.transfer

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.attestation.HardwareAttestationManager
import com.vettid.app.core.nats.DeviceInfo
import com.vettid.app.core.nats.NitroEnrollmentClient
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultEvent
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

private const val TAG = "TransferViewModel"

/**
 * ViewModel for device-to-device credential transfer (Issue #31).
 *
 * Handles both:
 * - Transfer REQUEST flow (new device requesting credentials)
 * - Transfer APPROVAL flow (old device approving/denying)
 *
 * The transfer flow:
 * 1. New device: Generate attestation, send transfer request
 * 2. Old device: Receive notification, show approval screen
 * 3. Old device: User authenticates with biometric, approves/denies
 * 4. New device: Receives approval/denial via NATS
 * 5. If approved: New device receives encrypted credentials
 *
 * Security:
 * - 15-minute timeout on requests
 * - Biometric required for approval
 * - Device attestation on both devices
 * - Single-use transfer tokens
 */
@HiltViewModel
class TransferViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialStore: CredentialStore,
    private val attestationManager: HardwareAttestationManager,
    private val nitroEnrollmentClient: NitroEnrollmentClient,
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    // Transfer ID from navigation (for approval flow)
    private val transferIdArg: String? = savedStateHandle.get<String>("transferId")

    // MARK: - Request Flow State (New Device)

    private val _requestState = MutableStateFlow<TransferRequestState>(TransferRequestState.Idle)
    val requestState: StateFlow<TransferRequestState> = _requestState.asStateFlow()

    private val _requestEffects = MutableSharedFlow<TransferRequestEffect>()
    val requestEffects: SharedFlow<TransferRequestEffect> = _requestEffects.asSharedFlow()

    // MARK: - Approval Flow State (Old Device)

    private val _approvalState = MutableStateFlow<TransferApprovalState>(TransferApprovalState.Loading)
    val approvalState: StateFlow<TransferApprovalState> = _approvalState.asStateFlow()

    private val _approvalEffects = MutableSharedFlow<TransferApprovalEffect>()
    val approvalEffects: SharedFlow<TransferApprovalEffect> = _approvalEffects.asSharedFlow()

    // Timer job for countdown
    private var countdownJob: Job? = null

    // Current transfer being processed
    private var currentTransferId: String? = null

    init {
        // If transfer ID was passed, we're in approval flow
        transferIdArg?.let { transferId ->
            loadTransferForApproval(transferId)
        }

        // Subscribe to vault events for transfer updates
        subscribeToTransferEvents()
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }

    // MARK: - Request Flow Events (New Device)

    fun onRequestEvent(event: TransferRequestEvent) {
        when (event) {
            is TransferRequestEvent.StartTransfer -> startTransfer()
            is TransferRequestEvent.CancelTransfer -> cancelTransfer()
            is TransferRequestEvent.Retry -> retryTransfer()
            is TransferRequestEvent.Dismiss -> dismissRequest()
        }
    }

    // MARK: - Approval Flow Events (Old Device)

    fun onApprovalEvent(event: TransferApprovalEvent) {
        when (event) {
            is TransferApprovalEvent.LoadTransfer -> loadTransferForApproval(event.transferId)
            is TransferApprovalEvent.ApproveTransfer -> initiateApproval()
            is TransferApprovalEvent.DenyTransfer -> denyTransfer()
            is TransferApprovalEvent.BiometricSuccess -> completeApproval()
            is TransferApprovalEvent.BiometricFailed -> handleBiometricFailure(event.error)
            is TransferApprovalEvent.Dismiss -> dismissApproval()
        }
    }

    // MARK: - Request Flow Implementation (New Device)

    private fun startTransfer() {
        viewModelScope.launch {
            _requestState.value = TransferRequestState.PreparingAttestation

            try {
                // Generate device attestation
                val attestation = generateAttestation()
                if (attestation == null) {
                    _requestState.value = TransferRequestState.Failed(
                        error = "Failed to generate device attestation",
                        retryable = true
                    )
                    return@launch
                }

                _requestState.value = TransferRequestState.SendingRequest

                // Send transfer request to vault
                val deviceInfo = getDeviceInfo()
                val response = sendTransferRequest(attestation, deviceInfo)

                if (response?.success == true && response.transfer != null) {
                    currentTransferId = response.transfer.transferId
                    startCountdown(response.transfer.expiresAt)
                    _requestState.value = TransferRequestState.WaitingForApproval(
                        transfer = response.transfer,
                        remainingSeconds = calculateRemainingSeconds(response.transfer.expiresAt)
                    )
                } else {
                    _requestState.value = TransferRequestState.Failed(
                        error = response?.error ?: "Failed to create transfer request",
                        retryable = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start transfer", e)
                _requestState.value = TransferRequestState.Failed(
                    error = e.message ?: "Failed to start transfer",
                    retryable = true
                )
            }
        }
    }

    private fun cancelTransfer() {
        viewModelScope.launch {
            countdownJob?.cancel()
            currentTransferId?.let { transferId ->
                // Send cancellation to vault
                try {
                    val request = mapOf(
                        "operation" to "cancel_transfer",
                        "transfer_id" to transferId
                    )
                    nitroEnrollmentClient.sendVaultOperation(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel transfer", e)
                }
            }
            currentTransferId = null
            _requestState.value = TransferRequestState.Idle
            _requestEffects.emit(TransferRequestEffect.NavigateBack)
        }
    }

    private fun retryTransfer() {
        _requestState.value = TransferRequestState.Idle
        startTransfer()
    }

    private fun dismissRequest() {
        viewModelScope.launch {
            countdownJob?.cancel()
            _requestEffects.emit(TransferRequestEffect.NavigateBack)
        }
    }

    // MARK: - Approval Flow Implementation (Old Device)

    private fun loadTransferForApproval(transferId: String) {
        viewModelScope.launch {
            _approvalState.value = TransferApprovalState.Loading
            currentTransferId = transferId

            try {
                // Fetch transfer details from vault
                val request = mapOf(
                    "operation" to "get_transfer",
                    "transfer_id" to transferId
                )
                val response = nitroEnrollmentClient.sendVaultOperation(request)

                @Suppress("UNCHECKED_CAST")
                val transferData = response?.get("transfer") as? Map<String, Any>

                if (transferData != null) {
                    val transfer = parseTransferRequest(transferId, transferData)

                    // Check if expired
                    if (transfer.expiresAt.isBefore(Instant.now())) {
                        _approvalState.value = TransferApprovalState.Expired(
                            transferId = transferId
                        )
                        return@launch
                    }

                    // Check if already decided
                    if (transfer.status != TransferStatus.PENDING) {
                        _approvalState.value = TransferApprovalState.Error(
                            message = "Transfer has already been ${transfer.status.name.lowercase()}"
                        )
                        return@launch
                    }

                    startCountdown(transfer.expiresAt)
                    _approvalState.value = TransferApprovalState.Ready(
                        transfer = transfer,
                        remainingSeconds = calculateRemainingSeconds(transfer.expiresAt)
                    )
                } else {
                    _approvalState.value = TransferApprovalState.Error(
                        message = "Transfer not found"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load transfer", e)
                _approvalState.value = TransferApprovalState.Error(
                    message = e.message ?: "Failed to load transfer details"
                )
            }
        }
    }

    private fun initiateApproval() {
        viewModelScope.launch {
            _approvalState.value = TransferApprovalState.AwaitingBiometric
            _approvalEffects.emit(TransferApprovalEffect.RequestBiometric)
        }
    }

    private fun completeApproval() {
        viewModelScope.launch {
            _approvalState.value = TransferApprovalState.ProcessingApproval

            try {
                // Generate attestation for approval
                val attestation = generateAttestation()

                val transferId = currentTransferId ?: return@launch
                val request = TransferDecisionRequest(
                    transferId = transferId,
                    approved = true,
                    deviceAttestation = attestation
                )

                val response = sendTransferDecision(request)

                if (response?.success == true) {
                    countdownJob?.cancel()
                    _approvalState.value = TransferApprovalState.Approved(
                        transferId = transferId
                    )
                    _approvalEffects.emit(TransferApprovalEffect.ShowSuccess("Transfer approved"))
                } else {
                    _approvalState.value = TransferApprovalState.Error(
                        message = response?.error ?: "Failed to approve transfer"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to approve transfer", e)
                _approvalState.value = TransferApprovalState.Error(
                    message = e.message ?: "Failed to approve transfer"
                )
            }
        }
    }

    private fun denyTransfer() {
        viewModelScope.launch {
            _approvalState.value = TransferApprovalState.ProcessingDenial

            try {
                val transferId = currentTransferId ?: return@launch
                val request = TransferDecisionRequest(
                    transferId = transferId,
                    approved = false
                )

                val response = sendTransferDecision(request)

                if (response?.success == true) {
                    countdownJob?.cancel()
                    _approvalState.value = TransferApprovalState.DeniedComplete(
                        transferId = transferId
                    )
                } else {
                    _approvalState.value = TransferApprovalState.Error(
                        message = response?.error ?: "Failed to deny transfer"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deny transfer", e)
                _approvalState.value = TransferApprovalState.Error(
                    message = e.message ?: "Failed to deny transfer"
                )
            }
        }
    }

    private fun handleBiometricFailure(error: String) {
        viewModelScope.launch {
            // Return to ready state to allow retry
            val currentState = _approvalState.value
            if (currentState is TransferApprovalState.AwaitingBiometric) {
                // We need to reload the transfer to get back to Ready state
                currentTransferId?.let { loadTransferForApproval(it) }
            }
            _approvalEffects.emit(TransferApprovalEffect.ShowError("Authentication failed: $error"))
        }
    }

    private fun dismissApproval() {
        viewModelScope.launch {
            countdownJob?.cancel()
            _approvalEffects.emit(TransferApprovalEffect.NavigateBack)
        }
    }

    // MARK: - NATS Event Subscription

    private fun subscribeToTransferEvents() {
        viewModelScope.launch {
            ownerSpaceClient.vaultEvents.collect { event ->
                handleVaultEvent(event)
            }
        }
    }

    private fun handleVaultEvent(event: VaultEvent) {
        val transferId = currentTransferId ?: return

        when (event) {
            is VaultEvent.TransferApproved -> {
                if (event.transferId == transferId) {
                    viewModelScope.launch {
                        _requestState.value = TransferRequestState.ReceivingCredentials
                        // Credentials will be received in a separate message
                    }
                }
            }
            is VaultEvent.TransferDenied -> {
                if (event.transferId == transferId) {
                    viewModelScope.launch {
                        countdownJob?.cancel()
                        _requestState.value = TransferRequestState.Denied(transferId)
                    }
                }
            }
            is VaultEvent.TransferCompleted -> {
                if (event.transferId == transferId) {
                    viewModelScope.launch {
                        countdownJob?.cancel()
                        _requestState.value = TransferRequestState.Completed(transferId)
                        _requestEffects.emit(TransferRequestEffect.NavigateToMain)
                    }
                }
            }
            is VaultEvent.TransferExpired -> {
                if (event.transferId == transferId) {
                    viewModelScope.launch {
                        countdownJob?.cancel()
                        _requestState.value = TransferRequestState.Expired(transferId)
                    }
                }
            }
            else -> { /* Ignore other events */ }
        }
    }

    // MARK: - Helper Functions

    private fun generateAttestation(): String? {
        return try {
            // Generate a random challenge for attestation
            val challenge = java.security.SecureRandom().let { random ->
                ByteArray(32).also { random.nextBytes(it) }
            }
            val result = attestationManager.generateAttestationKey(challenge)
            // Serialize the attestation certificate chain as base64
            val chainBytes = result.certificateChain.joinToString(",") { cert ->
                android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
            }
            chainBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate attestation", e)
            null
        }
    }

    private fun getDeviceInfo(): DeviceInfo {
        // Use Android ID or Build fingerprint as device identifier
        val deviceId = android.os.Build.FINGERPRINT.hashCode().toString(16)
        return DeviceInfo(
            deviceId = deviceId,
            model = android.os.Build.MODEL,
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            location = null // Could be populated with approximate location if permitted
        )
    }

    private suspend fun sendTransferRequest(
        attestation: String,
        deviceInfo: DeviceInfo
    ): InitiateTransferResponse? {
        val request = mapOf(
            "operation" to "initiate_transfer",
            "device_attestation" to attestation,
            "device_info" to mapOf(
                "device_id" to deviceInfo.deviceId,
                "model" to deviceInfo.model,
                "os_version" to deviceInfo.osVersion,
                "location" to deviceInfo.location
            )
        )

        val response = nitroEnrollmentClient.sendVaultOperation(request)

        return response?.let { resp ->
            @Suppress("UNCHECKED_CAST")
            val transferData = resp["transfer"] as? Map<String, Any>

            InitiateTransferResponse(
                success = resp["success"] as? Boolean ?: false,
                transfer = transferData?.let { parseTransferRequest(it["transfer_id"] as String, it) },
                error = resp["error"] as? String
            )
        }
    }

    private suspend fun sendTransferDecision(
        request: TransferDecisionRequest
    ): TransferDecisionResponse? {
        val requestMap = mutableMapOf<String, Any>(
            "operation" to if (request.approved) "approve_transfer" else "deny_transfer",
            "transfer_id" to request.transferId
        )
        request.deviceAttestation?.let { requestMap["device_attestation"] = it }

        val response = nitroEnrollmentClient.sendVaultOperation(requestMap)

        return response?.let { resp ->
            val statusStr = resp["status"] as? String
            TransferDecisionResponse(
                success = resp["success"] as? Boolean ?: false,
                status = statusStr?.let { TransferStatus.valueOf(it.uppercase()) },
                error = resp["error"] as? String
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTransferRequest(transferId: String, data: Map<String, Any>): TransferRequest {
        val deviceInfoData = data["device_info"] as? Map<String, Any> ?: emptyMap()

        return TransferRequest(
            transferId = transferId,
            sourceDeviceId = data["source_device_id"] as? String,
            targetDeviceId = data["target_device_id"] as? String,
            deviceInfo = DeviceInfo(
                deviceId = deviceInfoData["device_id"] as? String ?: "unknown",
                model = deviceInfoData["model"] as? String ?: "Unknown Device",
                osVersion = deviceInfoData["os_version"] as? String ?: "Unknown OS",
                location = deviceInfoData["location"] as? String
            ),
            createdAt = Instant.parse(data["created_at"] as? String ?: Instant.now().toString()),
            expiresAt = Instant.parse(data["expires_at"] as? String ?: Instant.now().plusSeconds(900).toString()),
            status = (data["status"] as? String)?.let {
                TransferStatus.valueOf(it.uppercase())
            } ?: TransferStatus.PENDING
        )
    }

    private fun calculateRemainingSeconds(expiresAt: Instant): Long {
        return Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(0)
    }

    private fun startCountdown(expiresAt: Instant) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                val remaining = calculateRemainingSeconds(expiresAt)
                if (remaining <= 0) {
                    // Expired
                    val transferId = currentTransferId ?: break
                    when (val state = _requestState.value) {
                        is TransferRequestState.WaitingForApproval -> {
                            _requestState.value = TransferRequestState.Expired(transferId)
                        }
                        else -> { /* Ignore in other states */ }
                    }
                    when (val state = _approvalState.value) {
                        is TransferApprovalState.Ready -> {
                            _approvalState.value = TransferApprovalState.Expired(transferId)
                        }
                        else -> { /* Ignore in other states */ }
                    }
                    break
                }

                // Update countdown in state
                when (val state = _requestState.value) {
                    is TransferRequestState.WaitingForApproval -> {
                        _requestState.value = state.copy(remainingSeconds = remaining)
                    }
                    else -> { /* Ignore in other states */ }
                }
                when (val state = _approvalState.value) {
                    is TransferApprovalState.Ready -> {
                        _approvalState.value = state.copy(remainingSeconds = remaining)
                    }
                    else -> { /* Ignore in other states */ }
                }

                delay(1000)
            }
        }
    }
}
