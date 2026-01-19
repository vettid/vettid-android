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
        // System fields (read-only from registration)
        private const val KEY_FIRST_NAME = "system_first_name"
        private const val KEY_LAST_NAME = "system_last_name"
        private const val KEY_EMAIL = "system_email"
        private const val KEY_SYSTEM_FIELDS_SET = "system_fields_set"

        // Optional fields (user-editable)
        private const val KEY_PHONE = "optional_phone"
        private const val KEY_STREET = "optional_street"
        private const val KEY_CITY = "optional_city"
        private const val KEY_STATE = "optional_state"
        private const val KEY_POSTAL_CODE = "optional_postal_code"
        private const val KEY_COUNTRY = "optional_country"
        private const val KEY_BIRTHDAY = "optional_birthday"

        // Custom fields
        private const val KEY_CUSTOM_FIELDS = "custom_fields"

        // Sync status
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_PENDING_SYNC = "pending_sync"
    }

    // MARK: - System Fields (Read-Only)

    /**
     * Get system fields from registration.
     * These fields are read-only and can only be changed by admin.
     *
     * @return SystemPersonalData or null if not yet set
     */
    fun getSystemFields(): SystemPersonalData? {
        if (!encryptedPrefs.getBoolean(KEY_SYSTEM_FIELDS_SET, false)) {
            return null
        }
        return SystemPersonalData(
            firstName = encryptedPrefs.getString(KEY_FIRST_NAME, "") ?: "",
            lastName = encryptedPrefs.getString(KEY_LAST_NAME, "") ?: "",
            email = encryptedPrefs.getString(KEY_EMAIL, "") ?: ""
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
        encryptedPrefs.edit().apply {
            putString(KEY_FIRST_NAME, data.firstName)
            putString(KEY_LAST_NAME, data.lastName)
            putString(KEY_EMAIL, data.email)
            putBoolean(KEY_SYSTEM_FIELDS_SET, true)
            apply()
        }
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
        return OptionalPersonalData(
            phone = encryptedPrefs.getString(KEY_PHONE, null),
            street = encryptedPrefs.getString(KEY_STREET, null),
            city = encryptedPrefs.getString(KEY_CITY, null),
            state = encryptedPrefs.getString(KEY_STATE, null),
            postalCode = encryptedPrefs.getString(KEY_POSTAL_CODE, null),
            country = encryptedPrefs.getString(KEY_COUNTRY, null),
            birthday = encryptedPrefs.getString(KEY_BIRTHDAY, null)
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
            putStringOrRemove(KEY_PHONE, data.phone)
            putStringOrRemove(KEY_STREET, data.street)
            putStringOrRemove(KEY_CITY, data.city)
            putStringOrRemove(KEY_STATE, data.state)
            putStringOrRemove(KEY_POSTAL_CODE, data.postalCode)
            putStringOrRemove(KEY_COUNTRY, data.country)
            putStringOrRemove(KEY_BIRTHDAY, data.birthday)
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
        val key = when (field) {
            OptionalField.PHONE -> KEY_PHONE
            OptionalField.STREET -> KEY_STREET
            OptionalField.CITY -> KEY_CITY
            OptionalField.STATE -> KEY_STATE
            OptionalField.POSTAL_CODE -> KEY_POSTAL_CODE
            OptionalField.COUNTRY -> KEY_COUNTRY
            OptionalField.BIRTHDAY -> KEY_BIRTHDAY
        }
        encryptedPrefs.edit().apply {
            putStringOrRemove(key, value)
            apply()
        }
        markPendingSync()
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
     * @return The created custom field
     */
    fun addCustomField(
        name: String,
        value: String,
        category: FieldCategory = FieldCategory.OTHER
    ): CustomField {
        val field = CustomField(
            id = UUID.randomUUID().toString(),
            name = name,
            value = value,
            category = category,
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
        data["phone"] = optional.phone
        data["street"] = optional.street
        data["city"] = optional.city
        data["state"] = optional.state
        data["postalCode"] = optional.postalCode
        data["country"] = optional.country
        data["birthday"] = optional.birthday

        // Custom fields
        val customFields = getCustomFields()
        if (customFields.isNotEmpty()) {
            data["customFields"] = customFields.map { field ->
                mapOf(
                    "id" to field.id,
                    "name" to field.name,
                    "value" to field.value,
                    "category" to field.category.name,
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
            phone = data["phone"] as? String,
            street = data["street"] as? String,
            city = data["city"] as? String,
            state = data["state"] as? String,
            postalCode = data["postalCode"] as? String,
            country = data["country"] as? String,
            birthday = data["birthday"] as? String
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
    val phone: String? = null,
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val birthday: String? = null  // ISO date format (YYYY-MM-DD)
)

/**
 * Enum for optional fields.
 */
enum class OptionalField {
    PHONE,
    STREET,
    CITY,
    STATE,
    POSTAL_CODE,
    COUNTRY,
    BIRTHDAY
}

/**
 * Custom user-created field.
 */
data class CustomField(
    val id: String,
    val name: String,
    val value: String,
    val category: FieldCategory = FieldCategory.OTHER,
    val createdAt: Long,
    val updatedAt: Long
)

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
