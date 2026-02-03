package com.vettid.app.features.personaldata

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.events.ProfilePhotoEvents
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.PersonalDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private const val TAG = "PersonalDataViewModel"

@HiltViewModel
class PersonalDataViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val personalDataStore: PersonalDataStore,
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

    // In-memory data store loaded from vault
    private val dataItems = mutableListOf<PersonalDataItem>()

    // Public profile fields set (namespaces that are shared)
    private var publicProfileFields = mutableSetOf<String>()

    init {
        Log.i(TAG, "PersonalDataViewModel created - starting load")
        loadPersonalData()
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
                        Log.d(TAG, "Vault load failed, falling back to local storage")
                        loadFromLocalStorageInto(newItems)
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

        // Extract system fields (registration info) - at top level
        val systemFirstName = result.get("first_name")?.takeIf { !it.isJsonNull }?.asString
        val systemLastName = result.get("last_name")?.takeIf { !it.isJsonNull }?.asString
        val systemEmail = result.get("email")?.takeIf { !it.isJsonNull }?.asString

        Log.d(TAG, "System fields: firstName=$systemFirstName, lastName=$systemLastName, email=$systemEmail")

        // Add system fields as items (read-only, always in public profile)
        if (!systemFirstName.isNullOrEmpty()) {
            items.add(PersonalDataItem(
                id = "_system_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = systemFirstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
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
                        createdAt = updatedAt,
                        updatedAt = updatedAt
                    ))

                    Log.d(TAG, "Added field: $namespace = $displayName ($category)")
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing field $namespace: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "No fields object in profile response")
        }

        Log.i(TAG, "Total items after parsing profile: ${items.size}")
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
            namespace.startsWith("contact.") -> DataCategory.CONTACT
            namespace.startsWith("social.") -> DataCategory.CONTACT
            namespace.startsWith("address.") -> DataCategory.ADDRESS
            namespace.startsWith("financial.") -> DataCategory.FINANCIAL
            namespace.startsWith("medical.") -> DataCategory.MEDICAL
            namespace.startsWith("crypto.") -> DataCategory.CRYPTO
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
            "address.work.street" to "Work Street",
            "address.work.city" to "Work City",
            "social.website.personal" to "Website",
            "social.linkedin.url" to "LinkedIn",
            "social.twitter.handle" to "X (Twitter)",
            "social.instagram.handle" to "Instagram",
            "social.github.username" to "GitHub",
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

        // Load system fields
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
                createdAt = Instant.ofEpochMilli(customField.createdAt),
                updatedAt = Instant.ofEpochMilli(customField.updatedAt)
            ))
        }

        Log.d(TAG, "Loaded ${items.size} items from local storage")
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
     */
    fun publishProfile() {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Publishing public profile...")
                val result = ownerSpaceClient.sendToVault("profile.publish", JsonObject())
                result.fold(
                    onSuccess = { requestId ->
                        Log.i(TAG, "Profile publish request sent: $requestId")
                        _hasUnpublishedChanges.value = false
                        _effects.emit(PersonalDataEffect.ShowSuccess("Public profile published"))
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to publish profile: ${error.message}")
                        _effects.emit(PersonalDataEffect.ShowError("Failed to publish: ${error.message}"))
                    }
                )
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
                        _editState.value = current.copy(isSaving = false)
                        _effects.emit(PersonalDataEffect.ShowError("Failed to save: ${response.message}"))
                        return@launch
                    }
                    null -> {
                        Log.w(TAG, "Timeout waiting for vault update confirmation")
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
     * Generate namespace from category and name.
     */
    private fun generateNamespace(category: DataCategory, name: String): String {
        val prefix = when (category) {
            DataCategory.IDENTITY -> "personal.custom"
            DataCategory.CONTACT -> "contact.custom"
            DataCategory.ADDRESS -> "address.custom"
            DataCategory.FINANCIAL -> "financial.custom"
            DataCategory.MEDICAL -> "medical.custom"
            DataCategory.CRYPTO -> "crypto.custom"
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
        val item = dataItems.find { it.id == itemId } ?: return
        if (item.isSystemField) return  // System fields can't be reordered

        val category = item.category
        val categoryItems = dataItems.filter { it.category == category && !it.isSystemField }
            .sortedBy { it.sortOrder }

        val index = categoryItems.indexOfFirst { it.id == itemId }
        if (index <= 0) return  // Already at top

        // Swap sort orders with the item above
        val itemAbove = categoryItems[index - 1]
        val newSortOrder = itemAbove.sortOrder
        val aboveSortOrder = item.sortOrder

        updateItemSortOrder(itemId, newSortOrder)
        updateItemSortOrder(itemAbove.id, aboveSortOrder)

        // Refresh state
        _state.value = PersonalDataState.Loaded(items = dataItems.toList())
    }

    /**
     * Move an item down within its category (increase sort order).
     */
    private fun moveItemDown(itemId: String) {
        val item = dataItems.find { it.id == itemId } ?: return
        if (item.isSystemField) return  // System fields can't be reordered

        val category = item.category
        val categoryItems = dataItems.filter { it.category == category && !it.isSystemField }
            .sortedBy { it.sortOrder }

        val index = categoryItems.indexOfFirst { it.id == itemId }
        if (index < 0 || index >= categoryItems.size - 1) return  // Already at bottom

        // Swap sort orders with the item below
        val itemBelow = categoryItems[index + 1]
        val newSortOrder = itemBelow.sortOrder
        val belowSortOrder = item.sortOrder

        updateItemSortOrder(itemId, newSortOrder)
        updateItemSortOrder(itemBelow.id, belowSortOrder)

        // Refresh state
        _state.value = PersonalDataState.Loaded(items = dataItems.toList())
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
     * Create a custom category.
     * Returns the created category info.
     */
    fun createCustomCategory(name: String): com.vettid.app.core.storage.CategoryInfo {
        Log.i(TAG, "Creating custom category: $name")
        val category = personalDataStore.addCustomCategory(name)
        Log.i(TAG, "Created custom category: ${category.id} - ${category.name}")
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
     * If no profile has been published yet, shows what would be published based on current selections.
     *
     * Uses sendAndAwaitResponse() for proper request/response correlation.
     */
    private fun fetchPublishedProfile() {
        viewModelScope.launch {
            _isLoadingPublishedProfile.value = true
            try {
                Log.d(TAG, "Fetching published profile...")
                val response = ownerSpaceClient.sendAndAwaitResponse("profile.get-published", JsonObject(), 10000L)

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
                            } else {
                                // No published profile yet - show local items marked for public profile
                                Log.d(TAG, "No published profile found, showing local preview")
                                showLocalPreview()
                            }
                        } else {
                            // No published profile yet - show local preview
                            Log.d(TAG, "Profile request failed, showing local preview")
                            showLocalPreview()
                        }
                    }
                    is VaultResponse.Error -> {
                        Log.e(TAG, "Error fetching published profile: ${response.message}")
                        // Fall back to local items
                        showLocalPreview()
                    }
                    null -> {
                        Log.w(TAG, "Published profile request timed out")
                        showLocalPreview()
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type: ${response::class.simpleName}")
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

        Log.d(TAG, "Parsing published profile: ${result.keySet()}")

        // Always-shared system fields
        result.get("first_name")?.takeIf { !it.isJsonNull }?.asString?.let { firstName ->
            items.add(PersonalDataItem(
                id = "_published_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = firstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true,
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
                        createdAt = now,
                        updatedAt = now
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing published field $name: ${e.message}")
                }
            }
        }

        val updatedAt = result.get("updated_at")?.takeIf { !it.isJsonNull }?.asString
        val photo = result.get("photo")?.takeIf { !it.isJsonNull }?.asString
        Log.i(TAG, "Parsed ${items.size} items from published profile, updated_at: $updatedAt, has_photo: ${photo != null}")

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
                    _profilePhoto.value = photo
                    Log.d(TAG, "Profile photo loaded: ${photo?.length ?: 0} chars")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to fetch profile photo", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching profile photo", e)
            }
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
