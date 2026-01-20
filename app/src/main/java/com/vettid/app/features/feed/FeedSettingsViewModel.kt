package com.vettid.app.features.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FeedSettingsViewModel"

/**
 * ViewModel for feed settings screen.
 *
 * Allows users to configure:
 * - Feed retention days
 * - Audit retention days
 * - Archive behavior
 * - Auto-archive toggle
 */
@HiltViewModel
class FeedSettingsViewModel @Inject constructor(
    private val feedClient: FeedClient
) : ViewModel() {

    private val _state = MutableStateFlow<FeedSettingsState>(FeedSettingsState.Loading)
    val state: StateFlow<FeedSettingsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FeedSettingsEffect>()
    val effects: SharedFlow<FeedSettingsEffect> = _effects.asSharedFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // Local editable state
    private val _editableSettings = MutableStateFlow<EditableFeedSettings?>(null)
    val editableSettings: StateFlow<EditableFeedSettings?> = _editableSettings.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _state.value = FeedSettingsState.Loading

            feedClient.getSettings()
                .onSuccess { settings ->
                    _editableSettings.value = EditableFeedSettings(
                        feedRetentionDays = settings.feedRetentionDays,
                        auditRetentionDays = settings.auditRetentionDays,
                        archiveBehavior = settings.archiveBehavior,
                        autoArchiveEnabled = settings.autoArchiveEnabled
                    )
                    _state.value = FeedSettingsState.Success(settings)
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load settings", error)
                    _state.value = FeedSettingsState.Error(error.message ?: "Failed to load settings")
                }
        }
    }

    fun updateFeedRetentionDays(days: Int) {
        _editableSettings.value = _editableSettings.value?.copy(feedRetentionDays = days)
    }

    fun updateAuditRetentionDays(days: Int) {
        _editableSettings.value = _editableSettings.value?.copy(auditRetentionDays = days)
    }

    fun updateArchiveBehavior(behavior: String) {
        _editableSettings.value = _editableSettings.value?.copy(archiveBehavior = behavior)
    }

    fun updateAutoArchiveEnabled(enabled: Boolean) {
        _editableSettings.value = _editableSettings.value?.copy(autoArchiveEnabled = enabled)
    }

    fun saveSettings() {
        val editable = _editableSettings.value ?: return

        viewModelScope.launch {
            _isSaving.value = true

            val settings = FeedSettings(
                feedRetentionDays = editable.feedRetentionDays,
                auditRetentionDays = editable.auditRetentionDays,
                archiveBehavior = editable.archiveBehavior,
                autoArchiveEnabled = editable.autoArchiveEnabled
            )

            feedClient.updateSettings(settings)
                .onSuccess { updated ->
                    _state.value = FeedSettingsState.Success(updated)
                    _effects.emit(FeedSettingsEffect.SettingsSaved)
                    Log.i(TAG, "Settings saved successfully")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to save settings", error)
                    _effects.emit(FeedSettingsEffect.SaveFailed(error.message ?: "Failed to save"))
                }

            _isSaving.value = false
        }
    }

    fun hasUnsavedChanges(): Boolean {
        val current = (_state.value as? FeedSettingsState.Success)?.settings ?: return false
        val editable = _editableSettings.value ?: return false

        return current.feedRetentionDays != editable.feedRetentionDays ||
                current.auditRetentionDays != editable.auditRetentionDays ||
                current.archiveBehavior != editable.archiveBehavior ||
                current.autoArchiveEnabled != editable.autoArchiveEnabled
    }

    fun discardChanges() {
        val current = (_state.value as? FeedSettingsState.Success)?.settings ?: return
        _editableSettings.value = EditableFeedSettings(
            feedRetentionDays = current.feedRetentionDays,
            auditRetentionDays = current.auditRetentionDays,
            archiveBehavior = current.archiveBehavior,
            autoArchiveEnabled = current.autoArchiveEnabled
        )
    }

    companion object {
        // Predefined options for retention days
        val FEED_RETENTION_OPTIONS = listOf(7, 14, 30, 60, 90)
        val AUDIT_RETENTION_OPTIONS = listOf(30, 60, 90, 180, 365)

        // Archive behavior options
        val ARCHIVE_BEHAVIORS = listOf(
            ArchiveBehaviorOption("archive", "Archive", "Move old items to archive"),
            ArchiveBehaviorOption("delete", "Delete", "Permanently remove old items")
        )
    }
}

/**
 * UI state for feed settings screen.
 */
sealed class FeedSettingsState {
    object Loading : FeedSettingsState()
    data class Success(val settings: FeedSettings) : FeedSettingsState()
    data class Error(val message: String) : FeedSettingsState()
}

/**
 * Effects emitted by the feed settings view model.
 */
sealed class FeedSettingsEffect {
    object SettingsSaved : FeedSettingsEffect()
    data class SaveFailed(val message: String) : FeedSettingsEffect()
}

/**
 * Editable copy of feed settings.
 */
data class EditableFeedSettings(
    val feedRetentionDays: Int,
    val auditRetentionDays: Int,
    val archiveBehavior: String,
    val autoArchiveEnabled: Boolean
)

/**
 * Archive behavior option for display.
 */
data class ArchiveBehaviorOption(
    val value: String,
    val label: String,
    val description: String
)
