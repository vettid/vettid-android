package com.vettid.app.features.secrets

import com.vettid.app.core.storage.MinorSecret
import com.vettid.app.core.storage.SecretCategory
import com.vettid.app.core.storage.SecretType

/**
 * State for the secrets list screen.
 */
sealed class SecretsState {
    object Loading : SecretsState()
    data class Loaded(
        val items: List<MinorSecret>,
        val searchQuery: String = "",
        val hasUnpublishedChanges: Boolean = false
    ) : SecretsState()
    data class Error(val message: String) : SecretsState()
    object Empty : SecretsState()
}

/**
 * State for add/edit secret dialog.
 */
data class EditSecretState(
    val id: String? = null,
    val name: String = "",
    val value: String = "",
    val type: SecretType = SecretType.TEXT,
    val category: SecretCategory = SecretCategory.OTHER,
    val notes: String = "",
    val isInPublicProfile: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    val valueError: String? = null
)

/**
 * Effects emitted by the secrets view model.
 */
sealed class SecretsEffect {
    data class ShowSuccess(val message: String) : SecretsEffect()
    data class ShowError(val message: String) : SecretsEffect()
    data class ShowQRCode(val secret: MinorSecret) : SecretsEffect()
    object SecretCopied : SecretsEffect()
}

/**
 * Events for the secrets screen.
 */
sealed class SecretsEvent {
    data class SearchQueryChanged(val query: String) : SecretsEvent()
    data class SecretClicked(val secretId: String) : SecretsEvent()
    data class CopySecret(val secretId: String) : SecretsEvent()
    data class ShowQRCode(val secretId: String) : SecretsEvent()
    object AddSecret : SecretsEvent()
    data class DeleteSecret(val secretId: String) : SecretsEvent()
    data class TogglePublicProfile(val secretId: String) : SecretsEvent()
    data class MoveSecretUp(val secretId: String) : SecretsEvent()
    data class MoveSecretDown(val secretId: String) : SecretsEvent()
    object Refresh : SecretsEvent()
    object PublishPublicKeys : SecretsEvent()
}

/**
 * Grouped secrets by category for display.
 * Each category section shows all secrets regardless of type,
 * with visibility toggle for PUBLIC_KEY types.
 */
data class GroupedByCategory(
    val categories: Map<SecretCategory, List<MinorSecret>>
) {
    companion object {
        fun fromItems(items: List<MinorSecret>): GroupedByCategory {
            val grouped = items.groupBy { it.category }
            // Sort categories in a logical order, and sort items within each category by sortOrder
            val orderedCategories = linkedMapOf<SecretCategory, List<MinorSecret>>()

            // Enrollment key (system field with sortOrder = -1) always first
            val systemSecrets = items.filter { it.isSystemField }.sortedBy { it.sortOrder }

            listOf(
                SecretCategory.CERTIFICATE,  // Includes enrollment key
                SecretCategory.CRYPTOCURRENCY,
                SecretCategory.BANK_ACCOUNT,
                SecretCategory.CREDIT_CARD,
                SecretCategory.INSURANCE,
                SecretCategory.DRIVERS_LICENSE,
                SecretCategory.PASSPORT,
                SecretCategory.SSN,
                SecretCategory.API_KEY,
                SecretCategory.PASSWORD,
                SecretCategory.WIFI,
                SecretCategory.NOTE,
                SecretCategory.OTHER
            ).forEach { category ->
                grouped[category]?.let { categoryItems ->
                    // Sort items within category by sortOrder, then by name as tiebreaker
                    orderedCategories[category] = categoryItems.sortedWith(
                        compareBy({ it.sortOrder }, { it.name })
                    )
                }
            }
            return GroupedByCategory(orderedCategories)
        }
    }
}

// Legacy compatibility - keep old model names as aliases
typealias Secret = MinorSecret
typealias SecretsListState = SecretsState
typealias SecretValueState = SecretsState
