package com.vettid.app.features.transfer

import com.google.gson.annotations.SerializedName
import com.vettid.app.core.nats.DeviceInfo
import java.time.Instant

/**
 * Data models for Issue #31: Device-to-Device Credential Transfer.
 *
 * Transfer allows instant credential transfer between a user's own devices,
 * bypassing the 24-hour recovery delay. Requires biometric authentication
 * on the existing device.
 *
 * Security:
 * - 15-minute timeout on transfer requests
 * - Biometric required on old device to approve
 * - Device attestation binds transfer to specific devices
 * - Single-use transfer tokens
 * - All transfers are audit logged
 */

// MARK: - Transfer Models

/**
 * A credential transfer request.
 */
data class TransferRequest(
    /** Unique transfer identifier */
    @SerializedName("transfer_id")
    val transferId: String,

    /** Device requesting the transfer (new device) */
    @SerializedName("source_device_id")
    val sourceDeviceId: String?,

    /** Device that must approve (old device) */
    @SerializedName("target_device_id")
    val targetDeviceId: String?,

    /** Information about the requesting device */
    @SerializedName("device_info")
    val deviceInfo: DeviceInfo,

    /** When the request was created */
    @SerializedName("created_at")
    val createdAt: Instant,

    /** When the request expires (15 minutes from creation) */
    @SerializedName("expires_at")
    val expiresAt: Instant,

    /** Current status */
    val status: TransferStatus
)

/**
 * Status of a transfer request.
 */
enum class TransferStatus {
    /** Transfer is pending approval */
    @SerializedName("pending")
    PENDING,

    /** Transfer was approved */
    @SerializedName("approved")
    APPROVED,

    /** Transfer was denied by user */
    @SerializedName("denied")
    DENIED,

    /** Transfer completed successfully */
    @SerializedName("completed")
    COMPLETED,

    /** Transfer expired without action */
    @SerializedName("expired")
    EXPIRED,

    /** Transfer failed */
    @SerializedName("failed")
    FAILED
}

// MARK: - API Request/Response Models

/**
 * Request to initiate a credential transfer (from new device).
 */
data class InitiateTransferRequest(
    /** Attestation from the new device */
    @SerializedName("device_attestation")
    val deviceAttestation: String,

    /** Device information for display on old device */
    @SerializedName("device_info")
    val deviceInfo: DeviceInfo
)

/**
 * Response from initiating a transfer.
 */
data class InitiateTransferResponse(
    /** Whether the request was created */
    val success: Boolean,

    /** Transfer request details */
    val transfer: TransferRequest?,

    /** Error message if failed */
    val error: String? = null
)

/**
 * Request to approve or deny a transfer (from old device).
 */
data class TransferDecisionRequest(
    /** Transfer being decided on */
    @SerializedName("transfer_id")
    val transferId: String,

    /** Whether to approve (true) or deny (false) */
    val approved: Boolean,

    /** Attestation from the approving device */
    @SerializedName("device_attestation")
    val deviceAttestation: String? = null
)

/**
 * Response from transfer decision.
 */
data class TransferDecisionResponse(
    /** Whether the decision was recorded */
    val success: Boolean,

    /** Updated transfer status */
    val status: TransferStatus?,

    /** Error message if failed */
    val error: String? = null
)

/**
 * Response from completing a transfer (new device receives credentials).
 */
data class TransferCompleteResponse(
    /** Whether transfer completed successfully */
    val success: Boolean,

    /** Encrypted credential package (same format as enrollment) */
    @SerializedName("encrypted_credential")
    val encryptedCredential: String?,

    /** Error message if failed */
    val error: String? = null
)

// MARK: - State Models

/**
 * State for the transfer request screen (new device).
 */
sealed class TransferRequestState {
    /** Initial state, ready to start */
    object Idle : TransferRequestState()

    /** Generating device attestation */
    object PreparingAttestation : TransferRequestState()

    /** Sending transfer request to vault */
    object SendingRequest : TransferRequestState()

    /** Waiting for approval from old device */
    data class WaitingForApproval(
        val transfer: TransferRequest,
        val remainingSeconds: Long
    ) : TransferRequestState()

    /** Transfer was approved, receiving credentials */
    object ReceivingCredentials : TransferRequestState()

    /** Transfer completed successfully */
    data class Completed(
        val transferId: String
    ) : TransferRequestState()

    /** Transfer was denied */
    data class Denied(
        val transferId: String,
        val message: String = "Transfer was denied by the other device"
    ) : TransferRequestState()

    /** Transfer expired */
    data class Expired(
        val transferId: String,
        val message: String = "Transfer request expired"
    ) : TransferRequestState()

    /** Transfer failed */
    data class Failed(
        val error: String,
        val retryable: Boolean = true
    ) : TransferRequestState()
}

/**
 * State for the transfer approval screen (old device).
 */
sealed class TransferApprovalState {
    /** Loading transfer details */
    object Loading : TransferApprovalState()

    /** Ready to approve/deny */
    data class Ready(
        val transfer: TransferRequest,
        val remainingSeconds: Long
    ) : TransferApprovalState()

    /** Awaiting biometric authentication */
    object AwaitingBiometric : TransferApprovalState()

    /** Processing approval */
    object ProcessingApproval : TransferApprovalState()

    /** Processing denial */
    object ProcessingDenial : TransferApprovalState()

    /** Approval completed */
    data class Approved(
        val transferId: String,
        val message: String = "Transfer approved successfully"
    ) : TransferApprovalState()

    /** Denial completed */
    data class DeniedComplete(
        val transferId: String,
        val message: String = "Transfer denied"
    ) : TransferApprovalState()

    /** Transfer expired while viewing */
    data class Expired(
        val transferId: String,
        val message: String = "Transfer request has expired"
    ) : TransferApprovalState()

    /** Error state */
    data class Error(
        val message: String
    ) : TransferApprovalState()
}

// MARK: - UI Events

/**
 * Events from the transfer request screen (new device).
 */
sealed class TransferRequestEvent {
    /** Start the transfer request process */
    object StartTransfer : TransferRequestEvent()

    /** Cancel the pending transfer */
    object CancelTransfer : TransferRequestEvent()

    /** Retry after failure */
    object Retry : TransferRequestEvent()

    /** Dismiss and go back */
    object Dismiss : TransferRequestEvent()
}

/**
 * Events from the transfer approval screen (old device).
 */
sealed class TransferApprovalEvent {
    /** Load transfer details */
    data class LoadTransfer(val transferId: String) : TransferApprovalEvent()

    /** User wants to approve the transfer */
    object ApproveTransfer : TransferApprovalEvent()

    /** User wants to deny the transfer */
    object DenyTransfer : TransferApprovalEvent()

    /** Biometric authentication succeeded */
    object BiometricSuccess : TransferApprovalEvent()

    /** Biometric authentication failed */
    data class BiometricFailed(val error: String) : TransferApprovalEvent()

    /** Dismiss and go back */
    object Dismiss : TransferApprovalEvent()
}

// MARK: - Effects

/**
 * Side effects from transfer request screen.
 */
sealed class TransferRequestEffect {
    /** Show error message */
    data class ShowError(val message: String) : TransferRequestEffect()

    /** Transfer completed, navigate to main */
    object NavigateToMain : TransferRequestEffect()

    /** Go back to previous screen */
    object NavigateBack : TransferRequestEffect()
}

/**
 * Side effects from transfer approval screen.
 */
sealed class TransferApprovalEffect {
    /** Show error message */
    data class ShowError(val message: String) : TransferApprovalEffect()

    /** Show success message */
    data class ShowSuccess(val message: String) : TransferApprovalEffect()

    /** Request biometric authentication */
    object RequestBiometric : TransferApprovalEffect()

    /** Go back to previous screen */
    object NavigateBack : TransferApprovalEffect()
}
