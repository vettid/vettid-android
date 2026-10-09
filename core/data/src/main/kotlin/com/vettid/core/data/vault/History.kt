package com.vettid.core.data.vault

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
    /** Unlocks and owner checks. */
    VAULT_ACCESS("unlocks", listOf("vault", "owner_check")),

    /** 0.1.17: `audit` (`audit.exported`, a History export) belongs here. */
    SECURITY("security", listOf("credential", "identity", "recovery", "audit")),
    DEVICES("devices", listOf("device", "approval")),
    CONNECTIONS("connections", listOf("connection", "intro", "profile")),
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

        /** Kinds whose `ref` is an item's id (§10.9 Search, item names): the vault may match `q` on the item's name. */
        val ITEM_REF_KINDS = setOf(
            "item.added", "item.updated", "item.deleted", "item.sensitivity_changed", "item.revealed",
            "share.included", "share.declined", "share.withdrawn", "wallet.created", "wallet.deleted", "wallet.address_issued",
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

    /** Whether [r] passes the category, the connection and the date range (what a vault before 0.20.0 may ignore). */
    fun acceptsExceptSearch(r: AuditRecord): Boolean =
        (category == null || r.category == category) &&
            (connectionId == null || r.connectionId == connectionId) &&
            inRange(r.at)

    private fun inRange(at: Instant?): Boolean =
        (since == null || (at != null && !at.isBefore(since))) && (until == null || (at != null && at.isBefore(until)))

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

        /** The kind as §10.9 searches it: itself and with `.`, `_`, `-` as spaces. */
        fun kindText(kind: String): List<String> = listOf(kind, kind.replace('.', ' ').replace('_', ' ').replace('-', ' '))

        /** §10.9's comparison: lower-case, substring, each field on its own, an empty field never matches. */
        fun matches(q: String, fields: List<String>): Boolean {
            val lq = q.lowercase()
            return fields.any { it.isNotEmpty() && it.lowercase().contains(lq) }
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
    val hasSearch: Boolean get() = q != null || since != null || until != null

    companion object {
        /** ANDROID-PLAN 0.1.11: pages of 50. */
        const val DEFAULT_LIMIT = 50
    }
}

/**
 * A page of the log, newest first. [nextBeforeSeq] continues it (null: the end of the results). [partial]: a search
 * ran out of its scan budget (§10.9, 0.20.0). [searchSent]: the request carried `q`/`since`/`until`.
 */
data class AuditResult(
    val entries: List<AuditRecord>,
    val nextBeforeSeq: Long?,
    val searchSent: Boolean,
    val partial: Boolean = false,
)

/** The member's audit log (§10.9): read-only. Failures are [VaultFailure]s. */
interface HistoryRepository {
    suspend fun auditPage(request: AuditRequest): AuditResult

    /** An entry already read in this session ([auditPage] keeps them), else null. */
    fun cached(seq: Long): AuditRecord?

    /**
     * The vault answered a search unfiltered (a vault before 0.20.0, which ignores unknown members): this session
     * stops sending the search and the app filters the entries it loads.
     */
    fun searchIgnored()
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
 * [HistoryRepository] over [AuditOps]. The search (`q`, `since`, `until`, VAULT-MESSAGING 0.20.0) has no capability
 * signal: it is sent until the vault refuses it with `bad_request` (then the request is sent again without it) or
 * answers it unfiltered ([searchIgnored], a staging S4 vault); from then on this session filters on the phone.
 */
class HistoryManager(private val ops: suspend () -> AuditOps) : HistoryRepository {
    @Volatile
    var searchUnsupported: Boolean = false
        private set

    private val cache = java.util.concurrent.ConcurrentHashMap<Long, AuditRecord>()

    override fun cached(seq: Long): AuditRecord? = cache[seq]

    override fun searchIgnored() {
        searchUnsupported = true
    }

    /** Forgets what was read (a wipe of this phone). */
    fun clear() {
        cache.clear()
        searchUnsupported = false
    }

    override suspend fun auditPage(request: AuditRequest): AuditResult {
        val withSearch = request.hasSearch && !searchUnsupported
        val page = try {
            fetch(request, withSearch)
        } catch (e: VaultFailure) {
            if (!withSearch || e.code != CODE_BAD_REQUEST) throw e
            searchUnsupported = true
            fetch(request, search = false)
        }
        val records = page.entries.map { AuditRecord.of(it) }
        if (cache.size > MAX_CACHE) cache.clear()
        records.forEach { cache[it.seq] = it }
        return AuditResult(records, page.nextBeforeSeq, searchSent = withSearch && !searchUnsupported, partial = page.partial)
    }

    private suspend fun fetch(r: AuditRequest, search: Boolean): AuditPage = vaultGuard {
        ops().auditList(
            connectionId = r.connectionId,
            kinds = r.kinds,
            beforeSeq = r.beforeSeq,
            limit = r.limit,
            q = r.q.takeIf { search },
            since = r.since?.takeIf { search }?.toString(),
            until = r.until?.takeIf { search }?.toString(),
        )
    }

    private companion object {
        const val CODE_BAD_REQUEST = "bad_request"
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
