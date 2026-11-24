package com.vettid.core.data.vault

import kotlinx.coroutines.sync.withLock
import com.vettid.core.vault.AuditEntry
import com.vettid.core.vault.AuditPage
import com.vettid.core.vault.VaultApi
import java.text.Normalizer
import java.time.Instant

/**
 * The History screen's categories (ANDROID-PLAN 0.1.11 "History", VAULT-MESSAGING 0.20.0 §10.9): each is the set of
 * audit kind prefixes it sends as `kinds` (an entry matches a prefix when its `kind` equals it or starts with it
 * followed by `.`). Every kind of §10.9 falls in exactly one; [OTHER] holds a kind a newer vault adds outside these
 * prefixes, which appears under "All" only (it is not a filter). [id] is the category's stable, untranslated name in
 * a History export file (ANDROID-PLAN 0.1.17).
 */
enum class AuditCategory(val id: String, val prefixes: List<String>) {
    /** Unlocks and daily checks. */
    VAULT_ACCESS("unlocks", listOf("vault", "owner_check")),

    /** 0.1.17: `audit` (`audit.exported`, a History export) belongs here. */
    SECURITY("security", listOf("credential", "identity", "recovery", "audit")),
    DEVICES("devices", listOf("device", "approval")),
    // 0.1.102: `vote.*` entries are the service connection's ("Votes" in ANDROID-PLAN §4 History).
    CONNECTIONS("connections", listOf("connection", "intro", "profile", "vote")),
    MESSAGES("messages", listOf("message", "call")),

    /** "Vault (items, sharing, wallet)". */
    ITEMS("vault", listOf("item", "tag", "share", "grant", "critical-secret", "wallet")),
    AGENTS("agents", listOf("leash", "action")),
    LOCATION("location", listOf("location")),
    ACCOUNT("account", listOf("account", "settings")),

    /** Blocked and dropped messages. */
    DROPPED("dropped", listOf("drop")),
    OTHER("other", emptyList()),
    ;

    companion object {
        /** The categories offered as filters, in chip order. */
        val filters: List<AuditCategory> get() = entries.filter { it != OTHER }

        /** §10.9's prefix rule. */
        fun matches(kind: String, prefix: String): Boolean = kind == prefix || kind.startsWith("$prefix.")

        /** The category of [kind]; [OTHER] for a kind no category names. */
        fun of(kind: String): AuditCategory = entries.firstOrNull { c -> c.prefixes.any { matches(kind, it) } } ?: OTHER
    }
}

/** An audit log entry (§10.9). [at] is null when the vault's timestamp does not parse; [atText] is as sent. */
data class AuditRecord(
    val entryId: String,
    val seq: Long,
    val at: Instant?,
    val kind: String,
    val connectionId: String? = null,
    val deviceId: String? = null,
    val ref: String? = null,
    val direction: String? = null,
    val prev: String = "",
    val hash: String = "",
    val atText: String = at?.toString() ?: "",
) {
    val category: AuditCategory get() = AuditCategory.of(kind)

    fun toEntry() = AuditEntry(entryId, seq, atText, kind, connectionId, deviceId, ref, direction, prev, hash)

    companion object {
        fun of(e: AuditEntry) = AuditRecord(
            entryId = e.entryId,
            seq = e.seq,
            at = parseInstant(e.at),
            kind = e.kind,
            connectionId = e.connectionId?.takeIf { it.isNotEmpty() },
            deviceId = e.deviceId?.takeIf { it.isNotEmpty() },
            ref = e.ref?.takeIf { it.isNotEmpty() },
            direction = e.direction?.takeIf { it.isNotEmpty() },
            prev = e.prev,
            hash = e.hash,
            atText = e.at,
        )
    }
}

/**
 * What the member filters History by (ANDROID-PLAN 0.1.11): one [category] (null: all), one connection, the search
 * [query] (`q`) and a date range ([since] inclusive, [until] exclusive).
 */
data class AuditFilter(
    val category: AuditCategory? = null,
    val connectionId: String? = null,
    val query: String = "",
    val since: Instant? = null,
    val until: Instant? = null,
) {
    /** `q` (§10.9): trimmed, NFC, at most 128 bytes of UTF-8 (cut at a character), null when blank. */
    val q: String? get() = searchText(query)

    val isEmpty: Boolean get() = category == null && connectionId == null && q == null && since == null && until == null

    /** The `kinds` member: the category's prefixes (at most 6 of §10.9's 16), null for all. */
    fun kinds(): List<String>? = category?.prefixes?.takeIf { it.isNotEmpty() }

    companion object {
        /** §10.9: `q` is 1–128 bytes. */
        const val MAX_Q_BYTES = 128

        /** [raw] as `q`: NFC, trimmed, cut to 128 bytes at a character boundary; control characters dropped. */
        fun searchText(raw: String): String? {
            val clean = Normalizer.normalize(raw, Normalizer.Form.NFC).filterNot { it.isISOControl() }.trim()
            if (clean.isEmpty()) return null
            val out = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < clean.length) {
                val cp = clean.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + n > MAX_Q_BYTES) break
                out.appendCodePoint(cp)
                bytes += n
                i += Character.charCount(cp)
            }
            return out.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}

/** One `audit.list` request (§10.9, newest first). */
data class AuditRequest(
    val connectionId: String? = null,
    val kinds: List<String>? = null,
    val beforeSeq: Long? = null,
    val limit: Int = DEFAULT_LIMIT,
    val q: String? = null,
    val since: Instant? = null,
    val until: Instant? = null,
) {
    companion object {
        /** ANDROID-PLAN 0.1.11: pages of 50. */
        const val DEFAULT_LIMIT = 50
    }
}

/**
 * A page of the log, newest first. [nextBeforeSeq] continues it (null: the end of the results). [partial]: a search
 * ran out of its scan budget (§10.9, 0.20.0).
 */
data class AuditResult(
    val entries: List<AuditRecord>,
    val nextBeforeSeq: Long?,
    val partial: Boolean = false,
)

/** The member's audit log (§10.9): read-only. Failures are [VaultFailure]s. */
interface HistoryRepository {
    suspend fun auditPage(request: AuditRequest): AuditResult

    /** An entry already read in this session ([auditPage] keeps them), else null. */
    fun cached(seq: Long): AuditRecord?

    /**
     * id → name of the vault's devices (`device.list`, §10.3), for the device of an entry (VAULT-MESSAGING 0.23.2:
     * `vault.unlocked` and a device's `vault.locked` carry `device_id`; ANDROID-PLAN 0.1.27). Read once and kept for the
     * process (until a wipe); [refresh] reads it again, and so does a [wanted] id the kept list lacks (a device paired
     * since the list was read), once per such id until the next refresh: one still missing after a fresh read is gone. A
     * read that fails names no one gone, so a later call tries again. One read at a time. Null when it was never read (the
     * call failed): the app then names no device, rather than calling every device removed.
     */
    suspend fun deviceNames(refresh: Boolean = false, wanted: Collection<String> = emptyList()): Map<String, String>? = null

    /** This phone's device id (from its pairing), to mark "This phone" on an entry; null when not known. */
    fun selfDeviceId(): String? = null
}

/** The vault call behind [HistoryManager]. */
fun interface AuditOps {
    @Suppress("LongParameterList")
    suspend fun auditList(
        connectionId: String?,
        kinds: List<String>?,
        beforeSeq: Long?,
        limit: Int,
        q: String?,
        since: String?,
        until: String?,
    ): AuditPage
}

/** [AuditOps] over the vault client. */
internal class VaultAuditOps(private val api: VaultApi) : AuditOps {
    override suspend fun auditList(
        connectionId: String?,
        kinds: List<String>?,
        beforeSeq: Long?,
        limit: Int,
        q: String?,
        since: String?,
        until: String?,
    ): AuditPage = api.auditList(connectionId, kinds, beforeSeq, null, limit, q, since, until)
}

/**
 * [HistoryRepository] over [AuditOps]. The vault filters and searches (`kinds`, `connection_id`, `q`, `since`, `until`,
 * VAULT-MESSAGING §10.9); a refusal (`bad_request`) is a failure like any other.
 */
class HistoryManager(
    private val devices: (suspend () -> Map<String, String>)? = null,
    private val self: () -> String? = { null },
    // Last, so that `HistoryManager { ops }` reads as before.
    private val ops: suspend () -> AuditOps,
) : HistoryRepository {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, AuditRecord>()

    override fun cached(seq: Long): AuditRecord? = cache[seq]

    @Volatile
    private var deviceCache: Map<String, String>? = null

    /** Ids a fresh read did not list: gone, so they make no further read (until a refresh). */
    private val knownGone = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** One `device.list` read at a time: two at once could repeat the read, or let the older answer be kept. */
    private val deviceRead = kotlinx.coroutines.sync.Mutex()

    override suspend fun deviceNames(refresh: Boolean, wanted: Collection<String>): Map<String, String>? {
        val read = devices
        return when {
            !refresh && keptFor(wanted) != null -> deviceCache
            read == null -> deviceCache
            // A read that ended while this one waited may already answer it.
            else -> deviceRead.withLock { keptFor(wanted).takeIf { !refresh } ?: readDevices(read, refresh, wanted) }
        }
    }

    private suspend fun readDevices(
        read: suspend () -> Map<String, String>,
        refresh: Boolean,
        wanted: Collection<String>,
    ): Map<String, String>? {
        val kept = deviceCache
        val missing = missingFrom(kept, wanted)
        return try {
            vaultGuard(resend = true) { read() }.also { fresh ->
                deviceCache = fresh
                if (refresh) knownGone.clear()
                knownGone += missing.filter { it !in fresh }
            }
        } catch (_: VaultFailure) {
            // Best effort, as in the export: an older list (or none) rather than a failed History.
            kept
        }
    }

    /** The kept list when it names every [wanted] id not known to be gone; null when a read is needed. */
    private fun keptFor(wanted: Collection<String>): Map<String, String>? =
        deviceCache?.takeIf { missingFrom(it, wanted).isEmpty() }

    // A device paired after the list was read is not in it: read again rather than call it removed (R33).
    private fun missingFrom(kept: Map<String, String>?, wanted: Collection<String>): List<String> =
        kept?.let { k -> wanted.filter { it !in k && it !in knownGone } }.orEmpty()

    override fun selfDeviceId(): String? = self()

    /** Forgets what was read (a wipe of this phone). */
    fun clear() {
        cache.clear()
        deviceCache = null
        knownGone.clear()
    }

    override suspend fun auditPage(request: AuditRequest): AuditResult {
        val page = vaultGuard(resend = true) {
            ops().auditList(
                connectionId = request.connectionId,
                kinds = request.kinds,
                beforeSeq = request.beforeSeq,
                limit = request.limit,
                q = request.q,
                since = request.since?.toString(),
                until = request.until?.toString(),
            )
        }
        val records = page.entries.map { AuditRecord.of(it) }
        if (cache.size > MAX_CACHE) cache.clear()
        records.forEach { cache[it.seq] = it }
        return AuditResult(records, page.nextBeforeSeq, partial = page.partial)
    }

    private companion object {
        const val MAX_CACHE = 5_000
    }
}

/**
 * The integrity check of ANDROID-PLAN 0.1.11 "History": an unfiltered list, newest first, chains (each entry's `prev`
 * is the next older entry's `hash`, and each `hash` is §10.9's) where its `seq`s are contiguous. Filtered and
 * searched lists are never checked. True when nothing contradicts the chain.
 */
object AuditChain {
    fun consistent(newestFirst: List<AuditRecord>): Boolean {
        if (newestFirst.isEmpty()) return true
        // Split into runs of consecutive seqs; each run is checked oldest first.
        val runs = mutableListOf(mutableListOf(newestFirst.first()))
        for (r in newestFirst.drop(1)) {
            if (r.seq == runs.last().last().seq - 1) runs.last() += r else runs += mutableListOf(r)
        }
        return runs.all { run ->
            val oldestFirst = run.asReversed().map { it.toEntry() }
            try {
                VaultApi.auditChains(oldestFirst) { Instant.parse(it).toEpochMilli() }
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: java.time.format.DateTimeParseException) {
                false
            }
        }
    }
}
