package com.vettid.feature.history

import com.vettid.core.data.vault.AuditChain
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.AuditRequest
import com.vettid.core.data.vault.HistoryRepository

/**
 * The audit log in pages, newest first (VAULT-MESSAGING 0.20.0 §10.9, ANDROID-PLAN 0.1.11): `limit` 50 and
 * `before_seq` = the previous page's `next_before_seq`; a page without it is the end. A `partial` page (a search that
 * ran out of its scan budget) may hold few or no entries: loading goes on with the cursor until [minPerLoad] new
 * entries arrived (or [maxPagesPerLoad] pages were read; the next load continues).
 *
 * The vault filters (`kinds`, `connection_id`, `q`, `since`, `until`). A vault before 0.20.0 (staging S4) refuses or
 * ignores the search: then this phone filters what it loads, the category, connection and dates exactly, and `q`
 * over what it knows ([localText]: the kind and the connection's names); [Snapshot.localSearch] says so. A vault
 * that answered `q` is trusted with it (it also matches device and item names the app does not hold), unless an
 * entry it returned could not match at all, which shows it ignored `q`.
 *
 * Unfiltered lists are chain-checked ([AuditChain]); filtered and searched ones are not.
 * Not thread-safe: one load at a time (the ViewModel serialises them).
 */
class HistoryPager(
    private val repo: HistoryRepository,
    private val localText: (AuditRecord) -> List<String>,
    private val pageSize: Int = AuditRequest.DEFAULT_LIMIT,
    private val minPerLoad: Int = MIN_PER_LOAD,
    private val maxPagesPerLoad: Int = MAX_PAGES_PER_LOAD,
) {
    /** What has been read: [entries] newest first; [end] once the results' end is reached. */
    data class Snapshot(
        val filter: AuditFilter = AuditFilter(),
        val entries: List<AuditRecord> = emptyList(),
        val end: Boolean = false,
        /** The search runs on this phone over the loaded entries (the vault does not search yet). */
        val localSearch: Boolean = false,
        /** The last page was `partial`: the vault is still searching older entries. */
        val partial: Boolean = false,
        /** An unfiltered list whose entries do not chain (§10.9: report it as tampered). */
        val chainBroken: Boolean = false,
    )

    var snapshot: Snapshot = Snapshot()
        private set

    private var cursor: Long? = null

    /** The request for the page before [beforeSeq] (null: the newest) under [filter]. */
    fun request(filter: AuditFilter, beforeSeq: Long?): AuditRequest = AuditRequest(
        connectionId = filter.connectionId,
        kinds = filter.kinds(),
        beforeSeq = beforeSeq,
        limit = pageSize,
        q = filter.q,
        since = filter.since,
        until = filter.until,
    )

    /** Starts over under [filter] and reads the first entries. */
    suspend fun reset(filter: AuditFilter): Snapshot {
        snapshot = Snapshot(filter = filter)
        cursor = null
        return more()
    }

    /** Reads older entries (nothing once [Snapshot.end]). */
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun more(): Snapshot {
        var s = snapshot
        var pages = 0
        var added = 0
        while (!s.end && pages < maxPagesPerLoad && added < minPerLoad) {
            val r = repo.auditPage(request(s.filter, cursor))
            pages++
            val q = s.filter.q
            var local = s.localSearch || (q != null && !r.searchSent)
            if (q != null && !local && r.entries.any { !couldMatch(q, it) }) {
                // The vault returned an entry `q` cannot match anywhere: it ignored the search (before 0.20.0).
                repo.searchIgnored()
                local = true
            }
            val accepted = r.entries.filter { e ->
                s.filter.acceptsExceptSearch(e) && (q == null || !local || AuditFilter.matches(q, localText(e)))
            }
            val known = s.entries.mapTo(HashSet()) { it.seq }
            val fresh = accepted.filter { it.seq !in known }
            added += fresh.size
            cursor = r.nextBeforeSeq
            val entries = s.entries + fresh
            s = s.copy(
                entries = entries,
                end = cursor == null,
                localSearch = local,
                partial = r.partial,
                // Only what this page adds, and its link to the page before.
                chainBroken = s.chainBroken ||
                    (s.filter.isEmpty && !AuditChain.consistent(listOfNotNull(s.entries.lastOrNull()) + fresh)),
            )
            snapshot = s
        }
        return s
    }

    /**
     * Whether a 0.20.0 vault could have matched [q] on [e]: on its kind or the connection's names the app knows, or on
     * the names of a device or an item, which the app does not hold.
     */
    private fun couldMatch(q: String, e: AuditRecord): Boolean =
        AuditFilter.matches(q, localText(e)) || e.deviceId != null || e.kind in AuditRecord.ITEM_REF_KINDS || e.connectionId != null

    companion object {
        const val MIN_PER_LOAD = 20
        const val MAX_PAGES_PER_LOAD = 8
    }
}
