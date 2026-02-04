package com.vettid.app.features.personaldata

import java.time.Instant

/**
 * Represents a personal data item stored in the vault.
 *
 * Data sensitivity types control how data can be shared:
 * - public: Can be shared freely (text displayed)
 * - private: Shared only with consent/contract (masked)
 * - key: Cryptographic keys (masked, configurable sharing)
 * - minor_secret: Never shared with connections (masked)
 *
 * The isInPublicProfile flag independently controls whether this field
 * appears in the user's public profile visible to connections.
 */
data class PersonalDataItem(
    val id: String,
    val name: String,
    val type: DataType,
    val value: String,
    val category: DataCategory? = null,
    val fieldType: FieldType = FieldType.TEXT,
    val isSystemField: Boolean = false,
    val isInPublicProfile: Boolean = false,  // Whether to include in public profile
    val isSensitive: Boolean = false,  // Whether to mask value (PASSWORD type fields)
    val sortOrder: Int = 0,  // Order within category (lower = higher up)
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Field types that define how data is stored and displayed.
 */
enum class FieldType(val displayName: String, val description: String) {
    TEXT("Text", "General text value"),
    PASSWORD("Password", "Masked/hidden value"),
    NUMBER("Number", "Numeric value"),
    DATE("Date", "Date value (YYYY-MM-DD)"),
    EMAIL("Email", "Email address"),
    PHONE("Phone", "Phone number"),
    URL("URL", "Web address"),
    NOTE("Note", "Multi-line text")
}

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
 * @deprecated Use GroupedByCategory instead
 */
data class GroupedPersonalData(
    val publicData: List<PersonalDataItem>,
    val privateData: List<PersonalDataItem>,
    val keys: List<PersonalDataItem>,
    val minorSecrets: List<PersonalDataItem>
)

/**
 * Grouped personal data by category for display.
 * Each category section shows all data items regardless of type,
 * with a toggle to include/exclude from public profile.
 */
data class GroupedByCategory(
    val categories: Map<DataCategory, List<PersonalDataItem>>
) {
    companion object {
        fun fromItems(items: List<PersonalDataItem>): GroupedByCategory {
            val grouped = items.groupBy { it.category ?: DataCategory.OTHER }
            // Sort categories in a logical order, and sort items within each category by sortOrder
            val orderedCategories = linkedMapOf<DataCategory, List<PersonalDataItem>>()
            listOf(
                DataCategory.IDENTITY,
                DataCategory.CONTACT,
                DataCategory.ADDRESS,
                DataCategory.FINANCIAL,
                DataCategory.MEDICAL,
                DataCategory.OTHER
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
    data class TogglePublicProfile(val itemId: String) : PersonalDataEvent()
    data class MoveItemUp(val itemId: String) : PersonalDataEvent()
    data class MoveItemDown(val itemId: String) : PersonalDataEvent()
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
    val fieldType: FieldType = FieldType.TEXT,
    val category: DataCategory? = null,
    val originalCategory: DataCategory? = null,  // Track original category for detecting changes
    val isInPublicProfile: Boolean = false,
    val sortOrder: Int = 0,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    val valueError: String? = null
)
