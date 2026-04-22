package com.vettid.app.features.enrollmentwizard

import com.vettid.app.core.nats.UtkInfo
import com.vettid.app.features.enrollment.AttestationInfo
import com.vettid.app.features.enrollment.PasswordStrength

/**
 * Wizard phase enumeration for step indicator.
 *
 * 7-step flow: Start → Review → PIN → Password → Verify → Permissions → Done.
 *
 * Collapsed from the earlier 9-step design:
 *   - ATTESTATION folded into START (attestation is automatic after
 *     scan; brief success animation, no separate step).
 *   - CONFIRM_IDENTITY and CONFIRM_PROFILE merged into REVIEW —
 *     both were verify-only (no editing), so one screen covers
 *     both "is this really you" and "this is what your connections
 *     will see". Default public profile publishing happens silently
 *     after credential verification; user can edit from the Data
 *     tab any time.
 */
enum class WizardPhase(val stepIndex: Int, val label: String) {
    START(0, "Start"),
    REVIEW(1, "Review"),
    PIN_SETUP(2, "PIN"),
    PASSWORD_SETUP(3, "Password"),
    VERIFY_CREDENTIAL(4, "Verify"),
    PERMISSIONS(5, "Permissions"),
    COMPLETE(6, "Done");

    companion object {
        const val TOTAL_STEPS = 7

        fun fromIndex(index: Int): WizardPhase? = entries.find { it.stepIndex == index }
    }
}

/**
 * Unified wizard state for all enrollment phases.
 * Combines states from EnrollmentScreen, PostEnrollmentScreen, and PersonalDataCollectionScreen.
 */
sealed class WizardState {
    /** Current wizard phase for step indicator */
    abstract val phase: WizardPhase

    // ============== INITIAL LOADING ==============

    /** Initial loading state - shown before initialize() determines which phase to start */
    data object Loading : WizardState() {
        override val phase = WizardPhase.START
    }

    // ============== PHASE 1: START ==============

    /** QR scanning */
    data class ScanningQR(
        val error: String? = null
    ) : WizardState() {
        override val phase = WizardPhase.START
    }

    /** Manual entry of invitation code */
    data class ManualEntry(
        val inviteCode: String = "",
        val error: String? = null
    ) : WizardState() {
        override val phase = WizardPhase.START
    }

    // ============== PHASE 1 (cont): START — attestation sub-states ==============
    //
    // Attestation happens automatically after the QR scan with a
    // progress indicator and brief success animation, but no
    // separate step in the indicator. All of the sub-states below
    // map to the START phase.

    /** Processing invite code */
    data class ProcessingInvite(
        val message: String = "Validating invitation..."
    ) : WizardState() {
        override val phase = WizardPhase.START
    }

    /** Connecting to NATS */
    data class ConnectingToNats(
        val message: String = "Connecting to secure vault..."
    ) : WizardState() {
        override val phase = WizardPhase.START
    }

    /** Requesting attestation from enclave */
    data class RequestingAttestation(
        val message: String = "Verifying enclave security...",
        val progress: Float = 0f
    ) : WizardState() {
        override val phase = WizardPhase.START
    }

    /** Attestation verified - brief display */
    data class AttestationVerified(
        val attestationInfo: AttestationInfo
    ) : WizardState() {
        override val phase = WizardPhase.START
    }

    // ============== PHASE 2: REVIEW ==============
    //
    // Merged from the earlier CONFIRM_IDENTITY + CONFIRM_PROFILE
    // phases. Shows name + email and previews what connections will
    // see (for now just those fields). No editing — if the user
    // spots something wrong, they abort and fix in the account
    // portal via the IdentityRejected state.

    /**
     * Review name/email + default public profile preview. Optional
     * profile-photo capture is offered inline: `photoBytes` holds
     * the confirmed photo (null = none), `isCapturingPhoto` drives
     * the fullscreen capture dialog at the screen layer.
     */
    data class ConfirmIdentity(
        val firstName: String,
        val lastName: String,
        val email: String,
        val attestationInfo: AttestationInfo? = null,
        val photoBytes: ByteArray? = null,
        val isCapturingPhoto: Boolean = false
    ) : WizardState() {
        override val phase = WizardPhase.REVIEW

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ConfirmIdentity) return false
            return firstName == other.firstName &&
                lastName == other.lastName &&
                email == other.email &&
                attestationInfo == other.attestationInfo &&
                photoBytes.contentEqualsOrBothNull(other.photoBytes) &&
                isCapturingPhoto == other.isCapturingPhoto
        }

        override fun hashCode(): Int {
            var result = firstName.hashCode()
            result = 31 * result + lastName.hashCode()
            result = 31 * result + email.hashCode()
            result = 31 * result + (attestationInfo?.hashCode() ?: 0)
            result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
            result = 31 * result + isCapturingPhoto.hashCode()
            return result
        }

        private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
            (this == null && other == null) || (this != null && other != null && this.contentEquals(other))
    }

    /** Identity mismatch reported - showing confirmation to user */
    data class IdentityRejected(
        val message: String = "The problem has been reported. Please request a new enrollment link from your account page.",
        val isReporting: Boolean = false
    ) : WizardState() {
        override val phase = WizardPhase.REVIEW
    }

    // ============== PHASE 4: PIN SETUP ==============

    /** PIN entry and confirmation */
    data class SettingPin(
        val pin: String = "",
        val confirmPin: String = "",
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val attestationInfo: AttestationInfo? = null
    ) : WizardState() {
        override val phase = WizardPhase.PIN_SETUP
    }

    // ============== PHASE 5: PASSWORD SETUP ==============

    /** Password creation with strength indicator */
    data class SettingPassword(
        val password: String = "",
        val confirmPassword: String = "",
        val strength: PasswordStrength = PasswordStrength.WEAK,
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val utks: List<UtkInfo> = emptyList()
    ) : WizardState() {
        override val phase = WizardPhase.PASSWORD_SETUP
    }

    /** Creating credential */
    data class CreatingCredential(
        val message: String = "Creating your secure credential...",
        val progress: Float = 0f
    ) : WizardState() {
        override val phase = WizardPhase.PASSWORD_SETUP
    }

    // ============== PHASE 6: VERIFY CREDENTIAL ==============

    /** Password verification entry */
    data class VerifyingPassword(
        val password: String = "",
        val isPasswordVisible: Boolean = false,
        val isSubmitting: Boolean = false,
        val error: String? = null
    ) : WizardState() {
        override val phase = WizardPhase.VERIFY_CREDENTIAL
    }

    /** Authenticating with entered password */
    data class Authenticating(
        val progress: Float = 0f,
        val statusMessage: String = "Verifying credential..."
    ) : WizardState() {
        override val phase = WizardPhase.VERIFY_CREDENTIAL
    }

    /** Verification successful */
    data class VerificationSuccess(
        val userGuid: String = "",
        val message: String = "Your credential has been verified successfully"
    ) : WizardState() {
        override val phase = WizardPhase.VERIFY_CREDENTIAL
    }

    // ============== TRANSIENT: silent default-profile publish ==============

    /**
     * Silent default-profile publish after verify succeeds. This
     * state is no longer a user-visible step — the screen simply
     * renders the verify-success checkmark while the publish runs
     * in the background, then auto-advances to Permissions. Kept
     * as a distinct state so the VM can surface publish errors if
     * the publish fails. Mapped to VERIFY_CREDENTIAL in the step
     * indicator so the bar doesn't move backwards.
     */
    data class ConfirmProfile(
        val firstName: String = "",
        val lastName: String = "",
        val email: String = "",
        val isPublishing: Boolean = false,
        val error: String? = null
    ) : WizardState() {
        override val phase = WizardPhase.VERIFY_CREDENTIAL
    }

    // ============== PHASE 8: PERMISSIONS ==============

    /** Request app permissions (notifications, etc.) */
    data class RequestingPermissions(
        val notificationsGranted: Boolean? = null,
        val notificationsRequested: Boolean = false
    ) : WizardState() {
        override val phase = WizardPhase.PERMISSIONS
    }

    // ============== PHASE 9: COMPLETE ==============

    /** Enrollment complete */
    data class Complete(
        val userGuid: String = "",
        val shouldNavigate: Boolean = false  // Set to true when ready to navigate to Main
    ) : WizardState() {
        override val phase = WizardPhase.COMPLETE
    }

    // ============== ERROR STATE ==============

    /** Error with optional retry */
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val previousPhase: WizardPhase = WizardPhase.START
    ) : WizardState() {
        override val phase = previousPhase
    }
}
