package com.vettid.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.Backup
import com.vettid.app.core.network.BackupApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the backup list screen.
 */
sealed class BackupListState {
    data object Loading : BackupListState()
    data object Empty : BackupListState()
    data class Loaded(val backups: List<Backup>) : BackupListState()
    data class Error(val message: String) : BackupListState()
}

/**
 * ViewModel for managing backup list.
 */
@HiltViewModel
class BackupListViewModel @Inject constructor(
    private val backupApiClient: BackupApiClient
) : ViewModel() {

    private val _state = MutableStateFlow<BackupListState>(BackupListState.Loading)
    val state: StateFlow<BackupListState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isCreatingBackup = MutableStateFlow(false)
    val isCreatingBackup: StateFlow<Boolean> = _isCreatingBackup.asStateFlow()

    init {
        loadBackups()
    }

    /**
     * Load the list of backups.
     */
    fun loadBackups() {
        viewModelScope.launch {
            if (_state.value !is BackupListState.Loading) {
                _isRefreshing.value = true
            }

            backupApiClient.listBackups().fold(
                onSuccess = { response ->
                    _state.value = if (response.backups.isEmpty()) {
                        BackupListState.Empty
                    } else {
                        BackupListState.Loaded(response.backups)
                    }
                },
                onFailure = { error ->
                    _state.value = BackupListState.Error(error.message ?: "Failed to load backups")
                }
            )

            _isRefreshing.value = false
        }
    }

    /**
     * Refresh the backup list.
     */
    fun refresh() {
        loadBackups()
    }

    /**
     * Trigger a new manual backup.
     */
    fun createBackup(includeMessages: Boolean = true) {
        viewModelScope.launch {
            _isCreatingBackup.value = true

            backupApiClient.triggerBackup(includeMessages).fold(
                onSuccess = {
                    // Reload the list to show the new backup
                    loadBackups()
                },
                onFailure = { error ->
                    // Keep current state but could show a snackbar
                    _state.value = BackupListState.Error(error.message ?: "Failed to create backup")
                }
            )

            _isCreatingBackup.value = false
        }
    }

    /**
     * Delete a backup.
     */
    fun deleteBackup(backupId: String) {
        viewModelScope.launch {
            backupApiClient.deleteBackup(backupId).fold(
                onSuccess = {
                    // Remove from local list
                    val currentState = _state.value
                    if (currentState is BackupListState.Loaded) {
                        val updatedList = currentState.backups.filter { it.backupId != backupId }
                        _state.value = if (updatedList.isEmpty()) {
                            BackupListState.Empty
                        } else {
                            BackupListState.Loaded(updatedList)
                        }
                    }
                },
                onFailure = { error ->
                    _state.value = BackupListState.Error(error.message ?: "Failed to delete backup")
                }
            )
        }
    }
}
