package com.vettid.app.features.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.AuditLogEntry
import com.vettid.app.core.nats.MigrationClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Security Audit Log screen.
 *
 * Loads and displays security-related events including:
 * - Enclave migrations
 * - Emergency recoveries
 * - Other security events
 */
@HiltViewModel
class SecurityAuditLogViewModel @Inject constructor(
    private val migrationClient: MigrationClient
) : ViewModel() {

    private val _state = MutableStateFlow<SecurityAuditLogState>(SecurityAuditLogState.Loading)
    val state: StateFlow<SecurityAuditLogState> = _state.asStateFlow()

    init {
        loadAuditLog()
    }

    fun loadAuditLog() {
        viewModelScope.launch {
            _state.value = SecurityAuditLogState.Loading

            migrationClient.getAuditLog(limit = 100)
                .onSuccess { entries ->
                    _state.value = if (entries.isEmpty()) {
                        SecurityAuditLogState.Empty
                    } else {
                        SecurityAuditLogState.Success(entries)
                    }
                }
                .onFailure { error ->
                    _state.value = SecurityAuditLogState.Error(
                        message = error.message ?: "Failed to load audit log"
                    )
                }
        }
    }

    fun refresh() {
        loadAuditLog()
    }
}

/**
 * UI state for the Security Audit Log screen.
 */
sealed class SecurityAuditLogState {
    object Loading : SecurityAuditLogState()
    object Empty : SecurityAuditLogState()
    data class Success(val entries: List<AuditLogEntry>) : SecurityAuditLogState()
    data class Error(val message: String) : SecurityAuditLogState()
}
