package com.vettid.app.features.personaldata

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vettid.app.core.events.ProfilePhotoEvents
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.MinorSecretsStore
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.core.storage.SecretType
import com.vettid.app.worker.PersonalDataSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private const val TAG = "PersonalDataViewModel"

@HiltViewModel
class PersonalDataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: CredentialStore,
    private val personalDataStore: PersonalDataStore,
    private val minorSecretsStore: MinorSecretsStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager,
    private val profilePhotoEvents: ProfilePhotoEvents
) : ViewModel() {

    private val _state = MutableStateFlow<PersonalDataState>(PersonalDataState.Loading)
    val state: StateFlow<PersonalDataState> = _state.asStateFlow()

    private val _editState = MutableStateFlow(EditDataItemState())
    val editState: StateFlow<EditDataItemState> = _editState.asStateFlow()

    private val _effects = MutableSharedFlow<PersonalDataEffect>()
    val effects: SharedFlow<PersonalDataEffect> = _effects.asSharedFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private val _showPublicProfilePreview = MutableStateFlow(false)
    val showPublicProfilePreview: StateFlow<Boolean> = _showPublicProfilePreview.asStateFlow()

    // Published profile data (what others see)
    private val _publishedProfile = MutableStateFlow<PublishedProfileData?>(null)
    val publishedProfile: StateFlow<PublishedProfileData?> = _publishedProfile.asStateFlow()

    private val _isLoadingPublishedProfile = MutableStateFlow(false)
    val isLoadingPublishedProfile: StateFlow<Boolean> = _isLoadingPublishedProfile.asStateFlow()

    // Profile photo (Base64-encoded JPEG)
    private val _profilePhoto = MutableStateFlow<String?>(null)
    val profilePhoto: StateFlow<String?> = _profilePhoto.asStateFlow()

    private val _isUploadingPhoto = MutableStateFlow(false)
    val isUploadingPhoto: StateFlow<Boolean> = _isUploadingPhoto.asStateFlow()

    private val _showPhotoCapture = MutableStateFlow(false)
    val showPhotoCapture: StateFlow<Boolean> = _showPhotoCapture.asStateFlow()

    // Track if there are unpublished changes
    private val _hasUnpublishedChanges = MutableStateFlow(false)
    val hasUnpublishedChanges: StateFlow<Boolean> = _hasUnpublishedChanges.asStateFlow()

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Custom categories (for dropdown in add/edit dialog)
    private val _customCategories = MutableStateFlow<List<com.vettid.app.core.storage.CategoryInfo>>(emptyList())
    val customCategories: StateFlow<List<com.vettid.app.core.storage.CategoryInfo>> = _customCategories.asStateFlow()

    // System fields (first name, last name, email) for profile header
    private val _systemFields = MutableStateFlow<com.vettid.app.core.storage.SystemPersonalData?>(null)
    val systemFields: StateFlow<com.vettid.app.core.storage.SystemPersonalData?> = _systemFields.asStateFlow()

    // Public metadata (names/types only, never values)
    private val _publicSecrets = MutableStateFlow<List<PublicMetadataItem>>(emptyList())
    val publicSecrets: StateFlow<List<PublicMetadataItem>> = _publicSecrets.asStateFlow()

    private val _publicPersonalData = MutableStateFlow<List<PublicMetadataItem>>(emptyList())
    val publicPersonalData: StateFlow<List<PublicMetadataItem>> = _publicPersonalData.asStateFlow()

    // In-memory data store loaded from vault
    private val dataItems = mutableListOf<PersonalDataItem>()

    // Public profile fields set (namespaces that are shared)
    private var publicProfileFields = mutableSetOf<String>()

    init {
        Log.i(TAG, "PersonalDataViewModel created - starting load")
        loadPersonalData()
        loadCustomCategories()
        loadPublicMetadata()
    }

    /**
     * Load custom categories from local storage.
     */
    private fun loadCustomCategories() {
        _customCategories.value = personalDataStore.getCustomCategories()
        Log.d(TAG, "Loaded ${_customCategories.value.size} custom categories")
    }

    fun onEvent(event: PersonalDataEvent) {
        when (event) {
            is PersonalDataEvent.SearchQueryChanged -> updateSearchQuery(event.query)
            is PersonalDataEvent.ItemClicked -> selectItem(event.itemId)
            is PersonalDataEvent.AddItem -> showAddDialog()
            is PersonalDataEvent.DeleteItem -> deleteItem(event.itemId)
            is PersonalDataEvent.TogglePublicProfile -> togglePublicProfile(event.itemId)
            is PersonalDataEvent.MoveItemUp -> moveItemUp(event.itemId)
            is PersonalDataEvent.MoveItemDown -> moveItemDown(event.itemId)
            is PersonalDataEvent.Refresh -> loadPersonalData()
        }
    }

    private fun loadPersonalData() {
        viewModelScope.launch {
            // Use refreshing state if already loaded, otherwise show loading
            val isRefresh = _state.value is PersonalDataState.Loaded || _state.value is PersonalDataState.Empty
            if (isRefresh) {
                _isRefreshing.value = true
            } else {
                _state.value = PersonalDataState.Loading
            }

            try {
                // Load into temp list first to avoid visual flash
                val newItems = mutableListOf<PersonalDataItem>()

                // Try to fetch from vault if connected
                if (connectionManager.isConnected()) {
                    Log.d(TAG, "Loading personal data from vault...")
                    val vaultLoadSuccess = loadFromVaultInto(newItems)
                    if (vaultLoadSuccess) {
                        Log.d(TAG, "Loaded ${newItems.size} items from vault")
                    } else {
                        Log.d(TAG, "Vault has no data, loading from local storage and re-syncing")
                        loadFromLocalStorageInto(newItems)
                        // Re-sync local data to the vault so it has the data again
                        if (newItems.isNotEmpty()) {
                            reSyncLocalDataToVault(newItems)
                        }
                    }
                } else {
                    Log.d(TAG, "Not connected to vault, loading from local storage")
                    loadFromLocalStorageInto(newItems)
                }

                // Only update dataItems once we have new data
                dataItems.clear()
                dataItems.addAll(newItems)

                if (dataItems.isEmpty()) {
                    _state.value = PersonalDataState.Empty
                } else {
                    _state.value = PersonalDataState.Loaded(items = dataItems.toList())
                }
                Log.d(TAG, "Total items loaded: ${dataItems.size}")

                // Update system fields from local storage for profile header
                _systemFields.value = personalDataStore.getSystemFields()

                // Also fetch the published profile status (non-blocking)
                if (connectionManager.isConnected()) {
                    fetchPublishedProfileStatus()
                    fetchProfilePhoto()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal data", e)
                _state.value = PersonalDataState.Error(e.message ?: "Failed to load data")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Load personal data directly from vault into the provided list.
     * Returns true if successful AND data was loaded, false otherwise.
     *
     * Uses profile.get which returns both system fields and personal data fields.
     * The enclave's profile.get reads from profile/_index which contains all fields.
     */
    private suspend fun loadFromVaultInto(items: MutableList<PersonalDataItem>): Boolean {
        return try {
            Log.i(TAG, "Requesting profile from vault...")
            val profileResponse = ownerSpaceClient.sendAndAwaitResponse("profile.get", JsonObject(), 10000L)

            when (profileResponse) {
                is VaultResponse.HandlerResult -> {
                    Log.i(TAG, "Profile response received, success=${profileResponse.success}")
                    Log.i(TAG, "Profile response keys: ${profileResponse.result?.keySet()}")
                    val fieldsObj = profileResponse.result?.getAsJsonObject("fields")
                    Log.i(TAG, "Profile fields count: ${fieldsObj?.size() ?: 0}, keys: ${fieldsObj?.keySet()}")
                    Log.d(TAG, "Full response: ${profileResponse.result}")

                    if (profileResponse.success && profileResponse.result != null) {
                        val itemCountBefore = items.size
                        parseProfileResponseInto(profileResponse.result, items)
                        val itemCountAfter = items.size
                        Log.i(TAG, "Parsed profile response: $itemCountBefore -> $itemCountAfter items")

                        // Return true only if we actually loaded some data
                        if (itemCountAfter > 0) {
                            true
                        } else {
                            Log.w(TAG, "Vault returned success but no data was parsed!")
                            false
                        }
                    } else {
                        Log.w(TAG, "Profile request failed: ${profileResponse.error}")
                        false
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "Profile request error: ${profileResponse.code} - ${profileResponse.message}")
                    false
                }
                null -> {
                    Log.w(TAG, "Profile request timed out")
                    false
                }
                else -> {
                    Log.w(TAG, "Unexpected response type: ${profileResponse::class.simpleName}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from vault", e)
            false
        }
    }

    /**
     * Parse all data from profile.get response into the provided list.
     * System fields are at top level, personal data fields are in the "fields" object.
     */
    private fun parseProfileResponseInto(result: JsonObject, items: MutableList<PersonalDataItem>) {
        val now = Instant.now()
        Log.d(TAG, "Parsing profile response: ${result.keySet()}")

        // Load public profile fields set
        loadPublicProfileSettings()

        // Load saved sort order from local storage
        val savedSortOrder = personalDataStore.getFieldSortOrder()

        // Extract system fields (registration info) - at top level
        val systemFirstName = result.get("first_name")?.takeIf { !it.isJsonNull }?.asString
        val systemLastName = result.get("last_name")?.takeIf { !it.isJsonNull }?.asString
        val systemEmail = result.get("email")?.takeIf { !it.isJsonNull }?.asString

        Log.d(TAG, "System fields: firstName=$systemFirstName, lastName=$systemLastName, email=$systemEmail")
        Log.i(TAG, "Saved sort orders: $savedSortOrder")
        Log.i(TAG, "System field sort orders: first_name=${savedSortOrder["_system_first_name"]}, last_name=${savedSortOrder["_system_last_name"]}, email=${savedSortOrder["_system_email"]}")

        // Add system fields as items (read-only, always in public profile)
        // Apply saved sort order so they can be reordered
        if (!systemFirstName.isNullOrEmpty()) {
            items.add(PersonalDataItem(
                id = "_system_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = systemFirstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_first_name"] ?: 0,
                createdAt = now,
                updatedAt = now
            ))
        }
        if (!systemLastName.isNullOrEmpty()) {
            items.add(PersonalDataItem(
                id = "_system_last_name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = systemLastName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_last_name"] ?: 1,
                createdAt = now,
                updatedAt = now
            ))
        }
        if (!systemEmail.isNullOrEmpty()) {
            items.add(PersonalDataItem(
                id = "_system_email",
                name = "Email",
                type = DataType.PUBLIC,
                value = systemEmail,
                category = DataCategory.CONTACT,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_email"] ?: 0,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Store system fields locally for offline access
        if (!systemFirstName.isNullOrEmpty() && !systemLastName.isNullOrEmpty() && !systemEmail.isNullOrEmpty()) {
            personalDataStore.storeSystemFields(
                com.vettid.app.core.storage.SystemPersonalData(
                    firstName = systemFirstName,
                    lastName = systemLastName,
                    email = systemEmail
                )
            )
        }

        // Extract personal data fields from the "fields" object
        val fieldsObject = result.getAsJsonObject("fields")
        if (fieldsObject != null) {
            Log.d(TAG, "Processing ${fieldsObject.size()} fields from profile")

            fieldsObject.entrySet().forEach { (namespace, value) ->
                try {
                    // Skip system fields (already handled above)
                    if (namespace.startsWith("_system_")) {
                        return@forEach
                    }

                    Log.d(TAG, "Processing field: $namespace")

                    // Extract value - could be object with "value" key or primitive
                    val fieldValue = if (value.isJsonObject) {
                        value.asJsonObject.get("value")?.takeIf { !it.isJsonNull }?.asString
                    } else if (value.isJsonPrimitive) {
                        value.asString
                    } else null

                    if (fieldValue.isNullOrEmpty()) {
                        Log.d(TAG, "Skipping empty field: $namespace")
                        return@forEach
                    }

                    // Extract updated_at if available
                    val updatedAt = if (value.isJsonObject) {
                        value.asJsonObject.get("updated_at")?.takeIf { !it.isJsonNull }?.asString?.let {
                            try { Instant.parse(it) } catch (e: Exception) { now }
                        } ?: now
                    } else now

                    // Derive category and display name from namespace
                    val category = categoryFromNamespace(namespace)
                    val displayName = displayNameFromNamespace(namespace)
                    val dataType = dataTypeFromNamespace(namespace)
                    val isInPublicProfile = publicProfileFields.contains(namespace)

                    items.add(PersonalDataItem(
                        id = namespace,
                        name = displayName,
                        type = dataType,
                        value = fieldValue,
                        category = category,
                        isSystemField = false,
                        isInPublicProfile = isInPublicProfile,
                        sortOrder = savedSortOrder[namespace] ?: 0,
                        createdAt = updatedAt,
                        updatedAt = updatedAt
                    ))

                    Log.d(TAG, "Added field: $namespace = $displayName ($category, sortOrder=${savedSortOrder[namespace] ?: 0})")
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing field $namespace: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "No fields object in profile response")
        }

        Log.i(TAG, "Total items after parsing profile: ${items.size}")

        // Initialize sort orders if all items have default (0) sort order
        initializeSortOrdersIfNeeded(items)
    }

    /**
     * Initialize sort orders for items that don't have saved orders.
     * Assigns sequential sort orders within each category.
     * Also fixes any duplicate sort orders within the same category.
     */
    private fun initializeSortOrdersIfNeeded(items: MutableList<PersonalDataItem>) {
        // Check each category for duplicate sort orders and fix them
        val categoriesWithDuplicates = mutableSetOf<DataCategory?>()
        items.groupBy { it.category }.forEach { (category, categoryItems) ->
            val sortOrders = categoryItems.map { it.sortOrder }
            if (sortOrders.size != sortOrders.toSet().size) {
                // Found duplicates in this category
                categoriesWithDuplicates.add(category)
            }
        }

        if (categoriesWithDuplicates.isEmpty()) {
            Log.d(TAG, "All categories have unique sort orders, no initialization needed")
            return
        }

        Log.i(TAG, "Fixing sort orders for categories with duplicates: $categoriesWithDuplicates")

        // Fix sort orders for categories with duplicates
        categoriesWithDuplicates.forEach { category ->
            val categoryItems = items.filter { it.category == category }
                .sortedBy { it.sortOrder }
            categoryItems.forEachIndexed { index, item ->
                val itemIndex = items.indexOfFirst { it.id == item.id }
                if (itemIndex >= 0) {
                    items[itemIndex] = item.copy(sortOrder = index)
                    Log.d(TAG, "Fixed sortOrder=$index for ${item.name} (was ${item.sortOrder})")
                }
            }
        }

        // Save the fixed sort orders
        val sortOrderMap = items.associate { it.id to it.sortOrder }
        viewModelScope.launch {
            personalDataStore.updateFieldSortOrder(sortOrderMap)
            Log.i(TAG, "Saved fixed sort orders for ${sortOrderMap.size} items")
        }
    }

    /**
     * Load public profile settings from vault.
     */
    private fun loadPublicProfileSettings() {
        publicProfileFields.clear()
        publicProfileFields.addAll(personalDataStore.getPublicProfileFields())

        // Check if there are unpublished changes based on local settings
        // This is a heuristic - if there are public profile fields selected but
        // we don't know if they were published, we assume no changes until user makes one
        _hasUnpublishedChanges.value = false
    }

    /**
     * Derive category from namespace prefix.
     */
    private fun categoryFromNamespace(namespace: String): DataCategory {
        return when {
            namespace.startsWith("personal.legal") -> DataCategory.IDENTITY
            namespace.startsWith("personal.info") -> DataCategory.IDENTITY
            namespace.startsWith("identity.") -> DataCategory.IDENTITY
            namespace.startsWith("contact.") -> DataCategory.CONTACT
            namespace.startsWith("social.") -> DataCategory.CONTACT
            namespace.startsWith("address.") -> DataCategory.ADDRESS
            namespace.startsWith("financial.") -> DataCategory.FINANCIAL
            namespace.startsWith("medical.") -> DataCategory.MEDICAL
            // Note: crypto.* moved to Secrets screen, map to OTHER for backward compatibility
            namespace.startsWith("crypto.") -> DataCategory.OTHER
            else -> DataCategory.OTHER
        }
    }

    /**
     * Derive display name from namespace.
     * Converts "personal.legal.first_name" -> "Legal First Name"
     */
    private fun displayNameFromNamespace(namespace: String): String {
        // Common namespace to display name mappings
        val knownMappings = mapOf(
            "personal.legal.prefix" to "Name Prefix",
            "personal.legal.first_name" to "Legal First Name",
            "personal.legal.middle_name" to "Legal Middle Name",
            "personal.legal.last_name" to "Legal Last Name",
            "personal.legal.suffix" to "Name Suffix",
            "personal.info.birthday" to "Birthday",
            "contact.phone.mobile" to "Mobile Phone",
            "contact.phone.work" to "Work Phone",
            "contact.phone.home" to "Home Phone",
            "contact.email.personal" to "Personal Email",
            "contact.email.work" to "Work Email",
            "address.home.street" to "Street Address",
            "address.home.street2" to "Address Line 2",
            "address.home.city" to "City",
            "address.home.state" to "State",
            "address.home.postal_code" to "Postal Code",
            "address.home.country" to "Country",
            "address.work.company" to "Company",
            "address.work.street" to "Work Street",
            "address.work.street2" to "Work Address Line 2",
            "address.work.city" to "Work City",
            "address.work.state" to "Work State",
            "address.work.postal_code" to "Work Postal Code",
            "address.work.country" to "Work Country",
            "social.website.personal" to "Website",
            "social.linkedin.url" to "LinkedIn",
            "social.twitter.handle" to "X (Twitter)",
            "social.instagram.handle" to "Instagram",
            "social.github.username" to "GitHub",
            "contact.family.name" to "Family Member",
            "contact.family.relationship" to "Relationship",
            "contact.family.phone" to "Family Phone",
            "contact.family.email" to "Family Email",
            "medical.emergency.name" to "Emergency Contact",
            "medical.emergency.relationship" to "Emergency Relationship",
            "medical.emergency.phone" to "Emergency Phone",
            "identity.gov_id.type" to "ID Type",
            "identity.gov_id.number" to "ID Number",
            "identity.gov_id.issuing_authority" to "Issuing Authority",
            "identity.gov_id.expiry" to "ID Expiry Date",
            "financial.bank.name" to "Bank Name",
            "financial.bank.account_number" to "Account Number",
            "financial.bank.routing_number" to "Routing Number"
        )

        // Return known mapping if exists
        knownMappings[namespace]?.let { return it }

        // Otherwise, derive from namespace parts
        // e.g., "custom.identity.my_field" -> "My Field"
        val parts = namespace.split(".")
        val lastPart = parts.lastOrNull() ?: namespace
        return lastPart
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Derive data type from namespace.
     */
    private fun dataTypeFromNamespace(namespace: String): DataType {
        return when {
            // Sensitive financial data
            namespace.contains("account_number") -> DataType.MINOR_SECRET
            namespace.contains("routing_number") -> DataType.MINOR_SECRET
            namespace.contains("ssn") -> DataType.MINOR_SECRET
            namespace.contains("password") -> DataType.MINOR_SECRET
            namespace.contains("pin") -> DataType.MINOR_SECRET
            namespace.contains("secret") -> DataType.MINOR_SECRET
            // Medical is private
            namespace.startsWith("medical.") -> DataType.PRIVATE
            // Financial is private
            namespace.startsWith("financial.") -> DataType.PRIVATE
            // Social links are public
            namespace.startsWith("social.") -> DataType.PUBLIC
            // Contact info is typically private
            namespace.startsWith("contact.") -> DataType.PRIVATE
            // Address is private
            namespace.startsWith("address.") -> DataType.PRIVATE
            // Legal name is private
            namespace.startsWith("personal.legal") -> DataType.PRIVATE
            else -> DataType.PRIVATE
        }
    }

    /**
     * Load from local storage as fallback when vault is unavailable.
     */
    private fun loadFromLocalStorageInto(items: MutableList<PersonalDataItem>) {
        val now = Instant.now()

        // Load saved sort order
        val savedSortOrder = personalDataStore.getFieldSortOrder()

        // Load system fields with saved sort order
        val systemFields = personalDataStore.getSystemFields()
        systemFields?.let { system ->
            items.add(PersonalDataItem(
                id = "_system_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = system.firstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_first_name"] ?: 0,
                createdAt = now,
                updatedAt = now
            ))
            items.add(PersonalDataItem(
                id = "_system_last_name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = system.lastName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_last_name"] ?: 1,
                createdAt = now,
                updatedAt = now
            ))
            items.add(PersonalDataItem(
                id = "_system_email",
                name = "Email",
                type = DataType.PUBLIC,
                value = system.email,
                category = DataCategory.CONTACT,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_email"] ?: 0,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Load optional fields from local storage
        val optional = personalDataStore.getOptionalFields()
        val publicFields = personalDataStore.getPublicProfileFields().toSet()

        fun addIfNotEmpty(namespace: String, displayName: String, value: String?, category: DataCategory, type: DataType = DataType.PRIVATE) {
            if (!value.isNullOrEmpty()) {
                items.add(PersonalDataItem(
                    id = namespace,
                    name = displayName,
                    type = type,
                    value = value,
                    category = category,
                    isSystemField = false,
                    isInPublicProfile = publicFields.contains(namespace),
                    sortOrder = savedSortOrder[namespace] ?: 0,
                    createdAt = now,
                    updatedAt = now
                ))
            }
        }

        // Legal name fields
        addIfNotEmpty("personal.legal.prefix", "Name Prefix", optional.prefix, DataCategory.IDENTITY)
        addIfNotEmpty("personal.legal.first_name", "Legal First Name", optional.firstName, DataCategory.IDENTITY)
        addIfNotEmpty("personal.legal.middle_name", "Legal Middle Name", optional.middleName, DataCategory.IDENTITY)
        addIfNotEmpty("personal.legal.last_name", "Legal Last Name", optional.lastName, DataCategory.IDENTITY)
        addIfNotEmpty("personal.legal.suffix", "Name Suffix", optional.suffix, DataCategory.IDENTITY)

        // Contact fields
        addIfNotEmpty("contact.phone.mobile", "Mobile Phone", optional.phone, DataCategory.CONTACT)
        addIfNotEmpty("personal.info.birthday", "Birthday", optional.birthday, DataCategory.IDENTITY)

        // Address fields
        addIfNotEmpty("address.home.street", "Street Address", optional.street, DataCategory.ADDRESS)
        addIfNotEmpty("address.home.street2", "Address Line 2", optional.street2, DataCategory.ADDRESS)
        addIfNotEmpty("address.home.city", "City", optional.city, DataCategory.ADDRESS)
        addIfNotEmpty("address.home.state", "State", optional.state, DataCategory.ADDRESS)
        addIfNotEmpty("address.home.postal_code", "Postal Code", optional.postalCode, DataCategory.ADDRESS)
        addIfNotEmpty("address.home.country", "Country", optional.country, DataCategory.ADDRESS)

        // Social fields
        addIfNotEmpty("social.website.personal", "Website", optional.website, DataCategory.CONTACT, DataType.PUBLIC)
        addIfNotEmpty("social.linkedin.url", "LinkedIn", optional.linkedin, DataCategory.CONTACT, DataType.PUBLIC)
        addIfNotEmpty("social.twitter.handle", "X (Twitter)", optional.twitter, DataCategory.CONTACT, DataType.PUBLIC)
        addIfNotEmpty("social.instagram.handle", "Instagram", optional.instagram, DataCategory.CONTACT, DataType.PUBLIC)
        addIfNotEmpty("social.github.username", "GitHub", optional.github, DataCategory.CONTACT, DataType.PUBLIC)

        // Load custom fields
        val customFields = personalDataStore.getCustomFields()
        customFields.forEach { customField ->
            val namespace = personalDataStore.generateNamespace(
                customField.category.name.lowercase(),
                customField.name
            )
            val category = when (customField.category) {
                com.vettid.app.core.storage.FieldCategory.IDENTITY -> DataCategory.IDENTITY
                com.vettid.app.core.storage.FieldCategory.CONTACT -> DataCategory.CONTACT
                com.vettid.app.core.storage.FieldCategory.ADDRESS -> DataCategory.ADDRESS
                com.vettid.app.core.storage.FieldCategory.FINANCIAL -> DataCategory.FINANCIAL
                com.vettid.app.core.storage.FieldCategory.MEDICAL -> DataCategory.MEDICAL
                com.vettid.app.core.storage.FieldCategory.OTHER -> DataCategory.OTHER
            }
            val dataType = when (customField.fieldType) {
                com.vettid.app.core.storage.FieldType.PASSWORD -> DataType.MINOR_SECRET
                else -> DataType.PRIVATE
            }
            items.add(PersonalDataItem(
                id = namespace,
                name = customField.name,
                type = dataType,
                value = customField.value,
                category = category,
                isSystemField = false,
                isInPublicProfile = publicFields.contains(namespace),
                sortOrder = savedSortOrder[namespace] ?: 0,
                createdAt = com.vettid.app.util.toInstant(customField.createdAt),
                updatedAt = com.vettid.app.util.toInstant(customField.updatedAt)
            ))
        }

        Log.d(TAG, "Loaded ${items.size} items from local storage with sort order applied")

        // Initialize sort orders if all items have default (0) sort order
        initializeSortOrdersIfNeeded(items)
    }

    /**
     * Re-sync all local personal data to the vault.
     * Called when the vault has empty storage (e.g., after cold restart
     * before the database persistence fix took effect).
     */
    private fun reSyncLocalDataToVault(items: List<PersonalDataItem>) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Re-syncing ${items.size} local items to vault...")

                // 1. Re-sync system fields via profile.update
                val systemFields = JsonObject()
                items.filter { it.isSystemField }.forEach { item ->
                    systemFields.addProperty(item.id, item.value)
                }
                if (systemFields.size() > 0) {
                    val profilePayload = JsonObject().apply {
                        add("fields", systemFields)
                    }
                    val profileResp = ownerSpaceClient.sendAndAwaitResponse(
                        "profile.update", profilePayload, 10000L
                    )
                    Log.i(TAG, "Re-synced ${systemFields.size()} system fields to vault: ${
                        when (profileResp) {
                            is VaultResponse.HandlerResult -> if (profileResp.success) "success" else "failed"
                            else -> "unknown"
                        }
                    }")
                }

                // 2. Re-sync personal data fields via personal-data.update
                val dataFields = JsonObject()
                items.filter { !it.isSystemField && it.value.isNotEmpty() }.forEach { item ->
                    dataFields.addProperty(item.id, item.value)
                }
                if (dataFields.size() > 0) {
                    val dataPayload = JsonObject().apply {
                        add("fields", dataFields)
                    }
                    val dataResp = ownerSpaceClient.sendAndAwaitResponse(
                        "personal-data.update", dataPayload, 10000L
                    )
                    Log.i(TAG, "Re-synced ${dataFields.size()} personal data fields to vault: ${
                        when (dataResp) {
                            is VaultResponse.HandlerResult -> if (dataResp.success) "success" else "failed"
                            else -> "unknown"
                        }
                    }")
                }

                Log.i(TAG, "Vault re-sync complete")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-sync local data to vault: ${e.message}")
            }
        }
    }

    private fun updateSearchQuery(query: String) {
        val currentState = _state.value
        if (currentState is PersonalDataState.Loaded) {
            val filtered = if (query.isBlank()) {
                dataItems.toList()
            } else {
                dataItems.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.value.contains(query, ignoreCase = true)
                }
            }
            _state.value = currentState.copy(items = filtered, searchQuery = query)
        }
    }

    private fun selectItem(itemId: String) {
        val item = dataItems.find { it.id == itemId }
        if (item != null) {
            _editState.value = EditDataItemState(
                id = item.id,
                name = item.name,
                value = item.value,
                type = item.type,
                category = item.category,
                originalCategory = item.category,  // Track original for detecting changes
                isInPublicProfile = item.isInPublicProfile,
                isEditing = true
            )
            _showAddDialog.value = true
        }
    }

    private fun showAddDialog() {
        _editState.value = EditDataItemState()
        _showAddDialog.value = true
    }

    fun dismissDialog() {
        _showAddDialog.value = false
        _editState.value = EditDataItemState()
    }

    /**
     * Publish public profile to NATS.
     * Uses sendAndAwaitResponse to ensure publish completes before returning.
     */
    fun publishProfile() {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Publishing public profile...")

                // Build payload with selected public profile fields
                val payload = JsonObject()
                val fieldsArray = JsonArray()
                publicProfileFields.forEach { fieldsArray.add(it) }

                // Also include public key secrets that are marked for public profile
                val publicKeySecrets = minorSecretsStore.getPublicProfileSecrets()
                publicKeySecrets.forEach { secret ->
                    val secretNamespace = "secrets.public_key.${secret.id}"
                    if (!publicProfileFields.contains(secretNamespace)) {
                        fieldsArray.add(secretNamespace)
                    }
                }

                payload.add("fields", fieldsArray)
                Log.d(TAG, "Publishing profile with ${fieldsArray.size()} fields: $fieldsArray")

                val response = ownerSpaceClient.sendAndAwaitResponse(
                    messageType = "profile.publish",
                    payload = payload,
                    timeoutMs = 15000L
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            Log.i(TAG, "Profile published successfully")
                            _hasUnpublishedChanges.value = false
                            _effects.emit(PersonalDataEffect.ShowSuccess("Public profile published"))
                        } else {
                            val errorMsg = response.error ?: "Unknown error"
                            Log.e(TAG, "Profile publish failed: $errorMsg")
                            _effects.emit(PersonalDataEffect.ShowError("Failed to publish: $errorMsg"))
                        }
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Profile publish error: ${response.message}")
                        _effects.emit(PersonalDataEffect.ShowError("Failed to publish: ${response.message}"))
                    }
                    null -> {
                        Log.e(TAG, "Profile publish timed out")
                        _effects.emit(PersonalDataEffect.ShowError("Publish timed out - please try again"))
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type: ${response::class.simpleName}")
                        // Assume success if we got an unexpected but not error response
                        _hasUnpublishedChanges.value = false
                        _effects.emit(PersonalDataEffect.ShowSuccess("Public profile published"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing profile", e)
                _effects.emit(PersonalDataEffect.ShowError(e.message ?: "Failed to publish"))
            }
        }
    }

    fun updateEditName(name: String) {
        _editState.value = _editState.value.copy(name = name, nameError = null)
    }

    fun updateEditValue(value: String) {
        _editState.value = _editState.value.copy(value = value, valueError = null)
    }

    fun updateEditType(type: DataType) {
        _editState.value = _editState.value.copy(type = type)
    }

    fun updateEditFieldType(fieldType: FieldType) {
        _editState.value = _editState.value.copy(fieldType = fieldType)
    }

    fun updateEditCategory(category: DataCategory?) {
        _editState.value = _editState.value.copy(category = category)
    }

    fun updateEditPublicProfile(isInPublic: Boolean) {
        _editState.value = _editState.value.copy(isInPublicProfile = isInPublic)
    }

    fun saveItem() {
        val current = _editState.value

        // Validate
        if (current.name.isBlank()) {
            _editState.value = current.copy(nameError = "Name is required")
            return
        }
        if (current.value.isBlank()) {
            _editState.value = current.copy(valueError = "Value is required")
            return
        }

        viewModelScope.launch {
            _editState.value = current.copy(isSaving = true)

            try {
                val newCategory = current.category ?: DataCategory.OTHER
                val oldNamespace = current.id

                // Check if category changed (for existing fields)
                val categoryChanged = current.isEditing &&
                    current.originalCategory != null &&
                    current.originalCategory != newCategory

                // Generate the new namespace based on category
                val newNamespace = if (categoryChanged || oldNamespace == null) {
                    // Category changed or new field - generate new namespace
                    generateNamespace(newCategory, current.name)
                } else {
                    // No category change - use existing namespace
                    oldNamespace
                }

                Log.d(TAG, "Save: categoryChanged=$categoryChanged, oldNamespace=$oldNamespace, newNamespace=$newNamespace")

                // If category changed, delete the old field first
                if (categoryChanged && oldNamespace != null) {
                    Log.i(TAG, "Category changed from ${current.originalCategory} to $newCategory, deleting old field: $oldNamespace")

                    val deletePayload = JsonObject().apply {
                        val namespacesArray = com.google.gson.JsonArray()
                        namespacesArray.add(oldNamespace)
                        add("namespaces", namespacesArray)  // Enclave expects "namespaces" not "fields"
                    }

                    val deleteResponse = ownerSpaceClient.sendAndAwaitResponse("personal-data.delete", deletePayload, 10000L)
                    when (deleteResponse) {
                        is VaultResponse.HandlerResult -> {
                            if (deleteResponse.success) {
                                Log.i(TAG, "Old field deleted: $oldNamespace")
                            } else {
                                Log.w(TAG, "Failed to delete old field: ${deleteResponse.error}")
                            }
                        }
                        is VaultResponse.Error -> {
                            Log.e(TAG, "Error deleting old field: ${deleteResponse.code} - ${deleteResponse.message}")
                        }
                        else -> {
                            Log.w(TAG, "Unexpected delete response: ${deleteResponse?.javaClass?.simpleName}")
                        }
                    }

                    // Also delete from local storage
                    deleteFieldLocally(oldNamespace)
                }

                // Save the field with the new namespace
                val payload = JsonObject().apply {
                    val fieldsObj = JsonObject()
                    fieldsObj.addProperty(newNamespace, current.value)
                    add("fields", fieldsObj)
                }

                Log.d(TAG, "Saving field to vault via personal-data.update: $payload")
                val response = ownerSpaceClient.sendAndAwaitResponse("personal-data.update", payload, 10000L)

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            Log.i(TAG, "Field saved confirmed by vault: $newNamespace")
                        } else {
                            Log.w(TAG, "Vault returned error: ${response.error}")
                        }
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Vault error saving field: ${response.code} - ${response.message}")
                        // Schedule background sync to retry later
                        personalDataStore.markPendingSync()
                        PersonalDataSyncWorker.scheduleImmediate(context)
                        Log.i(TAG, "Scheduled background sync for failed save")
                    }
                    null -> {
                        Log.w(TAG, "Timeout waiting for vault update confirmation")
                        // Schedule background sync to retry later
                        personalDataStore.markPendingSync()
                        PersonalDataSyncWorker.scheduleImmediate(context)
                        Log.i(TAG, "Scheduled background sync for timed-out save")
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type: ${response::class.simpleName}")
                    }
                }

                // Also save locally for offline access (with new namespace if changed)
                // If category changed, treat as new field since old one was deleted
                val localState = if (categoryChanged) {
                    current.copy(id = newNamespace, isEditing = false)
                } else {
                    current.copy(id = newNamespace)
                }
                saveFieldLocally(newNamespace, localState)

                // Mark as having unpublished changes if field is in public profile
                if (current.isInPublicProfile) {
                    _hasUnpublishedChanges.value = true
                }

                // Update local state optimistically instead of reloading from vault
                // This avoids race conditions with other vault requests
                val now = Instant.now()
                val updatedItem = PersonalDataItem(
                    id = newNamespace,
                    name = current.name,
                    type = current.type,
                    value = current.value,
                    category = newCategory,
                    fieldType = current.fieldType,
                    isSystemField = false,
                    isInPublicProfile = current.isInPublicProfile,
                    createdAt = now,
                    updatedAt = now
                )

                if (categoryChanged && oldNamespace != null) {
                    // Remove old item, add new one
                    dataItems.removeAll { it.id == oldNamespace }
                    dataItems.add(updatedItem)
                } else if (current.isEditing) {
                    // Update existing item
                    val index = dataItems.indexOfFirst { it.id == newNamespace }
                    if (index >= 0) {
                        dataItems[index] = updatedItem
                    } else {
                        dataItems.add(updatedItem)
                    }
                } else {
                    // Add new item
                    dataItems.add(updatedItem)
                }

                // Update UI state
                _state.value = PersonalDataState.Loaded(items = dataItems.toList())

                _showAddDialog.value = false
                _editState.value = EditDataItemState()
                _effects.emit(PersonalDataEffect.ShowSuccess(
                    if (current.isEditing) "Data updated" else "Data added"
                ))

                Log.i(TAG, "Save completed, local state updated optimistically")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save data", e)
                _effects.emit(PersonalDataEffect.ShowError(e.message ?: "Failed to save"))
                _editState.value = current.copy(isSaving = false)
            }
        }
    }

    /**
     * Save all non-empty fields from a multi-field template at once.
     */
    fun saveTemplate(formState: PersonalDataTemplateFormState, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val template = formState.template

                // Collect non-empty field values
                val fieldsToSave = template.fields.mapIndexedNotNull { index, field ->
                    val value = formState.getValue(index)
                    if (value.isNotBlank()) field to value else null
                }

                if (fieldsToSave.isEmpty()) {
                    _effects.emit(PersonalDataEffect.ShowError("No fields to save"))
                    return@launch
                }

                Log.i(TAG, "Saving template '${template.name}' with ${fieldsToSave.size} fields")

                // Build fields JSON payload
                val fieldsObj = JsonObject()
                fieldsToSave.forEach { (field, value) ->
                    fieldsObj.addProperty(field.namespace, value)
                }

                // Send to vault
                val payload = JsonObject().apply {
                    add("fields", fieldsObj)
                }

                Log.d(TAG, "Saving template fields to vault: $payload")
                val response = ownerSpaceClient.sendAndAwaitResponse("personal-data.update", payload, 10000L)

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            Log.i(TAG, "Template fields saved to vault")
                        } else {
                            Log.w(TAG, "Vault returned error saving template: ${response.error}")
                        }
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Vault error saving template: ${response.code} - ${response.message}")
                        _effects.emit(PersonalDataEffect.ShowError("Failed to save: ${response.message}"))
                        return@launch
                    }
                    null -> {
                        Log.w(TAG, "Timeout waiting for vault template save confirmation")
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response: ${response::class.simpleName}")
                    }
                }

                // Save each field locally and update in-memory list
                val now = Instant.now()
                fieldsToSave.forEach { (field, value) ->
                    // Save locally for offline access
                    val editState = EditDataItemState(
                        id = field.namespace,
                        name = field.name,
                        value = value,
                        category = field.category,
                        isEditing = false
                    )
                    saveFieldLocally(field.namespace, editState)

                    // Update in-memory data
                    val existingIndex = dataItems.indexOfFirst { it.id == field.namespace }
                    val item = PersonalDataItem(
                        id = field.namespace,
                        name = displayNameFromNamespace(field.namespace),
                        type = dataTypeFromNamespace(field.namespace),
                        value = value,
                        category = field.category,
                        isSystemField = false,
                        isInPublicProfile = false,
                        createdAt = now,
                        updatedAt = now
                    )
                    if (existingIndex >= 0) {
                        dataItems[existingIndex] = item
                    } else {
                        dataItems.add(item)
                    }
                }

                // Update UI state
                if (dataItems.isEmpty()) {
                    _state.value = PersonalDataState.Empty
                } else {
                    _state.value = PersonalDataState.Loaded(items = dataItems.toList())
                }

                _effects.emit(PersonalDataEffect.ShowSuccess("${fieldsToSave.size} fields saved"))
                onComplete()

                Log.i(TAG, "Template save completed: ${fieldsToSave.size} fields")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save template", e)
                _effects.emit(PersonalDataEffect.ShowError(e.message ?: "Failed to save template"))
            }
        }
    }

    /**
     * Generate namespace from category and name.
     */
    private fun generateNamespace(category: DataCategory, name: String): String {
        val prefix = when (category) {
            DataCategory.IDENTITY -> "personal.custom"
            DataCategory.CONTACT -> "contact.custom"
            DataCategory.ADDRESS -> "address.custom"
            DataCategory.FINANCIAL -> "financial.custom"
            DataCategory.MEDICAL -> "medical.custom"
            DataCategory.OTHER -> "other.custom"
        }
        val sanitizedName = name.lowercase().replace(" ", "_").replace(Regex("[^a-z0-9_]"), "")
        return "$prefix.$sanitizedName"
    }

    /**
     * Save field locally for offline access.
     */
    private fun saveFieldLocally(namespace: String, state: EditDataItemState) {
        // Map to local storage if it's a known optional field
        val updated = personalDataStore.updateOptionalFieldByNamespace(namespace, state.value)
        if (!updated) {
            // It's a custom field, save as such
            val fieldCategory = when (state.category) {
                DataCategory.IDENTITY -> com.vettid.app.core.storage.FieldCategory.IDENTITY
                DataCategory.CONTACT -> com.vettid.app.core.storage.FieldCategory.CONTACT
                DataCategory.ADDRESS -> com.vettid.app.core.storage.FieldCategory.ADDRESS
                DataCategory.FINANCIAL -> com.vettid.app.core.storage.FieldCategory.FINANCIAL
                DataCategory.MEDICAL -> com.vettid.app.core.storage.FieldCategory.MEDICAL
                else -> com.vettid.app.core.storage.FieldCategory.OTHER
            }
            val fieldType = when (state.type) {
                DataType.MINOR_SECRET -> com.vettid.app.core.storage.FieldType.PASSWORD
                else -> com.vettid.app.core.storage.FieldType.TEXT
            }

            if (state.isEditing && state.id != null) {
                // Update existing custom field
                val existingFields = personalDataStore.getCustomFields()
                val existing = existingFields.find {
                    personalDataStore.generateNamespace(it.category.name.lowercase(), it.name) == state.id
                }
                if (existing != null) {
                    personalDataStore.updateCustomField(existing.copy(
                        name = state.name,
                        value = state.value,
                        category = fieldCategory,
                        fieldType = fieldType
                    ))
                }
            } else {
                // Add new custom field
                personalDataStore.addCustomField(
                    name = state.name,
                    value = state.value,
                    category = fieldCategory,
                    fieldType = fieldType
                )
            }
        }
    }

    private fun deleteItem(itemId: String) {
        viewModelScope.launch {
            try {
                val item = dataItems.find { it.id == itemId }
                if (item?.isSystemField == true) {
                    _effects.emit(PersonalDataEffect.ShowError("Cannot delete system fields"))
                    return@launch
                }

                // Delete from vault
                val payload = JsonObject().apply {
                    val namespacesArray = com.google.gson.JsonArray()
                    namespacesArray.add(itemId)
                    add("namespaces", namespacesArray)  // Enclave expects "namespaces" not "fields"
                }

                Log.d(TAG, "Deleting field from vault: $itemId")
                val response = ownerSpaceClient.sendAndAwaitResponse("personal-data.delete", payload, 10000L)

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            Log.i(TAG, "Field deleted from vault: $itemId")
                        } else {
                            Log.w(TAG, "Vault returned error deleting field: ${response.error}")
                        }
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Failed to delete field: ${response.code} - ${response.message}")
                        _effects.emit(PersonalDataEffect.ShowError("Failed to delete: ${response.message}"))
                        return@launch
                    }
                    null -> {
                        Log.w(TAG, "Delete request timed out, proceeding with local delete")
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type: ${response::class.simpleName}")
                    }
                }

                // Also delete locally
                deleteFieldLocally(itemId)

                _effects.emit(PersonalDataEffect.ShowSuccess("Data deleted"))
                loadPersonalData()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete item", e)
                _effects.emit(PersonalDataEffect.ShowError("Failed to delete"))
            }
        }
    }

    /**
     * Delete field from local storage.
     */
    private fun deleteFieldLocally(namespace: String) {
        // Try to clear as optional field
        personalDataStore.updateOptionalFieldByNamespace(namespace, null)

        // Also try to remove as custom field
        val customFields = personalDataStore.getCustomFields()
        val matching = customFields.find {
            personalDataStore.generateNamespace(it.category.name.lowercase(), it.name) == namespace
        }
        matching?.let {
            personalDataStore.removeCustomField(it.id)
        }
    }

    /**
     * Toggle whether an item is included in the public profile.
     */
    private fun togglePublicProfile(itemId: String) {
        viewModelScope.launch {
            try {
                val index = dataItems.indexOfFirst { it.id == itemId }
                if (index >= 0) {
                    val item = dataItems[index]

                    // System fields are always in public profile
                    if (item.isSystemField) {
                        _effects.emit(PersonalDataEffect.ShowError("System fields are always shared"))
                        return@launch
                    }

                    val newValue = !item.isInPublicProfile

                    // Update in-memory
                    dataItems[index] = item.copy(isInPublicProfile = newValue)

                    // Update local storage
                    if (newValue) {
                        publicProfileFields.add(itemId)
                    } else {
                        publicProfileFields.remove(itemId)
                    }
                    personalDataStore.updatePublicProfileFields(publicProfileFields.toList())

                    // Update vault public profile settings
                    ownerSpaceClient.updatePublicProfileSettings(publicProfileFields.toList())

                    // Mark as having unpublished changes
                    _hasUnpublishedChanges.value = true

                    // Refresh state
                    _state.value = PersonalDataState.Loaded(items = dataItems.toList())

                    val statusText = if (newValue) "added to" else "removed from"
                    _effects.emit(PersonalDataEffect.ShowSuccess("${item.name} $statusText public profile"))

                    Log.d(TAG, "Toggled public profile for $itemId: $newValue")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle public profile", e)
                _effects.emit(PersonalDataEffect.ShowError("Failed to update public profile"))
            }
        }
    }

    /**
     * Move an item up within its category (decrease sort order).
     */
    private fun moveItemUp(itemId: String) {
        Log.i(TAG, "=== moveItemUp called for itemId: $itemId ===")
        val item = dataItems.find { it.id == itemId }
        if (item == null) {
            Log.w(TAG, "moveItemUp: item not found in dataItems (size=${dataItems.size})")
            return
        }

        val category = item.category
        // Sort matching display order: sortOrder first, then name as tiebreaker
        val categoryItems = dataItems.filter { it.category == category }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))

        val index = categoryItems.indexOfFirst { it.id == itemId }
        if (index <= 0) {
            Log.d(TAG, "moveItemUp: already at top")
            return
        }

        // Normalize sort orders first to ensure they're distinct
        categoryItems.forEachIndexed { i, ci ->
            updateItemSortOrder(ci.id, i)
        }

        // Now swap the target with the item above
        val itemAbove = categoryItems[index - 1]
        updateItemSortOrder(itemId, index - 1)
        updateItemSortOrder(itemAbove.id, index)
        Log.d(TAG, "moveItemUp: swapped '${item.name}' to position ${index - 1}, '${itemAbove.name}' to $index")

        // Refresh state
        val newItems = dataItems.toList()
        val currentState = _state.value
        _state.value = if (currentState is PersonalDataState.Loaded) {
            currentState.copy(items = newItems)
        } else {
            PersonalDataState.Loaded(items = newItems)
        }

        // Persist to vault
        persistSortOrder(category)
    }

    /**
     * Move an item down within its category (increase sort order).
     */
    private fun moveItemDown(itemId: String) {
        Log.i(TAG, "=== moveItemDown called for itemId: $itemId ===")
        val item = dataItems.find { it.id == itemId }
        if (item == null) {
            Log.w(TAG, "moveItemDown: item not found")
            return
        }

        val category = item.category
        // Sort matching display order: sortOrder first, then name as tiebreaker
        val categoryItems = dataItems.filter { it.category == category }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))

        val index = categoryItems.indexOfFirst { it.id == itemId }
        if (index < 0 || index >= categoryItems.size - 1) {
            Log.d(TAG, "moveItemDown: already at bottom")
            return
        }

        // Normalize sort orders first to ensure they're distinct
        categoryItems.forEachIndexed { i, ci ->
            updateItemSortOrder(ci.id, i)
        }

        // Now swap the target with the item below
        val itemBelow = categoryItems[index + 1]
        updateItemSortOrder(itemId, index + 1)
        updateItemSortOrder(itemBelow.id, index)
        Log.d(TAG, "moveItemDown: swapped '${item.name}' to position ${index + 1}, '${itemBelow.name}' to $index")

        // Refresh state
        val newItems = dataItems.toList()
        val currentState = _state.value
        _state.value = if (currentState is PersonalDataState.Loaded) {
            currentState.copy(items = newItems)
        } else {
            PersonalDataState.Loaded(items = newItems)
        }

        // Persist to vault
        persistSortOrder(category)
    }

    /**
     * Update sort order for an item in the local list.
     */
    private fun updateItemSortOrder(itemId: String, newSortOrder: Int) {
        val index = dataItems.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            dataItems[index] = dataItems[index].copy(sortOrder = newSortOrder)
        }
    }

    /**
     * Persist field sort order to vault and local storage.
     * Called after moving items up/down.
     *
     * @param category The category that was reordered (for logging)
     */
    private fun persistSortOrder(category: DataCategory?) {
        viewModelScope.launch {
            try {
                // Build sort order map from all fields (including system fields)
                val sortOrderMap = dataItems
                    .associate { it.id to it.sortOrder }

                Log.d(TAG, "Persisting sort order for ${sortOrderMap.size} fields (category: ${category?.displayName ?: "all"})")

                // Always persist to local storage first (guaranteed to work)
                personalDataStore.updateFieldSortOrder(sortOrderMap)
                Log.d(TAG, "Sort order saved to local storage")

                // Then try to persist to vault (may fail if backend handler not implemented)
                val result = ownerSpaceClient.updateFieldSortOrder(sortOrderMap)
                result.onSuccess {
                    Log.i(TAG, "Sort order persisted to vault")
                }.onFailure { error ->
                    Log.w(TAG, "Failed to persist sort order to vault (local storage succeeded): ${error.message}")
                    // Don't show error to user - local state is still correct
                    // Vault sync will eventually be implemented
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting sort order", e)
            }
        }
    }

    /**
     * Create a custom category.
     * Returns the created category info.
     */
    fun createCustomCategory(name: String): com.vettid.app.core.storage.CategoryInfo {
        Log.i(TAG, "Creating custom category: $name")
        val category = personalDataStore.addCustomCategory(name)
        Log.i(TAG, "Created custom category: ${category.id} - ${category.name}")
        // Update the StateFlow so UI sees the new category immediately
        _customCategories.value = personalDataStore.getCustomCategories()
        return category
    }

    /**
     * Get all custom categories.
     */
    fun getCustomCategories(): List<com.vettid.app.core.storage.CategoryInfo> {
        return personalDataStore.getCustomCategories()
    }

    /**
     * Get personal data grouped by category.
     */
    fun getDataByCategory(): GroupedByCategory {
        return GroupedByCategory.fromItems(dataItems)
    }

    /**
     * Get items that are included in the public profile.
     */
    fun getPublicProfileItems(): List<PersonalDataItem> {
        return dataItems.filter { it.isInPublicProfile }
    }

    /**
     * Show the public profile preview dialog.
     * Fetches the actual published profile from the vault/NATS.
     */
    fun showPublicProfilePreview() {
        // Set loading state BEFORE opening dialog to avoid flash of "no profile" state
        _isLoadingPublishedProfile.value = true
        _showPublicProfilePreview.value = true
        fetchPublishedProfile()
    }

    /**
     * Hide the public profile preview dialog.
     */
    fun hidePublicProfilePreview() {
        _showPublicProfilePreview.value = false
    }

    /**
     * Fetch the published profile from the vault.
     * This is what connections actually see in the NATS message space.
     * Automatically retries if profile not found (handles eventual consistency).
     *
     * Uses sendAndAwaitResponse() for proper request/response correlation.
     */
    private fun fetchPublishedProfile() {
        viewModelScope.launch {
            _isLoadingPublishedProfile.value = true

            try {
                // Retry up to 3 times with increasing delays for eventual consistency
                val maxRetries = 3
                val retryDelays = listOf(500L, 1000L, 1500L)

                for (attempt in 0 until maxRetries) {
                    if (attempt > 0) {
                        Log.d(TAG, "Retrying fetch published profile (attempt ${attempt + 1}/$maxRetries)")
                        kotlinx.coroutines.delay(retryDelays[attempt - 1])
                    }

                    Log.d(TAG, "Fetching published profile... (attempt ${attempt + 1})")
                    val response = ownerSpaceClient.sendAndAwaitResponse("profile.get-published", JsonObject(), 10000L)

                    val success = handlePublishedProfileResponse(response, attempt, maxRetries)
                    if (success) {
                        return@launch // Found profile, done
                    }
                    // If not successful and not the last attempt, loop will continue
                    if (attempt >= maxRetries - 1) {
                        // All retries exhausted
                        showLocalPreview()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching published profile", e)
                showLocalPreview()
            } finally {
                _isLoadingPublishedProfile.value = false
            }
        }
    }

    /**
     * Handle the response from profile.get-published request.
     * @return true if profile was successfully loaded, false to retry
     */
    private fun handlePublishedProfileResponse(
        response: VaultResponse?,
        attempt: Int,
        maxRetries: Int
    ): Boolean {
        when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    // Check if profile was actually published by looking for:
                    // 1. explicit "published" boolean field
                    // 2. presence of "fields" object with content
                    // 3. presence of system fields like first_name/email
                    val hasPublishedFlag = response.result.get("published")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    val hasFields = response.result.has("fields") &&
                        response.result.get("fields")?.isJsonObject == true &&
                        response.result.getAsJsonObject("fields").size() > 0
                    val hasSystemFields = response.result.has("first_name") || response.result.has("email")

                    val isPublished = hasPublishedFlag || hasFields || hasSystemFields
                    Log.d(TAG, "Published profile check: hasPublishedFlag=$hasPublishedFlag, hasFields=$hasFields, hasSystemFields=$hasSystemFields, isPublished=$isPublished")

                    if (isPublished) {
                        parsePublishedProfile(response.result)
                        return true
                    } else {
                        Log.d(TAG, "No published profile found on attempt ${attempt + 1}")
                        return false
                    }
                } else {
                    Log.d(TAG, "Profile request returned no result on attempt ${attempt + 1}")
                    return false
                }
            }
            is VaultResponse.Error -> {
                Log.e(TAG, "Error fetching published profile: ${response.message}")
                return false
            }
            null -> {
                Log.w(TAG, "Published profile request timed out on attempt ${attempt + 1}")
                return false
            }
            else -> {
                Log.w(TAG, "Unexpected response type: ${response::class.simpleName}")
                return false
            }
        }
    }

    /**
     * Show that no profile has been published yet.
     * We don't show a preview - only actual published data.
     */
    private fun showLocalPreview() {
        Log.d(TAG, "No published profile found")
        _publishedProfile.value = PublishedProfileData(
            items = emptyList(),
            isFromVault = false
        )
    }

    /**
     * Fetch published profile status (non-blocking, for showing publish status in UI).
     * Unlike fetchPublishedProfile(), this doesn't set loading state for preview dialog.
     */
    private fun fetchPublishedProfileStatus() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Fetching published profile status...")
                val response = ownerSpaceClient.sendAndAwaitResponse("profile.get-published", JsonObject())

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            // Check if profile was actually published
                            val hasPublishedFlag = response.result.get("published")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                            val hasFields = response.result.has("fields") &&
                                response.result.get("fields")?.isJsonObject == true &&
                                response.result.getAsJsonObject("fields").size() > 0
                            val hasSystemFields = response.result.has("first_name") || response.result.has("email")

                            val isPublished = hasPublishedFlag || hasFields || hasSystemFields

                            if (isPublished) {
                                parsePublishedProfile(response.result)
                                Log.i(TAG, "Published profile status loaded: isPublished=true")
                            } else {
                                showLocalPreview()
                                Log.d(TAG, "Published profile status: not yet published")
                            }
                        } else {
                            showLocalPreview()
                        }
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Error fetching published profile status: ${response.message}")
                        showLocalPreview()
                    }
                    else -> {
                        showLocalPreview()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching published profile status", e)
            }
        }
    }

    /**
     * Parse the published profile from vault response.
     */
    private fun parsePublishedProfile(result: JsonObject) {
        val items = mutableListOf<PersonalDataItem>()
        val now = Instant.now()

        // Load saved sort order to apply to published items
        val savedSortOrder = personalDataStore.getFieldSortOrder()

        Log.d(TAG, "Parsing published profile: ${result.keySet()}")

        // Always-shared system fields - apply saved sort order
        result.get("first_name")?.takeIf { !it.isJsonNull }?.asString?.let { firstName ->
            items.add(PersonalDataItem(
                id = "_published_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = firstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_first_name"] ?: 0,
                createdAt = now,
                updatedAt = now
            ))
        }

        result.get("last_name")?.takeIf { !it.isJsonNull }?.asString?.let { lastName ->
            items.add(PersonalDataItem(
                id = "_published_last_name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = lastName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_last_name"] ?: 1,
                createdAt = now,
                updatedAt = now
            ))
        }

        result.get("email")?.takeIf { !it.isJsonNull }?.asString?.let { email ->
            items.add(PersonalDataItem(
                id = "_published_email",
                name = "Email",
                type = DataType.PUBLIC,
                value = email,
                category = DataCategory.CONTACT,
                isSystemField = true,
                isInPublicProfile = true,
                sortOrder = savedSortOrder["_system_email"] ?: 0,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Identity public key (Ed25519) - always shared as system field
        result.get("public_key")?.takeIf { !it.isJsonNull }?.asString?.let { publicKey ->
            items.add(PersonalDataItem(
                id = "_published_public_key",
                name = "Identity Public Key",
                type = DataType.KEY,
                value = publicKey,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
                isSensitive = false,
                sortOrder = savedSortOrder["_system_public_key"] ?: 99,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Parse additional fields
        result.getAsJsonObject("fields")?.let { fieldsObj ->
            fieldsObj.entrySet().forEach { (name, fieldData) ->
                try {
                    val displayName = fieldData.asJsonObject.get("display_name")?.asString ?: name
                    val value = fieldData.asJsonObject.get("value")?.asString ?: ""
                    val fieldType = fieldData.asJsonObject.get("field_type")?.asString ?: "text"

                    items.add(PersonalDataItem(
                        id = "_published_$name",
                        name = displayName,
                        type = DataType.PUBLIC,
                        value = value,
                        category = categoryFromNamespace(name),
                        isSystemField = false,
                        isInPublicProfile = true,
                        sortOrder = savedSortOrder[name] ?: 0,
                        createdAt = now,
                        updatedAt = now
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing published field $name: ${e.message}")
                }
            }
        }

        // Include public keys from secrets store that are marked for public profile
        val hasIdentityKeyFromVault = items.any { it.id == "_published_public_key" }
        try {
            val publicKeySecrets = minorSecretsStore.getPublicProfileSecrets()
            var addedCount = 0
            publicKeySecrets.forEach { secret ->
                // Skip system enrollment key if vault already provided it
                if (secret.isSystemField && hasIdentityKeyFromVault) return@forEach

                items.add(PersonalDataItem(
                    id = "_secret_${secret.id}",
                    name = secret.name,
                    type = DataType.KEY,
                    value = secret.value,
                    category = DataCategory.IDENTITY,
                    isSystemField = secret.isSystemField,
                    isInPublicProfile = true,
                    isSensitive = false,
                    sortOrder = secret.sortOrder,
                    createdAt = com.vettid.app.util.toInstant(secret.createdAt),
                    updatedAt = com.vettid.app.util.toInstant(secret.updatedAt)
                ))
                addedCount++
            }
            if (addedCount > 0) {
                Log.d(TAG, "Added $addedCount public key(s) from secrets store to profile")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load public keys from secrets store", e)
        }

        val updatedAt = result.get("updated_at")?.takeIf { !it.isJsonNull }?.asString
        val photo = result.get("photo")?.takeIf { !it.isJsonNull }?.asString
        Log.i(TAG, "Parsed ${items.size} items from published profile, updated_at: $updatedAt, has_photo: ${photo != null}")

        // Update the profile photo for the header and notify app-level state
        if (photo != null && _profilePhoto.value == null) {
            _profilePhoto.value = photo
            profilePhotoEvents.notifyPhotoUpdated(photo)
            Log.d(TAG, "Set profile photo from published profile data and notified app")
        }

        _publishedProfile.value = PublishedProfileData(
            items = items,
            isFromVault = true,
            updatedAt = updatedAt,
            photo = photo
        )
    }

    // MARK: - Profile Photo Methods

    /**
     * Fetch profile photo from vault (non-blocking).
     */
    private fun fetchProfilePhoto() {
        viewModelScope.launch {
            try {
                val result = ownerSpaceClient.getProfilePhoto()
                result.onSuccess { photo ->
                    if (!photo.isNullOrEmpty()) {
                        _profilePhoto.value = photo
                        personalDataStore.saveProfilePhoto(photo)
                        profilePhotoEvents.notifyPhotoUpdated(photo)
                        Log.d(TAG, "Profile photo loaded from vault: ${photo.length} chars")
                    } else {
                        // Vault has no photo - try local cache
                        loadPhotoFromLocalCache()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to fetch profile photo from vault, trying local cache", error)
                    loadPhotoFromLocalCache()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching profile photo, trying local cache", e)
                loadPhotoFromLocalCache()
            }
        }
    }

    private fun loadPhotoFromLocalCache() {
        val localPhoto = personalDataStore.getProfilePhoto()
        if (!localPhoto.isNullOrEmpty()) {
            _profilePhoto.value = localPhoto
            profilePhotoEvents.notifyPhotoUpdated(localPhoto)
            Log.d(TAG, "Profile photo loaded from local cache: ${localPhoto.length} chars")
            // Re-upload to vault so it has the photo again
            reUploadPhotoToVault(localPhoto)
        } else {
            Log.d(TAG, "No profile photo in local cache")
        }
    }

    private fun reUploadPhotoToVault(base64Photo: String) {
        viewModelScope.launch {
            try {
                ownerSpaceClient.updateProfilePhoto(base64Photo)
                Log.i(TAG, "Re-uploaded local photo to vault")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-upload photo to vault: ${e.message}")
            }
        }
    }

    /**
     * Load metadata visible to agents, services, and connections.
     * Shows names and types only - never actual values.
     */
    private fun loadPublicMetadata() {
        viewModelScope.launch {
            // Public secrets (keys in public profile)
            val secrets = minorSecretsStore.getPublicProfileSecrets()
            _publicSecrets.value = secrets.map { secret ->
                PublicMetadataItem(
                    name = secret.name,
                    type = secret.type.name,
                    category = secret.category.displayName
                )
            }

            // Public personal data fields
            val publicFieldIds = personalDataStore.getPublicProfileFields()
            val customFields = personalDataStore.getCustomFields()
            val publicItems = mutableListOf<PublicMetadataItem>()

            // System fields (always potentially visible)
            personalDataStore.getSystemFields()?.let { sys ->
                if (sys.firstName.isNotBlank()) publicItems.add(PublicMetadataItem("First Name", "System", "Identity"))
                if (sys.lastName.isNotBlank()) publicItems.add(PublicMetadataItem("Last Name", "System", "Identity"))
                if (sys.email.isNotBlank()) publicItems.add(PublicMetadataItem("Email", "System", "Contact"))
            }

            // Custom fields marked as public profile
            customFields.filter { it.id in publicFieldIds }.forEach { field ->
                publicItems.add(PublicMetadataItem(
                    name = field.name,
                    type = field.fieldType.displayName,
                    category = field.category.name.lowercase().replaceFirstChar { it.uppercase() }
                ))
            }

            _publicPersonalData.value = publicItems
        }
    }

    /**
     * Show the photo capture dialog.
     */
    fun showPhotoCaptureDialog() {
        _showPhotoCapture.value = true
    }

    /**
     * Hide the photo capture dialog.
     */
    fun hidePhotoCaptureDialog() {
        _showPhotoCapture.value = false
    }

    /**
     * Upload a new profile photo.
     *
     * @param photoBytes Compressed JPEG bytes from camera capture
     */
    fun uploadPhoto(photoBytes: ByteArray) {
        viewModelScope.launch {
            _isUploadingPhoto.value = true
            try {
                // Convert to Base64
                val base64Photo = android.util.Base64.encodeToString(
                    photoBytes,
                    android.util.Base64.NO_WRAP
                )
                Log.d(TAG, "Uploading photo: ${photoBytes.size} bytes, ${base64Photo.length} chars base64")

                val result = ownerSpaceClient.updateProfilePhoto(base64Photo)
                result.onSuccess {
                    _profilePhoto.value = base64Photo
                    _showPhotoCapture.value = false
                    personalDataStore.saveProfilePhoto(base64Photo)
                    _effects.emit(PersonalDataEffect.ShowSuccess("Profile photo updated"))
                    // Notify app-level state so header/drawer update
                    profilePhotoEvents.notifyPhotoUpdated(base64Photo)
                    Log.i(TAG, "Profile photo uploaded successfully")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to upload profile photo", error)
                    _effects.emit(PersonalDataEffect.ShowError("Failed to upload photo: ${error.message}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading profile photo", e)
                _effects.emit(PersonalDataEffect.ShowError("Error uploading photo: ${e.message}"))
            } finally {
                _isUploadingPhoto.value = false
            }
        }
    }

    /**
     * Delete the current profile photo.
     */
    fun deletePhoto() {
        viewModelScope.launch {
            try {
                val result = ownerSpaceClient.deleteProfilePhoto()
                result.onSuccess {
                    _profilePhoto.value = null
                    personalDataStore.saveProfilePhoto(null)
                    _effects.emit(PersonalDataEffect.ShowSuccess("Profile photo removed"))
                    // Notify app-level state so header/drawer update
                    profilePhotoEvents.notifyPhotoUpdated(null)
                    Log.i(TAG, "Profile photo deleted")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to delete profile photo", error)
                    _effects.emit(PersonalDataEffect.ShowError("Failed to remove photo: ${error.message}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting profile photo", e)
                _effects.emit(PersonalDataEffect.ShowError("Error removing photo: ${e.message}"))
            }
        }
    }
}

/**
 * Data class for published profile display.
 */
data class PublishedProfileData(
    val items: List<PersonalDataItem>,
    val isFromVault: Boolean,
    val updatedAt: String? = null,
    val photo: String? = null
)

/**
 * A metadata item visible to connections/agents/services.
 * Shows name and type only - never the actual value.
 */
data class PublicMetadataItem(
    val name: String,
    val type: String,
    val category: String
)
