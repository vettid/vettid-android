package com.vettid.app.features.enrollmentwizard

import com.vettid.app.core.nats.UtkInfo
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.OptionalPersonalData
import com.vettid.app.core.storage.SystemPersonalData
import com.vettid.app.features.enrollment.AttestationInfo
import com.vettid.app.features.enrollment.PasswordStrength

/**
 * Wizard phase enumeration for step indicator
 */
enum class WizardPhase(val stepIndex: Int, val label: String) {
    START(0, "Start"),
    ATTESTATION(1, "Verify"),
    PIN_SETUP(2, "PIN"),
    PASSWORD_SETUP(3, "Password"),
    VERIFY_CREDENTIAL(4, "Confirm"),
    PERSONAL_DATA(5, "Profile"),
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

    // ============== PHASE 1: START ==============

    /** Initial state - QR scanning */
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

    // ============== PHASE 2: ATTESTATION ==============

    /** Processing invite code */
    data class ProcessingInvite(
        val message: String = "Validating invitation..."
    ) : WizardState() {
        override val phase = WizardPhase.ATTESTATION
    }

    /** Connecting to NATS */
    data class ConnectingToNats(
        val message: String = "Connecting to secure vault..."
    ) : WizardState() {
        override val phase = WizardPhase.ATTESTATION
    }

    /** Requesting attestation from enclave */
    data class RequestingAttestation(
        val message: String = "Verifying enclave security...",
        val progress: Float = 0f
    ) : WizardState() {
        override val phase = WizardPhase.ATTESTATION
    }

    /** Attestation verified - brief display */
    data class AttestationVerified(
        val attestationInfo: AttestationInfo
    ) : WizardState() {
        override val phase = WizardPhase.ATTESTATION
    }

    // ============== PHASE 3: PIN SETUP ==============

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

    // ============== PHASE 4: PASSWORD SETUP ==============

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

    // ============== PHASE 5: VERIFY CREDENTIAL ==============

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

    // ============== PHASE 6: PERSONAL DATA ==============

    /** Personal data collection */
    data class PersonalData(
        val isLoading: Boolean = false,
        val isSyncing: Boolean = false,
        val systemFields: SystemPersonalData? = null,
        val optionalFields: OptionalPersonalData = OptionalPersonalData(),
        val customFields: List<CustomField> = emptyList(),
        val hasPendingSync: Boolean = false,
        val error: String? = null,
        val showAddFieldDialog: Boolean = false,
        val editingField: CustomField? = null
    ) : WizardState() {
        override val phase = WizardPhase.PERSONAL_DATA
    }

    // ============== PHASE 7: COMPLETE ==============

    /** Enrollment complete */
    data class Complete(
        val userGuid: String = ""
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
