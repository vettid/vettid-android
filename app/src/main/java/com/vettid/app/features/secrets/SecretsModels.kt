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
        // Preferred display order for categories
        private val categoryOrder = listOf(
            SecretCategory.IDENTITY,
            SecretCategory.CERTIFICATE,
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
        )

        fun fromItems(items: List<MinorSecret>): GroupedByCategory {
            val grouped = items.groupBy { it.category }
            val orderedCategories = linkedMapOf<SecretCategory, List<MinorSecret>>()

            // Add categories in preferred order
            categoryOrder.forEach { category ->
                grouped[category]?.let { categoryItems ->
                    orderedCategories[category] = categoryItems.sortedWith(
                        compareBy({ it.sortOrder }, { it.name })
                    )
                }
            }

            // Include any categories not in the preferred order list (future-proof)
            grouped.keys.filter { it !in categoryOrder }.forEach { category ->
                grouped[category]?.let { categoryItems ->
                    orderedCategories[category] = categoryItems.sortedWith(
                        compareBy({ it.sortOrder }, { it.name })
                    )
                }
            }

            return GroupedByCategory(orderedCategories)
        }
    }
}

// MARK: - Secret Templates

/**
 * Hint for what kind of input widget to use for a template field.
 */
enum class FieldInputHint {
    TEXT,           // Standard text with word capitalization
    DATE,           // Date picker (MM/DD/YYYY)
    EXPIRY_DATE,    // Expiry date picker (MM/YYYY)
    COUNTRY,        // Country dropdown
    STATE,          // US state / province dropdown
    NUMBER,         // Numeric keyboard
    PASSWORD,       // Hidden password input
    PIN             // Short numeric PIN
}

/**
 * A field definition within a template.
 */
data class TemplateField(
    val name: String,
    val type: SecretType,
    val placeholder: String = "",
    val inputHint: FieldInputHint = FieldInputHint.TEXT
)

/**
 * A pre-defined collection of fields for common secret types.
 */
data class SecretTemplate(
    val name: String,
    val description: String,
    val category: SecretCategory,
    val iconName: String,
    val fields: List<TemplateField>
) {
    companion object {
        val all = listOf(
            SecretTemplate(
                name = "Driver's License",
                description = "License number, state, expiry, class",
                category = SecretCategory.DRIVERS_LICENSE,
                iconName = "badge",
                fields = listOf(
                    TemplateField("License Number", SecretType.ACCOUNT_NUMBER, "e.g., D1234567", FieldInputHint.TEXT),
                    TemplateField("State / Province", SecretType.TEXT, "", FieldInputHint.STATE),
                    TemplateField("Expiration Date", SecretType.TEXT, "", FieldInputHint.DATE),
                    TemplateField("Date of Birth", SecretType.TEXT, "", FieldInputHint.DATE),
                    TemplateField("License Class", SecretType.TEXT, "e.g., C")
                )
            ),
            SecretTemplate(
                name = "Passport",
                description = "Passport number, country, expiry",
                category = SecretCategory.PASSPORT,
                iconName = "flight",
                fields = listOf(
                    TemplateField("Passport Number", SecretType.ACCOUNT_NUMBER, "e.g., 123456789", FieldInputHint.NUMBER),
                    TemplateField("Country", SecretType.TEXT, "", FieldInputHint.COUNTRY),
                    TemplateField("Expiration Date", SecretType.TEXT, "", FieldInputHint.DATE),
                    TemplateField("Date of Birth", SecretType.TEXT, "", FieldInputHint.DATE),
                    TemplateField("Place of Birth", SecretType.TEXT, "e.g., New York, NY")
                )
            ),
            SecretTemplate(
                name = "Bank Account",
                description = "Account number, routing, bank name",
                category = SecretCategory.BANK_ACCOUNT,
                iconName = "account_balance",
                fields = listOf(
                    TemplateField("Bank Name", SecretType.TEXT, "e.g., Chase"),
                    TemplateField("Account Number", SecretType.ACCOUNT_NUMBER, "", FieldInputHint.NUMBER),
                    TemplateField("Routing Number", SecretType.ACCOUNT_NUMBER, "", FieldInputHint.NUMBER),
                    TemplateField("Account Type", SecretType.TEXT, "e.g., Checking")
                )
            ),
            SecretTemplate(
                name = "Credit Card",
                description = "Card number, expiry, CVV",
                category = SecretCategory.CREDIT_CARD,
                iconName = "credit_card",
                fields = listOf(
                    TemplateField("Cardholder Name", SecretType.TEXT, "Name on card"),
                    TemplateField("Card Number", SecretType.ACCOUNT_NUMBER, "", FieldInputHint.NUMBER),
                    TemplateField("Expiration", SecretType.TEXT, "", FieldInputHint.EXPIRY_DATE),
                    TemplateField("CVV", SecretType.PIN, "", FieldInputHint.PIN),
                    TemplateField("Card Issuer", SecretType.TEXT, "e.g., Visa, Mastercard")
                )
            ),
            SecretTemplate(
                name = "Cryptocurrency Wallet",
                description = "Wallet address, seed phrase",
                category = SecretCategory.CRYPTOCURRENCY,
                iconName = "currency_bitcoin",
                fields = listOf(
                    TemplateField("Wallet Name", SecretType.TEXT, "e.g., Bitcoin Main"),
                    TemplateField("Public Address", SecretType.PUBLIC_KEY, ""),
                    TemplateField("Seed Phrase", SecretType.SEED_PHRASE, "12 or 24 word phrase")
                )
            ),
            SecretTemplate(
                name = "Insurance",
                description = "Provider, policy, member ID",
                category = SecretCategory.INSURANCE,
                iconName = "health_and_safety",
                fields = listOf(
                    TemplateField("Provider", SecretType.TEXT, "e.g., Blue Cross"),
                    TemplateField("Policy Number", SecretType.ACCOUNT_NUMBER, "", FieldInputHint.NUMBER),
                    TemplateField("Group Number", SecretType.ACCOUNT_NUMBER, "", FieldInputHint.NUMBER),
                    TemplateField("Member ID", SecretType.ACCOUNT_NUMBER, "", FieldInputHint.NUMBER)
                )
            ),
            SecretTemplate(
                name = "Social Security",
                description = "SSN",
                category = SecretCategory.SSN,
                iconName = "security",
                fields = listOf(
                    TemplateField("Social Security Number", SecretType.ACCOUNT_NUMBER, "e.g., 123-45-6789", FieldInputHint.NUMBER)
                )
            ),
            SecretTemplate(
                name = "WiFi Network",
                description = "Network name and password",
                category = SecretCategory.WIFI,
                iconName = "wifi",
                fields = listOf(
                    TemplateField("Network Name (SSID)", SecretType.TEXT, ""),
                    TemplateField("Password", SecretType.PASSWORD, "", FieldInputHint.PASSWORD)
                )
            )
        )
    }
}

/**
 * State for the template form dialog.
 */
data class TemplateFormState(
    val template: SecretTemplate,
    val fieldValues: Map<Int, String> = emptyMap(),
    val isSaving: Boolean = false
) {
    fun getValue(fieldIndex: Int): String = fieldValues[fieldIndex] ?: ""
    fun isComplete(): Boolean = template.fields.indices.all { getValue(it).isNotBlank() }
}

// MARK: - Critical Secrets (Credential-Embedded Secrets)

/** A critical secret item for display in the metadata list. */
data class CriticalSecretItem(
    val id: String,
    val name: String,
    val category: String,
    val description: String?,
    val owner: String?,
    val createdAt: String
)

/** A crypto key item for display in the metadata list. */
data class CryptoKeyItem(
    val id: String,
    val label: String,
    val type: String,
    val publicKey: String?,
    val derivationPath: String?,
    val createdAt: String
)

/** Credential info metadata for display. */
data class CredentialInfoItem(
    val identityFingerprint: String,
    val vaultId: String?,
    val boundAt: String?,
    val version: Int,
    val createdAt: String,
    val lastModified: String
)

// Legacy compatibility - keep old model names as aliases
typealias Secret = MinorSecret
typealias SecretsListState = SecretsState
typealias SecretValueState = SecretsState
