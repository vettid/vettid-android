package com.vettid.app.features.auth

import com.vettid.app.core.network.LAT

/**
 * Authentication flow states following vault-services-api.yaml
 *
 * Flow: Initial → RequestingAction → VerifyingLAT → EnteringPassword → Executing → Result
 */
sealed class AuthenticationState {

    /**
     * Initial state - ready to start authentication
     */
    object Initial : AuthenticationState()

    /**
     * Requesting action token from server
     */
    data class RequestingAction(
        val action: String,
        val progress: Float = 0f
    ) : AuthenticationState()

    /**
     * Server returned LAT - user must verify it matches stored LAT
     * This prevents phishing attacks
     */
    data class VerifyingLAT(
        val authSessionId: String,
        val serverLat: LAT,
        val storedLatToken: String,
        val endpoint: String,
        val latMatch: Boolean
    ) : AuthenticationState()

    /**
     * LAT verified - user enters password
     */
    data class EnteringPassword(
        val authSessionId: String,
        val endpoint: String,
        val password: String = "",
        val error: String? = null,
        val isSubmitting: Boolean = false
    ) : AuthenticationState()

    /**
     * Executing authentication (sending encrypted credentials)
     */
    data class Executing(
        val authSessionId: String,
        val progress: Float = 0f,
        val statusMessage: String = "Authenticating..."
    ) : AuthenticationState()

    /**
     * Authentication successful
     */
    data class Success(
        val actionToken: String,
        val message: String = "Authentication successful",
        val keysReplenished: Int = 0
    ) : AuthenticationState()

    /**
     * Authentication failed
     */
    data class Error(
        val message: String,
        val code: AuthErrorCode = AuthErrorCode.UNKNOWN,
        val retryable: Boolean = true,
        val previousState: AuthenticationState? = null
    ) : AuthenticationState()
}

/**
 * Authentication error codes
 */
enum class AuthErrorCode {
    NETWORK_ERROR,
    INVALID_CREDENTIALS,
    LAT_MISMATCH,
    SESSION_EXPIRED,
    CONCURRENT_SESSION,
    NO_TRANSACTION_KEYS,
    CREDENTIAL_NOT_FOUND,
    UNKNOWN
}

/**
 * Authentication events
 */
sealed class AuthenticationEvent {
    /**
     * Start authentication for an action
     */
    data class StartAuth(val action: String) : AuthenticationEvent()

    /**
     * User confirms LAT verification
     */
    object ConfirmLAT : AuthenticationEvent()

    /**
     * User rejects LAT (possible phishing)
     */
    object RejectLAT : AuthenticationEvent()

    /**
     * Password input changed
     */
    data class PasswordChanged(val password: String) : AuthenticationEvent()

    /**
     * Submit password for authentication
     */
    object SubmitPassword : AuthenticationEvent()

    /**
     * Retry after error
     */
    object Retry : AuthenticationEvent()

    /**
     * Cancel authentication
     */
    object Cancel : AuthenticationEvent()

    /**
     * Authentication complete - proceed with action
     */
    object Proceed : AuthenticationEvent()
}

/**
 * Side effects from authentication
 */
sealed class AuthenticationEffect {
    /**
     * Navigate back to previous screen
     */
    object NavigateBack : AuthenticationEffect()

    /**
     * Authentication complete - proceed with action token
     */
    data class AuthComplete(val actionToken: String) : AuthenticationEffect()

    /**
     * Show error toast/snackbar
     */
    data class ShowError(val message: String) : AuthenticationEffect()

    /**
     * Credentials were rotated
     */
    object CredentialsRotated : AuthenticationEffect()

    /**
     * Transaction keys replenished
     */
    data class KeysReplenished(val count: Int) : AuthenticationEffect()

    /**
     * LAT mismatch - possible phishing attack
     */
    object PhishingWarning : AuthenticationEffect()
}
