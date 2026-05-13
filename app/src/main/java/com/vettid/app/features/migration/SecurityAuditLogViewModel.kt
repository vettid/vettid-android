package com.vettid.app.features.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.AuditLogEntry
import com.vettid.app.core.nats.MigrationClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Filter state for the audit log surface. event_types is a set of
 * raw vault event types (e.g. "identity.key.used"); the UI maps
 * curated categories to one or more of these so a user-friendly
 * "Identity verification" chip filters to both `connection.verified`
 * and `connection.verify.denied`. Date range is a pair of Unix-second
 * bounds, both optional.
 */
data class SecurityAuditFilters(
    val selectedCategories: Set<String> = emptySet(),
    val startSeconds: Long? = null,
    val endSeconds: Long? = null,
) {
    val hasActive: Boolean
        get() = selectedCategories.isNotEmpty() || startSeconds != null || endSeconds != null
}

/**
 * Curated filter categories shown as chips. Each maps to one or more
 * vault event_type strings.
 */
data class SecurityAuditCategory(
    val id: String,
    val label: String,
    val types: List<String>,
)

val SECURITY_AUDIT_CATEGORIES = listOf(
    SecurityAuditCategory("verify", "Identity verifications", listOf("connection.verified", "connection.verify.denied")),
    SecurityAuditCategory("idkey", "Identity-key signings", listOf("identity.key.used")),
    SecurityAuditCategory("critical", "Critical-secret use", listOf("critical_secret.used")),
    SecurityAuditCategory("migration", "Vault migrations", listOf("migration_sealed_material", "migration_verified", "migration_old_version_deleted")),
    SecurityAuditCategory("auth", "Auth attempts", listOf("auth.attempt_failed", "auth.success")),
    SecurityAuditCategory("alert", "Security alerts", listOf("security.alert")),
)

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

    private val _filters = MutableStateFlow(SecurityAuditFilters())
    val filters: StateFlow<SecurityAuditFilters> = _filters.asStateFlow()

    init {
        loadAuditLog()
    }

    fun loadAuditLog() {
        val f = _filters.value
        val categoryTypes = f.selectedCategories
            .mapNotNull { id -> SECURITY_AUDIT_CATEGORIES.firstOrNull { it.id == id }?.types }
            .flatten()
            .takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            _state.value = SecurityAuditLogState.Loading
            migrationClient.getAuditLog(
                limit = 100,
                eventTypes = categoryTypes,
                startTime = f.startSeconds,
                endTime = f.endSeconds,
            )
                .onSuccess { page ->
                    _state.value = if (page.entries.isEmpty()) {
                        SecurityAuditLogState.Empty
                    } else {
                        SecurityAuditLogState.Success(page.entries, page.chainStatus)
                    }
                }
                .onFailure { error ->
                    _state.value = SecurityAuditLogState.Error(
                        message = error.message ?: "Failed to load audit log"
                    )
                }
        }
    }

    fun toggleCategory(id: String) {
        _filters.update { f ->
            val sel = if (id in f.selectedCategories) f.selectedCategories - id else f.selectedCategories + id
            f.copy(selectedCategories = sel)
        }
        loadAuditLog()
    }

    fun setDateRange(startSeconds: Long?, endSeconds: Long?) {
        _filters.update { it.copy(startSeconds = startSeconds, endSeconds = endSeconds) }
        loadAuditLog()
    }

    fun clearFilters() {
        _filters.value = SecurityAuditFilters()
        loadAuditLog()
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
    data class Success(
        val entries: List<AuditLogEntry>,
        val chainStatus: com.vettid.app.core.audit.AuditChainVerifier.ChainStatus =
            com.vettid.app.core.audit.AuditChainVerifier.ChainStatus.Empty,
    ) : SecurityAuditLogState()
    data class Error(val message: String) : SecurityAuditLogState()
}
