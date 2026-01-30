package com.vettid.app.features.personaldata

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.PersonalDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

private const val TAG = "PersonalDataViewModel"

@HiltViewModel
class PersonalDataViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val personalDataStore: PersonalDataStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    private val _state = MutableStateFlow<PersonalDataState>(PersonalDataState.Loading)
    val state: StateFlow<PersonalDataState> = _state.asStateFlow()

    private val _editState = MutableStateFlow(EditDataItemState())
    val editState: StateFlow<EditDataItemState> = _editState.asStateFlow()

    private val _effects = MutableSharedFlow<PersonalDataEffect>()
    val effects: SharedFlow<PersonalDataEffect> = _effects.asSharedFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    // In-memory data store loaded from vault
    private val dataItems = mutableListOf<PersonalDataItem>()

    // Public profile fields set (namespaces that are shared)
    private var publicProfileFields = mutableSetOf<String>()

    init {
        loadPersonalData()
    }

    fun onEvent(event: PersonalDataEvent) {
        when (event) {
            is PersonalDataEvent.SearchQueryChanged -> updateSearchQuery(event.query)
            is PersonalDataEvent.ItemClicked -> selectItem(event.itemId)
            is PersonalDataEvent.AddItem -> showAddDialog()
            is PersonalDataEvent.DeleteItem -> deleteItem(event.itemId)
            is PersonalDataEvent.TogglePublicProfile -> togglePublicProfile(event.itemId)
            is PersonalDataEvent.Refresh -> loadPersonalData()
        }
    }

    private fun loadPersonalData() {
        viewModelScope.launch {
            _state.value = PersonalDataState.Loading
            try {
                dataItems.clear()

                // Try to fetch from vault if connected
                if (connectionManager.isConnected()) {
                    Log.d(TAG, "Loading personal data from vault...")
                    val vaultLoadSuccess = loadFromVault()
                    if (vaultLoadSuccess) {
                        Log.d(TAG, "Loaded ${dataItems.size} items from vault")
                    } else {
                        Log.d(TAG, "Vault load failed, falling back to local storage")
                        loadFromLocalStorage()
                    }
                } else {
                    Log.d(TAG, "Not connected to vault, loading from local storage")
                    loadFromLocalStorage()
                }

                if (dataItems.isEmpty()) {
                    _state.value = PersonalDataState.Empty
                } else {
                    _state.value = PersonalDataState.Loaded(items = dataItems.toList())
                }
                Log.d(TAG, "Total items loaded: ${dataItems.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal data", e)
                _state.value = PersonalDataState.Error(e.message ?: "Failed to load data")
            }
        }
    }

    /**
     * Load personal data directly from vault.
     * Returns true if successful, false otherwise.
     */
    private suspend fun loadFromVault(): Boolean {
        return try {
            // Send profile.get request
            val requestResult = ownerSpaceClient.getProfileFromVault()
            if (requestResult.isFailure) {
                Log.e(TAG, "Failed to request profile from vault: ${requestResult.exceptionOrNull()?.message}")
                return false
            }

            val requestId = requestResult.getOrThrow()
            Log.d(TAG, "Profile request sent, waiting for response: $requestId")

            // Wait for response with timeout
            val response = withTimeoutOrNull(10000L) {
                ownerSpaceClient.vaultResponses.first { it.requestId == requestId }
            }

            if (response == null) {
                Log.w(TAG, "Profile request timed out")
                return false
            }

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        parseVaultResponse(response.result)
                        true
                    } else {
                        Log.w(TAG, "Profile request failed: ${response.error}")
                        false
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "Profile request error: ${response.code} - ${response.message}")
                    false
                }
                else -> {
                    Log.w(TAG, "Unexpected response type for profile request")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from vault", e)
            false
        }
    }

    /**
     * Parse vault response and populate dataItems directly.
     * This is the core of the dynamic loading - no static field mappings.
     */
    private fun parseVaultResponse(result: JsonObject) {
        val now = Instant.now()
        Log.d(TAG, "Parsing vault response: ${result.keySet()}")

        // Load public profile fields set
        loadPublicProfileSettings()

        // Extract system fields (registration info) - always at top level
        val systemFirstName = result.get("first_name")?.takeIf { !it.isJsonNull }?.asString
        val systemLastName = result.get("last_name")?.takeIf { !it.isJsonNull }?.asString
        val systemEmail = result.get("email")?.takeIf { !it.isJsonNull }?.asString

        // Add system fields as items (read-only, always in public profile)
        if (!systemFirstName.isNullOrEmpty()) {
            dataItems.add(PersonalDataItem(
                id = "_system_first_name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = systemFirstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true, // Always shared
                createdAt = now,
                updatedAt = now
            ))
        }
        if (!systemLastName.isNullOrEmpty()) {
            dataItems.add(PersonalDataItem(
                id = "_system_last_name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = systemLastName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                isInPublicProfile = true, // Always shared
                createdAt = now,
                updatedAt = now
            ))
        }
        if (!systemEmail.isNullOrEmpty()) {
            dataItems.add(PersonalDataItem(
                id = "_system_email",
                name = "Email",
                type = DataType.PUBLIC,
                value = systemEmail,
                category = DataCategory.CONTACT,
                isSystemField = true,
                isInPublicProfile = true, // Always shared
                createdAt = now,
                updatedAt = now
            ))
        }

        // Extract dynamic fields from the "fields" object
        val fieldsObject = result.getAsJsonObject("fields")
        if (fieldsObject != null) {
            Log.d(TAG, "Processing ${fieldsObject.size()} fields from vault")

            fieldsObject.entrySet().forEach { (namespace, value) ->
                try {
                    // Skip system fields (already handled above)
                    if (namespace.startsWith("_system_")) {
                        return@forEach
                    }

                    // Extract value - could be object with "value" key or primitive
                    val fieldValue = if (value.isJsonObject) {
                        value.asJsonObject.get("value")?.takeIf { !it.isJsonNull }?.asString
                    } else if (value.isJsonPrimitive) {
                        value.asString
                    } else null

                    if (fieldValue.isNullOrEmpty()) {
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

                    dataItems.add(PersonalDataItem(
                        id = namespace,
                        name = displayName,
                        type = dataType,
                        value = fieldValue,
                        category = category,
                        isSystemField = false,
                        isInPublicProfile = isInPublicProfile,
                        createdAt = updatedAt, // Use updatedAt as createdAt if not available
                        updatedAt = updatedAt
                    ))

                    Log.d(TAG, "Added field: $namespace = $displayName ($category)")
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing field $namespace: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "No fields object in vault response")
        }

        // Also store system fields locally for offline access
        if (!systemFirstName.isNullOrEmpty() && !systemLastName.isNullOrEmpty() && !systemEmail.isNullOrEmpty()) {
            personalDataStore.storeSystemFields(
                com.vettid.app.core.storage.SystemPersonalData(
                    firstName = systemFirstName,
                    lastName = systemLastName,
                    email = systemEmail
                )
            )
        }

        Log.i(TAG, "Parsed ${dataItems.size} items from vault")
    }

    /**
     * Load public profile settings from vault.
     */
    private fun loadPublicProfileSettings() {
        publicProfileFields.clear()
        publicProfileFields.addAll(personalDataStore.getPublicProfileFields())
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
            namespace.startsWith("document.") -> DataCategory.DOCUMENT
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
            "personal.legal.middle_name" to "Middle Name",
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
    private fun loadFromLocalStorage() {
        val now = Instant.now()

        // Load system fields
        val systemFields = personalDataStore.getSystemFields()
        systemFields?.let { system ->
            dataItems.add(PersonalDataItem(
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
            dataItems.add(PersonalDataItem(
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
            dataItems.add(PersonalDataItem(
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
                dataItems.add(PersonalDataItem(
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
        addIfNotEmpty("personal.legal.middle_name", "Middle Name", optional.middleName, DataCategory.IDENTITY)
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
            dataItems.add(PersonalDataItem(
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

        Log.d(TAG, "Loaded ${dataItems.size} items from local storage")
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

    fun updateEditName(name: String) {
        _editState.value = _editState.value.copy(name = name, nameError = null)
    }

    fun updateEditValue(value: String) {
        _editState.value = _editState.value.copy(value = value, valueError = null)
    }

    fun updateEditType(type: DataType) {
        _editState.value = _editState.value.copy(type = type)
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
                val category = current.category ?: DataCategory.OTHER
                val namespace = current.id ?: generateNamespace(category, current.name)

                // Save to vault
                val payload = JsonObject().apply {
                    val fieldsObj = JsonObject()
                    fieldsObj.addProperty(namespace, current.value)
                    add("fields", fieldsObj)
                }

                val result = ownerSpaceClient.sendToVault("profile.update", payload)

                result.fold(
                    onSuccess = { requestId ->
                        Log.i(TAG, "Field saved to vault: $namespace, requestId: $requestId")

                        // Also save locally for offline access
                        saveFieldLocally(namespace, current)

                        _showAddDialog.value = false
                        _editState.value = EditDataItemState()
                        _effects.emit(PersonalDataEffect.ShowSuccess(
                            if (current.isEditing) "Data updated" else "Data added"
                        ))
                        loadPersonalData() // Reload to show updated data
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to save field: ${error.message}")
                        _editState.value = current.copy(isSaving = false)
                        _effects.emit(PersonalDataEffect.ShowError("Failed to save: ${error.message}"))
                    }
                )
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
            DataCategory.DOCUMENT -> "document.custom"
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
                    val fieldsArray = com.google.gson.JsonArray()
                    fieldsArray.add(itemId)
                    add("fields", fieldsArray)
                }

                val result = ownerSpaceClient.sendToVault("profile.delete", payload)

                result.fold(
                    onSuccess = { requestId ->
                        Log.i(TAG, "Field deleted from vault: $itemId, requestId: $requestId")

                        // Also delete locally
                        deleteFieldLocally(itemId)

                        _effects.emit(PersonalDataEffect.ShowSuccess("Data deleted"))
                        loadPersonalData()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to delete field: ${error.message}")
                        _effects.emit(PersonalDataEffect.ShowError("Failed to delete: ${error.message}"))
                    }
                )
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
     * Get personal data grouped by category.
     */
    fun getDataByCategory(): GroupedByCategory {
        return GroupedByCategory.fromItems(dataItems)
    }
}
