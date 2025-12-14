package com.vettid.app.features.personaldata

import java.time.Instant

/**
 * Represents a personal data item stored in the vault.
 *
 * Data types:
 * - public: Shared with all connections automatically
 * - private: Shared only with consent/contract
 * - key: Cryptographic keys (configurable sharing)
 * - minor_secret: Comfortable in NATS, never shared
 */
data class PersonalDataItem(
    val id: String,
    val name: String,
    val type: DataType,
    val value: String,
    val category: DataCategory? = null,
    val isSystemField: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Data visibility types per mobile-ui-plan.md Section 5.2
 */
enum class DataType(val displayName: String, val description: String) {
    PUBLIC("Public", "Shared with all connections"),
    PRIVATE("Private", "Shared only with consent"),
    KEY("Key", "Cryptographic keys"),
    MINOR_SECRET("Minor Secret", "Never shared")
}

/**
 * Categories for organizing personal data.
 */
enum class DataCategory(val displayName: String, val iconName: String) {
    IDENTITY("Identity", "person"),
    CONTACT("Contact", "phone"),
    ADDRESS("Address", "location_on"),
    FINANCIAL("Financial", "account_balance"),
    MEDICAL("Medical", "medical_services"),
    CRYPTO("Cryptocurrency", "currency_bitcoin"),
    DOCUMENT("Document", "description"),
    OTHER("Other", "category")
}

/**
 * State for the personal data list screen.
 */
sealed class PersonalDataState {
    object Loading : PersonalDataState()
    data class Loaded(
        val items: List<PersonalDataItem>,
        val searchQuery: String = ""
    ) : PersonalDataState()
    data class Error(val message: String) : PersonalDataState()
    object Empty : PersonalDataState()
}

/**
 * Grouped personal data by type for display.
 */
data class GroupedPersonalData(
    val publicData: List<PersonalDataItem>,
    val privateData: List<PersonalDataItem>,
    val keys: List<PersonalDataItem>,
    val minorSecrets: List<PersonalDataItem>
)

/**
 * Effects emitted by the personal data view model.
 */
sealed class PersonalDataEffect {
    data class ShowSuccess(val message: String) : PersonalDataEffect()
    data class ShowError(val message: String) : PersonalDataEffect()
    data class NavigateToEdit(val itemId: String?) : PersonalDataEffect()
    object NavigateBack : PersonalDataEffect()
}

/**
 * Events for the personal data screen.
 */
sealed class PersonalDataEvent {
    data class SearchQueryChanged(val query: String) : PersonalDataEvent()
    data class ItemClicked(val itemId: String) : PersonalDataEvent()
    object AddItem : PersonalDataEvent()
    data class DeleteItem(val itemId: String) : PersonalDataEvent()
    object Refresh : PersonalDataEvent()
}

/**
 * State for add/edit dialog.
 */
data class EditDataItemState(
    val id: String? = null,
    val name: String = "",
    val value: String = "",
    val type: DataType = DataType.PRIVATE,
    val category: DataCategory? = null,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    val valueError: String? = null
)
