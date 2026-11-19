package com.vettid.core.data.feed

import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.AskBatching
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * Items shown on the Notifications screen (ANDROID-PLAN 0.1.26): those of [itemIds] still unread and not `urgent`
     * are marked read, one `feed.update` each, at most 4 in flight (shared with [markAllRead]), without waiting for the
     * answers and past the screen's end. Urgent items stay unread until tapped or marked read.
     */
    fun markViewed(itemIds: Collection<String>)

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
 * - **The release check** ([held], Rule 3): `feed.event`s (live or replayed) and arrivals wait in memory, and come when
 *   it is over with the vault not behind, or when the gate lifts ([releaseHeld]); a clear drops them. Until they came,
 *   a later event or arrival waits behind them, so none overtakes them ([queuesBehind]). Nothing is held while the owner
 *   check is due or held ([ownerGated]), and a released arrival is the feed's item as it is then, or nothing. A held
 *   event the open read covers notifies only if the vault sent it since it opened ([vaultOpened]) or it came from a push
 *   wake: the opening's backlog stays silent (ANDROID-PLAN §6.7).
 */
@Suppress("TooManyFunctions")
class FeedManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> FeedOps,
    /** True while the vault is held or due (VAULT-MESSAGING §3.6.3): nothing of the feed is shown. */
    private val gated: () -> Boolean = { false },
    private val now: () -> Instant = Instant::now,
    /** (Rule 3) True while the release gate checks the vault's release: nothing notifies before it is known current. */
    private val held: () -> Boolean = { false },
    /**
     * The daily owner check alone is due or held (part of [gated], without the release gate's pause): what comes then is
     * never held for the release check's end, so nothing missed while held notifies at the lift (owner decision
     * 2026-11-18, as the iPhone app).
     */
    private val ownerGated: () -> Boolean = { false },
    /**
     * The vault is open on this phone (the phase, not the feed's state: the owner check's hold clears the feed while it
     * stays open). A held-back or live event that comes while it is not is kept nowhere ([waitsForTheRead]).
     */
    private val vaultOpen: () -> Boolean = { true },
) : FeedRepository {
    private val lock = Any()
    private var byId: Map<String, FeedItem> = emptyMap()
    private var cursor = 0L
    private var retentionDays = DEFAULT_RETENTION_DAYS

    /** (Rule 3) The `feed.event`s and arrivals that came while [held], in order (under [lock]; at most [ARRIVAL_BUFFER] each). */
    private val heldEvents = ArrayDeque<HeldEvent>()

    /**
     * (Rule 3) When the vault last opened, on the vault's clock: a held event the feed's read covers notifies only if the
     * vault sent it since. One sent before is the mailbox's backlog of what changed while this phone was away, which is
     * never notified (ANDROID-PLAN §6.7). The phone's clock at the opening ([vaultOpened]) until the first answer of this
     * opening fixes it ([openingMarked]); null until the first opening. Under [lock].
     */
    private var openedAt: Instant? = null

    /** The owner check's hold or due state last ended here, on the vault's clock ([ownerCheckLifted]). Under [lock]. */
    private var liftedAt: Instant? = null

    /**
     * The lifts counted ([ownerCheckLifted]): an incremental read begun before one reads from the cursor of before the due
     * state, so it applies nothing and reads in full instead ([readAfter]). Under [lock].
     */
    private var lifts = 0

    /** (Rule 3) The openings counted ([vaultOpened]): a mark for an earlier one is ignored ([openingMarked]). Under [lock]. */
    private var opening = 0

    /**
     * (Rule 3) The feed's opening is current: from [vaultOpened] until a hard [clear] (a lock, the hold, a wipe). It takes its
     * mark only then, and only then does a held-back event wait for the read ([waitsForTheRead]). Under [lock].
     */
    private var markable = false

    /**
     * The opening was closed: a hard [clear] that ends it (a lock, a wipe, a deletion, an identity switch; not the owner
     * check's hold, which keeps the vault open). Until the next [vaultOpened]. Under [lock].
     */
    private var openingEnded = false

    /**
     * (ANDROID-PLAN 0.1.134) The `seq` each item last notified at during this opening: no second alert for the same or an
     * older change. Emptied at each opening and by a hard [clear]. Under [lock].
     */
    private val notifiedSeq = HashMap<String, Long>()

    /**
     * (ANDROID-PLAN 0.1.134, as the iPhone app) What a status read that could not be made kept of the loaded list: its cursor and
     * each item's `seq`, ids and numbers only (no item's content is kept while the vault is not open). The first full read
     * of the opening that continues notifies what a catch-up from that cursor would have brought (an item sent during the
     * blip whose `feed.event` comes after the read is no longer silent), then it is dropped. Dropped by any other clear, a new
     * opening and a lift (a lift's full read notifies nothing). Under [lock].
     */
    private var kept: KeptList? = null

    private class KeptList(val cursor: Long, val seqs: Map<String, Long>)

    /**
     * (Rule 3) The feed's reads started or under way ([open], [catchUp], [refresh]): what was held is released only after
     * them ([readEnded]), against the feed as they leave it, so an item read or decided elsewhere meanwhile does not notify.
     */
    private val readsRunning = java.util.concurrent.atomic.AtomicInteger(0)

    /** A held `feed.event`, and whether it came from a push wake ([replay]: what the push was for, never backlog). */
    private class HeldEvent(val m: VaultMessage, val wake: Boolean)
    private val heldArrivals = LinkedHashMap<String, FeedItem>()

    /** (Rule 3) The last read that ended failed: the feed may be behind what the vault holds, though loaded. */
    @Volatile
    private var behind = false

    /** (Rule 3) One catch-up was scheduled after a failed read (no loop): cleared by a read that succeeds. */
    @Volatile
    private var retried = false

    /**
     * (Rule 3) An incremental read failed with something held (the feed loaded but behind): one catch-up after
     * [RETRY_AFTER_MS], so what is held is not left waiting
     * for the next trigger (or dropped by a lock) when none comes. Once per failure run: a second failure waits for a
     * trigger, as before.
     */
    private fun retryOnce() {
        // Only for a read that left the feed loaded but behind (an incremental one): one that leaves it not loaded starts no
        // read of its own; the next event starts one (ANDROID-PLAN 0.1.134).
        if (retried || gated() || loadState.value != ListLoad.LOADED) return
        if (!synchronized(lock) { queuesBehind() }) return
        retried = true
        scope.launch {
            kotlinx.coroutines.delay(RETRY_AFTER_MS)
            if (vaultOpen() && synchronized(lock) { queuesBehind() }) catchUp()
        }
    }

    /** Bumped by [clear]: a read that started before it is not applied after it. */
    private var generation = 0
    private val reads = Mutex()

    /** Mark all as read and viewed items share it: at most 4 `feed.update` in flight. */
    private val marking = Semaphore(MARK_ALL_IN_FLIGHT)

    @Volatile
    private var catchUpQueued = false

    private val list = MutableStateFlow<List<FeedItem>>(emptyList())
    private val loadState = MutableStateFlow(ListLoad.NOT_LOADED)
    private val badgeFlow = MutableStateFlow(FeedBadge())

    override val items: StateFlow<List<FeedItem>> = list.asStateFlow()
    override val load: StateFlow<ListLoad> = loadState.asStateFlow()
    override val badge: StateFlow<FeedBadge> = badgeFlow.asStateFlow()

    private val arrivalFlow =
        MutableSharedFlow<FeedItem>(extraBufferCapacity = ARRIVAL_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * New and changed unread items as they arrive (`feed.event`, and the items of a catch-up), never from a full read:
     * the notifications' source (ANDROID-PLAN 0.1.23, Notification modes 7), so that nothing missed while locked or
     * held is replayed as a notification. A batch's later ask arrives again (its `count` changed).
     */
    val arrivals: SharedFlow<FeedItem> = arrivalFlow.asSharedFlow()

    /** The highest `seq` applied (tests). */
    val seq: Long get() = synchronized(lock) { cursor }

    /** The vault opened (an unlock, app start with it open, a passed owner check): the feed is read in the background. */
    fun open() {
        readsRunning.incrementAndGet() // counted before it starts, so a release asked for meanwhile waits for it
        scope.launch {
            var ok = false
            try {
                ok = runCatching { reads.withLock { readAfter(full = true) } }.isSuccess
            } finally {
                readEnded(ok)
            }
        }
    }

    /**
     * Forgets everything: the vault locked, was held or wiped. With [keepHeld] (a status read that could not be made, which
     * closes nothing, ANDROID-PLAN 0.1.134), the lists go as at a lock, but what this opening keeps for itself stays: what the
     * release check holds, what it notified, its mark and the hold's lift. If the opening continues they carry on; any close
     * that follows (a lock, a sign-out, a wipe) clears it all.
     */
    fun clear(keepHeld: Boolean = false, endsOpening: Boolean = true) {
        synchronized(lock) {
            // The owner check's hold clears the feed but closes nothing: the opening goes on ([openingContinues]).
            if (!keepHeld && endsOpening) openingEnded = true
            behind = false
            retried = false
            // Kept once, from a loaded list: a second soft clear before a read keeps the first.
            kept = when {
                !keepHeld -> null
                loadState.value == ListLoad.LOADED -> KeptList(cursor, byId.mapValues { it.value.seq })
                else -> kept
            }
            if (!keepHeld) {
                arrivedSince = null
                notifiedSeq.clear()
                markable = false // an answer the device tells after a lock belongs to no opening of this feed
                liftedAt = null
                heldEvents.clear()
                heldArrivals.clear()
            }
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
                // Held while the release is checked (Rule 3). While the owner check holds the vault only the clone alarm's
                // item arrives; it opens the alarm path elsewhere, it is not listed.
                // A live one that comes while the feed is not loaded (its full read under way, or failed) waits for the read
                // and is judged then, as a held one: the opening's backlog drained meanwhile stays silent (0.1.134).
                if (takenElsewhere(m, wake = false)) return
                val item = runCatching { VaultJson.decode(FeedItem.serializer(), m.body) }.getOrNull() ?: return
                onItem(item, notify = !fromTheHold(m, item))
            }
            "sync.event" -> when (VaultJson.str(m.body, "kind")) {
                SYNC_UPDATED, SYNC_DELETED -> if (!gated()) catchUp()
            }
        }
    }

    /**
     * A `feed.event` that was held back while this phone did not know whether its vault was open (`HeldBackEvents`), now
     * that it is: an arrival like a live one. When the full read of the open already brought the item, it arrives as the
     * feed has it now (not at all once read, archived or deleted), and only if a push wake started the process ([wake]:
     * what the push was for) or the vault sent it since the opening; else it is the opening's backlog and stays silent.
     */
    fun replay(m: VaultMessage, wake: Boolean) = replay(m, notified = emptyMap(), wake = wake)

    /**
     * (Rule 3) The vault opened, called as the release check begins, with [phoneNow] the phone's clock then: a held
     * `feed.event` the open read covers notifies only if the vault sent it at or after the opening ([replay]). The opening
     * is on the phone's clock until the first answer of this opening puts it on the vault's ([openingMarked]); never a
     * vault time learnt before it (ANDROID-PLAN 0.1.134). Returns the opening's number, for [openingMarked].
     */
    fun vaultOpened(phoneNow: Instant): Int = synchronized(lock) {
        openedAt = phoneNow
        notifiedSeq.clear()
        liftedAt = null
        arrivedSince = null // a replay window kept across Unreachable belongs to the opening before
        // A new opening holds nothing of an earlier one (a soft clear kept it only for an opening that continues).
        heldEvents.clear()
        heldArrivals.clear()
        kept = null
        markable = true
        openingEnded = false
        ++opening
    }

    /**
     * (Rule 3) The opening [forOpening] on the vault's clock, from the first answer of that opening
     * (`VaultDevice.beginOpening`): kept from now on as a fixed time for it, whatever happens to the device. Ignored for an
     * earlier opening, and after a [clear] until the next opening.
     */
    fun openingMarked(at: Instant, forOpening: Int) {
        synchronized(lock) { if (markable && forOpening == opening) openedAt = at }
    }

    /**
     * (Rule 3) The vault is open again on this phone and only a status read that could not be made came between (the
     * phase's own rule): the same opening carries on, its moment kept, so what the vault sent meanwhile is judged against
     * it, not against a new mark taken now. False when there was no opening to carry on (the caller then opens a new one).
     */
    fun openingContinues(): Boolean = synchronized(lock) {
        if (opening == 0 || openedAt == null || openingEnded) return@synchronized false
        markable = true // a hold's clear left it off; the same opening goes on
        true
    }

    /**
     * (ANDROID-PLAN 0.1.134, "nothing missed while due or held notifies later") The owner check's hold or due state ended at
     * [at], on the vault's clock: an event the vault sent before then (left on the relay during the hold, collected after
     * it) is applied but notifies nothing. Kept until the next opening or a close. What the vault changed during the hold is
     * covered by the full read every lift starts (a full read notifies nothing), so later catch-ups bring only what changed
     * after it: an item is never judged by its own `at`, which a batch's later ask keeps.
     */
    fun ownerCheckLifted(at: Instant?) {
        synchronized(lock) {
            // Not known (no time on the vault's clock): no mark, but the rest all the same.
            if (at != null) liftedAt = liftedAt?.let { maxOf(it, at) } ?: at
            lifts++
            kept = null // the lift's full read notifies nothing: what changed while due is covered by it
            // Held arrivals from before the due state notify nothing after it (the clone alarm's still do); held events
            // are judged by their own `ts` against the lift as they are released.
            heldArrivals.values.removeAll { it.kind !in NOTIFIES_WHILE_HELD }
            // The cursor is what it was before the due state: until a full read, nothing is read after it, and an event
            // that comes waits for that read and is judged then (told before the lift is published, so none comes first).
            if (loadState.value == ListLoad.LOADED) loadState.value = ListLoad.NOT_LOADED
        }
    }

    /**
     * The event was sent while the owner check was due or held ([ownerCheckLifted]), and its kind does not notify then (the
     * clone alarm does, "also while held": [NOTIFIES_WHILE_HELD]).
     */
    private fun fromTheHold(m: VaultMessage, item: FeedItem): Boolean =
        item.kind !in NOTIFIES_WHILE_HELD && sentInTheHold(m.ts)

    private fun sentInTheHold(at: Instant): Boolean = synchronized(lock) { liftedAt }?.let { at.isBefore(it) } ?: false

    /** (Rule 3) The opening's moment as it is judged now (tests). */
    val openingMark: Instant? get() = synchronized(lock) { openedAt }

    /**
     * [replay], except that an item [notified] (it just arrived from [releaseHeld], at that `seq`) is applied but not
     * notified again for a change no newer than the one notified. An event the feed's read covers notifies only if it came
     * from a push wake ([wake]) or the vault sent it since it opened ([openedAt]): the opening's backlog stays silent.
     */
    private fun replay(m: VaultMessage, notified: Map<String, Long>, wake: Boolean) {
        if (m.type != "feed.event" || takenElsewhere(m, wake)) return
        val item = runCatching { VaultJson.decode(FeedItem.serializer(), m.body) }.getOrNull() ?: return
        val notify = notified[item.itemId]?.let { item.seq > it } ?: true
        // Whether the feed's read already covers the event, and the item as the list has it now (null: gone).
        val (covered, listed) = synchronized(lock) {
            (loadState.value == ListLoad.LOADED && item.seq <= cursor) to byId[item.itemId]
        }
        val mayNotify = notify && !fromTheHold(m, item)
        if (!covered) {
            onItem(item, mayNotify)
        } else if (mayNotify && isNewsSinceTheOpening(m, wake) && listed?.status == STATUS_ACTIVE) {
            emitArrival(listed)
        }
    }

    /**
     * [m] goes no further here: dropped while the vault is not open on this phone (a lock drops everything held, so nothing
     * that comes after its clear, a push wake's replay still on its way included, may be held for the next opening), held
     * for the release check, dropped while gated, or waiting for the feed's read.
     */
    private fun takenElsewhere(m: VaultMessage, wake: Boolean): Boolean =
        !vaultOpen() || holds(m, wake) || gated() || !wake && waitsForTheRead(m)

    /**
     * (ANDROID-PLAN 0.1.134) A held-back event of a start no push caused, while the feed is not loaded (its full read under
     * way, or failed): it cannot be told from the opening's backlog yet, so it waits in [heldEvents] and is judged when the
     * read ends ([readEnded], [releaseNow]); a feed with no read under way is read first ([releaseIfOver]). True when it
     * waits, or when it is dropped: one that comes while the vault is not open on this phone ([vaultOpen]: a lock while it
     * was on its way) is kept nowhere and starts no read, since nothing is kept outside an open vault. After the owner
     * check's hold (which clears the feed while the vault stays open) it waits for the read as at any time.
     */
    private fun waitsForTheRead(m: VaultMessage): Boolean {
        var dropped = false
        val waits = synchronized(lock) {
            dropped = !vaultOpen() && loadState.value != ListLoad.LOADED
            (!dropped && loadState.value != ListLoad.LOADED).also { w ->
                if (w) {
                    heldEvents.addLast(HeldEvent(m, wake = false))
                    while (heldEvents.size > ARRIVAL_BUFFER) heldEvents.removeFirst()
                }
            }
        }
        if (waits) releaseIfOver()
        return waits || dropped
    }

    /**
     * (Rule 3, ANDROID-PLAN §6.7) A covered event is news, not the opening's backlog: what a push wake was for, or sent
     * at or after the vault opened ([openedAt]); with no opening marked, backlog.
     */
    private fun isNewsSinceTheOpening(m: VaultMessage, wake: Boolean): Boolean =
        wake || synchronized(lock) { openedAt }?.let { !m.ts.isBefore(it) } ?: false

    /**
     * (Rule 3) While the release is checked, [m] waits for [releaseHeld] (also while a failed check pauses the feed). Once
     * the check is over, an event that comes while what was held has not come yet waits behind it, and releases it first
     * ([queuesBehind]). True when the caller takes it no further: held, or dropped while the feed is [gated] after the
     * check (decided on the same reading as the queue, so a lift that comes meanwhile cannot let it overtake the held ones).
     */
    private fun holds(m: VaultMessage, wake: Boolean): Boolean {
        val checking = held() && !ownerGated()
        val gatedNow = gated()
        val queued = synchronized(lock) {
            (checking || (!gatedNow && queuesBehind())).also { q ->
                if (q) {
                    heldEvents.addLast(HeldEvent(m, wake))
                    while (heldEvents.size > ARRIVAL_BUFFER) heldEvents.removeFirst()
                }
            }
        }
        if (queued) releaseIfOver()
        return queued || gatedNow
    }

    /**
     * (Rule 3) After something was added to the held queues: if the check is over and the feed not [gated], it is released
     * now. Read again after the add, not before: a check that ended (and a release that ran) between the first reading and
     * the add would otherwise leave it waiting for the next release, or for a lock to drop it. Not while [gated] (a check
     * that ended behind): what is held then waits for the lift, whose release delivers it.
     */
    private fun releaseIfOver() {
        if (!held() && !gated()) releaseHeld()
    }

    /**
     * (Rule 3) The check is over but what it held has not come yet ([releaseHeld] not run): a later event or arrival waits
     * behind it, so that none overtakes it (the callers ask only while the feed is not [gated]). Under [lock].
     */
    private fun queuesBehind(): Boolean = heldEvents.isNotEmpty() || heldArrivals.isNotEmpty()

    /**
     * (Rule 3) The release check is over with the vault not behind, or the gate lifted: what waited comes now, as it would
     * have come live: the arrivals as the feed has them now (not at all once read, archived, deleted or no longer listed),
     * then the events
     * ([replay]; an event of an item that just arrived here is applied, and notified again only for a newer change).
     * Nothing while the feed is [gated] (the owner check due or held: as live ones, they are not notified).
     *
     * It all runs under [lock], the notifications' emission included (a non-suspending `tryEmit`): the queues are emptied at
     * its start, so an event or arrival on another thread is kept behind them only by waiting for the lock. Emitting after
     * the lock is let go would let one that comes then (the queues empty, nothing held) be notified before them.
     */
    fun releaseHeld() {
        when {
            // Not while the vault is not open on this phone (a status read that could not be made keeps the queues for the
            // opening that may continue; a close drops them). [releaseNow] looks again.
            !vaultOpen() -> Unit
            // After a read that failed the feed may be behind, though loaded: one catch-up first, the release at its end.
            !gated() && readsRunning.get() == 0 && behind -> catchUp()
            // After the feed's reads under way ([readEnded]), from a loaded feed: one that is not loaded is read first.
            !gated() && (readsRunning.get() > 0 || loadState.value != ListLoad.LOADED) -> if (readsRunning.get() == 0) catchUp()
            else -> releaseNow()
        }
    }

    private fun releaseNow() {
        if (vaultOpen()) releaseNowOpen()
    }

    private fun releaseNowOpen() {
        val gatedNow = gated()
        synchronized(lock) {
            // Read again here, as late as it can be: a full read that began (or a clear) since the caller looked leaves the
            // queues for that read's end ([readEnded]) to release. `loadState` is written outside [lock], so a read starting
            // after this look is not excluded; its end releases what comes after, against the feed it leaves.
            if (!gatedNow && loadState.value != ListLoad.LOADED) return
            val events = heldEvents.toList()
            val arrived = heldArrivals.values.toList()
            heldEvents.clear()
            heldArrivals.clear()
            if (gatedNow) return
            val notified = HashMap<String, Long>()
            arrived.forEach { a ->
                // The feed's item as it is now, never the copy held: one the feed no longer lists (deleted, past retention)
                // is not notified (owner decision 2026-11-18).
                byId[a.itemId]?.takeIf { it.status == STATUS_ACTIVE }?.let {
                    notified[it.itemId] = it.seq
                    // A held waiting-asks replay stays one ([FeedItem.replayed]): it is posted as a new arrival.
                    emitArrival(if (a.replayed) it.copy(replayed = true) else it)
                }
            }
            events.forEach { replay(it.m, notified, it.wake) }
        }
    }

    private fun onItem(item: FeedItem, notify: Boolean = true) {
        val inOrder = synchronized(lock) {
            if (loadState.value != ListLoad.LOADED) return@synchronized false
            if (item.seq <= cursor) return // already applied
            val next = item.seq == cursor + 1
            apply(listOf(item))
            if (next) cursor = item.seq
            next
        }
        if (notify && item.status == STATUS_ACTIVE) emitArrival(item)
        if (!inOrder) catchUp()
    }

    /**
     * Reads what changed after the cursor (a `seq` gap, another device's change, a return to the foreground or a
     * relay reconnect). Coalesced: one read in flight and at most one queued behind it; before the first full read,
     * a full read.
     */
    fun catchUp() {
        // §3.6.3 and Rule 3: nothing is read while the check is due or held, or the release gate pauses the vault's work
        // (every caller: the return to the foreground, the relay's reconnection, a push wake, `sync.event`).
        if (gated() || catchUpQueued) return
        catchUpQueued = true
        readsRunning.incrementAndGet()
        scope.launch {
            var ok = false
            try {
                ok = runCatching {
                    reads.withLock {
                        catchUpQueued = false
                        if (loadState.value == ListLoad.LOADED) readAfter(full = false) else readAfter(full = true)
                    }
                }.isSuccess
            } finally {
                readEnded(ok)
            }
        }
    }

    override suspend fun refresh() {
        readsRunning.incrementAndGet()
        runCounted()
    }

    /**
     * (Rule 3) A full read ([refresh]) counted among the reads under way from now, before it runs: a release asked for
     * between now and its start waits for it (the owner check's lift: the read of `afterHoldPassed` is launched, and the
     * lift's sends release what was held). [ReservedRead.run] runs it; [ReservedRead.drop] gives the place back unrun. Each
     * reservation ends exactly once.
     */
    fun reserveRefresh(): ReservedRead {
        readsRunning.incrementAndGet()
        return ReservedRead()
    }

    inner class ReservedRead internal constructor() {
        private val ended = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Runs the read reserved (at most once; a second call, or one after [drop], reads nothing). */
        suspend fun run() {
            if (ended.compareAndSet(false, true)) runCounted()
        }

        /**
         * The read reserved will not run: its place is given back (nothing if it ran or was dropped), and a release that
         * waited only for it goes ahead as if it had never been reserved (it read nothing, so it left the feed as it was).
         */
        fun drop() {
            if (ended.compareAndSet(false, true)) readEnded(ok = true, ran = false)
        }
    }

    /** A full read already counted in [readsRunning]. */
    private suspend fun runCounted() {
        var ok = false
        try {
            reads.withLock { readAfter(full = true) }
            ok = true
        } finally {
            readEnded(ok)
        }
    }

    /**
     * (Rule 3) A read ended: when it was the last one under way, it succeeded and the feed is loaded, what was held comes
     * now (if the check is over and nothing gates the feed). A read that failed releases nothing and starts no other (no
     * loop), also an incremental one that left the feed loaded but behind: the next event, catch-up (the return to the
     * foreground, the relay's reconnection, a push wake) or release ([releaseHeld]) does.
     */
    private fun readEnded(ok: Boolean, ran: Boolean = true) {
        // A reservation given back unrun read nothing: what the last read said of the feed stands.
        if (ran) {
            behind = !ok
            if (ok) retried = false else retryOnce()
        }
        if (readsRunning.decrementAndGet() > 0 || !ok) return
        val releasable = vaultOpen() && !held() && !gated() && loadState.value == ListLoad.LOADED
        if (releasable && synchronized(lock) { queuesBehind() }) releaseNow()
    }

    /** One paged read from the cursor ([full]: from 0, replacing everything). Runs under [reads]. */
    private suspend fun readAfter(full: Boolean) {
        val (gen, liftsAtStart) = synchronized(lock) { generation to lifts }
        if (full) loadState.value = ListLoad.LOADING
        try {
            if (full) readRetention()
            val (changed, seq) = pages(if (full) 0L else synchronized(lock) { cursor })
            val arrived = when (val a = applyRead(full, gen, liftsAtStart, changed, seq)) {
                Applied.Cleared -> return
                Applied.Lifted -> return readAfter(full = true)
                is Applied.Read -> a.arrived
            }
            arrived.distinctBy { it.itemId }.forEach { emitArrival(byIdNow(it.itemId) ?: it) }
            loadState.value = ListLoad.LOADED
        } catch (e: VaultFailure) {
            if (full && synchronized(lock) { gen == generation }) loadState.value = ListLoad.FAILED
            throw e
        }
    }

    /** What a read's answer came to ([applyRead]). */
    private sealed interface Applied {
        /** A [clear] came since the read began: nothing applied. */
        data object Cleared : Applied

        /** A read across a lift ([ownerCheckLifted]): nothing applied; a full read again. */
        data object Lifted : Applied

        class Read(val arrived: List<FeedItem>) : Applied
    }

    /** A read's answer applied, under [lock], unless a clear or (for an incremental read) a lift came since it began. */
    private fun applyRead(full: Boolean, gen: Int, liftsAtStart: Int, changed: List<FeedItem>, seq: Long): Applied =
        synchronized(lock) {
            when {
                gen != generation -> Applied.Cleared
                // A full read too: begun before the lift, its cursor may stand before changes of the due state.
                lifts != liftsAtStart -> Applied.Lifted
                else -> {
                    val before = if (full) emptyMap() else byId
                    if (full) byId = emptyMap()
                    apply(changed)
                    cursor = maxOf(if (full) 0 else cursor, seq)
                    // The first full read after a blip notifies what a catch-up from the kept cursor would have ([kept]).
                    val arrived = when {
                        !full -> newUnread(changed) { before[it]?.seq }
                        else -> kept?.let { k -> newUnread(changed.filter { it.seq > k.cursor }) { k.seqs[it] } }.orEmpty()
                    }
                    if (full) kept = null
                    Applied.Read(arrived)
                }
            }
        }

    /** `feed.list{after_seq}` paged from [from] until a page is shorter than the limit: the items and the answer's `seq`. */
    private suspend fun pages(from: Long): Pair<List<FeedItem>, Long> {
        var after = from
        val changed = ArrayList<FeedItem>()
        var seq = from
        for (i in 0 until MAX_PAGES) {
            val page = vaultGuard(resend = true) { ops().list(after, PAGE) }
            seq = maxOf(seq, page.seq)
            changed += page.items
            if (page.items.size < PAGE) break
            after = page.items.last().seq
        }
        return changed to seq
    }

    private suspend fun readRetention() {
        val d = runCatching { vaultGuard(resend = true) { ops().retentionDays() } }.getOrNull() ?: return
        if (d in 1..MAX_RETENTION_DAYS) retentionDays = d
    }

    /** What a catch-up brought that is new or changed, and unread (a full read brings nothing new). */
    private fun newUnread(changed: List<FeedItem>, seqBefore: (String) -> Long?): List<FeedItem> =
        changed.filter { c -> c.status == STATUS_ACTIVE && (seqBefore(c.itemId) ?: 0) < c.seq }

    private fun byIdNow(id: String): FeedItem? = synchronized(lock) { byId[id] }

    /** Items that arrived since [startReplayWindow] (live or a catch-up): the replay does not send them again. */
    private var arrivedSince: MutableSet<String>? = null

    /** The replay that owns [arrivedSince]: a later one replaces it, and an earlier one then sends nothing. */
    private var replayToken = 0

    /** Before the read of a hold's end: what arrives from here on is noted, so that [replayWaitingAsks] skips it. */
    fun startReplayWindow(): Int = synchronized(lock) {
        arrivedSince = mutableSetOf()
        ++replayToken
    }

    /** The replay [token] still owns an open window: no later replay replaced it, and no clear (a lock, a hold) closed it. */
    fun replayOwns(token: Int): Boolean = synchronized(lock) { token == replayToken && arrivedSince != null }

    /** The replay [token] will not run: what arrives is no longer noted (unless a later replay owns the window). */
    fun endReplayWindow(token: Int) {
        synchronized(lock) { if (token == replayToken) arrivedSince = null }
    }

    /**
     * (Owner decision a, 2026-11-15) A hold ended by a passed check: each ask still waiting ([WaitingAsks], against
     * [approvals] as read after the hold) arrives once, so that it notifies; nothing else of the full read does, and an
     * ask that already arrived since [startReplayWindow] (live, during the reads) is not sent twice. False, with the window
     * kept, while the feed is not loaded (a read failed or runs): the caller reads it and asks again. True once done (sent,
     * or nothing to send: a later replay owns the window, or the check is due or held again).
     */
    fun replayWaitingAsks(token: Int, approvals: List<com.vettid.core.data.social.Approval>, now: java.time.Instant): Boolean {
        // Null: not done, the window kept.
        val waiting: List<FeedItem>? = synchronized(lock) {
            when {
                token != replayToken -> emptyList() // a later replay owns the window and sends what is waiting
                gated() -> emptyList<FeedItem>().also { arrivedSince = null }
                loadState.value != ListLoad.LOADED -> null
                else -> {
                    val seen = arrivedSince.also { arrivedSince = null }
                    WaitingAsks.of(byId.values, approvals, now).filterNot { seen != null && it.itemId in seen }
                }
            }
        }
        waiting?.forEach { emitArrival(it.copy(replayed = true)) }
        return waiting != null
    }

    /** The feed holds a full read (none failed or runs since, and nothing cleared it). */
    val loaded: Boolean get() = loadState.value == ListLoad.LOADED

    /** A notification's source, except the History-only kinds (ANDROID-PLAN 0.1.52 §4 "Event titles", de-duplication). */
    private fun emitArrival(item: FeedItem) {
        synchronized(lock) { arrivedSince?.add(item.itemId) }
        if (FeedKinds.historyOnly(item.kind)) return
        // Rule 3: while the release is checked, nothing notifies yet ([releaseHeld]); after it, nothing overtakes what it held.
        // Not while the owner check is due or held ([ownerGated]): then it goes as it would with no release check.
        val checking = held() && !ownerGated()
        val gatedNow = gated()
        val waits = synchronized(lock) {
            (checking || (!gatedNow && queuesBehind())).also { w ->
                if (w) {
                    // A newer arrival of a held waiting-asks replay is still that replay (posted as a new arrival).
                    val replay = heldArrivals.remove(item.itemId)?.replayed == true
                    heldArrivals[item.itemId] = if (replay && !item.replayed) item.copy(replayed = true) else item
                    while (heldArrivals.size > ARRIVAL_BUFFER) heldArrivals.remove(heldArrivals.keys.first())
                }
            }
        }
        if (waits) {
            releaseIfOver()
        } else if (firstAlertAt(item)) {
            arrivalFlow.tryEmit(item)
        }
    }

    /**
     * (ANDROID-PLAN 0.1.134) Not notified again during this opening for the same or an older change (`seq`); noted when it
     * is. A waiting-asks replay ([FeedItem.replayed]) is the replay rule's own second alert (Notifications 7, 0.1.132),
     * so it is not held back by this, only noted; a quiet re-post ([FeedItem.quietRepost]: a withdrawn ask's notification
     * updated without sound) is no alert, and passes.
     */
    private fun firstAlertAt(item: FeedItem): Boolean = synchronized(lock) {
        if (item.quietRepost) return@synchronized true
        val last = notifiedSeq[item.itemId]
        if (!item.replayed && last != null && item.seq <= last) return@synchronized false
        notifiedSeq[item.itemId] = maxOf(item.seq, last ?: item.seq)
        true
    }

    /** Replaces each item by id, removes the deleted ones, drops what is past retention, and publishes. Under [lock]. */
    private fun apply(changed: List<FeedItem>) {
        if (changed.isEmpty()) {
            publish()
            return
        }
        val m = byId.toMutableMap()
        for (item in changed) {
            // The History-only kinds (ANDROID-PLAN 0.1.52 §4 "Event titles") are neither listed nor counted.
            if (item.status == STATUS_DELETED || FeedKinds.historyOnly(item.kind)) m.remove(item.itemId) else m[item.itemId] = item
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

    /**
     * (VAULT-MESSAGING 0.80.0 §10.4) One ask of a connection's asks was withdrawn. The vault leaves the feed item as it is
     * (a batch's `count` is unchanged).
     * - The withdrawn ask's own item without a `count` stands only for that ask: it is marked read.
     * - A batch's item is marked read when no ask of the batch is still open; otherwise it stays and its notification is
     *   posted again, quietly (and only if it is still showing), with the asks left, the earliest deadline among them for
     *   display and the latest to go by itself.
     * Nothing happens for an ask whose item this feed does not hold.
     */
    fun askWithdrawn(b: WithdrawnBatch) {
        // The withdrawn ask's own item first, even with a count: another batch's item is never picked for it.
        val (own, item) = synchronized(lock) { ownItem(b).let { it to (it ?: batchItem(b)) } }
        if (own != null && own.count == null) {
            markReadQuietly(own.itemId)
            return
        }
        if (item == null) return
        if (b.remaining <= 0) {
            markReadQuietly(item.itemId)
        } else if (item.status == STATUS_ACTIVE) {
            emitArrival(
                item.copy(count = b.remaining.takeIf { it > 1 }, openUntil = b.openUntil, closeAt = b.closeAt, quietRepost = true),
            )
        }
    }

    /** The ask-kind item whose `ref` is the withdrawn ask itself. Under [lock]. */
    private fun ownItem(b: WithdrawnBatch): FeedItem? =
        byId.values.firstOrNull { it.connectionId == b.connectionId && FeedAsks.approvalKey(it.kind, it.ref) == b.askKey }

    /**
     * The batch item that stands for [b]: the ask-kind item of the connection whose `ref` is one of the batch's asks; if
     * the first ask of the batch is gone already, the newest unread batch item of the connection that began within the
     * window before the ask was created, by the vault's own times where the app has the ask's (else the phone's).
     */
    private fun batchItem(b: WithdrawnBatch): FeedItem? {
        val items = byId.values.filter { it.connectionId == b.connectionId && it.kind in FeedAsks.ASK_KINDS }
        items.firstOrNull { FeedAsks.approvalKey(it.kind, it.ref) in b.askKeys }?.let { return it }
        val at = b.askTs ?: b.receivedAt
        val from = at.minus(BATCH_WINDOW).minus(BATCH_SKEW)
        val to = at.plus(BATCH_SKEW)
        return items.filter { it.count != null && it.status == STATUS_ACTIVE }
            .mapNotNull { i -> runCatching { Instant.parse(i.at) }.getOrNull()?.let { i to it } }
            .filter { (_, t) -> !t.isBefore(from) && !t.isAfter(to) }
            .maxByOrNull { it.second }?.first
    }

    override suspend fun delete(itemId: String) {
        vaultGuard { ops().delete(itemId) }
        synchronized(lock) {
            byId = byId - itemId
            publish()
        }
    }

    override fun markViewed(itemIds: Collection<String>) {
        val wanted = itemIds.toSet()
        val ids = synchronized(lock) {
            byId.values.filter { it.itemId in wanted && it.status == STATUS_ACTIVE && it.priority != PRIORITY_URGENT }.map { it.itemId }
        }
        if (ids.isEmpty()) return
        scope.launch { runCatching { markRead(ids) } }
    }

    override suspend fun markAllRead() {
        markRead(synchronized(lock) { byId.values.filter { it.status == STATUS_ACTIVE }.map { it.itemId } })
    }

    /** `feed.update{status: "read"}` for each of [ids], at most 4 in flight; throws the first failure after the others ran. */
    private suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        val failures = coroutineScope {
            ids.map { id ->
                async {
                    marking.withPermit {
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
        /** Kinds that notify while the owner check holds the vault (Notification modes: the clone alarm, "also while held"). */
        val NOTIFIES_WHILE_HELD = setOf("credential.alarm")

        /** (Rule 3) How long after a failed read the one catch-up for what is held waits ([retryOnce]). */
        const val RETRY_AFTER_MS = 5_000L

        private val BATCH_WINDOW: java.time.Duration = AskBatching.WINDOW
        private val BATCH_SKEW: java.time.Duration = java.time.Duration.ofMinutes(2)
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
        private const val ARRIVAL_BUFFER = 64

        /** An item's `at`; null when it does not parse. */
        fun at(item: FeedItem): Instant? = try {
            Instant.parse(item.at)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * A withdrawn ask of [connectionId]'s asks (VAULT-MESSAGING 0.80.0 §10.4): [askKeys] are the keys of the batch it was
 * in (itself included), [receivedAt] when it reached the member, [remaining] the asks of the batch still open and
 * [openUntil] the earliest `exp` among them.
 */
data class WithdrawnBatch(
    val connectionId: String,
    val askKeys: Set<String>,
    val receivedAt: Instant,
    val remaining: Int,
    val openUntil: Instant?,
    /** The withdrawn ask's key (`auth:<request_id>`). */
    val askKey: String = "",
    /** When the re-posted notification may go by itself: the latest `exp` of the asks left; null if none, or one has no `exp`. */
    val closeAt: Instant? = null,
    /** The vault's time of the ask's creation event, when the app still has it. */
    val askTs: Instant? = null,
)
