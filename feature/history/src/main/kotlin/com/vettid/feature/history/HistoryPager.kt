package com.vettid.feature.history

import com.vettid.core.data.vault.AuditChain
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.AuditRequest
import com.vettid.core.data.vault.AuditResult
import com.vettid.core.data.vault.HistoryRepository

/**
 * The audit log in pages, newest first (VAULT-MESSAGING 0.20.0 §10.9, ANDROID-PLAN 0.1.11): `limit` 50 and
 * `before_seq` = the previous page's `next_before_seq`; a page without it is the end. A `partial` page (a search that
 * ran out of its scan budget) may hold few or no entries: loading goes on with the cursor until [minPerLoad] new
 * entries arrived (or [maxPagesPerLoad] pages were read; the next load continues).
 *
 * The vault filters and searches (`kinds`, `connection_id`, `q`, `since`, `until`; it also matches device and item
 * names the app does not hold).
 *
 * Unfiltered lists are chain-checked ([AuditChain]); filtered and searched ones are not.
 * Not thread-safe: one load at a time (the ViewModel serialises them).
 */
class HistoryPager(
    private val repo: HistoryRepository,
    private val pageSize: Int = AuditRequest.DEFAULT_LIMIT,
    private val minPerLoad: Int = MIN_PER_LOAD,
    private val maxPagesPerLoad: Int = MAX_PAGES_PER_LOAD,
) {
    /** What has been read: [entries] newest first; [end] once the results' end is reached. */
    data class Snapshot(
        val filter: AuditFilter = AuditFilter(),
        val entries: List<AuditRecord> = emptyList(),
        val end: Boolean = false,
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

    /**
     * Reads the newest page again and puts the entries that arrived since the newest one shown on top (the vault added
     * them while History was open or away, e.g. `audit.exported` after an export). The older entries and the cursor
     * stay. When more arrived than the page holds (its cursor is above the newest shown) or when nothing is shown yet,
     * it starts over ([reset]).
     */
    suspend fun refresh(): Snapshot {
        val s = snapshot
        val top = s.entries.firstOrNull()?.seq
        val r = top?.let { repo.auditPage(request(s.filter, null)) }
        return if (top == null || r == null || startsOver(r, top)) reset(s.filter) else prepend(s, r, top)
    }

    /** Whether [refresh] must start over: entries between the newest page and [top]. */
    private fun startsOver(r: AuditResult, top: Long): Boolean {
        val next = r.nextBeforeSeq
        return next != null && next > top + 1
    }

    /** The entries of [r] newer than [top], on top of [s]. */
    private fun prepend(s: Snapshot, r: AuditResult, top: Long): Snapshot {
        val fresh = r.entries.filter { e -> e.seq > top }
        return if (fresh.isEmpty()) {
            s
        } else {
            s.copy(
                entries = fresh + s.entries,
                chainBroken = s.chainBroken || (s.filter.isEmpty && !AuditChain.consistent(fresh + s.entries.first())),
            ).also { snapshot = it }
        }
    }

    /** Reads older entries (nothing once [Snapshot.end]). */
    suspend fun more(): Snapshot {
        var s = snapshot
        var pages = 0
        var added = 0
        while (!s.end && pages < maxPagesPerLoad && added < minPerLoad) {
            val r = repo.auditPage(request(s.filter, cursor))
            pages++
            val known = s.entries.mapTo(HashSet()) { it.seq }
            val fresh = r.entries.filter { it.seq !in known }
            added += fresh.size
            cursor = r.nextBeforeSeq
            val entries = s.entries + fresh
            s = s.copy(
                entries = entries,
                end = cursor == null,
                partial = r.partial,
                // Only what this page adds, and its link to the page before.
                chainBroken = s.chainBroken ||
                    (s.filter.isEmpty && !AuditChain.consistent(listOfNotNull(s.entries.lastOrNull()) + fresh)),
            )
            snapshot = s
        }
        return s
    }

    companion object {
        const val MIN_PER_LOAD = 20
        const val MAX_PAGES_PER_LOAD = 8
    }
}
