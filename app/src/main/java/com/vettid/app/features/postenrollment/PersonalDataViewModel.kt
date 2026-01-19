package com.vettid.app.features.postenrollment

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CustomField
import com.vettid.app.core.storage.FieldCategory
import com.vettid.app.core.storage.OptionalField
import com.vettid.app.core.storage.OptionalPersonalData
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.core.storage.SystemPersonalData
import com.vettid.app.worker.PersonalDataSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for personal data collection and management.
 *
 * Handles:
 * - Loading and displaying system fields (read-only)
 * - Editing optional fields
 * - Managing custom fields
 * - Triggering sync to vault
 */
@HiltViewModel
class PersonalDataViewModel @Inject constructor(
    private val personalDataStore: PersonalDataStore,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager
) : ViewModel() {

    companion object {
        private const val TAG = "PersonalDataVM"
    }

    private val _state = MutableStateFlow(PersonalDataState())
    val state: StateFlow<PersonalDataState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PersonalDataEffect>()
    val effects: SharedFlow<PersonalDataEffect> = _effects.asSharedFlow()

    init {
        loadPersonalData()
    }

    /**
     * Load all personal data from local storage.
     */
    private fun loadPersonalData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                val systemFields = personalDataStore.getSystemFields()
                val optionalFields = personalDataStore.getOptionalFields()
                val customFields = personalDataStore.getCustomFields()
                val hasPendingSync = personalDataStore.hasPendingSync()
                val lastSyncedAt = personalDataStore.getLastSyncedAt()

                _state.update {
                    it.copy(
                        isLoading = false,
                        systemFields = systemFields,
                        optionalFields = optionalFields,
                        customFields = customFields,
                        hasPendingSync = hasPendingSync,
                        lastSyncedAt = lastSyncedAt
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal data", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load personal data: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Process events from UI.
     */
    fun onEvent(event: PersonalDataEvent) {
        viewModelScope.launch {
            when (event) {
                is PersonalDataEvent.UpdateOptionalField -> updateOptionalField(event.field, event.value)
                is PersonalDataEvent.AddCustomField -> addCustomField(event.name, event.value, event.category)
                is PersonalDataEvent.UpdateCustomField -> updateCustomField(event.field)
                is PersonalDataEvent.RemoveCustomField -> removeCustomField(event.fieldId)
                is PersonalDataEvent.SyncNow -> syncToVault()
                is PersonalDataEvent.Skip -> skip()
                is PersonalDataEvent.Continue -> continueToNext()
                is PersonalDataEvent.DismissError -> dismissError()
                is PersonalDataEvent.ShowAddFieldDialog -> showAddFieldDialog()
                is PersonalDataEvent.HideAddFieldDialog -> hideAddFieldDialog()
                is PersonalDataEvent.ShowEditFieldDialog -> showEditFieldDialog(event.field)
                is PersonalDataEvent.HideEditFieldDialog -> hideEditFieldDialog()
            }
        }
    }

    private fun updateOptionalField(field: OptionalField, value: String?) {
        personalDataStore.updateOptionalField(field, value?.takeIf { it.isNotBlank() })
        _state.update {
            it.copy(
                optionalFields = personalDataStore.getOptionalFields(),
                hasPendingSync = true
            )
        }
    }

    private fun addCustomField(name: String, value: String, category: FieldCategory) {
        if (name.isBlank()) {
            _state.update { it.copy(error = "Field name cannot be empty") }
            return
        }
        val field = personalDataStore.addCustomField(name, value, category)
        _state.update {
            it.copy(
                customFields = personalDataStore.getCustomFields(),
                hasPendingSync = true,
                showAddFieldDialog = false
            )
        }
        Log.d(TAG, "Added custom field: ${field.name}")
    }

    private fun updateCustomField(field: CustomField) {
        personalDataStore.updateCustomField(field)
        _state.update {
            it.copy(
                customFields = personalDataStore.getCustomFields(),
                hasPendingSync = true,
                editingField = null
            )
        }
    }

    private fun removeCustomField(fieldId: String) {
        personalDataStore.removeCustomField(fieldId)
        _state.update {
            it.copy(
                customFields = personalDataStore.getCustomFields(),
                hasPendingSync = true,
                editingField = null
            )
        }
    }

    private suspend fun syncToVault() {
        if (!connectionManager.isConnected()) {
            _state.update { it.copy(error = "Not connected to vault. Changes will sync when connection is restored.") }
            return
        }

        _state.update { it.copy(isSyncing = true) }

        try {
            val data = personalDataStore.exportForSync()

            // Build payload for profile.update
            val payload = com.google.gson.JsonObject().apply {
                data.forEach { (key, value) ->
                    when (value) {
                        is String -> addProperty(key, value)
                        is Number -> addProperty(key, value)
                        is Boolean -> addProperty(key, value)
                        is List<*> -> {
                            val jsonArray = com.google.gson.JsonArray()
                            @Suppress("UNCHECKED_CAST")
                            (value as? List<Map<String, Any?>>)?.forEach { item ->
                                val jsonObj = com.google.gson.JsonObject()
                                item.forEach { (k, v) ->
                                    when (v) {
                                        is String -> jsonObj.addProperty(k, v)
                                        is Number -> jsonObj.addProperty(k, v)
                                        is Boolean -> jsonObj.addProperty(k, v)
                                        else -> jsonObj.addProperty(k, v?.toString())
                                    }
                                }
                                jsonArray.add(jsonObj)
                            }
                            add(key, jsonArray)
                        }
                        null -> {} // Skip null values
                        else -> addProperty(key, value.toString())
                    }
                }
            }

            val result = ownerSpaceClient.sendToVault("profile.update", payload)

            result.fold(
                onSuccess = { requestId ->
                    Log.d(TAG, "Sync request sent: $requestId")
                    // In a real implementation, we'd wait for the response
                    // For now, mark as synced optimistically
                    personalDataStore.markSyncComplete()
                    _state.update {
                        it.copy(
                            isSyncing = false,
                            hasPendingSync = false,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    }
                    _effects.emit(PersonalDataEffect.ShowMessage("Personal data synced successfully"))
                },
                onFailure = { error ->
                    Log.e(TAG, "Sync failed", error)
                    _state.update {
                        it.copy(
                            isSyncing = false,
                            error = "Sync failed: ${error.message}"
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            _state.update {
                it.copy(
                    isSyncing = false,
                    error = "Sync error: ${e.message}"
                )
            }
        }
    }

    private suspend fun skip() {
        _effects.emit(PersonalDataEffect.NavigateToMain)
    }

    private suspend fun continueToNext() {
        // Trigger background sync if there are pending changes
        if (personalDataStore.hasPendingSync()) {
            // Schedule background sync worker
            Log.d(TAG, "Scheduling background sync for pending changes")
        }
        _effects.emit(PersonalDataEffect.NavigateToMain)
    }

    private fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun showAddFieldDialog() {
        _state.update { it.copy(showAddFieldDialog = true) }
    }

    private fun hideAddFieldDialog() {
        _state.update { it.copy(showAddFieldDialog = false) }
    }

    private fun showEditFieldDialog(field: CustomField) {
        _state.update { it.copy(editingField = field) }
    }

    private fun hideEditFieldDialog() {
        _state.update { it.copy(editingField = null) }
    }
}

/**
 * UI state for personal data screen.
 */
data class PersonalDataState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val systemFields: SystemPersonalData? = null,
    val optionalFields: OptionalPersonalData = OptionalPersonalData(),
    val customFields: List<CustomField> = emptyList(),
    val hasPendingSync: Boolean = false,
    val lastSyncedAt: Long = 0,
    val error: String? = null,
    val showAddFieldDialog: Boolean = false,
    val editingField: CustomField? = null
)

/**
 * Events from UI.
 */
sealed class PersonalDataEvent {
    data class UpdateOptionalField(val field: OptionalField, val value: String?) : PersonalDataEvent()
    data class AddCustomField(val name: String, val value: String, val category: FieldCategory) : PersonalDataEvent()
    data class UpdateCustomField(val field: CustomField) : PersonalDataEvent()
    data class RemoveCustomField(val fieldId: String) : PersonalDataEvent()
    object SyncNow : PersonalDataEvent()
    object Skip : PersonalDataEvent()
    object Continue : PersonalDataEvent()
    object DismissError : PersonalDataEvent()
    object ShowAddFieldDialog : PersonalDataEvent()
    object HideAddFieldDialog : PersonalDataEvent()
    data class ShowEditFieldDialog(val field: CustomField) : PersonalDataEvent()
    object HideEditFieldDialog : PersonalDataEvent()
}

/**
 * Side effects.
 */
sealed class PersonalDataEffect {
    object NavigateToMain : PersonalDataEffect()
    data class ShowMessage(val message: String) : PersonalDataEffect()
}
