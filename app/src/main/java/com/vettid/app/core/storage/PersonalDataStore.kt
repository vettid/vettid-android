package com.vettid.app.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for personal data using EncryptedSharedPreferences.
 *
 * Personal data is organized into three categories:
 * 1. System fields (read-only): firstName, lastName, email from registration
 * 2. Optional fields (editable): phone, address, birthday, etc.
 * 3. Custom fields (user-created): arbitrary key-value pairs
 *
 * System fields come from registration and cannot be changed without admin approval.
 * All data is synced to the vault via profile.update NATS topic.
 */
@Singleton
class PersonalDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vettid_personal_data",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val TAG = "PersonalDataStore"

        // System fields (read-only from registration)
        private const val KEY_FIRST_NAME = "system_first_name"
        private const val KEY_LAST_NAME = "system_last_name"
        private const val KEY_EMAIL = "system_email"
        private const val KEY_SYSTEM_FIELDS_SET = "system_fields_set"

        // Optional fields (user-editable)
        // Name fields
        private const val KEY_PREFIX = "optional_prefix"
        private const val KEY_OPT_FIRST_NAME = "optional_first_name"
        private const val KEY_MIDDLE_NAME = "optional_middle_name"
        private const val KEY_OPT_LAST_NAME = "optional_last_name"
        private const val KEY_SUFFIX = "optional_suffix"
        // Contact fields
        private const val KEY_PHONE = "optional_phone"
        private const val KEY_BIRTHDAY = "optional_birthday"
        // Address fields
        private const val KEY_STREET = "optional_street"
        private const val KEY_STREET2 = "optional_street2"
        private const val KEY_CITY = "optional_city"
        private const val KEY_STATE = "optional_state"
        private const val KEY_POSTAL_CODE = "optional_postal_code"
        private const val KEY_COUNTRY = "optional_country"
        // Social/Web fields
        private const val KEY_WEBSITE = "optional_website"
        private const val KEY_LINKEDIN = "optional_linkedin"
        private const val KEY_TWITTER = "optional_twitter"
        private const val KEY_INSTAGRAM = "optional_instagram"
        private const val KEY_GITHUB = "optional_github"

        // Custom fields
        private const val KEY_CUSTOM_FIELDS = "custom_fields"

        // Custom categories (user-defined)
        private const val KEY_CUSTOM_CATEGORIES = "custom_categories"

        // Public profile settings (which fields to share)
        private const val KEY_PUBLIC_PROFILE_FIELDS = "public_profile_fields"
        private const val KEY_PUBLIC_PROFILE_VERSION = "public_profile_version"

        // Sync status
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_PENDING_SYNC = "pending_sync"

        // Field sort order
        private const val KEY_FIELD_SORT_ORDER = "field_sort_order"

        // Predefined categories matching enclave
        val PREDEFINED_CATEGORIES = listOf(
            CategoryInfo("identity", "Identity", "person"),
            CategoryInfo("contact", "Contact", "phone"),
            CategoryInfo("address", "Address", "location"),
            CategoryInfo("financial", "Financial", "account_balance"),
            CategoryInfo("medical", "Medical", "medical"),
            CategoryInfo("other", "Other", "more")
        )
    }

    // MARK: - System Fields (Read-Only)

    /**
     * Get system fields from registration.
     * These fields are read-only and can only be changed by admin.
     *
     * @return SystemPersonalData or null if not yet set
     */
    fun getSystemFields(): SystemPersonalData? {
        val isSet = encryptedPrefs.getBoolean(KEY_SYSTEM_FIELDS_SET, false)
        android.util.Log.d("PersonalDataStore", "getSystemFields: isSet=$isSet")
        if (!isSet) {
            return null
        }
        val firstName = encryptedPrefs.getString(KEY_FIRST_NAME, "") ?: ""
        val lastName = encryptedPrefs.getString(KEY_LAST_NAME, "") ?: ""
        val email = encryptedPrefs.getString(KEY_EMAIL, "") ?: ""
        android.util.Log.d("PersonalDataStore", "getSystemFields returning: $firstName $lastName <$email>")
        return SystemPersonalData(
            firstName = firstName,
            lastName = lastName,
            email = email
        )
    }

    /**
     * Store system fields from registration.
     * This should only be called once during enrollment or when
     * syncing from the vault's registration data.
     *
     * @param data System personal data from registration
     */
    fun storeSystemFields(data: SystemPersonalData) {
        android.util.Log.d("PersonalDataStore", "storeSystemFields called with: ${data.firstName} ${data.lastName} <${data.email}>")
        encryptedPrefs.edit().apply {
            putString(KEY_FIRST_NAME, data.firstName)
            putString(KEY_LAST_NAME, data.lastName)
            putString(KEY_EMAIL, data.email)
            putBoolean(KEY_SYSTEM_FIELDS_SET, true)
            commit()  // Use commit() instead of apply() to ensure synchronous write
        }
        // Verify the data was stored correctly
        val verified = encryptedPrefs.getBoolean(KEY_SYSTEM_FIELDS_SET, false)
        val storedFirstName = encryptedPrefs.getString(KEY_FIRST_NAME, null)
        android.util.Log.d("PersonalDataStore", "storeSystemFields verified: flag=$verified, firstName=$storedFirstName")
        markPendingSync()
    }

    /**
     * Check if system fields have been set.
     */
    fun hasSystemFields(): Boolean {
        return encryptedPrefs.getBoolean(KEY_SYSTEM_FIELDS_SET, false)
    }

    // MARK: - Optional Fields (Editable)

    /**
     * Get optional user-editable fields.
     *
     * @return OptionalPersonalData with current values
     */
    fun getOptionalFields(): OptionalPersonalData {
        val firstName = encryptedPrefs.getString(KEY_OPT_FIRST_NAME, null)
        val middleName = encryptedPrefs.getString(KEY_MIDDLE_NAME, null)
        val lastName = encryptedPrefs.getString(KEY_OPT_LAST_NAME, null)
        android.util.Log.d(TAG, "getOptionalFields: firstName=$firstName, middleName=$middleName, lastName=$lastName")
        return OptionalPersonalData(
            // Name fields
            prefix = encryptedPrefs.getString(KEY_PREFIX, null),
            firstName = firstName,
            middleName = middleName,
            lastName = lastName,
            suffix = encryptedPrefs.getString(KEY_SUFFIX, null),
            // Contact fields
            phone = encryptedPrefs.getString(KEY_PHONE, null),
            birthday = encryptedPrefs.getString(KEY_BIRTHDAY, null),
            // Address fields
            street = encryptedPrefs.getString(KEY_STREET, null),
            street2 = encryptedPrefs.getString(KEY_STREET2, null),
            city = encryptedPrefs.getString(KEY_CITY, null),
            state = encryptedPrefs.getString(KEY_STATE, null),
            postalCode = encryptedPrefs.getString(KEY_POSTAL_CODE, null),
            country = encryptedPrefs.getString(KEY_COUNTRY, null),
            // Social/Web fields
            website = encryptedPrefs.getString(KEY_WEBSITE, null),
            linkedin = encryptedPrefs.getString(KEY_LINKEDIN, null),
            twitter = encryptedPrefs.getString(KEY_TWITTER, null),
            instagram = encryptedPrefs.getString(KEY_INSTAGRAM, null),
            github = encryptedPrefs.getString(KEY_GITHUB, null)
        )
    }

    /**
     * Update optional fields.
     * Null values will clear the field.
     *
     * @param data Updated optional data
     */
    fun updateOptionalFields(data: OptionalPersonalData) {
        encryptedPrefs.edit().apply {
            // Name fields
            putStringOrRemove(KEY_PREFIX, data.prefix)
            putStringOrRemove(KEY_OPT_FIRST_NAME, data.firstName)
            putStringOrRemove(KEY_MIDDLE_NAME, data.middleName)
            putStringOrRemove(KEY_OPT_LAST_NAME, data.lastName)
            putStringOrRemove(KEY_SUFFIX, data.suffix)
            // Contact fields
            putStringOrRemove(KEY_PHONE, data.phone)
            putStringOrRemove(KEY_BIRTHDAY, data.birthday)
            // Address fields
            putStringOrRemove(KEY_STREET, data.street)
            putStringOrRemove(KEY_STREET2, data.street2)
            putStringOrRemove(KEY_CITY, data.city)
            putStringOrRemove(KEY_STATE, data.state)
            putStringOrRemove(KEY_POSTAL_CODE, data.postalCode)
            putStringOrRemove(KEY_COUNTRY, data.country)
            // Social/Web fields
            putStringOrRemove(KEY_WEBSITE, data.website)
            putStringOrRemove(KEY_LINKEDIN, data.linkedin)
            putStringOrRemove(KEY_TWITTER, data.twitter)
            putStringOrRemove(KEY_INSTAGRAM, data.instagram)
            putStringOrRemove(KEY_GITHUB, data.github)
            apply()
        }
        markPendingSync()
    }

    /**
     * Update a single optional field.
     *
     * @param field The field to update
     * @param value The new value (null to clear)
     */
    fun updateOptionalField(field: OptionalField, value: String?) {
        android.util.Log.d(TAG, "updateOptionalField: field=$field, value=$value")
        val key = when (field) {
            // Name fields
            OptionalField.PREFIX -> KEY_PREFIX
            OptionalField.FIRST_NAME -> KEY_OPT_FIRST_NAME
            OptionalField.MIDDLE_NAME -> KEY_MIDDLE_NAME
            OptionalField.LAST_NAME -> KEY_OPT_LAST_NAME
            OptionalField.SUFFIX -> KEY_SUFFIX
            // Contact fields
            OptionalField.PHONE -> KEY_PHONE
            OptionalField.BIRTHDAY -> KEY_BIRTHDAY
            // Address fields
            OptionalField.STREET -> KEY_STREET
            OptionalField.STREET2 -> KEY_STREET2
            OptionalField.CITY -> KEY_CITY
            OptionalField.STATE -> KEY_STATE
            OptionalField.POSTAL_CODE -> KEY_POSTAL_CODE
            OptionalField.COUNTRY -> KEY_COUNTRY
            // Social/Web fields
            OptionalField.WEBSITE -> KEY_WEBSITE
            OptionalField.LINKEDIN -> KEY_LINKEDIN
            OptionalField.TWITTER -> KEY_TWITTER
            OptionalField.INSTAGRAM -> KEY_INSTAGRAM
            OptionalField.GITHUB -> KEY_GITHUB
        }
        android.util.Log.d(TAG, "updateOptionalField: storing to key=$key")
        encryptedPrefs.edit().apply {
            putStringOrRemove(key, value)
            apply()
        }
        markPendingSync()
    }

    /**
     * Update an optional field by its key name.
     * Used for bulk import from vault sync.
     *
     * @param keyName The field key name (e.g., "phone", "middleName", "street")
     * @param value The new value (null to clear)
     * @return true if the key was recognized and updated
     */
    fun updateOptionalFieldByKey(keyName: String, value: String?): Boolean {
        val field = when (keyName) {
            // Name fields
            "prefix" -> OptionalField.PREFIX
            "firstName", "first_name" -> OptionalField.FIRST_NAME
            "middleName", "middle_name" -> OptionalField.MIDDLE_NAME
            "lastName", "last_name" -> OptionalField.LAST_NAME
            "suffix" -> OptionalField.SUFFIX
            // Contact fields
            "phone" -> OptionalField.PHONE
            "birthday" -> OptionalField.BIRTHDAY
            // Address fields
            "street" -> OptionalField.STREET
            "street2" -> OptionalField.STREET2
            "city" -> OptionalField.CITY
            "state" -> OptionalField.STATE
            "postalCode", "postal_code" -> OptionalField.POSTAL_CODE
            "country" -> OptionalField.COUNTRY
            // Social/Web fields
            "website" -> OptionalField.WEBSITE
            "linkedin" -> OptionalField.LINKEDIN
            "twitter" -> OptionalField.TWITTER
            "instagram" -> OptionalField.INSTAGRAM
            "github" -> OptionalField.GITHUB
            else -> null
        }

        return if (field != null) {
            updateOptionalField(field, value)
            true
        } else {
            false
        }
    }

    /**
     * Update an optional field by its dotted namespace.
     * Used for importing data from vault which uses namespace format.
     *
     * @param namespace The dotted namespace (e.g., "personal.legal.first_name", "contact.phone.mobile")
     * @param value The new value (null to clear)
     * @return true if the namespace was recognized and updated
     */
    fun updateOptionalFieldByNamespace(namespace: String, value: String?): Boolean {
        val field = when (namespace) {
            // Name fields (personal.legal.*)
            "personal.legal.prefix" -> OptionalField.PREFIX
            "personal.legal.first_name" -> OptionalField.FIRST_NAME
            "personal.legal.middle_name" -> OptionalField.MIDDLE_NAME
            "personal.legal.last_name" -> OptionalField.LAST_NAME
            "personal.legal.suffix" -> OptionalField.SUFFIX
            // Contact fields
            "contact.phone.mobile" -> OptionalField.PHONE
            "personal.info.birthday" -> OptionalField.BIRTHDAY
            // Address fields (address.home.*)
            "address.home.street" -> OptionalField.STREET
            "address.home.street2" -> OptionalField.STREET2
            "address.home.city" -> OptionalField.CITY
            "address.home.state" -> OptionalField.STATE
            "address.home.postal_code" -> OptionalField.POSTAL_CODE
            "address.home.country" -> OptionalField.COUNTRY
            // Social/Web fields (social.*)
            "social.website.personal" -> OptionalField.WEBSITE
            "social.linkedin.url" -> OptionalField.LINKEDIN
            "social.twitter.handle" -> OptionalField.TWITTER
            "social.instagram.handle" -> OptionalField.INSTAGRAM
            "social.github.username" -> OptionalField.GITHUB
            else -> null
        }

        return if (field != null) {
            updateOptionalField(field, value)
            true
        } else {
            // Not a known optional field - might be a custom field
            false
        }
    }

    // MARK: - Custom Fields

    /**
     * Get all custom fields.
     *
     * @return List of custom fields
     */
    fun getCustomFields(): List<CustomField> {
        val json = encryptedPrefs.getString(KEY_CUSTOM_FIELDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CustomField>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Add a new custom field.
     *
     * @param name Field name
     * @param value Field value
     * @param category Optional category
     * @param fieldType The type of field (determines display/input behavior)
     * @return The created custom field
     */
    /**
     * Check if a field name already exists (case-insensitive).
     *
     * @param name The field name to check
     * @param excludeId Optional field ID to exclude (for updates)
     * @return true if name is already used
     */
    fun isFieldNameTaken(name: String, excludeId: String? = null): Boolean {
        val normalizedName = name.trim().lowercase()
        return getCustomFields().any {
            it.id != excludeId && it.name.trim().lowercase() == normalizedName
        }
    }

    /**
     * Normalize a field name to a consistent format.
     * - Trims whitespace
     * - Converts to Title Case
     *
     * @param name The raw field name
     * @return Normalized field name
     */
    fun normalizeFieldName(name: String): String {
        return name.trim()
            .split(" ")
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Validate a field name.
     *
     * @param name The field name to validate
     * @return Error message if invalid, null if valid
     */
    fun validateFieldName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Field name cannot be empty"
            trimmed.length < 2 -> "Field name must be at least 2 characters"
            trimmed.length > 50 -> "Field name cannot exceed 50 characters"
            !trimmed.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9 '-]*[a-zA-Z0-9]$|^[a-zA-Z0-9]$")) ->
                "Field name can only contain letters, numbers, spaces, hyphens, and apostrophes"
            else -> null
        }
    }

    fun addCustomField(
        name: String,
        value: String,
        category: FieldCategory = FieldCategory.OTHER,
        fieldType: FieldType = FieldType.TEXT
    ): CustomField {
        val normalizedName = normalizeFieldName(name)
        val field = CustomField(
            id = UUID.randomUUID().toString(),
            name = normalizedName,
            value = value,
            category = category,
            fieldType = fieldType,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val fields = getCustomFields().toMutableList()
        fields.add(field)
        saveCustomFields(fields)
        markPendingSync()
        return field
    }

    /**
     * Update an existing custom field.
     *
     * @param field The field to update (matched by ID)
     */
    fun updateCustomField(field: CustomField) {
        val fields = getCustomFields().toMutableList()
        val index = fields.indexOfFirst { it.id == field.id }
        if (index >= 0) {
            fields[index] = field.copy(updatedAt = System.currentTimeMillis())
            saveCustomFields(fields)
            markPendingSync()
        }
    }

    /**
     * Remove a custom field by ID.
     *
     * @param id The field ID to remove
     */
    fun removeCustomField(id: String) {
        val fields = getCustomFields().toMutableList()
        fields.removeAll { it.id == id }
        saveCustomFields(fields)
        markPendingSync()
    }

    private fun saveCustomFields(fields: List<CustomField>) {
        val json = gson.toJson(fields)
        encryptedPrefs.edit().putString(KEY_CUSTOM_FIELDS, json).apply()
    }

    // MARK: - Sync Status

    /**
     * Check if there are pending changes to sync.
     */
    fun hasPendingSync(): Boolean {
        return encryptedPrefs.getBoolean(KEY_PENDING_SYNC, false)
    }

    /**
     * Mark that there are pending changes to sync.
     */
    fun markPendingSync() {
        encryptedPrefs.edit().putBoolean(KEY_PENDING_SYNC, true).apply()
    }

    /**
     * Mark sync as complete.
     */
    fun markSyncComplete() {
        encryptedPrefs.edit().apply {
            putBoolean(KEY_PENDING_SYNC, false)
            putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            apply()
        }
    }

    /**
     * Get the last sync timestamp.
     *
     * @return Epoch millis of last sync, or 0 if never synced
     */
    fun getLastSyncedAt(): Long {
        return encryptedPrefs.getLong(KEY_LAST_SYNCED_AT, 0)
    }

    // MARK: - Export/Import for Sync

    /**
     * Export all personal data as a map for syncing to vault.
     * This format is suitable for the profile.update NATS topic.
     *
     * @return Map of all personal data
     */
    fun exportForSync(): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>()

        // System fields
        getSystemFields()?.let { system ->
            data["firstName"] = system.firstName
            data["lastName"] = system.lastName
            data["email"] = system.email
        }

        // Optional fields
        val optional = getOptionalFields()
        // Name fields
        data["prefix"] = optional.prefix
        data["middleName"] = optional.middleName
        data["suffix"] = optional.suffix
        // Contact fields
        data["phone"] = optional.phone
        data["birthday"] = optional.birthday
        // Address fields
        data["street"] = optional.street
        data["street2"] = optional.street2
        data["city"] = optional.city
        data["state"] = optional.state
        data["postalCode"] = optional.postalCode
        data["country"] = optional.country
        // Social/Web fields
        data["website"] = optional.website
        data["linkedin"] = optional.linkedin
        data["twitter"] = optional.twitter
        data["instagram"] = optional.instagram
        data["github"] = optional.github

        // Custom fields
        val customFields = getCustomFields()
        if (customFields.isNotEmpty()) {
            data["customFields"] = customFields.map { field ->
                mapOf(
                    "id" to field.id,
                    "name" to field.name,
                    "value" to field.value,
                    "category" to field.category.name,
                    "fieldType" to field.fieldType.name,
                    "createdAt" to field.createdAt,
                    "updatedAt" to field.updatedAt
                )
            }
        }

        return data
    }

    /**
     * Import personal data from vault sync response.
     *
     * @param data Map of personal data from vault
     */
    fun importFromSync(data: Map<String, Any?>) {
        // Import system fields if present
        val firstName = data["firstName"] as? String
        val lastName = data["lastName"] as? String
        val email = data["email"] as? String
        if (firstName != null && lastName != null && email != null) {
            storeSystemFields(SystemPersonalData(firstName, lastName, email))
        }

        // Import optional fields
        updateOptionalFields(OptionalPersonalData(
            // Name fields
            prefix = data["prefix"] as? String,
            middleName = data["middleName"] as? String,
            suffix = data["suffix"] as? String,
            // Contact fields
            phone = data["phone"] as? String,
            birthday = data["birthday"] as? String,
            // Address fields
            street = data["street"] as? String,
            street2 = data["street2"] as? String,
            city = data["city"] as? String,
            state = data["state"] as? String,
            postalCode = data["postalCode"] as? String,
            country = data["country"] as? String,
            // Social/Web fields
            website = data["website"] as? String,
            linkedin = data["linkedin"] as? String,
            twitter = data["twitter"] as? String,
            instagram = data["instagram"] as? String,
            github = data["github"] as? String
        ))

        // Import custom fields
        @Suppress("UNCHECKED_CAST")
        val customFieldsData = data["customFields"] as? List<Map<String, Any?>>
        if (customFieldsData != null) {
            val customFields = customFieldsData.mapNotNull { fieldData ->
                try {
                    CustomField(
                        id = fieldData["id"] as String,
                        name = fieldData["name"] as String,
                        value = fieldData["value"] as String,
                        category = try {
                            FieldCategory.valueOf(fieldData["category"] as String)
                        } catch (e: Exception) {
                            FieldCategory.OTHER
                        },
                        fieldType = try {
                            FieldType.valueOf(fieldData["fieldType"] as? String ?: "TEXT")
                        } catch (e: Exception) {
                            FieldType.TEXT
                        },
                        createdAt = (fieldData["createdAt"] as Number).toLong(),
                        updatedAt = (fieldData["updatedAt"] as Number).toLong()
                    )
                } catch (e: Exception) {
                    null
                }
            }
            saveCustomFields(customFields)
        }

        // Don't mark pending sync since we just synced from vault
        encryptedPrefs.edit().apply {
            putBoolean(KEY_PENDING_SYNC, false)
            putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            apply()
        }
    }

    // MARK: - Custom Categories

    /**
     * Get all categories (predefined + custom).
     */
    fun getAllCategories(): List<CategoryInfo> {
        return PREDEFINED_CATEGORIES + getCustomCategories()
    }

    /**
     * Get custom user-defined categories.
     */
    fun getCustomCategories(): List<CategoryInfo> {
        val json = encryptedPrefs.getString(KEY_CUSTOM_CATEGORIES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CategoryInfo>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Add a custom category.
     */
    fun addCustomCategory(name: String, icon: String? = null): CategoryInfo {
        val category = CategoryInfo(
            id = UUID.randomUUID().toString(),
            name = name,
            icon = icon ?: "category",
            createdAt = System.currentTimeMillis()
        )
        val categories = getCustomCategories().toMutableList()
        categories.add(category)
        saveCustomCategories(categories)
        return category
    }

    /**
     * Remove a custom category by ID.
     */
    fun removeCustomCategory(id: String) {
        val categories = getCustomCategories().filter { it.id != id }
        saveCustomCategories(categories)
    }

    private fun saveCustomCategories(categories: List<CategoryInfo>) {
        val json = gson.toJson(categories)
        encryptedPrefs.edit().putString(KEY_CUSTOM_CATEGORIES, json).apply()
    }

    // MARK: - Public Profile Settings

    /**
     * Get the list of fields selected for public profile sharing.
     */
    fun getPublicProfileFields(): List<String> {
        val json = encryptedPrefs.getString(KEY_PUBLIC_PROFILE_FIELDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Update the list of fields to share in public profile.
     */
    fun updatePublicProfileFields(fields: List<String>) {
        val json = gson.toJson(fields)
        encryptedPrefs.edit()
            .putString(KEY_PUBLIC_PROFILE_FIELDS, json)
            .putInt(KEY_PUBLIC_PROFILE_VERSION, getPublicProfileVersion() + 1)
            .apply()
        markPendingSync()
    }

    /**
     * Set whether a specific field is included in the public profile.
     *
     * @param fieldId The field ID to update
     * @param isInPublicProfile Whether to include in public profile
     */
    fun setPublicProfileStatus(fieldId: String, isInPublicProfile: Boolean) {
        val currentFields = getPublicProfileFields().toMutableList()
        if (isInPublicProfile) {
            if (!currentFields.contains(fieldId)) {
                currentFields.add(fieldId)
            }
        } else {
            currentFields.remove(fieldId)
        }
        updatePublicProfileFields(currentFields)
    }

    /**
     * Check if a field is in the public profile.
     *
     * @param fieldId The field ID to check
     * @return true if the field is included in public profile
     */
    fun isFieldInPublicProfile(fieldId: String): Boolean {
        return getPublicProfileFields().contains(fieldId)
    }

    /**
     * Get the current public profile version.
     */
    fun getPublicProfileVersion(): Int {
        return encryptedPrefs.getInt(KEY_PUBLIC_PROFILE_VERSION, 0)
    }

    // MARK: - Field Sort Order

    /**
     * Get field sort order map.
     * Returns a map of field namespace to sort order index.
     */
    fun getFieldSortOrder(): Map<String, Int> {
        val json = encryptedPrefs.getString(KEY_FIELD_SORT_ORDER, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Int>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Update field sort order.
     * Persists the sort order locally for offline access.
     *
     * @param sortOrder Map of field namespace to sort order index
     */
    fun updateFieldSortOrder(sortOrder: Map<String, Int>) {
        val json = gson.toJson(sortOrder)
        encryptedPrefs.edit()
            .putString(KEY_FIELD_SORT_ORDER, json)
            .apply()
    }

    /**
     * Get sort order for a specific field.
     *
     * @param namespace The field namespace
     * @return Sort order index, or default (Int.MAX_VALUE) if not set
     */
    fun getFieldSortOrderFor(namespace: String): Int {
        return getFieldSortOrder()[namespace] ?: Int.MAX_VALUE
    }

    /**
     * Update sort order for a specific field.
     *
     * @param namespace The field namespace
     * @param sortOrder The new sort order index
     */
    fun updateFieldSortOrderFor(namespace: String, sortOrder: Int) {
        val currentOrder = getFieldSortOrder().toMutableMap()
        currentOrder[namespace] = sortOrder
        updateFieldSortOrder(currentOrder)
    }

    // MARK: - Namespace Helpers

    /**
     * Convert a dotted namespace to display name.
     * e.g., "contact.phone.mobile" → "Mobile Phone"
     */
    fun namespaceToDisplayName(namespace: String): String {
        val parts = namespace.split(".")
        return when {
            parts.size >= 2 -> {
                val lastPart = parts.last()
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                lastPart
            }
            else -> namespace.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Generate a dotted namespace from category and field name.
     * e.g., category="contact", name="Mobile Phone" → "contact.phone.mobile"
     */
    fun generateNamespace(categoryId: String, fieldName: String): String {
        val normalizedName = fieldName
            .lowercase()
            .trim()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return "$categoryId.$normalizedName"
    }

    /**
     * Get the category from a namespace.
     * e.g., "contact.phone.mobile" → "contact"
     */
    fun getNamespaceCategory(namespace: String): String {
        return namespace.split(".").firstOrNull() ?: "other"
    }

    // MARK: - Enhanced Export for Enclave

    /**
     * Export personal data in the format expected by the enclave.
     * Uses the new PersonalDataField structure with dotted namespaces.
     */
    fun exportForEnclaveSync(): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>()

        // System fields with _system_ prefix
        getSystemFields()?.let { system ->
            data["_system_first_name"] = system.firstName
            data["_system_last_name"] = system.lastName
            data["_system_email"] = system.email
            data["_system_stored_at"] = System.currentTimeMillis()
        }

        // Optional fields as PersonalDataField format
        val optional = getOptionalFields()
        val optionalFieldsData = mutableListOf<Map<String, Any?>>()

        // Helper to add optional field if not null
        fun addOptionalField(namespace: String, displayName: String, value: String?, fieldType: String, category: String) {
            if (value != null) {
                optionalFieldsData.add(mapOf(
                    "id" to UUID.randomUUID().toString(),
                    "name" to namespace,
                    "display_name" to displayName,
                    "value" to value,
                    "field_type" to fieldType,
                    "category" to category,
                    "is_sensitive" to false,
                    "created_at" to System.currentTimeMillis(),
                    "updated_at" to System.currentTimeMillis()
                ))
            }
        }

        // Map optional fields to namespaces
        addOptionalField("personal.legal.prefix", "Name Prefix", optional.prefix, "text", "identity")
        addOptionalField("personal.legal.first_name", "Legal First Name", optional.firstName, "text", "identity")
        addOptionalField("personal.legal.middle_name", "Middle Name", optional.middleName, "text", "identity")
        addOptionalField("personal.legal.last_name", "Legal Last Name", optional.lastName, "text", "identity")
        addOptionalField("personal.legal.suffix", "Name Suffix", optional.suffix, "text", "identity")
        addOptionalField("contact.phone.mobile", "Mobile Phone", optional.phone, "phone", "contact")
        addOptionalField("personal.info.birthday", "Birthday", optional.birthday, "date", "identity")
        addOptionalField("address.home.street", "Street Address", optional.street, "text", "address")
        addOptionalField("address.home.street2", "Street Address 2", optional.street2, "text", "address")
        addOptionalField("address.home.city", "City", optional.city, "text", "address")
        addOptionalField("address.home.state", "State", optional.state, "text", "address")
        addOptionalField("address.home.postal_code", "Postal Code", optional.postalCode, "text", "address")
        addOptionalField("address.home.country", "Country", optional.country, "text", "address")
        addOptionalField("social.website.personal", "Personal Website", optional.website, "url", "contact")
        addOptionalField("social.linkedin.url", "LinkedIn", optional.linkedin, "url", "contact")
        addOptionalField("social.twitter.handle", "X/Twitter", optional.twitter, "text", "contact")
        addOptionalField("social.instagram.handle", "Instagram", optional.instagram, "text", "contact")
        addOptionalField("social.github.username", "GitHub", optional.github, "url", "contact")

        // Add custom fields
        getCustomFields().forEach { field ->
            val namespace = generateNamespace(field.category.name.lowercase(), field.name)
            optionalFieldsData.add(mapOf(
                "id" to field.id,
                "name" to namespace,
                "display_name" to field.name,
                "value" to field.value,
                "field_type" to field.fieldType.name.lowercase(),
                "category" to field.category.name.lowercase(),
                "is_sensitive" to (field.fieldType == FieldType.PASSWORD),
                "created_at" to field.createdAt,
                "updated_at" to field.updatedAt
            ))
        }

        if (optionalFieldsData.isNotEmpty()) {
            data["fields"] = optionalFieldsData
        }

        // Include custom categories
        val customCategories = getCustomCategories()
        if (customCategories.isNotEmpty()) {
            data["categories"] = customCategories.map { cat ->
                mapOf(
                    "id" to cat.id,
                    "name" to cat.name,
                    "icon" to cat.icon,
                    "created_at" to cat.createdAt
                )
            }
        }

        // Include public profile settings
        val publicFields = getPublicProfileFields()
        if (publicFields.isNotEmpty()) {
            data["public_profile"] = mapOf(
                "version" to getPublicProfileVersion(),
                "fields" to publicFields
            )
        }

        return data
    }

    /**
     * Export personal data as a simple field map for profile.update.
     * The enclave expects: { "fields": { "field_name": "value" } }
     *
     * This produces the flat fields map with dotted namespaces:
     * - _system_first_name, _system_last_name, _system_email (registration info)
     * - personal.legal.first_name, contact.phone.mobile, etc. (optional fields)
     */
    fun exportFieldsMapForProfileUpdate(): Map<String, String> {
        val fields = mutableMapOf<String, String>()

        // System fields (registration info) - these use _system_ prefix
        getSystemFields()?.let { system ->
            fields["_system_first_name"] = system.firstName
            fields["_system_last_name"] = system.lastName
            fields["_system_email"] = system.email
            fields["_system_stored_at"] = System.currentTimeMillis().toString()
        }

        // Optional fields - use dotted namespace format
        val optional = getOptionalFields()

        // Name fields
        optional.prefix?.let { fields["personal.legal.prefix"] = it }
        optional.firstName?.let { fields["personal.legal.first_name"] = it }
        optional.middleName?.let { fields["personal.legal.middle_name"] = it }
        optional.lastName?.let { fields["personal.legal.last_name"] = it }
        optional.suffix?.let { fields["personal.legal.suffix"] = it }

        // Contact fields
        optional.phone?.let { fields["contact.phone.mobile"] = it }
        optional.birthday?.let { fields["personal.info.birthday"] = it }

        // Address fields
        optional.street?.let { fields["address.home.street"] = it }
        optional.street2?.let { fields["address.home.street2"] = it }
        optional.city?.let { fields["address.home.city"] = it }
        optional.state?.let { fields["address.home.state"] = it }
        optional.postalCode?.let { fields["address.home.postal_code"] = it }
        optional.country?.let { fields["address.home.country"] = it }

        // Social/Web fields
        optional.website?.let { fields["social.website.personal"] = it }
        optional.linkedin?.let { fields["social.linkedin.url"] = it }
        optional.twitter?.let { fields["social.twitter.handle"] = it }
        optional.instagram?.let { fields["social.instagram.handle"] = it }
        optional.github?.let { fields["social.github.username"] = it }

        // Custom fields - generate namespace from category and name
        getCustomFields().forEach { field ->
            val namespace = generateNamespace(field.category.name.lowercase(), field.name)
            fields[namespace] = field.value
        }

        return fields
    }

    /**
     * Export fields map for personal-data.update (vault personal data storage).
     * This excludes system fields which are stored separately in profile/_system_*.
     * Returns a map of namespace -> value for user-added personal data only.
     */
    fun exportFieldsMapForPersonalData(): Map<String, String> {
        val fields = mutableMapOf<String, String>()

        // Optional fields - use dotted namespace format
        val optional = getOptionalFields()

        // Name fields
        optional.prefix?.let { fields["personal.legal.prefix"] = it }
        optional.firstName?.let { fields["personal.legal.first_name"] = it }
        optional.middleName?.let { fields["personal.legal.middle_name"] = it }
        optional.lastName?.let { fields["personal.legal.last_name"] = it }
        optional.suffix?.let { fields["personal.legal.suffix"] = it }

        // Contact fields
        optional.phone?.let { fields["contact.phone.mobile"] = it }
        optional.birthday?.let { fields["personal.info.birthday"] = it }

        // Address fields
        optional.street?.let { fields["address.home.street"] = it }
        optional.street2?.let { fields["address.home.street2"] = it }
        optional.city?.let { fields["address.home.city"] = it }
        optional.state?.let { fields["address.home.state"] = it }
        optional.postalCode?.let { fields["address.home.postal_code"] = it }
        optional.country?.let { fields["address.home.country"] = it }

        // Social/Web fields
        optional.website?.let { fields["social.website.personal"] = it }
        optional.linkedin?.let { fields["social.linkedin.url"] = it }
        optional.twitter?.let { fields["social.twitter.handle"] = it }
        optional.instagram?.let { fields["social.instagram.handle"] = it }
        optional.github?.let { fields["social.github.username"] = it }

        // Custom fields - generate namespace from category and name
        getCustomFields().forEach { field ->
            val namespace = generateNamespace(field.category.name.lowercase(), field.name)
            fields[namespace] = field.value
        }

        return fields
    }

    // MARK: - Clear

    /**
     * Clear all personal data.
     * Use with caution - this is not reversible.
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }

    // MARK: - Helper Extensions

    private fun android.content.SharedPreferences.Editor.putStringOrRemove(
        key: String,
        value: String?
    ): android.content.SharedPreferences.Editor {
        return if (value != null) {
            putString(key, value)
        } else {
            remove(key)
        }
    }
}

// MARK: - Data Models

/**
 * System personal data from registration.
 * These fields are READ-ONLY - changes require admin approval.
 */
data class SystemPersonalData(
    val firstName: String,
    val lastName: String,
    val email: String
)

/**
 * Optional personal data that users can edit.
 */
data class OptionalPersonalData(
    // Name fields (legal name components)
    val prefix: String? = null,       // Mr, Ms, Mrs, Dr, etc.
    val firstName: String? = null,    // Editable first name (legal name)
    val middleName: String? = null,
    val lastName: String? = null,     // Editable last name (legal name)
    val suffix: String? = null,       // Jr, Sr, III, etc.
    // Contact fields
    val phone: String? = null,
    val birthday: String? = null,     // ISO date format (YYYY-MM-DD)
    // Address fields
    val street: String? = null,       // Street address line 1
    val street2: String? = null,      // Street address line 2 (apartment, suite, etc.)
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    // Social/Web fields
    val website: String? = null,      // Personal website URL
    val linkedin: String? = null,     // LinkedIn profile URL or username
    val twitter: String? = null,      // X/Twitter handle (without @)
    val instagram: String? = null,    // Instagram handle (without @)
    val github: String? = null        // GitHub username
)

/**
 * Enum for optional fields.
 */
enum class OptionalField {
    // Name fields
    PREFIX,
    FIRST_NAME,
    MIDDLE_NAME,
    LAST_NAME,
    SUFFIX,
    // Contact fields
    PHONE,
    BIRTHDAY,
    // Address fields
    STREET,
    STREET2,
    CITY,
    STATE,
    POSTAL_CODE,
    COUNTRY,
    // Social/Web fields
    WEBSITE,
    LINKEDIN,
    TWITTER,
    INSTAGRAM,
    GITHUB
}

/**
 * Custom user-created field.
 */
data class CustomField(
    val id: String,
    val name: String,
    val value: String,
    val category: FieldCategory = FieldCategory.OTHER,
    val fieldType: FieldType = FieldType.TEXT,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Types of custom fields.
 * Determines how the value is displayed and input.
 */
enum class FieldType(val displayName: String, val description: String) {
    TEXT("Text", "General text field"),
    PASSWORD("Password", "Hidden/masked value"),
    NUMBER("Number", "Numeric value"),
    DATE("Date", "Date value (YYYY-MM-DD)"),
    EMAIL("Email", "Email address"),
    PHONE("Phone", "Phone number"),
    URL("URL", "Website address"),
    NOTE("Note", "Multi-line text")
}

/**
 * Categories for custom fields.
 */
enum class FieldCategory {
    IDENTITY,   // Name, SSN, passport, etc.
    CONTACT,    // Phone, email, social media
    ADDRESS,    // Physical addresses
    FINANCIAL,  // Bank accounts, credit cards
    MEDICAL,    // Health info, allergies
    OTHER       // Miscellaneous
}

/**
 * Category information for display and sync.
 * Supports both predefined and custom categories.
 */
data class CategoryInfo(
    val id: String,
    val name: String,
    val icon: String,
    val createdAt: Long = 0
) {
    /**
     * Convert to FieldCategory enum if this is a predefined category.
     */
    fun toFieldCategory(): FieldCategory {
        return try {
            FieldCategory.valueOf(id.uppercase())
        } catch (e: Exception) {
            FieldCategory.OTHER
        }
    }
}
