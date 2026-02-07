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
 * Template for common personal data fields.
 * Provides standardized naming conventions and appropriate field types.
 */
data class PersonalDataTemplate(
    val name: String,
    val category: DataCategory,
    val fieldType: FieldType,
    val description: String
)

/**
 * Standard templates for common personal data fields.
 * Using consistent naming helps with data interoperability and sharing.
 */
object PersonalDataTemplates {
    val templates = listOf(
        // Identity
        PersonalDataTemplate("Date of Birth", DataCategory.IDENTITY, FieldType.DATE, "Your birth date"),
        PersonalDataTemplate("Social Security Number", DataCategory.IDENTITY, FieldType.TEXT, "SSN (US)"),
        PersonalDataTemplate("National ID", DataCategory.IDENTITY, FieldType.TEXT, "Government-issued ID number"),
        PersonalDataTemplate("Passport Number", DataCategory.IDENTITY, FieldType.TEXT, "Passport document number"),
        PersonalDataTemplate("Driver License", DataCategory.IDENTITY, FieldType.TEXT, "Driver's license number"),
        PersonalDataTemplate("Place of Birth", DataCategory.IDENTITY, FieldType.TEXT, "City/country of birth"),
        PersonalDataTemplate("Nationality", DataCategory.IDENTITY, FieldType.TEXT, "Your nationality/citizenship"),
        PersonalDataTemplate("Gender", DataCategory.IDENTITY, FieldType.TEXT, "Your gender identity"),

        // Contact
        PersonalDataTemplate("Mobile Phone", DataCategory.CONTACT, FieldType.PHONE, "Primary mobile number"),
        PersonalDataTemplate("Home Phone", DataCategory.CONTACT, FieldType.PHONE, "Home landline number"),
        PersonalDataTemplate("Work Phone", DataCategory.CONTACT, FieldType.PHONE, "Work/office number"),
        PersonalDataTemplate("Personal Email", DataCategory.CONTACT, FieldType.EMAIL, "Personal email address"),
        PersonalDataTemplate("Work Email", DataCategory.CONTACT, FieldType.EMAIL, "Work/business email"),
        PersonalDataTemplate("Website", DataCategory.CONTACT, FieldType.URL, "Personal website URL"),
        PersonalDataTemplate("LinkedIn", DataCategory.CONTACT, FieldType.URL, "LinkedIn profile URL"),

        // Address
        PersonalDataTemplate("Home Address", DataCategory.ADDRESS, FieldType.NOTE, "Full residential address"),
        PersonalDataTemplate("Mailing Address", DataCategory.ADDRESS, FieldType.NOTE, "Postal/mailing address"),
        PersonalDataTemplate("Work Address", DataCategory.ADDRESS, FieldType.NOTE, "Office/work address"),
        PersonalDataTemplate("City", DataCategory.ADDRESS, FieldType.TEXT, "City of residence"),
        PersonalDataTemplate("State/Province", DataCategory.ADDRESS, FieldType.TEXT, "State or province"),
        PersonalDataTemplate("Postal Code", DataCategory.ADDRESS, FieldType.TEXT, "ZIP or postal code"),
        PersonalDataTemplate("Country", DataCategory.ADDRESS, FieldType.TEXT, "Country of residence"),

        // Financial
        PersonalDataTemplate("Bank Name", DataCategory.FINANCIAL, FieldType.TEXT, "Primary bank name"),
        PersonalDataTemplate("Bank Account", DataCategory.FINANCIAL, FieldType.TEXT, "Account number"),
        PersonalDataTemplate("Routing Number", DataCategory.FINANCIAL, FieldType.TEXT, "Bank routing/ABA number"),
        PersonalDataTemplate("IBAN", DataCategory.FINANCIAL, FieldType.TEXT, "International bank account number"),
        PersonalDataTemplate("Tax ID", DataCategory.FINANCIAL, FieldType.TEXT, "Tax identification number"),

        // Medical
        PersonalDataTemplate("Blood Type", DataCategory.MEDICAL, FieldType.TEXT, "Your blood type (e.g., A+, O-)"),
        PersonalDataTemplate("Allergies", DataCategory.MEDICAL, FieldType.NOTE, "Known allergies"),
        PersonalDataTemplate("Medical Conditions", DataCategory.MEDICAL, FieldType.NOTE, "Relevant medical conditions"),
        PersonalDataTemplate("Emergency Contact", DataCategory.MEDICAL, FieldType.TEXT, "Emergency contact name"),
        PersonalDataTemplate("Emergency Phone", DataCategory.MEDICAL, FieldType.PHONE, "Emergency contact phone"),
        PersonalDataTemplate("Insurance Provider", DataCategory.MEDICAL, FieldType.TEXT, "Health insurance company"),
        PersonalDataTemplate("Insurance ID", DataCategory.MEDICAL, FieldType.TEXT, "Insurance policy/member ID"),
        PersonalDataTemplate("Primary Physician", DataCategory.MEDICAL, FieldType.TEXT, "Primary care doctor's name")
    )

    /** Get templates for a specific category */
    fun forCategory(category: DataCategory): List<PersonalDataTemplate> =
        templates.filter { it.category == category }
}

// MARK: - Multi-Field Templates

/**
 * Input hint for multi-field template fields.
 * Controls which widget is used in the template form.
 */
enum class PersonalDataFieldInputHint {
    TEXT,           // Standard text with word capitalization
    DATE,           // Date picker (MM/DD/YYYY)
    EXPIRY_DATE,    // Expiry date picker (MM/YYYY)
    COUNTRY,        // Country dropdown
    STATE,          // US state / province dropdown
    NUMBER,         // Numeric keyboard
    PHONE,          // Phone number input
    EMAIL           // Email keyboard
}

/**
 * A field definition within a multi-field personal data template.
 */
data class PersonalDataTemplateField(
    val name: String,
    val namespace: String,
    val category: DataCategory,
    val placeholder: String = "",
    val inputHint: PersonalDataFieldInputHint = PersonalDataFieldInputHint.TEXT
)

/**
 * A pre-defined collection of fields for common personal data types.
 * Similar to SecretTemplate but for personal data.
 */
data class PersonalDataMultiTemplate(
    val name: String,
    val description: String,
    val category: DataCategory,
    val fields: List<PersonalDataTemplateField>
) {
    companion object {
        val all = listOf(
            PersonalDataMultiTemplate(
                name = "Home Address",
                description = "Street, city, state, postal code, country",
                category = DataCategory.ADDRESS,
                fields = listOf(
                    PersonalDataTemplateField("Street", "address.home.street", DataCategory.ADDRESS, "e.g., 123 Main St"),
                    PersonalDataTemplateField("Street Line 2", "address.home.street2", DataCategory.ADDRESS, "Apt, Suite, etc."),
                    PersonalDataTemplateField("City", "address.home.city", DataCategory.ADDRESS, "e.g., San Francisco"),
                    PersonalDataTemplateField("State / Province", "address.home.state", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.STATE),
                    PersonalDataTemplateField("Postal Code", "address.home.postal_code", DataCategory.ADDRESS, "e.g., 94102", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Country", "address.home.country", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.COUNTRY)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Business Address",
                description = "Company name, street, city, state, postal code, country",
                category = DataCategory.ADDRESS,
                fields = listOf(
                    PersonalDataTemplateField("Company", "address.work.company", DataCategory.ADDRESS, "e.g., Acme Inc."),
                    PersonalDataTemplateField("Street", "address.work.street", DataCategory.ADDRESS, "e.g., 456 Office Blvd"),
                    PersonalDataTemplateField("Street Line 2", "address.work.street2", DataCategory.ADDRESS, "Floor, Suite, etc."),
                    PersonalDataTemplateField("City", "address.work.city", DataCategory.ADDRESS, "e.g., New York"),
                    PersonalDataTemplateField("State / Province", "address.work.state", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.STATE),
                    PersonalDataTemplateField("Postal Code", "address.work.postal_code", DataCategory.ADDRESS, "e.g., 10001", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Country", "address.work.country", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.COUNTRY)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Family Member",
                description = "Name, relationship, phone, email",
                category = DataCategory.CONTACT,
                fields = listOf(
                    PersonalDataTemplateField("Full Name", "contact.family.name", DataCategory.CONTACT, "e.g., Jane Doe"),
                    PersonalDataTemplateField("Relationship", "contact.family.relationship", DataCategory.CONTACT, "e.g., Spouse, Parent"),
                    PersonalDataTemplateField("Phone", "contact.family.phone", DataCategory.CONTACT, "", PersonalDataFieldInputHint.PHONE),
                    PersonalDataTemplateField("Email", "contact.family.email", DataCategory.CONTACT, "", PersonalDataFieldInputHint.EMAIL)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Emergency Contact",
                description = "Name, relationship, phone",
                category = DataCategory.MEDICAL,
                fields = listOf(
                    PersonalDataTemplateField("Name", "medical.emergency.name", DataCategory.MEDICAL, "e.g., John Smith"),
                    PersonalDataTemplateField("Relationship", "medical.emergency.relationship", DataCategory.MEDICAL, "e.g., Spouse, Parent"),
                    PersonalDataTemplateField("Phone", "medical.emergency.phone", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.PHONE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Full Name",
                description = "Prefix, first, middle, last, suffix",
                category = DataCategory.IDENTITY,
                fields = listOf(
                    PersonalDataTemplateField("Prefix", "personal.legal.prefix", DataCategory.IDENTITY, "e.g., Mr., Ms., Dr."),
                    PersonalDataTemplateField("First Name", "personal.legal.first_name", DataCategory.IDENTITY, ""),
                    PersonalDataTemplateField("Middle Name", "personal.legal.middle_name", DataCategory.IDENTITY, ""),
                    PersonalDataTemplateField("Last Name", "personal.legal.last_name", DataCategory.IDENTITY, ""),
                    PersonalDataTemplateField("Suffix", "personal.legal.suffix", DataCategory.IDENTITY, "e.g., Jr., III")
                )
            ),
            PersonalDataMultiTemplate(
                name = "Government ID",
                description = "ID type, number, issuing authority, expiry",
                category = DataCategory.IDENTITY,
                fields = listOf(
                    PersonalDataTemplateField("ID Type", "identity.gov_id.type", DataCategory.IDENTITY, "e.g., Passport, Driver License"),
                    PersonalDataTemplateField("Number", "identity.gov_id.number", DataCategory.IDENTITY, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Issuing Authority", "identity.gov_id.issuing_authority", DataCategory.IDENTITY, "e.g., State of California"),
                    PersonalDataTemplateField("Expiry Date", "identity.gov_id.expiry", DataCategory.IDENTITY, "", PersonalDataFieldInputHint.EXPIRY_DATE)
                )
            )
        )
    }
}

/**
 * State for the multi-field template form dialog.
 */
data class PersonalDataTemplateFormState(
    val template: PersonalDataMultiTemplate,
    val fieldValues: Map<Int, String> = emptyMap(),
    val isSaving: Boolean = false
) {
    fun getValue(fieldIndex: Int): String = fieldValues[fieldIndex] ?: ""
    fun hasAnyValue(): Boolean = fieldValues.values.any { it.isNotBlank() }
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
