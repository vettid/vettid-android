package com.vettid.app.features.nats

import com.vettid.app.core.nats.NatsAccount
import com.vettid.app.core.nats.NatsPermissions

/**
 * State for NATS setup and connection management.
 */
sealed class NatsSetupState {
    object Initial : NatsSetupState()
    object CheckingAccount : NatsSetupState()
    object CreatingAccount : NatsSetupState()
    object GeneratingToken : NatsSetupState()
    object Connecting : NatsSetupState()

    data class Connected(
        val account: NatsAccount,
        val tokenExpiresAt: String,
        val permissions: NatsPermissions
    ) : NatsSetupState()

    data class Disconnected(
        val account: NatsAccount?,
        val reason: String? = null
    ) : NatsSetupState()

    data class Error(
        val message: String,
        val code: NatsErrorCode,
        val retryable: Boolean = true
    ) : NatsSetupState()
}

/**
 * Error codes for NATS setup failures.
 */
enum class NatsErrorCode {
    NO_AUTH_TOKEN,
    ACCOUNT_CREATION_FAILED,
    TOKEN_GENERATION_FAILED,
    CONNECTION_FAILED,
    NETWORK_ERROR,
    UNAUTHORIZED,
    UNKNOWN
}

/**
 * Events for NATS setup.
 */
sealed class NatsSetupEvent {
    data class SetupNats(val authToken: String) : NatsSetupEvent()
    object Disconnect : NatsSetupEvent()
    data class RefreshToken(val authToken: String) : NatsSetupEvent()
    object Retry : NatsSetupEvent()
    object DismissError : NatsSetupEvent()
}

/**
 * Side effects from NATS setup.
 */
sealed class NatsSetupEffect {
    object RequireAuth : NatsSetupEffect()
    data class ShowError(val message: String) : NatsSetupEffect()
    data class ConnectionEstablished(val account: NatsAccount) : NatsSetupEffect()
    object Disconnected : NatsSetupEffect()
}
