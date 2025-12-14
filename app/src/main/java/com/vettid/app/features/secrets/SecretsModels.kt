package com.vettid.app.features.secrets

import java.time.Instant

/**
 * Represents a secret stored in the vault.
 */
data class Secret(
    val id: String,
    val name: String,
    val category: SecretCategory,
    val createdAt: Instant,
    val updatedAt: Instant,
    val notes: String? = null
)

/**
 * Categories for organizing secrets.
 */
enum class SecretCategory(val displayName: String, val iconName: String) {
    PASSWORD("Password", "password"),
    API_KEY("API Key", "key"),
    CERTIFICATE("Certificate", "verified_user"),
    CRYPTO_KEY("Crypto Key", "enhanced_encryption"),
    NOTE("Secure Note", "note"),
    OTHER("Other", "lock")
}

/**
 * State for the secrets list screen.
 */
sealed class SecretsListState {
    object Loading : SecretsListState()
    data class Loaded(
        val secrets: List<Secret>,
        val searchQuery: String = ""
    ) : SecretsListState()
    data class Error(val message: String) : SecretsListState()
    object Empty : SecretsListState()
}

/**
 * State for viewing a secret's value.
 */
sealed class SecretValueState {
    object Hidden : SecretValueState()
    object PasswordRequired : SecretValueState()
    object Verifying : SecretValueState()
    data class Revealed(
        val value: String,
        val autoHideSeconds: Int = 30
    ) : SecretValueState()
    data class Error(val message: String) : SecretValueState()
}

/**
 * Effects emitted by the secrets view model.
 */
sealed class SecretsEffect {
    object ShowPasswordPrompt : SecretsEffect()
    data class ShowSuccess(val message: String) : SecretsEffect()
    data class ShowError(val message: String) : SecretsEffect()
    object SecretCopied : SecretsEffect()
    data class NavigateToEdit(val secretId: String) : SecretsEffect()
}

/**
 * Events for the secrets screen.
 */
sealed class SecretsEvent {
    data class SearchQueryChanged(val query: String) : SecretsEvent()
    data class SecretClicked(val secretId: String) : SecretsEvent()
    data class RevealSecret(val secretId: String, val password: String) : SecretsEvent()
    data class CopySecret(val secretId: String) : SecretsEvent()
    object HideSecret : SecretsEvent()
    object AddSecret : SecretsEvent()
    data class DeleteSecret(val secretId: String) : SecretsEvent()
    object Refresh : SecretsEvent()
}
