package com.vettid.core.data.feed

import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.FeedItem
import com.vettid.core.vault.FeedPage
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The vault calls behind [FeedManager] (VAULT-MESSAGING §10.9), so that it can be tested without a vault. */
interface FeedOps {
    /** `feed.list{after_seq, limit}`: every item changed after [afterSeq], in `seq` order, deleted ones included. */
    suspend fun list(afterSeq: Long, limit: Int): FeedPage

    suspend fun update(itemId: String, status: String): FeedItem

    suspend fun delete(itemId: String)

    /** `feed.retention_days` (§10.8); null when the vault does not say. */
    suspend fun retentionDays(): Int?
}

/** [FeedOps] over the vault client. */
internal class VaultFeedOps(private val api: VaultApi) : FeedOps {
    override suspend fun list(afterSeq: Long, limit: Int): FeedPage = api.feedList(afterSeq = afterSeq, limit = limit)

    override suspend fun update(itemId: String, status: String): FeedItem = api.feedUpdate(itemId, status = status)

    override suspend fun delete(itemId: String) = api.feedDelete(itemId)

    override suspend fun retentionDays(): Int? =
        (api.settingsGet().settings[FeedManager.KEY_RETENTION] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}

/** The bell's count (ANDROID-PLAN 0.1.23): unread items, and whether one of them is urgent (the badge turns red). */
data class FeedBadge(val unread: Int = 0, val urgent: Boolean = false)

/**
 * The vault's feed in the app (ANDROID-PLAN 0.1.23 Notifications, VAULT-MESSAGING §10.9): the owner's activity list,
 * with read and archive state shared by the owner's devices. Every failure is a [VaultFailure].
 */
interface FeedRepository {
    /** The live items (never `deleted`), newest first by `at`. */
    val items: StateFlow<List<FeedItem>>

    val load: StateFlow<ListLoad>

    val badge: StateFlow<FeedBadge>

    /** Reads the whole feed again. */
    suspend fun refresh()

    /** `feed.update{status}`: shown at once, undone if the vault refuses. */
    suspend fun setStatus(itemId: String, status: String)

    /** Marks [itemId] read without waiting for the answer (a tap on a row or a notification). */
    fun markReadQuietly(itemId: String)

    /** `feed.delete` (only from Archived, confirmed). */
    suspend fun delete(itemId: String)

    /**
     * Marks every unread item read, one `feed.update` each (no bulk type, §9 question 4), at most 4 in flight; the
     * count falls as they land. Throws the first failure after the others ran.
     */
    suspend fun markAllRead()
}

/**
 * [FeedRepository] over [FeedOps] (the pattern of `ItemsManager`). The items, the cursor (the highest `seq` applied)
 * and the load state are kept in memory only, while the vault is open: never in a file, a database or DataStore,
 * and cleared on lock, on entering the hold, on a wipe and with the process (ANDROID-PLAN 0.1.23, 10).
 *
 * - **Open** ([open], [refresh]): `feed.list{after_seq: 0, limit: 500}`, repeated from the last item's `seq` until a
 *   page is shorter than the limit; `deleted` items are dropped; the cursor is the answer's `seq`.
 * - **Live** ([onEvent]): `feed.event` with `seq` = cursor + 1 is applied; any other `seq`, and `sync.event`
 *   `feed.updated` / `feed.deleted`, start a catch-up ([catchUp]): `feed.list{after_seq: cursor}`, paged, coalesced
 *   (one in flight, one queued). A batch's later ask comes as a change of its item (`count`, `seq`) with the status
 *   the member left (0.23.1); it replaces the cached item where it is.
 * - **Own changes** ([setStatus], [delete]) apply the answer at once: the vault sends no `sync.event` to the sender.
 * - **Retention**: items older than `feed.retention_days` (default 30) are dropped here as well.
 * - **Held or due** ([gated]): nothing is listed and events are ignored; after the check, a full read.
 */
@Suppress("TooManyFunctions")
class FeedManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> FeedOps,
    /** True while the vault is held or due (VAULT-MESSAGING §3.6.3): nothing of the feed is shown. */
    private val gated: () -> Boolean = { false },
    private val now: () -> Instant = Instant::now,
) : FeedRepository {
    private val lock = Any()
    private var byId: Map<String, FeedItem> = emptyMap()
    private var cursor = 0L
    private var retentionDays = DEFAULT_RETENTION_DAYS

    /** Bumped by [clear]: a read that started before it is not applied after it. */
    private var generation = 0
    private val reads = Mutex()

    @Volatile
    private var catchUpQueued = false

    private val list = MutableStateFlow<List<FeedItem>>(emptyList())
    private val loadState = MutableStateFlow(ListLoad.NOT_LOADED)
    private val badgeFlow = MutableStateFlow(FeedBadge())

    override val items: StateFlow<List<FeedItem>> = list.asStateFlow()
    override val load: StateFlow<ListLoad> = loadState.asStateFlow()
    override val badge: StateFlow<FeedBadge> = badgeFlow.asStateFlow()

    /** The highest `seq` applied (tests). */
    val seq: Long get() = synchronized(lock) { cursor }

    /** The vault opened (an unlock, app start with it open, a passed owner check): the feed is read in the background. */
    fun open() {
        scope.launch { runCatching { refresh() } }
    }

    /** Forgets everything: the vault locked, was held or wiped. */
    fun clear() {
        synchronized(lock) {
            generation++
            byId = emptyMap()
            cursor = 0
            catchUpQueued = false
            publish()
        }
        loadState.value = ListLoad.NOT_LOADED
    }

    /** `feed.event` and `sync.event{feed.updated, feed.deleted}` (§10.1, §10.9). */
    fun onEvent(m: VaultMessage) {
        when (m.type) {
            "feed.event" -> {
                // While held only the clone alarm's item arrives; it opens the alarm path elsewhere, it is not listed.
                if (gated()) return
                val item = runCatching { VaultJson.decode(FeedItem.serializer(), m.body) }.getOrNull() ?: return
                onItem(item)
            }
            "sync.event" -> when (VaultJson.str(m.body, "kind")) {
                SYNC_UPDATED, SYNC_DELETED -> if (!gated()) catchUp()
            }
        }
    }

    private fun onItem(item: FeedItem) {
        val inOrder = synchronized(lock) {
            if (loadState.value != ListLoad.LOADED) return@synchronized false
            if (item.seq <= cursor) return // already applied
            val next = item.seq == cursor + 1
            apply(listOf(item))
            if (next) cursor = item.seq
            next
        }
        if (!inOrder) catchUp()
    }

    /**
     * Reads what changed after the cursor (a `seq` gap, another device's change, a return to the foreground or a
     * relay reconnect). Coalesced: one read in flight and at most one queued behind it; before the first full read,
     * a full read.
     */
    fun catchUp() {
        if (catchUpQueued) return
        catchUpQueued = true
        scope.launch {
            runCatching {
                reads.withLock {
                    catchUpQueued = false
                    if (loadState.value == ListLoad.LOADED) readAfter(full = false) else readAfter(full = true)
                }
            }
        }
    }

    override suspend fun refresh() {
        reads.withLock { readAfter(full = true) }
    }

    /** One paged read from the cursor ([full]: from 0, replacing everything). Runs under [reads]. */
    private suspend fun readAfter(full: Boolean) {
        val gen = synchronized(lock) { generation }
        if (full) loadState.value = ListLoad.LOADING
        try {
            if (full) readRetention()
            val (changed, seq) = pages(if (full) 0L else synchronized(lock) { cursor })
            synchronized(lock) {
                if (gen != generation) return
                if (full) byId = emptyMap()
                apply(changed)
                cursor = maxOf(if (full) 0 else cursor, seq)
            }
            loadState.value = ListLoad.LOADED
        } catch (e: VaultFailure) {
            if (full && synchronized(lock) { gen == generation }) loadState.value = ListLoad.FAILED
            throw e
        }
    }

    /** `feed.list{after_seq}` paged from [from] until a page is shorter than the limit: the items and the answer's `seq`. */
    private suspend fun pages(from: Long): Pair<List<FeedItem>, Long> {
        var after = from
        val changed = ArrayList<FeedItem>()
        var seq = from
        for (i in 0 until MAX_PAGES) {
            val page = vaultGuard { ops().list(after, PAGE) }
            seq = maxOf(seq, page.seq)
            changed += page.items
            if (page.items.size < PAGE) break
            after = page.items.last().seq
        }
        return changed to seq
    }

    private suspend fun readRetention() {
        val d = runCatching { vaultGuard { ops().retentionDays() } }.getOrNull() ?: return
        if (d in 1..MAX_RETENTION_DAYS) retentionDays = d
    }

    /** Replaces each item by id, removes the deleted ones, drops what is past retention, and publishes. Under [lock]. */
    private fun apply(changed: List<FeedItem>) {
        if (changed.isEmpty()) {
            publish()
            return
        }
        val m = byId.toMutableMap()
        for (item in changed) {
            if (item.status == STATUS_DELETED) m.remove(item.itemId) else m[item.itemId] = item
        }
        byId = m
        publish()
    }

    /** Under [lock]. */
    private fun publish() {
        val cutoff = now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        byId = byId.filterValues { i -> at(i)?.isBefore(cutoff) != true }
        val sorted = byId.values.sortedWith(compareByDescending<FeedItem> { at(it) ?: Instant.EPOCH }.thenByDescending { it.itemId })
        list.value = sorted
        val unread = sorted.filter { it.status == STATUS_ACTIVE }
        badgeFlow.value = FeedBadge(unread.size, unread.any { it.priority == PRIORITY_URGENT })
    }

    // --- the member's changes ---

    override suspend fun setStatus(itemId: String, status: String) {
        require(status in SETTABLE) { "status $status" }
        val before = synchronized(lock) { byId[itemId] } ?: return
        if (before.status == status) return
        replace(before.copy(status = status))
        val answer = try {
            vaultGuard { ops().update(itemId, status) }
        } catch (e: VaultFailure) {
            // Undone unless something newer arrived meanwhile.
            synchronized(lock) { if (byId[itemId]?.let { it.seq == before.seq && it.status == status } == true) apply(listOf(before)) }
            throw e
        }
        synchronized(lock) {
            if (answer.seq == cursor + 1) cursor = answer.seq
            if ((byId[itemId]?.seq ?: 0) <= answer.seq) apply(listOf(answer))
        }
    }

    private fun replace(item: FeedItem) = synchronized(lock) {
        if (byId.containsKey(item.itemId)) apply(listOf(item))
    }

    override fun markReadQuietly(itemId: String) {
        val item = synchronized(lock) { byId[itemId] } ?: return
        if (item.status != STATUS_ACTIVE) return
        scope.launch { runCatching { setStatus(itemId, STATUS_READ) } }
    }

    /**
     * Marks read the unread items of [kinds] whose `ref` is [ref] (an ask decided in Approvals, §9 question 2), or,
     * with [connectionId], those of that connection (a conversation opened: its `message.received` items).
     */
    fun markReadWhere(kinds: Set<String>, ref: String? = null, connectionId: String? = null) {
        val ids = synchronized(lock) {
            byId.values.filter { i ->
                i.status == STATUS_ACTIVE && i.kind in kinds &&
                    (ref == null || i.ref == ref) && (connectionId == null || i.connectionId == connectionId)
            }.map { it.itemId }
        }
        ids.forEach { markReadQuietly(it) }
    }

    override suspend fun delete(itemId: String) {
        vaultGuard { ops().delete(itemId) }
        synchronized(lock) {
            byId = byId - itemId
            publish()
        }
    }

    override suspend fun markAllRead() {
        val ids = synchronized(lock) { byId.values.filter { it.status == STATUS_ACTIVE }.map { it.itemId } }
        if (ids.isEmpty()) return
        val gate = Semaphore(MARK_ALL_IN_FLIGHT)
        val failures = coroutineScope {
            ids.map { id ->
                async {
                    gate.withPermit {
                        try {
                            setStatus(id, STATUS_READ)
                            null
                        } catch (e: VaultFailure) {
                            e
                        }
                    }
                }
            }.awaitAll()
        }
        failures.firstOrNull { it != null }?.let { throw it }
    }

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_READ = "read"
        const val STATUS_ARCHIVED = "archived"
        const val STATUS_DELETED = "deleted"
        const val PRIORITY_LOW = "low"
        const val PRIORITY_NORMAL = "normal"
        const val PRIORITY_HIGH = "high"
        const val PRIORITY_URGENT = "urgent"
        const val KEY_RETENTION = "feed.retention_days"
        const val DEFAULT_RETENTION_DAYS = 30
        private const val MAX_RETENTION_DAYS = 365
        private const val SYNC_UPDATED = "feed.updated"
        private const val SYNC_DELETED = "feed.deleted"
        private val SETTABLE = setOf(STATUS_ACTIVE, STATUS_READ, STATUS_ARCHIVED)

        /** `feed.list`'s largest page (§10.9). */
        const val PAGE = 500

        /** The feed keeps at most 1,000 live items (§10.9); deleted ones come too, so a few pages more. */
        private const val MAX_PAGES = 8
        private const val MARK_ALL_IN_FLIGHT = 4

        /** An item's `at`; null when it does not parse. */
        fun at(item: FeedItem): Instant? = try {
            Instant.parse(item.at)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
