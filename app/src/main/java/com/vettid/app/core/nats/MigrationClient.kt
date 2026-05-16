package com.vettid.app.core.nats

import android.util.Log
import com.google.gson.JsonObject
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.features.feed.FeedRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for enclave migration operations.
 *
 * Handles:
 * - Checking migration status on app startup
 * - Acknowledging migration notifications
 *
 * Migration is driven by the PIN-unlock-coupled flow in
 * PinUnlockViewModel; this client mostly observes status + records
 * acknowledgements. The emergency-recovery surface that used to live
 * here was removed 2026-05-11 per architect §6 (vault never emitted
 * the status; whole branch was dead UI).
 */
@Singleton
class MigrationClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val feedRepository: FeedRepository,
) {
    companion object {
        private const val TAG = "MigrationClient"
        private const val TIMEOUT_MS = 10_000L
        private val SECURITY_AUDIT_TYPES = setOf(
            "identity.key.used",
            "critical_secret.used",
            "connection.verified",
            "connection.verify.denied",
            "security.alert",
            "auth.attempt_failed",
            "auth.success",
        )
    }

    private val _migrationEvents = MutableSharedFlow<MigrationEvent>(extraBufferCapacity = 8)
    val migrationEvents: SharedFlow<MigrationEvent> = _migrationEvents.asSharedFlow()

    /**
     * Check migration status on app startup.
     *
     * @return MigrationStatus indicating current state
     */
    suspend fun checkMigrationStatus(): MigrationStatus {
        Log.d(TAG, "Checking migration status")

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.status", JsonObject(), TIMEOUT_MS
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    parseMigrationStatus(response.result)
                } else {
                    Log.w(TAG, "Migration status check failed: ${response.error}")
                    MigrationStatus.Unknown
                }
            }
            is VaultResponse.Error -> {
                Log.d(TAG, "Migration status: ${response.code}")
                MigrationStatus.None
            }
            else -> MigrationStatus.Unknown
        }
    }

    /**
     * Mark user as notified about migration completion.
     * Call this when user dismisses the migration notification banner.
     */
    suspend fun acknowledgeNotification(): Result<Unit> {
        Log.d(TAG, "Acknowledging migration notification")

        val payload = JsonObject().apply {
            addProperty("acknowledged", true)
            addProperty("acknowledged_at", System.currentTimeMillis())
        }

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.acknowledge", payload, TIMEOUT_MS
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success) {
                    Log.i(TAG, "Migration notification acknowledged")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.error ?: "Acknowledgment failed"))
                }
            }
            is VaultResponse.Error -> {
                Result.failure(Exception("${response.code}: ${response.message}"))
            }
            else -> {
                Result.failure(Exception("Timeout waiting for acknowledgment response"))
            }
        }
    }

    // performEmergencyRecovery was removed 2026-05-11. The vault-side
    // credential.emergency_recovery handler never existed; the call
    // would always 404. Per architect §6 decision: real recovery
    // today is decommission-vault.sh + re-enroll. A proper escape
    // hatch would need a recovery secret stamped at enrollment +
    // a new vault handler — design that as its own feature when
    // prioritized.

    /**
     * Get audit log entries for migrations.
     *
     * @param limit Maximum number of entries to return
     * @return List of audit log entries
     */
    suspend fun getAuditLog(
        limit: Int = 50,
        eventTypes: List<String>? = null,
        startTime: Long? = null,
        endTime: Long? = null,
    ): Result<AuditLogPage> {
        Log.d(TAG, "Fetching audit log")

        val payload = JsonObject().apply {
            addProperty("limit", limit)
            // event_types: Unix-second timestamps, no transformation
            // needed — vault QueryAuditEvents accepts both filters.
            if (!eventTypes.isNullOrEmpty()) {
                val arr = com.google.gson.JsonArray()
                eventTypes.forEach { arr.add(it) }
                add("event_types", arr)
            }
            if (startTime != null && startTime > 0L) {
                addProperty("start_time", startTime)
            }
            if (endTime != null && endTime > 0L) {
                addProperty("end_time", endTime)
            }
        }

        val response = ownerSpaceClient.sendAndAwaitResponse("audit.query", payload, TIMEOUT_MS)

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    Result.success(parseAuditLogPage(response.result))
                } else {
                    Result.failure(Exception(response.error ?: "Failed to fetch audit log"))
                }
            }
            is VaultResponse.Error -> {
                if (response.code == "TIMEOUT") {
                    Result.success(AuditLogPage(emptyList()))
                } else {
                    Result.failure(Exception("${response.code}: ${response.message}"))
                }
            }
            else -> {
                Result.failure(Exception("Timeout waiting for audit log response"))
            }
        }
    }

    /**
     * Check if a vault security update is available.
     * Sends credential.migration.config to the vault.
     *
     * @return MigrationConfig if update is available, null otherwise
     */
    suspend fun getMigrationConfig(): MigrationConfig? {
        Log.d(TAG, "Checking for migration config")

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.config", JsonObject(), TIMEOUT_MS
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    parseMigrationConfig(response.result)
                } else {
                    Log.w(TAG, "Migration config check failed: ${response.error}")
                    null
                }
            }
            else -> {
                Log.d(TAG, "Migration config: no update available")
                null
            }
        }
    }

    /**
     * Start the migration (re-seal vault for new enclave).
     * Called when user taps "Update Now".
     *
     * @return Result<String> with the migration version on success
     */
    suspend fun startMigration(): Result<String> {
        Log.i(TAG, "Starting vault migration (re-seal)")

        val response = ownerSpaceClient.sendAndAwaitResponse(
            "credential.migration.start", JsonObject(), 30_000L
        )

        return when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success) {
                    val version = response.result?.get("version")?.asString ?: ""
                    Log.i(TAG, "Migration completed successfully: $version")
                    _migrationEvents.tryEmit(MigrationEvent.MigrationCompleted(version))
                    Result.success(version)
                } else {
                    val error = response.error ?: "Migration failed"
                    Log.e(TAG, "Migration failed: $error")
                    _migrationEvents.tryEmit(MigrationEvent.MigrationFailed(error))
                    Result.failure(Exception(error))
                }
            }
            is VaultResponse.Error -> {
                val error = "${response.code}: ${response.message}"
                _migrationEvents.tryEmit(MigrationEvent.MigrationFailed(error))
                Result.failure(Exception(error))
            }
            else -> {
                val error = "Timeout waiting for migration response"
                _migrationEvents.tryEmit(MigrationEvent.MigrationFailed(error))
                Result.failure(Exception(error))
            }
        }
    }

    private fun parseMigrationConfig(json: JsonObject): MigrationConfig? {
        val available = json.get("available")?.asBoolean ?: false
        if (!available) return null

        // Vault's MigrationConfigResponse returns the new PCR0 as a flat
        // `new_pcr0` field. Also accept the nested `new_pcrs.pcr0` shape
        // used in the signed manifest for forward compat with older vault
        // builds that might send that form.
        val newPcr0 = json.get("new_pcr0")?.takeIf { !it.isJsonNull }?.asString
            ?: json.getAsJsonObject("new_pcrs")?.get("pcr0")?.asString

        return MigrationConfig(
            version = json.get("version")?.asString ?: "",
            summary = json.get("summary")?.asString ?: "",
            detailsUrl = json.get("details_url")?.asString,
            publishedAt = json.get("published_at")?.asString,
            mandatoryAfter = json.get("mandatory_after")?.asString,
            newPcr0 = newPcr0
        )
    }

    private fun parseMigrationStatus(json: JsonObject): MigrationStatus {
        val status = json.get("status")?.asString

        return when (status) {
            "none", null -> MigrationStatus.None
            "in_progress" -> MigrationStatus.InProgress(
                progress = json.get("progress")?.asFloat ?: 0f
            )
            "complete" -> MigrationStatus.Complete(
                migratedAt = json.get("migrated_at")?.asString,
                userNotified = json.get("user_notified")?.asBoolean ?: false,
                fromPcrVersion = json.get("from_pcr_version")?.asString,
                toPcrVersion = json.get("to_pcr_version")?.asString
            )
            else -> {
                Log.w(TAG, "Unknown migration status: $status")
                MigrationStatus.Unknown
            }
        }
    }

    /**
     * Parse audit.query response. The vault returns
     *   { "events": [{ event_id, event_type, title, created_at, metadata, ... }], ... }
     *
     * which is the same FeedEvent shape used everywhere else. The old
     * code looked for `entries` (never existed in the response) — so
     * this method silently returned an empty list for every call, which
     * was the root cause of the "no audit log entries" report.
     *
     * Filter client-side to security-relevant types so the screen
     * stays focused: identity-key signings, critical-secret use,
     * connection verify outcomes, migrations, auth events.
     */
    private fun parseAuditLogPage(json: JsonObject): AuditLogPage {
        // Verify against the *full* chain, then filter for display. The
        // chain's previous_hash linkage spans every event in the audit
        // store — connection.created, audit.binding, etc. — not just
        // the security-relevant subset shown on the screen. Filtering
        // before verification broke prev_hash continuity (a kept row's
        // previous_hash pointed at a filtered-out row's entry_hash) and
        // the verifier returned "chain integrity broken" even on an
        // intact chain. Surfaced 2026-05-16 on the post-Phase-1 test pass.
        val allEntries = parseAuditLogEntries(json)
        if (allEntries.isEmpty()) return AuditLogPage(emptyList())
        val verifier = com.vettid.app.core.audit.AuditChainVerifier()
        val (perRow, chainStatus) = verifier.verifyChain(
            rows = allEntries,
            auditPubB64 = json.get("audit_pub")?.asString,
            bindingSigB64 = json.get("binding_sig")?.asString,
            identityPub = json.get("identity_pub")?.asString?.let { decodeBase64Safely(it) },
            entryHashOf = { e -> Triple(e.entryHash, e.previousHash, e.entrySig) },
        )
        // Stamp each entry with its chain-verification state and return
        // the full set. The hardcoded isSecurityRelevantType whitelist
        // that used to filter here surprised users — with no UI filters
        // applied, the screen still hid 12 of 14 events. The audit log
        // now defaults to "show everything"; user-driven filters
        // (category chips, future connection/date filters) narrow from
        // there. 2026-05-16.
        val display = allEntries.mapIndexed { i, e ->
            e.copy(verification = perRow.getOrNull(i)?.state ?: com.vettid.app.core.audit.AuditChainVerifier.RowState.Unsigned)
        }
        return AuditLogPage(entries = display, chainStatus = chainStatus)
    }

    // See AuditChainVerifier.decodeB64Safe for the order rationale:
    // vault encodes with Go's base64.StdEncoding, so try standard first.
    // URL_SAFE first silently dropped `+`/`/` chars from the input.
    private fun decodeBase64Safely(s: String): ByteArray? = try {
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
            ?: android.util.Base64.decode(s, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    } catch (_: Exception) {
        try { android.util.Base64.decode(s, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE) } catch (_: Exception) { null }
    }

    private fun parseAuditLogEntries(json: JsonObject): List<AuditLogEntry> {
        val eventsArray = json.getAsJsonArray("events") ?: return emptyList()
        // Resolve connection_id → peer display name from the cached
        // connections store, so each row can tag "Identity verified —
        // Al Liebl" without a per-row vault round-trip. Built once per
        // parse so a long list doesn't hit the store O(n) times.
        val nameByConn: Map<String, String> = feedRepository.getCachedConnections()
            .associate { conn ->
                val first = conn.peerProfile?.firstName.orEmpty().trim()
                val last = conn.peerProfile?.lastName.orEmpty().trim()
                val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
                conn.connectionId to (full.ifEmpty { conn.label.trim() })
            }
        return eventsArray.mapNotNull { element ->
            try {
                val obj = element.asJsonObject
                val type = obj.get("event_type")?.asString ?: return@mapNotNull null
                // Don't filter by isSecurityRelevantType here — the chain
                // verifier needs every entry with a prev_hash link in
                // order so the chain validates. The display filter runs
                // in parseAuditLogPage after verification.
                val sourceId = obj.get("source_id")?.asString.orEmpty()
                val sourceType = obj.get("source_type")?.asString.orEmpty()
                val peerName = if (sourceType == "connection" && sourceId.isNotEmpty()) {
                    nameByConn[sourceId]?.takeIf { it.isNotBlank() }
                } else null
                AuditLogEntry(
                    id = obj.get("event_id")?.asString ?: return@mapNotNull null,
                    type = type,
                    title = obj.get("title")?.asString?.takeIf { it.isNotBlank() }
                        ?: getAuditLogTitle(type),
                    description = obj.get("message")?.asString.orEmpty(),
                    timestamp = (obj.get("created_at")?.asLong ?: 0L) * 1000L,
                    metadata = obj.getAsJsonObject("metadata"),
                    connectionId = sourceId.takeIf { sourceType == "connection" && it.isNotEmpty() },
                    peerName = peerName,
                    entryHash = obj.get("entry_hash")?.asString?.takeIf { it.isNotBlank() },
                    previousHash = obj.get("previous_hash")?.asString?.takeIf { it.isNotBlank() },
                    entrySig = obj.get("entry_sig")?.asString?.takeIf { it.isNotBlank() },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse audit log entry", e)
                null
            }
        }
    }

    private fun isSecurityRelevantType(type: String): Boolean = type in SECURITY_AUDIT_TYPES ||
        type.startsWith("migration_") ||
        type.startsWith("auth.") ||
        type.startsWith("security.")

    private fun getAuditLogTitle(type: String): String {
        return when (type) {
            "migration_sealed_material" -> "Credential Migration Started"
            "migration_verified" -> "Credential Migration Completed"
            "migration_old_version_deleted" -> "Old Credential Version Removed"
            "identity.key.used" -> "Identity key used"
            "critical_secret.used" -> "Critical secret used"
            "connection.verified" -> "Identity verified"
            "connection.verify.denied" -> "Identity verification denied"
            "auth.attempt_failed" -> "Authentication attempt failed"
            "auth.success" -> "Authentication succeeded"
            "security.alert" -> "Security alert"
            else -> type.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }

}

/**
 * Migration status states.
 */
sealed class MigrationStatus {
    /** No migration has occurred */
    object None : MigrationStatus()

    /** Migration completed successfully */
    data class Complete(
        val migratedAt: String?,
        val userNotified: Boolean,
        val fromPcrVersion: String?,
        val toPcrVersion: String?
    ) : MigrationStatus()

    /** Migration is currently in progress (server-side) */
    data class InProgress(val progress: Float) : MigrationStatus()

    /** Could not determine migration status */
    object Unknown : MigrationStatus()
}

/**
 * Migration config returned by the vault when an update is available.
 */
data class MigrationConfig(
    val version: String,
    val summary: String,
    val detailsUrl: String?,
    val publishedAt: String?,
    val mandatoryAfter: String?,
    val newPcr0: String? = null
)

/**
 * Migration events for UI updates.
 */
sealed class MigrationEvent {
    data class MigrationCompleted(val version: String) : MigrationEvent()
    data class MigrationFailed(val error: String) : MigrationEvent()
}

/**
 * Audit log entry for security events.
 */
data class AuditLogEntry(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val timestamp: Long,
    val metadata: JsonObject?,
    /** Source connection ID when the event was scoped to one. Null for system-wide events (migrations, security alerts). */
    val connectionId: String? = null,
    /** Resolved peer display name for connectionId. Null when source is system-wide or the connection isn't cached locally. */
    val peerName: String? = null,
    /** Audit-chain anchors carried back to the verifier. Hex-encoded. Null for legacy / pre-chain rows. */
    val entryHash: String? = null,
    val previousHash: String? = null,
    val entrySig: String? = null,
    /** Verification state computed in MigrationClient after parsing the response. */
    val verification: com.vettid.app.core.audit.AuditChainVerifier.RowState =
        com.vettid.app.core.audit.AuditChainVerifier.RowState.Unsigned,
)

/**
 * Page of audit entries + the chain status for the page. The screen
 * uses ChainStatus to render the green "verified" / yellow "unsigned"
 * / red "tampered" pill at the top of the list; per-row state lives
 * on each AuditLogEntry.
 */
data class AuditLogPage(
    val entries: List<AuditLogEntry>,
    val chainStatus: com.vettid.app.core.audit.AuditChainVerifier.ChainStatus =
        com.vettid.app.core.audit.AuditChainVerifier.ChainStatus.Empty,
)
