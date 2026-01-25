package com.vettid.app.features.enrollmentwizard

import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.OptionalField

/**
 * All possible events in the enrollment wizard.
 * Unified event system for all phases.
 */
sealed class WizardEvent {
    // ============== NAVIGATION EVENTS ==============

    /** Go to next step (where applicable) */
    object NextStep : WizardEvent()

    /** Go to previous step */
    object PreviousStep : WizardEvent()

    /** Skip current step (where applicable) */
    object Skip : WizardEvent()

    /** Retry after error */
    object Retry : WizardEvent()

    /** Cancel wizard and exit */
    object Cancel : WizardEvent()

    // ============== START PHASE EVENTS ==============

    /** Start QR scanning */
    object StartScanning : WizardEvent()

    /** Switch to manual code entry */
    object SwitchToManualEntry : WizardEvent()

    /** Switch to QR scanning from manual entry */
    object SwitchToScanning : WizardEvent()

    /** QR code scanned */
    data class QRCodeScanned(val qrData: String) : WizardEvent()

    /** Manual invite code changed */
    data class InviteCodeChanged(val inviteCode: String) : WizardEvent()

    /** Submit manually entered invite code */
    object SubmitInviteCode : WizardEvent()

    // ============== PIN PHASE EVENTS ==============

    /** PIN field changed */
    data class PinChanged(val pin: String) : WizardEvent()

    /** Confirm PIN field changed */
    data class ConfirmPinChanged(val confirmPin: String) : WizardEvent()

    /** Submit PIN */
    object SubmitPin : WizardEvent()

    // ============== PASSWORD PHASE EVENTS ==============

    /** Password field changed */
    data class PasswordChanged(val password: String) : WizardEvent()

    /** Confirm password field changed */
    data class ConfirmPasswordChanged(val confirmPassword: String) : WizardEvent()

    /** Submit password */
    object SubmitPassword : WizardEvent()

    // ============== VERIFY PHASE EVENTS ==============

    /** Verification password changed */
    data class VerifyPasswordChanged(val password: String) : WizardEvent()

    /** Toggle password visibility */
    object TogglePasswordVisibility : WizardEvent()

    /** Submit verification password */
    object SubmitVerifyPassword : WizardEvent()

    /** Continue after successful verification */
    object ContinueAfterVerification : WizardEvent()

    // ============== PERSONAL DATA PHASE EVENTS ==============

    /** Update optional field */
    data class UpdateOptionalField(val field: OptionalField, val value: String?) : WizardEvent()

    /** Add custom field */
    data class AddCustomField(
        val name: String,
        val value: String,
        val category: FieldCategory
    ) : WizardEvent()

    /** Update existing custom field */
    data class UpdateCustomField(val field: CustomField) : WizardEvent()

    /** Remove custom field */
    data class RemoveCustomField(val fieldId: String) : WizardEvent()

    /** Sync personal data to vault */
    object SyncPersonalData : WizardEvent()

    /** Show add field dialog */
    object ShowAddFieldDialog : WizardEvent()

    /** Hide add field dialog */
    object HideAddFieldDialog : WizardEvent()

    /** Show edit field dialog */
    data class ShowEditFieldDialog(val field: CustomField) : WizardEvent()

    /** Hide edit field dialog */
    object HideEditFieldDialog : WizardEvent()

    /** Dismiss error message */
    object DismissError : WizardEvent()

    // ============== COMPLETE PHASE EVENTS ==============

    /** Navigate to main app */
    object NavigateToMain : WizardEvent()
}

/**
 * Side effects emitted by the wizard.
 */
sealed class WizardEffect {
    /** Navigate to main app screen */
    object NavigateToMain : WizardEffect()

    /** Show toast message */
    data class ShowToast(val message: String) : WizardEffect()

    /** Close wizard (cancel/exit) */
    object CloseWizard : WizardEffect()
}
