package com.vettid.app.features.postenrollment

/**
 * State machine for the post-enrollment authentication flow.
 *
 * After enrollment completes, the user goes through this flow to:
 * 1. Verify their credential works by authenticating with the vault
 * 2. Proceed to personal data collection or main app
 *
 * Flow:
 * Initial -> PasswordEntry -> Authenticating -> Success/Error
 */
sealed class PostEnrollmentState {

    /**
     * Initial state - waiting to start authentication.
     */
    object Initial : PostEnrollmentState()

    /**
     * User is entering their password to verify their credential.
     */
    data class PasswordEntry(
        val password: String = "",
        val isPasswordVisible: Boolean = false,
        val error: String? = null,
        val isSubmitting: Boolean = false
    ) : PostEnrollmentState()

    /**
     * Authenticating with the vault.
     */
    data class Authenticating(
        val progress: Float = 0f,
        val statusMessage: String = "Verifying credential..."
    ) : PostEnrollmentState()

    /**
     * Authentication succeeded - credential is valid.
     */
    data class Success(
        val userGuid: String,
        val message: String? = null
    ) : PostEnrollmentState()

    /**
     * Authentication failed.
     */
    data class Error(
        val message: String,
        val retryable: Boolean = true,
        val errorCode: String? = null
    ) : PostEnrollmentState()
}

/**
 * Events that can occur in the post-enrollment flow.
 */
sealed class PostEnrollmentEvent {
    object StartAuthentication : PostEnrollmentEvent()
    data class PasswordChanged(val password: String) : PostEnrollmentEvent()
    object TogglePasswordVisibility : PostEnrollmentEvent()
    object SubmitPassword : PostEnrollmentEvent()
    object Retry : PostEnrollmentEvent()
    object Skip : PostEnrollmentEvent()
    object Continue : PostEnrollmentEvent()
}

/**
 * Side effects that can be triggered by the post-enrollment flow.
 */
sealed class PostEnrollmentEffect {
    object NavigateToPersonalData : PostEnrollmentEffect()
    object NavigateToMain : PostEnrollmentEffect()
    data class ShowError(val message: String) : PostEnrollmentEffect()
}
