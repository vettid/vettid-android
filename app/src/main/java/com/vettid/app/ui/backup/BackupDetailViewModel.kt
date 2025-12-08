package com.vettid.app.ui.backup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.Backup
import com.vettid.app.core.network.BackupApiClient
import com.vettid.app.core.network.RestoreResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the backup detail screen.
 */
sealed class BackupDetailState {
    data object Loading : BackupDetailState()
    data class Loaded(val backup: Backup) : BackupDetailState()
    data class Restoring(val backup: Backup) : BackupDetailState()
    data class RestoreComplete(val result: RestoreResult) : BackupDetailState()
    data class Error(val message: String) : BackupDetailState()
}

/**
 * ViewModel for backup details and restore operations.
 */
@HiltViewModel
class BackupDetailViewModel @Inject constructor(
    private val backupApiClient: BackupApiClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val backupId: String = savedStateHandle["backupId"]
        ?: throw IllegalArgumentException("backupId is required")

    private val _state = MutableStateFlow<BackupDetailState>(BackupDetailState.Loading)
    val state: StateFlow<BackupDetailState> = _state.asStateFlow()

    private val _deleteSuccess = MutableStateFlow(false)
    val deleteSuccess: StateFlow<Boolean> = _deleteSuccess.asStateFlow()

    init {
        loadBackup()
    }

    /**
     * Load backup details.
     */
    fun loadBackup() {
        viewModelScope.launch {
            _state.value = BackupDetailState.Loading

            backupApiClient.getBackup(backupId).fold(
                onSuccess = { backup ->
                    _state.value = BackupDetailState.Loaded(backup)
                },
                onFailure = { error ->
                    _state.value = BackupDetailState.Error(error.message ?: "Failed to load backup")
                }
            )
        }
    }

    /**
     * Restore from this backup.
     */
    fun restoreBackup(overwriteConflicts: Boolean = false) {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is BackupDetailState.Loaded) {
                _state.value = BackupDetailState.Restoring(currentState.backup)

                backupApiClient.restoreBackup(backupId, overwriteConflicts).fold(
                    onSuccess = { result ->
                        _state.value = BackupDetailState.RestoreComplete(result)
                    },
                    onFailure = { error ->
                        _state.value = BackupDetailState.Error(error.message ?: "Failed to restore backup")
                    }
                )
            }
        }
    }

    /**
     * Delete this backup.
     */
    fun deleteBackup() {
        viewModelScope.launch {
            backupApiClient.deleteBackup(backupId).fold(
                onSuccess = {
                    _deleteSuccess.value = true
                },
                onFailure = { error ->
                    _state.value = BackupDetailState.Error(error.message ?: "Failed to delete backup")
                }
            )
        }
    }

    /**
     * Reset restore state to go back to loaded state.
     */
    fun resetRestoreState() {
        loadBackup()
    }
}
