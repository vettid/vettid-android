package com.vettid.app.core.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache + vault-backed writer for personal data.
 *
 * The vault is the only persistent home for user data — every
 * field, custom category, public-profile selection, and sort-order
 * entry lives in a vault namespace (`personal-data/`,
 * `profile/_categories`, `profile/_public`, `personal-data/_sort_order`).
 * This class is a transient cache: in-memory state only, populated
 * on demand via [hydrate] after PIN unlock, dropped when the
 * process ends.
 *
 * Why a cache layer at all: the read API is invoked from many
 * non-coroutine call sites (composables, sync ViewModel methods),
 * so we need synchronous reads without blocking on a NATS round-trip
 * each time. The cache mirrors authoritative vault state and is
 * refreshed whenever the vault publishes a new snapshot via
 * `forApp.profile.public`.
 *
 * Writes flow vault-first: every public mutator dispatches the
 * appropriate vault op (`personal-data.update`, etc.) and updates
 * the cache only on success. Failures surface as exceptions to the
 * caller; the cache stays consistent with the vault.
 */
@Singleton
class PersonalDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ownerSpaceClient: OwnerSpaceClient,
) {
    private val gson = Gson()

    /**
     * In-memory KV that mirrors a SharedPreferences API surface so
     * the rest of the class doesn't change — every call site keeps
     * working unchanged. The map is the only place the data lives;
     * nothing reaches device disk.
     */
    private val encryptedPrefs: MemPrefs = MemPrefs()

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    init {
        // Re-hydrate whenever the vault publishes a fresh own-profile
        // snapshot — covers multi-device edits and any out-of-band
        // catalog changes the local ViewModels didn't drive.
        scope.launch {
            ownerSpaceClient.ownProfileSnapshotTick.collect {
                try { hydrate() } catch (_: Exception) { /* swallow */ }
            }
        }
    }

    /**
     * Suspend hydration entry point. ViewModels that depend on
     * fresh-from-vault state should call this once after PIN
     * unlock; it fans out the necessary vault reads and populates
     * the cache. Subsequent reads via the existing sync API serve
     * from the cache, so composables don't pay a NATS round-trip
     * per render.
     *
     * Idempotent — calling more than once just refreshes.
     */
    suspend fun hydrate() {
        // profile.get-published gives us the canonical view of
        // system fields, custom categories, sort order, public-
        // profile selections, and photo. We map each section into
        // the cache so the existing accessors return live state.
        val resp = ownerSpaceClient.sendAndAwaitResponse(
            "profile.get-published", JsonObject(), 15_000L
        ) as? VaultResponse.HandlerResult ?: return
        if (!resp.success || resp.result == null) return
        val r = resp.result

        // System fields land at the top level of the response.
        val firstName = r.get("first_name")?.takeIf { !it.isJsonNull }?.asString
        val lastName = r.get("last_name")?.takeIf { !it.isJsonNull }?.asString
        val email = r.get("email")?.takeIf { !it.isJsonNull }?.asString
        if (firstName != null && lastName != null && email != null) {
            encryptedPrefs.edit()
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_LAST_NAME, lastName)
                .putString(KEY_EMAIL, email)
                .putBoolean(KEY_SYSTEM_FIELDS_SET, true)
                .apply()
        }

        // Photo lives inline on the published-profile response.
        r.get("photo")?.takeIf { !it.isJsonNull }?.asString?.let { photo ->
            encryptedPrefs.edit().putString(KEY_PROFILE_PHOTO, photo).apply()
        }

        // public_profile field selection — feeds the "in profile"
        // toggle on each Data row. Comes back as a JSON array of
        // namespaces from the published settings block.
        r.getAsJsonArray("public_profile_fields")?.let { arr ->
            val list = arr.mapNotNull { it?.takeIf { !it.isJsonNull }?.asString }
            encryptedPrefs.edit()
                .putString(KEY_PUBLIC_PROFILE_FIELDS, gson.toJson(list))
                .apply()
        }

        // Categories — both predefined (server constant) and
        // user-defined custom categories.
        val categoriesResp = ownerSpaceClient.sendAndAwaitResponse(
            "profile.categories.get", JsonObject(), 10_000L
        ) as? VaultResponse.HandlerResult
        if (categoriesResp != null && categoriesResp.success && categoriesResp.result != null) {
            val customs = categoriesResp.result.getAsJsonArray("custom")
                ?.mapNotNull { el ->
                    try {
                        val o = el.asJsonObject
                        CategoryInfo(
                            id = o.get("id")?.asString ?: return@mapNotNull null,
                            name = o.get("name")?.asString ?: "",
                            icon = o.get("icon")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()
            encryptedPrefs.edit()
                .putString(KEY_CUSTOM_CATEGORIES, gson.toJson(customs))
                .apply()
        }

        // Personal-data fields → optional + custom field maps. The
        // existing API splits these by namespace prefix.
        val pdResp = ownerSpaceClient.sendAndAwaitResponse(
            "personal-data.get", JsonObject(), 15_000L
        ) as? VaultResponse.HandlerResult
        if (pdResp != null && pdResp.success && pdResp.result != null) {
            val fields = pdResp.result.getAsJsonObject("fields")
            if (fields != null) {
                hydrateOptionalFromVaultFields(fields)
                hydrateCustomFromVaultFields(fields)
            }
        }

        // Sort-order is its own vault op for now (legacy).
        val sortResp = ownerSpaceClient.sendAndAwaitResponse(
            "personal-data.get-sort-order", JsonObject(), 10_000L
        ) as? VaultResponse.HandlerResult
        if (sortResp != null && sortResp.success && sortResp.result != null) {
            val order = sortResp.result.getAsJsonObject("sort_order")
            if (order != null) {
                val map = mutableMapOf<String, Int>()
                order.entrySet().forEach { (k, v) ->
                    try { map[k] = v.asInt } catch (_: Exception) {}
                }
                if (map.isNotEmpty()) {
                    encryptedPrefs.edit().putString(KEY_FIELD_SORT_ORDER, gson.toJson(map)).apply()
                }
            }
        }
    }

    private fun hydrateOptionalFromVaultFields(fields: JsonObject) {
        // Map known optional namespaces into their KEY_* slots so
        // the existing getOptionalFields() reads light up. Unknown
        // namespaces go to the custom-fields path below.
        val knownToKey = mapOf(
            "personal.legal.prefix" to KEY_PREFIX,
            "personal.legal.first_name" to KEY_OPT_FIRST_NAME,
            "personal.legal.middle_name" to KEY_MIDDLE_NAME,
            "personal.legal.last_name" to KEY_OPT_LAST_NAME,
            "personal.legal.suffix" to KEY_SUFFIX,
            "contact.phone.mobile" to KEY_PHONE,
            "personal.info.birthday" to KEY_BIRTHDAY,
            "address.home.street" to KEY_STREET,
            "address.home.street2" to KEY_STREET2,
            "address.home.city" to KEY_CITY,
            "address.home.state" to KEY_STATE,
            "address.home.postal_code" to KEY_POSTAL_CODE,
            "address.home.country" to KEY_COUNTRY,
            "social.website.personal" to KEY_WEBSITE,
            "social.linkedin.url" to KEY_LINKEDIN,
            "social.twitter.handle" to KEY_TWITTER,
            "social.instagram.handle" to KEY_INSTAGRAM,
            "social.github.username" to KEY_GITHUB,
        )
        val edit = encryptedPrefs.edit()
        knownToKey.forEach { (ns, key) ->
            val obj = fields.get(ns)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val value = obj.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            edit.putString(key, value)
        }
        edit.apply()
    }

    private fun hydrateCustomFromVaultFields(fields: JsonObject) {
        // Anything that isn't a known optional namespace and isn't a
        // system field is treated as a custom field.
        val customs = mutableListOf<CustomField>()
        val now = System.currentTimeMillis()
        fields.entrySet().forEach { (key, valueJson) ->
            if (key.startsWith("_system_")) return@forEach
            val (ns, _) = splitFieldKey(key)
            if (ns in KNOWN_OPTIONAL_NAMESPACES) return@forEach
            try {
                val obj = valueJson?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val value = obj.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                customs += CustomField(
                    id = key,
                    name = namespaceToDisplayName(ns),
                    value = value,
                    category = FieldCategory.OTHER, // best-effort — proper category mapping needs a vault hint
                    fieldType = FieldType.TEXT,
                    createdAt = now,
                    updatedAt = now,
                )
            } catch (_: Exception) { /* skip */ }
        }
        encryptedPrefs.edit().putString(KEY_CUSTOM_FIELDS, gson.toJson(customs)).apply()
    }

    private fun splitFieldKey(key: String): Pair<String, String> {
        val idx = key.indexOf("::")
        return if (idx >= 0) key.substring(0, idx) to key.substring(idx + 2) else key to ""
    }

    /**
     * Drop the in-memory cache (e.g. on logout). The vault retains
     * everything; a subsequent [hydrate] rebuilds the cache.
     */
    fun reset() { encryptedPrefs.edit().clear().apply() }

    private class MemPrefs {
        // Synchronous in-memory map. Chosen over StateFlow here so
        // the existing SharedPreferences-shaped API stays drop-in;
        // ViewModels that need reactivity should consume from the
        // VM's own state flows, populated by hydrate-driven loads.
        private val data = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun getString(key: String, default: String?): String? = (data[key] as? String) ?: default
        fun getBoolean(key: String, default: Boolean): Boolean = (data[key] as? Boolean) ?: default
        fun getLong(key: String, default: Long): Long = (data[key] as? Long) ?: default
        fun getInt(key: String, default: Int): Int = (data[key] as? Int) ?: default
        fun contains(key: String): Boolean = data.containsKey(key)
        fun edit(): Editor = Editor(data)
        class Editor(private val data: java.util.concurrent.ConcurrentHashMap<String, Any>) {
            fun putString(key: String, value: String?): Editor {
                if (value == null) data.remove(key) else data[key] = value
                return this
            }
            fun putBoolean(key: String, value: Boolean): Editor { data[key] = value; return this }
            fun putLong(key: String, value: Long): Editor { data[key] = value; return this }
            fun putInt(key: String, value: Int): Editor { data[key] = value; return this }
            fun remove(key: String): Editor { data.remove(key); return this }
            fun clear(): Editor { data.clear(); return this }
            fun apply() { /* in-memory; no flush needed */ }
            fun commit(): Boolean { return true }
        }
    }

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
        private const val KEY_HIDDEN_FROM_CATALOG_FIELDS = "hidden_from_catalog_fields"
        private const val KEY_PUBLIC_PROFILE_VERSION = "public_profile_version"

        // Profile photo (local cache)
        private const val KEY_PROFILE_PHOTO = "profile_photo_base64"

        // Field sort order
        private const val KEY_FIELD_SORT_ORDER = "field_sort_order"

        // Published profile cache (instant load on cold start)
        private const val KEY_PUBLISHED_PROFILE_CACHE = "published_profile_cache"
        private const val KEY_PUBLISHED_PROFILE_CACHE_TIME = "published_profile_cache_time"

        // Optional-field namespaces — used during hydrate to split
        // vault fields into "known optional" (mapped to their KEY_*
        // slots) vs "custom" (added to the custom-fields list).
        internal val KNOWN_OPTIONAL_NAMESPACES = setOf(
            "personal.legal.prefix",
            "personal.legal.first_name",
            "personal.legal.middle_name",
            "personal.legal.last_name",
            "personal.legal.suffix",
            "contact.phone.mobile",
            "personal.info.birthday",
            "address.home.street",
            "address.home.street2",
            "address.home.city",
            "address.home.state",
            "address.home.postal_code",
            "address.home.country",
            "social.website.personal",
            "social.linkedin.url",
            "social.twitter.handle",
            "social.instagram.handle",
            "social.github.username",
        )

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
     * Save profile photo locally so it survives app and vault restarts.
     */
    fun saveProfilePhoto(base64Photo: String?) {
        encryptedPrefs.edit().apply {
            if (base64Photo != null) {
                putString(KEY_PROFILE_PHOTO, base64Photo)
            } else {
                remove(KEY_PROFILE_PHOTO)
            }
            commit()
        }
    }

    /**
     * Get locally cached profile photo.
     */
    fun getProfilePhoto(): String? {
        return encryptedPrefs.getString(KEY_PROFILE_PHOTO, null)
    }

    /**
     * Get system fields from registration.
     * These fields are read-only and can only be changed by admin.
     *
     * @return SystemPersonalData or null if not yet set
     */
    fun getSystemFields(): SystemPersonalData? {
        val isSet = encryptedPrefs.getBoolean(KEY_SYSTEM_FIELDS_SET, false)
        if (!isSet) {
            return null
        }
        val firstName = encryptedPrefs.getString(KEY_FIRST_NAME, "") ?: ""
        val lastName = encryptedPrefs.getString(KEY_LAST_NAME, "") ?: ""
        val email = encryptedPrefs.getString(KEY_EMAIL, "") ?: ""
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
        encryptedPrefs.edit().apply {
            putString(KEY_FIRST_NAME, data.firstName)
            putString(KEY_LAST_NAME, data.lastName)
            putString(KEY_EMAIL, data.email)
            putBoolean(KEY_SYSTEM_FIELDS_SET, true)
            commit()  // Use commit() instead of apply() to ensure synchronous write
        }
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
    }

    /**
     * Update a single optional field.
     *
     * @param field The field to update
     * @param value The new value (null to clear)
     */
    fun updateOptionalField(field: OptionalField, value: String?) {
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
        encryptedPrefs.edit().apply {
            putStringOrRemove(key, value)
            apply()
        }
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
    }

    private fun saveCustomFields(fields: List<CustomField>) {
        val json = gson.toJson(fields)
        encryptedPrefs.edit().putString(KEY_CUSTOM_FIELDS, json).apply()
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
    }

    /**
     * Get the set of personal-data field ids the user has flipped to
     * "hide from catalog" (Discoverability=Private). Default empty —
     * all items are cataloged.
     */
    fun getHiddenFromCatalogFields(): Set<String> {
        val json = encryptedPrefs.getString(KEY_HIDDEN_FROM_CATALOG_FIELDS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Persist the set of items flagged as hidden from the catalog.
     */
    fun updateHiddenFromCatalogFields(fields: Set<String>) {
        val json = gson.toJson(fields.toList())
        encryptedPrefs.edit()
            .putString(KEY_HIDDEN_FROM_CATALOG_FIELDS, json)
            .apply()
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

    // MARK: - Published Profile Cache

    /**
     * Cache the published profile JSON for instant loading on next app open.
     */
    fun cachePublishedProfile(json: String) {
        encryptedPrefs.edit()
            .putString(KEY_PUBLISHED_PROFILE_CACHE, json)
            .putLong(KEY_PUBLISHED_PROFILE_CACHE_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get the cached published profile JSON, or null if not cached.
     */
    fun getCachedPublishedProfile(): String? {
        return encryptedPrefs.getString(KEY_PUBLISHED_PROFILE_CACHE, null)
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

    private fun MemPrefs.Editor.putStringOrRemove(
        key: String,
        value: String?
    ): MemPrefs.Editor {
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
    IDENTITY,       // Name, SSN, passport, etc.
    CONTACT,        // Phone, email, social media
    ADDRESS,        // Physical addresses
    FINANCIAL,      // Bank accounts, credit cards
    MEDICAL,        // Health info, allergies
    PROFESSIONAL,   // Employment, licenses, certifications
    EDUCATION,      // Degrees, student IDs, transcripts
    VEHICLE,        // Cars, registration, VIN
    LEGAL,          // Power of attorney, beneficiaries
    DIGITAL,        // Social handles, digital accounts
    TRAVEL,         // Loyalty programs, visas, traveler IDs
    MEMBERSHIP,     // Gym, clubs, library cards
    PROPERTY,       // Real estate, mortgage, HOA
    OTHER           // Miscellaneous
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
