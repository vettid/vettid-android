package com.vettid.app.features.personaldata

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

private const val TAG = "PersonalDataViewModel"

@HiltViewModel
class PersonalDataViewModel @Inject constructor(
    private val credentialStore: CredentialStore
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
                // Initialize with system fields if empty
                if (dataItems.isEmpty()) {
                    dataItems.addAll(generateSystemFields())
                }

                if (dataItems.isEmpty()) {
                    _state.value = PersonalDataState.Empty
                } else {
                    _state.value = PersonalDataState.Loaded(items = dataItems.toList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal data", e)
                _state.value = PersonalDataState.Error(e.message ?: "Failed to load data")
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
                val now = Instant.now()
                if (current.id != null) {
                    // Update existing item
                    val index = dataItems.indexOfFirst { it.id == current.id }
                    if (index >= 0) {
                        val existing = dataItems[index]
                        if (!existing.isSystemField) {
                            dataItems[index] = existing.copy(
                                name = current.name,
                                value = current.value,
                                type = current.type,
                                category = current.category,
                                updatedAt = now
                            )
                        }
                    }
                    _effects.emit(PersonalDataEffect.ShowSuccess("Data updated"))
                } else {
                    // Create new item
                    val newItem = PersonalDataItem(
                        id = UUID.randomUUID().toString(),
                        name = current.name,
                        value = current.value,
                        type = current.type,
                        category = current.category,
                        isSystemField = false,
                        createdAt = now,
                        updatedAt = now
                    )
                    dataItems.add(newItem)
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

                dataItems.removeAll { it.id == itemId }
                loadPersonalData()
                _effects.emit(PersonalDataEffect.ShowSuccess("Data deleted"))
            } catch (e: Exception) {
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
     * Generate system fields from membership data.
     * In production, these would be populated from the enrollment response.
     */
    private fun generateSystemFields(): List<PersonalDataItem> {
        val now = Instant.now()
        return listOf(
            PersonalDataItem(
                id = "system-first-name",
                name = "First Name",
                type = DataType.PUBLIC,
                value = "Al",  // Would come from membership
                category = DataCategory.IDENTITY,
                isSystemField = true,
                createdAt = now,
                updatedAt = now
            ),
            PersonalDataItem(
                id = "system-last-name",
                name = "Last Name",
                type = DataType.PUBLIC,
                value = "Liebl",  // Would come from membership
                category = DataCategory.IDENTITY,
                isSystemField = true,
                createdAt = now,
                updatedAt = now
            ),
            PersonalDataItem(
                id = "system-email",
                name = "Email",
                type = DataType.PUBLIC,
                value = "al@liebl.me",  // Would come from membership
                category = DataCategory.CONTACT,
                isSystemField = true,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
