package com.vettid.app.features.personaldata

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.FieldType
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.core.storage.OptionalPersonalData
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.core.storage.SystemPersonalData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
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

    // In-memory data store (would be persisted to vault in production)
    private val dataItems = mutableListOf<PersonalDataItem>()

    init {
        loadPersonalData()
    }

    fun onEvent(event: PersonalDataEvent) {
        when (event) {
            is PersonalDataEvent.SearchQueryChanged -> updateSearchQuery(event.query)
            is PersonalDataEvent.ItemClicked -> selectItem(event.itemId)
            is PersonalDataEvent.AddItem -> showAddDialog()
            is PersonalDataEvent.DeleteItem -> deleteItem(event.itemId)
            is PersonalDataEvent.Refresh -> loadPersonalData()
        }
    }

    private fun loadPersonalData() {
        viewModelScope.launch {
            _state.value = PersonalDataState.Loading
            try {
                // First try to fetch from vault if connected
                if (connectionManager.isConnected()) {
                    Log.d(TAG, "Attempting to load personal data from vault")
                    val vaultLoadSuccess = loadFromVault()
                    if (vaultLoadSuccess) {
                        Log.d(TAG, "Personal data loaded from vault")
                    } else {
                        Log.d(TAG, "Vault load failed, falling back to local storage")
                    }
                } else {
                    Log.d(TAG, "Not connected to vault, loading from local storage")
                }

                // Load from local storage (may have been updated by vault sync)
                dataItems.clear()
                dataItems.addAll(loadAllPersonalData())

                if (dataItems.isEmpty()) {
                    _state.value = PersonalDataState.Empty
                } else {
                    _state.value = PersonalDataState.Loaded(items = dataItems.toList())
                }
                Log.d(TAG, "Loaded ${dataItems.size} personal data items")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal data", e)
                _state.value = PersonalDataState.Error(e.message ?: "Failed to load data")
            }
        }
    }

    /**
     * Load personal data from vault.
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
                        processVaultProfileResponse(response.result)
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
     * Process profile data from vault response and update local storage.
     */
    private fun processVaultProfileResponse(result: JsonObject) {
        try {
            Log.d(TAG, "Processing vault profile response: ${result.keySet()}")

            // Extract system fields (_system_* prefix)
            val systemFirstName = result.get("_system_first_name")?.asString
            val systemLastName = result.get("_system_last_name")?.asString
            val systemEmail = result.get("_system_email")?.asString

            if (systemFirstName != null && systemLastName != null && systemEmail != null) {
                Log.d(TAG, "Storing system fields from vault: $systemFirstName $systemLastName")
                personalDataStore.storeSystemFields(
                    SystemPersonalData(
                        firstName = systemFirstName,
                        lastName = systemLastName,
                        email = systemEmail
                    )
                )
            } else {
                // Migration: if local system fields exist but not in vault, sync them up
                val localSystemFields = personalDataStore.getSystemFields()
                if (localSystemFields != null && systemFirstName == null) {
                    Log.i(TAG, "Migrating local system fields to vault")
                    migrateLocalSystemFieldsToVault(localSystemFields)
                }
            }

            // Extract optional fields (non-system fields, non-underscore prefix)
            result.entrySet().forEach { (key, value) ->
                if (!key.startsWith("_") && value.isJsonPrimitive) {
                    val stringValue = value.asString
                    if (stringValue.isNotEmpty()) {
                        val updated = personalDataStore.updateOptionalFieldByKey(key, stringValue)
                        if (updated) {
                            Log.d(TAG, "Updated optional field from vault: $key")
                        }
                    }
                }
            }

            // Mark sync as complete
            personalDataStore.markSyncComplete()
            Log.i(TAG, "Profile loaded from vault successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing vault profile response", e)
        }
    }

    /**
     * Migrate local system fields to vault for existing users.
     */
    private fun migrateLocalSystemFieldsToVault(systemFields: SystemPersonalData) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("_system_first_name", systemFields.firstName)
                    addProperty("_system_last_name", systemFields.lastName)
                    addProperty("_system_email", systemFields.email)
                    addProperty("_system_stored_at", System.currentTimeMillis())
                }

                ownerSpaceClient.sendToVault("profile.update", payload).fold(
                    onSuccess = { Log.i(TAG, "System fields migrated to vault") },
                    onFailure = { Log.e(TAG, "Failed to migrate system fields: ${it.message}") }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error migrating system fields to vault", e)
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
                if (current.id != null) {
                    // Update existing item
                    val existingItem = dataItems.find { it.id == current.id }
                    if (existingItem != null && !existingItem.isSystemField) {
                        // Handle custom fields
                        if (current.id.startsWith("custom-")) {
                            val customFieldId = current.id.removePrefix("custom-")
                            val fieldCategory = when (current.category) {
                                DataCategory.IDENTITY -> FieldCategory.IDENTITY
                                DataCategory.CONTACT -> FieldCategory.CONTACT
                                DataCategory.ADDRESS -> FieldCategory.ADDRESS
                                DataCategory.FINANCIAL -> FieldCategory.FINANCIAL
                                DataCategory.MEDICAL -> FieldCategory.MEDICAL
                                else -> FieldCategory.OTHER
                            }
                            val fieldType = when (current.type) {
                                DataType.MINOR_SECRET -> FieldType.PASSWORD
                                else -> FieldType.TEXT
                            }
                            val customFields = personalDataStore.getCustomFields()
                            val existingCustom = customFields.find { it.id == customFieldId }
                            if (existingCustom != null) {
                                personalDataStore.updateCustomField(existingCustom.copy(
                                    name = current.name,
                                    value = current.value,
                                    category = fieldCategory,
                                    fieldType = fieldType
                                ))
                            }
                        }
                        // Handle optional fields
                        else if (current.id.startsWith("optional-")) {
                            val optionalField = when (current.id) {
                                "optional-prefix" -> OptionalField.PREFIX
                                "optional-first-name" -> OptionalField.FIRST_NAME
                                "optional-middle-name" -> OptionalField.MIDDLE_NAME
                                "optional-last-name" -> OptionalField.LAST_NAME
                                "optional-suffix" -> OptionalField.SUFFIX
                                "optional-phone" -> OptionalField.PHONE
                                "optional-birthday" -> OptionalField.BIRTHDAY
                                "optional-street" -> OptionalField.STREET
                                "optional-street2" -> OptionalField.STREET2
                                "optional-city" -> OptionalField.CITY
                                "optional-state" -> OptionalField.STATE
                                "optional-postal-code" -> OptionalField.POSTAL_CODE
                                "optional-country" -> OptionalField.COUNTRY
                                "optional-website" -> OptionalField.WEBSITE
                                "optional-linkedin" -> OptionalField.LINKEDIN
                                "optional-twitter" -> OptionalField.TWITTER
                                "optional-instagram" -> OptionalField.INSTAGRAM
                                "optional-github" -> OptionalField.GITHUB
                                else -> null
                            }
                            if (optionalField != null) {
                                personalDataStore.updateOptionalField(optionalField, current.value.ifBlank { null })
                            }
                        }
                    }
                    _effects.emit(PersonalDataEffect.ShowSuccess("Data updated"))
                } else {
                    // Create new custom field
                    val fieldCategory = when (current.category) {
                        DataCategory.IDENTITY -> FieldCategory.IDENTITY
                        DataCategory.CONTACT -> FieldCategory.CONTACT
                        DataCategory.ADDRESS -> FieldCategory.ADDRESS
                        DataCategory.FINANCIAL -> FieldCategory.FINANCIAL
                        DataCategory.MEDICAL -> FieldCategory.MEDICAL
                        else -> FieldCategory.OTHER
                    }
                    val fieldType = when (current.type) {
                        DataType.MINOR_SECRET -> FieldType.PASSWORD
                        else -> FieldType.TEXT
                    }
                    personalDataStore.addCustomField(
                        name = current.name,
                        value = current.value,
                        category = fieldCategory,
                        fieldType = fieldType
                    )
                    _effects.emit(PersonalDataEffect.ShowSuccess("Data added"))
                }

                _showAddDialog.value = false
                _editState.value = EditDataItemState()
                loadPersonalData()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save data", e)
                _effects.emit(PersonalDataEffect.ShowError(e.message ?: "Failed to save"))
                _editState.value = current.copy(isSaving = false)
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

                // Handle custom fields
                if (itemId.startsWith("custom-")) {
                    val customFieldId = itemId.removePrefix("custom-")
                    personalDataStore.removeCustomField(customFieldId)
                }
                // Handle optional fields (clear value)
                else if (itemId.startsWith("optional-")) {
                    val optionalField = when (itemId) {
                        "optional-prefix" -> OptionalField.PREFIX
                        "optional-first-name" -> OptionalField.FIRST_NAME
                        "optional-middle-name" -> OptionalField.MIDDLE_NAME
                        "optional-last-name" -> OptionalField.LAST_NAME
                        "optional-suffix" -> OptionalField.SUFFIX
                        "optional-phone" -> OptionalField.PHONE
                        "optional-birthday" -> OptionalField.BIRTHDAY
                        "optional-street" -> OptionalField.STREET
                        "optional-street2" -> OptionalField.STREET2
                        "optional-city" -> OptionalField.CITY
                        "optional-state" -> OptionalField.STATE
                        "optional-postal-code" -> OptionalField.POSTAL_CODE
                        "optional-country" -> OptionalField.COUNTRY
                        "optional-website" -> OptionalField.WEBSITE
                        "optional-linkedin" -> OptionalField.LINKEDIN
                        "optional-twitter" -> OptionalField.TWITTER
                        "optional-instagram" -> OptionalField.INSTAGRAM
                        "optional-github" -> OptionalField.GITHUB
                        else -> null
                    }
                    if (optionalField != null) {
                        personalDataStore.updateOptionalField(optionalField, null)
                    }
                }

                loadPersonalData()
                _effects.emit(PersonalDataEffect.ShowSuccess("Data deleted"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete item", e)
                _effects.emit(PersonalDataEffect.ShowError("Failed to delete"))
            }
        }
    }

    /**
     * Get personal data grouped by type.
     */
    fun getGroupedData(): GroupedPersonalData {
        return GroupedPersonalData(
            publicData = dataItems.filter { it.type == DataType.PUBLIC },
            privateData = dataItems.filter { it.type == DataType.PRIVATE },
            keys = dataItems.filter { it.type == DataType.KEY },
            minorSecrets = dataItems.filter { it.type == DataType.MINOR_SECRET }
        )
    }

    /**
     * Load all personal data from the PersonalDataStore.
     * Converts system fields, optional fields, and custom fields to PersonalDataItem objects.
     */
    private fun loadAllPersonalData(): List<PersonalDataItem> {
        val now = Instant.now()
        val items = mutableListOf<PersonalDataItem>()

        // Load system fields (read-only from registration)
        val systemFields = personalDataStore.getSystemFields()
        android.util.Log.d("PersonalDataVM", "System fields: ${systemFields?.firstName} ${systemFields?.lastName}, hasSystemFields: ${personalDataStore.hasSystemFields()}")
        systemFields?.let { system ->
            items.add(PersonalDataItem(
                id = "system-first-name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = system.firstName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                createdAt = now,
                updatedAt = now
            ))
            items.add(PersonalDataItem(
                id = "system-last-name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = system.lastName,
                category = DataCategory.IDENTITY,
                isSystemField = true,
                createdAt = now,
                updatedAt = now
            ))
            items.add(PersonalDataItem(
                id = "system-email",
                name = "Email",
                type = DataType.PUBLIC,
                value = system.email,
                category = DataCategory.CONTACT,
                isSystemField = true,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Load optional fields
        val optional = personalDataStore.getOptionalFields()

        // Legal name fields
        optional.prefix?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-prefix",
                name = "Name Prefix",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.IDENTITY,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.firstName?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-first-name",
                name = "Legal First Name",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.IDENTITY,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.middleName?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-middle-name",
                name = "Middle Name",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.IDENTITY,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.lastName?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-last-name",
                name = "Legal Last Name",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.IDENTITY,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.suffix?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-suffix",
                name = "Name Suffix",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.IDENTITY,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Contact fields
        optional.phone?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-phone",
                name = "Phone",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.CONTACT,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.birthday?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-birthday",
                name = "Birthday",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.IDENTITY,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Address fields
        optional.street?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-street",
                name = "Street Address",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.ADDRESS,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.street2?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-street2",
                name = "Address Line 2",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.ADDRESS,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.city?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-city",
                name = "City",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.ADDRESS,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.state?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-state",
                name = "State",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.ADDRESS,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.postalCode?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-postal-code",
                name = "Postal Code",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.ADDRESS,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.country?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-country",
                name = "Country",
                type = DataType.PRIVATE,
                value = value,
                category = DataCategory.ADDRESS,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Social/Web fields
        optional.website?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-website",
                name = "Website",
                type = DataType.PUBLIC,
                value = value,
                category = DataCategory.CONTACT,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.linkedin?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-linkedin",
                name = "LinkedIn",
                type = DataType.PUBLIC,
                value = value,
                category = DataCategory.CONTACT,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.twitter?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-twitter",
                name = "X (Twitter)",
                type = DataType.PUBLIC,
                value = value,
                category = DataCategory.CONTACT,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.instagram?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-instagram",
                name = "Instagram",
                type = DataType.PUBLIC,
                value = value,
                category = DataCategory.CONTACT,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }
        optional.github?.let { value ->
            items.add(PersonalDataItem(
                id = "optional-github",
                name = "GitHub",
                type = DataType.PUBLIC,
                value = value,
                category = DataCategory.CONTACT,
                isSystemField = false,
                createdAt = now,
                updatedAt = now
            ))
        }

        // Load custom fields
        personalDataStore.getCustomFields().forEach { customField ->
            val category = when (customField.category) {
                FieldCategory.IDENTITY -> DataCategory.IDENTITY
                FieldCategory.CONTACT -> DataCategory.CONTACT
                FieldCategory.ADDRESS -> DataCategory.ADDRESS
                FieldCategory.FINANCIAL -> DataCategory.FINANCIAL
                FieldCategory.MEDICAL -> DataCategory.MEDICAL
                FieldCategory.OTHER -> DataCategory.OTHER
            }
            val dataType = when (customField.fieldType) {
                FieldType.PASSWORD -> DataType.MINOR_SECRET
                FieldType.NOTE -> DataType.PRIVATE
                else -> DataType.PRIVATE
            }
            items.add(PersonalDataItem(
                id = "custom-${customField.id}",
                name = customField.name,
                type = dataType,
                value = customField.value,
                category = category,
                isSystemField = false,
                createdAt = Instant.ofEpochMilli(customField.createdAt),
                updatedAt = Instant.ofEpochMilli(customField.updatedAt)
            ))
        }

        return items
    }
}
