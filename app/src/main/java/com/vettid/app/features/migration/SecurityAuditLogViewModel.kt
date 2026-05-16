package com.vettid.app.features.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.AuditLogEntry
import com.vettid.app.core.nats.MigrationClient
import com.vettid.app.features.feed.FeedRepository
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
 * bounds, both optional. Connection filter is client-side because the
 * vault's audit.query accepts only a single source_id at a time.
 */
data class SecurityAuditFilters(
    val selectedCategories: Set<String> = emptySet(),
    val selectedConnectionIds: Set<String> = emptySet(),
    val startSeconds: Long? = null,
    val endSeconds: Long? = null,
    val datePresetId: String = "all",
) {
    val hasActive: Boolean
        get() = selectedCategories.isNotEmpty() ||
                selectedConnectionIds.isNotEmpty() ||
                datePresetId != "all"
}

/**
 * Quick-pick date ranges shown as chips. "all" disables date
 * filtering; the others resolve to (startSeconds, endSeconds) when the
 * user picks them. "custom" opens the date-range picker dialog.
 */
data class SecurityAuditDatePreset(
    val id: String,
    val label: String,
    val rangeSeconds: (nowSeconds: Long) -> Pair<Long?, Long?>,
)

val SECURITY_AUDIT_DATE_PRESETS = listOf(
    SecurityAuditDatePreset("today", "Today") { now -> startOfTodaySeconds(now) to null },
    SecurityAuditDatePreset("7d", "Last 7 days") { now -> (now - 7L * 24 * 3600) to null },
    SecurityAuditDatePreset("30d", "Last 30 days") { now -> (now - 30L * 24 * 3600) to null },
    SecurityAuditDatePreset("all", "All time") { _ -> null to null },
    SecurityAuditDatePreset("custom", "Custom…") { _ -> null to null },
)

private fun startOfTodaySeconds(nowSeconds: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = nowSeconds * 1000L
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis / 1000L
}

/**
 * Lightweight connection projection for the connection-filter
 * dropdown. Display label prefers full name → first → connection alias.
 */
data class AuditConnectionOption(val connectionId: String, val label: String)

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
    private val migrationClient: MigrationClient,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SecurityAuditLogState>(SecurityAuditLogState.Loading)
    val state: StateFlow<SecurityAuditLogState> = _state.asStateFlow()

    private val _filters = MutableStateFlow(SecurityAuditFilters())
    val filters: StateFlow<SecurityAuditFilters> = _filters.asStateFlow()

    private val _connections = MutableStateFlow<List<AuditConnectionOption>>(emptyList())
    val connections: StateFlow<List<AuditConnectionOption>> = _connections.asStateFlow()

    init {
        // Connections list comes from FeedRepository's already-cached
        // connection list — same source MigrationClient uses to resolve
        // peer names for the audit log rows themselves. No round-trip.
        _connections.value = feedRepository.getCachedConnections()
            .map { c ->
                val first = c.peerProfile?.firstName.orEmpty().trim()
                val last = c.peerProfile?.lastName.orEmpty().trim()
                val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
                AuditConnectionOption(
                    connectionId = c.connectionId,
                    label = full.ifEmpty { c.label.trim().ifEmpty { c.connectionId } },
                )
            }
            .sortedBy { it.label.lowercase() }
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
                    // Connection filter is applied client-side because the
                    // vault's audit.query takes only a single source_id.
                    // Entries without a connection (system events) stay
                    // visible only when no connection filter is active.
                    val filtered = if (f.selectedConnectionIds.isEmpty()) {
                        page.entries
                    } else {
                        page.entries.filter { it.connectionId != null && it.connectionId in f.selectedConnectionIds }
                    }
                    _state.value = if (filtered.isEmpty()) {
                        SecurityAuditLogState.Empty
                    } else {
                        SecurityAuditLogState.Success(
                            entries = filtered,
                            chainStatus = page.chainStatus,
                            auditPubB64 = page.auditPubB64,
                            bindingSigB64 = page.bindingSigB64,
                            identityPubB64 = page.identityPubB64,
                        )
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

    fun toggleConnection(id: String) {
        _filters.update { f ->
            val sel = if (id in f.selectedConnectionIds) f.selectedConnectionIds - id else f.selectedConnectionIds + id
            f.copy(selectedConnectionIds = sel)
        }
        loadAuditLog()
    }

    /** Apply a named preset. "custom" leaves bounds alone — caller follows up with setCustomDateRange. */
    fun applyDatePreset(id: String) {
        val now = System.currentTimeMillis() / 1000L
        val preset = SECURITY_AUDIT_DATE_PRESETS.firstOrNull { it.id == id } ?: return
        if (id == "custom") {
            _filters.update { it.copy(datePresetId = "custom") }
            return
        }
        val (start, end) = preset.rangeSeconds(now)
        _filters.update { it.copy(startSeconds = start, endSeconds = end, datePresetId = id) }
        loadAuditLog()
    }

    fun setCustomDateRange(startSeconds: Long?, endSeconds: Long?) {
        _filters.update { it.copy(startSeconds = startSeconds, endSeconds = endSeconds, datePresetId = "custom") }
        loadAuditLog()
    }

    fun clearFilters() {
        _filters.value = SecurityAuditFilters()
        loadAuditLog()
    }

    fun refresh() {
        loadAuditLog()
    }

    /**
     * Build the JSON payload for an export. Includes the full chain
     * (entries currently in view) plus a stamp of which filters
     * produced this view so a verifier downstream can re-derive the
     * scope. Anchor is omitted here because export is keyed on
     * already-verified state; the chain hashes inside each entry are
     * still self-checkable against the same audit_pub the app holds.
     */
    fun buildExportJson(): String {
        val s = _state.value as? SecurityAuditLogState.Success
        val entries = s?.entries.orEmpty()
        val f = _filters.value
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"_verification\": {\n")
        builder.append("    \"summary\": \"This export contains a tamper-evident audit chain. ")
        builder.append("Each entry's entry_sig is an Ed25519 signature over its entry_hash by audit_pub. ")
        builder.append("audit_pub itself is bound to your identity via binding_sig, which is an Ed25519 signature by identity_pub over the bytes \\\"vettid-audit-binding-v1\\\" concatenated with audit_pub. ")
        builder.append("Each entry's previous_hash chains it to the prior entry's entry_hash so reordering or deletion is detectable.\",\n")
        builder.append("    \"binding_domain\": \"vettid-audit-binding-v1\",\n")
        builder.append("    \"steps\": [\n")
        builder.append("      \"1. Decode audit_pub, binding_sig, identity_pub from base64 (standard alphabet, not URL-safe).\",\n")
        builder.append("      \"2. Verify Ed25519(identity_pub, \\\"vettid-audit-binding-v1\\\" || audit_pub, binding_sig). If this fails, the audit_pub is not bound to identity_pub and the chain is untrusted.\",\n")
        builder.append("      \"3. For each entry in chronological order (oldest first): confirm previous_hash equals the prior entry's entry_hash (or empty/genesis for the oldest). Mismatch means the chain was tampered.\",\n")
        builder.append("      \"4. For each entry with a non-empty entry_sig: verify Ed25519(audit_pub, entry_hash.utf8_bytes, hex_decode(entry_sig)). Entries with empty entry_sig were written before the audit key was loaded (pre-PIN-unlock) and are intentionally unsigned.\",\n")
        builder.append("      \"5. The 'verification' field on each entry shows how the in-app verifier classified it at export time (Verified / Unsigned / Tampered).\"\n")
        builder.append("    ]\n")
        builder.append("  },\n")
        builder.append("  \"exported_at\": ").append(System.currentTimeMillis() / 1000L).append(",\n")
        builder.append("  \"audit_pub\": ").append(s?.auditPubB64?.let { "\"$it\"" } ?: "null").append(",\n")
        builder.append("  \"binding_sig\": ").append(s?.bindingSigB64?.let { "\"$it\"" } ?: "null").append(",\n")
        builder.append("  \"identity_pub\": ").append(s?.identityPubB64?.let { "\"$it\"" } ?: "null").append(",\n")
        builder.append("  \"filter\": {\n")
        builder.append("    \"categories\": ").append(jsonStringArray(f.selectedCategories)).append(",\n")
        builder.append("    \"connection_ids\": ").append(jsonStringArray(f.selectedConnectionIds)).append(",\n")
        builder.append("    \"date_preset\": \"").append(f.datePresetId).append("\",\n")
        builder.append("    \"start_seconds\": ").append(f.startSeconds ?: "null").append(",\n")
        builder.append("    \"end_seconds\": ").append(f.endSeconds ?: "null").append("\n")
        builder.append("  },\n")
        builder.append("  \"chain_status\": \"").append((s as Any?)?.javaClass?.simpleName ?: "Empty").append("\",\n")
        builder.append("  \"entry_count\": ").append(entries.size).append(",\n")
        builder.append("  \"entries\": [\n")
        entries.forEachIndexed { i, e ->
            builder.append("    {")
            builder.append("\"id\": \"").append(escapeJson(e.id)).append("\", ")
            builder.append("\"type\": \"").append(escapeJson(e.type)).append("\", ")
            builder.append("\"title\": \"").append(escapeJson(e.title)).append("\", ")
            builder.append("\"description\": \"").append(escapeJson(e.description)).append("\", ")
            builder.append("\"timestamp_ms\": ").append(e.timestamp).append(", ")
            builder.append("\"connection_id\": ").append(e.connectionId?.let { "\"${escapeJson(it)}\"" } ?: "null").append(", ")
            builder.append("\"peer_name\": ").append(e.peerName?.let { "\"${escapeJson(it)}\"" } ?: "null").append(", ")
            builder.append("\"entry_hash\": ").append(e.entryHash?.let { "\"$it\"" } ?: "null").append(", ")
            builder.append("\"previous_hash\": ").append(e.previousHash?.let { "\"$it\"" } ?: "null").append(", ")
            builder.append("\"entry_sig\": ").append(e.entrySig?.let { "\"$it\"" } ?: "null").append(", ")
            builder.append("\"verification\": \"").append(e.verification.name).append("\"")
            builder.append("}").append(if (i < entries.size - 1) "," else "").append("\n")
        }
        builder.append("  ]\n")
        builder.append("}\n")
        return builder.toString()
    }

    private fun jsonStringArray(items: Iterable<String>): String =
        items.joinToString(prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
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
        // Anchor fields carried through from the audit.query response so
        // the export can be re-verified offline against the user's
        // identity public key.
        val auditPubB64: String? = null,
        val bindingSigB64: String? = null,
        val identityPubB64: String? = null,
    ) : SecurityAuditLogState()
    data class Error(val message: String) : SecurityAuditLogState()
}
