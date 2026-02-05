package com.vettid.app.features.secrets

/**
 * State for the critical secrets screen.
 * No caching - all state is in-memory and cleared when navigating away.
 */
sealed class CriticalSecretsState {
    /** Password prompt to authenticate. */
    object PasswordPrompt : CriticalSecretsState()

    /** Authenticating with vault. */
    object Authenticating : CriticalSecretsState()

    /** Metadata list showing credential contents. */
    data class MetadataList(
        val secrets: List<CriticalSecretItem>,
        val cryptoKeys: List<CryptoKeyItem>,
        val credentialInfo: CredentialInfoItem?,
        val searchQuery: String = ""
    ) : CriticalSecretsState()

    /** Second password prompt to reveal a specific secret value. */
    data class SecondPasswordPrompt(
        val secretId: String,
        val secretName: String
    ) : CriticalSecretsState()

    /** Retrieving secret value from vault. */
    data class Retrieving(val secretName: String) : CriticalSecretsState()

    /** Revealed secret value with countdown timer. */
    data class Revealed(
        val secretId: String,
        val secretName: String,
        val value: String,
        val remainingSeconds: Int
    ) : CriticalSecretsState()

    /** Error state. */
    data class Error(val message: String) : CriticalSecretsState()
}

/**
 * Events for the critical secrets screen.
 */
sealed class CriticalSecretsScreenEvent {
    /** Submit password for authentication. */
    data class SubmitPassword(val password: String) : CriticalSecretsScreenEvent()

    /** Tap a secret to reveal its value. */
    data class RevealSecret(val secretId: String, val secretName: String) : CriticalSecretsScreenEvent()

    /** Submit password for secret reveal. */
    data class SubmitRevealPassword(val password: String) : CriticalSecretsScreenEvent()

    /** Timer expired, hide value. */
    object TimerExpired : CriticalSecretsScreenEvent()

    /** Go back to metadata list from reveal/prompt. */
    object BackToList : CriticalSecretsScreenEvent()

    /** Search query changed. */
    data class SearchQueryChanged(val query: String) : CriticalSecretsScreenEvent()
}
