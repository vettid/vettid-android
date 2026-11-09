package com.vettid.core.notify

import android.content.res.Resources
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.IdleLocked
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private val NO_IDLE: StateFlow<IdleLocked?> = MutableStateFlow(null)

/** The member's notification settings (device preferences, DataStore): the mode and what notifications show. */
data class NotifySettings(
    val mode: NotificationMode = NotificationMode.SERVICE,
    val previews: NotificationPreviews = NotificationPreviews.NAMES,
)

/**
 * The screen the member is on while VettID is in the foreground ([NotifyVisibility]): nothing about what it shows is
 * notified (ANDROID-PLAN 0.1.23, Notification modes 7, "none").
 */
sealed interface Visible {
    data object Notifications : Visible

    data class Target(val target: FeedTarget) : Visible
}

/** Where the app's shell says which screen is in front; null in the background. */
class NotifyVisibility {
    private val flow = MutableStateFlow<Visible?>(null)
    val visible: StateFlow<Visible?> = flow.asStateFlow()

    fun set(v: Visible?) {
        flow.value = v
    }
}

/**
 * What [FeedNotifier] reads of the vault on this phone: the feed's new and changed items ([arrivals]: live events and
 * catch-ups, never a full read, so that nothing missed is replayed), the items now, whether the vault is open and not
 * held, the owner check, the clone alarm, and the names to show.
 */
interface NotifySource {
    val arrivals: Flow<FeedItem>
    val items: StateFlow<List<FeedItem>>

    /** The vault is open on this phone and not held (§3.6.3). */
    val open: StateFlow<Boolean>

    /**
     * The app lock's cover is up (locked, or not yet known): the app may be in front but the member has not passed the
     * biometric or screen-lock gate, so no notification shows a name, a title or a text (heads-up and shade included).
     */
    val covered: StateFlow<Boolean>

    /** [covered] read now from its source, not through a flow that may lag a dispatch behind it. */
    fun coveredNow(): Boolean = covered.value

    /** The vault locked, from another device or by itself (not by the member on this phone): "Your vault is locked". */
    val lockedElsewhere: StateFlow<Boolean>

    /**
     * (VAULT-MESSAGING 0.29.0 §3.6.8, release L) The vault locked itself because no check was passed in time
     * (`vault.locking{reason: "idle"}`): the locked notification says so.
     */
    val idleLocked: StateFlow<IdleLocked?> get() = NO_IDLE
    val ownerCheck: StateFlow<OwnerCheckView?>
    val alarm: StateFlow<CredentialAlarm?>

    fun names(): FeedNames

    /**
     * Reads the votes list afresh (review 1, M3): true when the read went through, so that [names]' `votesGaveUp` is the
     * vault's state after the item arrived. A source without votes has nothing to read.
     */
    suspend fun refreshVotes(): Boolean = true

    /** A message's text from the app's in-memory cache (shown only with "Names and message text"). */
    suspend fun messageText(connectionId: String, messageId: String): String?
}

/**
 * Turns the feed and the vault's state into Android notifications through the member's mode (ANDROID-PLAN 0.1.23,
 * Notification modes 7 and 8). One per item, updated in place for a batch (alert once), cancelled when the item is
 * read, archived or deleted on any device; every item notification is cancelled when the vault locks or is held,
 * and with the Off mode. The vault's own state: "Your vault is locked" (Security), "Daily check due" with counts only
 * (Daily check, updated in place), and the clone alarm while held (Security). Nothing here is kept on disk.
 */
@Suppress("TooManyFunctions")
class FeedNotifier(
    private val scope: CoroutineScope,
    private val source: NotifySource,
    private val settings: StateFlow<NotifySettings>,
    private val visibility: StateFlow<Visible?>,
    private val res: () -> Resources,
    private val poster: Poster,
) {
    /** The item notifications showing, by tag, as last posted but with the names taken out (memory only). */
    private val posted = LinkedHashMap<String, LocalNotification>()
    private val lock = Any()

    /** A cover-up ran for the cover that is up now; reset when the cover is seen gone or a named notification is posted. */
    private var coverDone = false

    /** The `vote.result` items waiting for the votes list, by tag: the arrival each waits as (under [lock]). */
    private val voteWaiting = HashMap<String, Long>()
    private var voteSeq = 0L

    /** Every `vote.result` arrival still waiting, in the order they arrived: they are posted in that order (under [lock]). */
    private val voteQueue = ArrayDeque<VoteWait>()

    /** A `vote.result` arrival waiting for the votes list; [n] is set (or left null: nothing to post) once it is [ready]. */
    private class VoteWait(val item: FeedItem, val seq: Long) {
        var ready = false
        var n: LocalNotification? = null
    }

    /** The votes list read asked for and not yet started, and whether a read is running (under [lock]). */
    private var voteReadNext: CompletableDeferred<Boolean>? = null
    private var voteReading = false

    private val on: Boolean get() = settings.value.mode != NotificationMode.OFF

    fun start() {
        scope.launch { source.arrivals.collect { onArrival(it) } }
        scope.launch { source.items.collect { onItems(it) } }
        scope.launch { source.open.collect { open -> if (!open) cancelItems() } }
        scope.launch { source.covered.collect { if (it) coverUp() else synchronized(lock) { coverDone = false } } }
        scope.launch { settings.map { it.mode }.distinctUntilChanged().collect { if (it == NotificationMode.OFF) cancelAll() } }
        scope.launch { combine(source.lockedElsewhere, source.idleLocked) { l, i -> l to i }.collect { (l, i) -> onLocked(l, i) } }
        scope.launch { source.ownerCheck.collect { onOwnerCheck(it) } }
        scope.launch { source.alarm.collect { onAlarm(it) } }
    }

    @Suppress("ReturnCount")
    private suspend fun onArrival(item: FeedItem) {
        // The cover's timer runs on monotonic time, which stops in deep sleep: the clock is looked at here too.
        recheckCover()
        // A re-post after a withdrawal only updates what is showing: a notification the member dismissed stays dismissed.
        if (item.quietRepost && !synchronized(lock) { stillShowing(item) }) return
        val channel = NotificationMapping.channel(item)?.takeIf { notifiable(item) } ?: return
        val covered = source.coveredNow()
        if (item.kind == VOTE_RESULT && !covered) {
            // Titled in its own job (#313): the items behind it are not held up by the list read. A later arrival of the
            // same item replaces one still waiting. Checked and queued in one go: a lock cannot come in between.
            val (wait, read) = synchronized(lock) {
                if (!notifiable(item)) return
                val w = VoteWait(item, ++voteSeq)
                voteWaiting[item.itemId] = w.seq
                voteQueue.addLast(w)
                w to requestVoteRead()
            }
            scope.launch { postVoteResult(wait, channel, read) }
            return
        }
        // Posted now (under the cover): one still waiting for the list must not replace it.
        if (item.kind == VOTE_RESULT) synchronized(lock) { voteWaiting.remove(item.itemId) }
        // The app lock's cover is up: the public form only, whatever the member's choice of previews (nothing is read).
        val previews = if (covered) NotificationPreviews.NOTHING else settings.value.previews
        val names = if (covered) FeedNames() else source.names()
        val text = if (previews == NotificationPreviews.NAMES_AND_TEXT && item.kind == "message.received") {
            val c = item.connectionId
            val m = item.ref
            if (c != null && m != null) runCatching { source.messageText(c, m) }.getOrNull() else null
        } else {
            null
        }
        val n = NotificationContent.of(item, channel, previews, names, res(), text)
        synchronized(lock) { postLocked(item, n) }
    }

    /**
     * A `vote.result` is the give-up item only by its vote's state in the list (it carries none), and that list may be
     * older than the item (a re-check's verdict, or the vault giving up on its own clock): it is titled from the list as
     * read again after it arrived ([read], shared by every `vote.result` that arrived before that read started), waiting
     * at most [VOTE_LIST_WAIT_MS]; if the read does not go through in time, the generic "Vote results are out" (review 1,
     * M3). The items that arrive while a read runs wait for the next one, which has only what is left of their 3 s, so
     * after a slow read they mostly get the generic title. Posted in the order they arrived ([drainVoteResults]).
     */
    private suspend fun postVoteResult(wait: VoteWait, channel: String, read: Deferred<Boolean>) {
        var n: LocalNotification? = null
        try {
            val fresh = withTimeoutOrNull(VOTE_LIST_WAIT_MS) { read.await() } == true
            val covered = source.coveredNow()
            val previews = if (covered) NotificationPreviews.NOTHING else settings.value.previews
            val names = when {
                covered -> FeedNames()
                fresh -> source.names()
                else -> source.names().copy(votesGaveUp = emptySet())
            }
            n = NotificationContent.of(wait.item, channel, previews, names, res(), null)
        } finally {
            // Always marked ready, so that one that failed never holds up those behind it.
            synchronized(lock) {
                wait.n = n
                wait.ready = true
                drainVoteResults()
            }
        }
    }

    /**
     * Under [lock]: posts the waiting `vote.result` items that are ready, in the order they arrived, up to the first one
     * still waiting (they share reads and are ready in that order, give or take a dispatch). One is dropped if the same
     * item arrived again since ([voteWaiting]), the vault locked meanwhile (it was forgotten, then [postLocked]), or the
     * feed now lists it as read or archived. The dismissal check is a defence only: a `vote.result` is never a quiet
     * re-post.
     */
    private fun drainVoteResults() {
        while (voteQueue.firstOrNull()?.ready == true) {
            val w = voteQueue.removeFirst()
            val n = w.n
            if (stillWaiting(w) && n != null) postLocked(w.item, n)
        }
    }

    /** Under [lock]: [w] is the item's latest arrival (it is forgotten now), still active and not dismissed. */
    private fun stillWaiting(w: VoteWait): Boolean {
        val id = w.item.itemId
        if (voteWaiting[id] != w.seq) return false
        voteWaiting.remove(id)
        val inactive = source.items.value.any { it.itemId == id && it.status != FeedManager.STATUS_ACTIVE }
        return !inactive && !(w.item.quietRepost && !stillShowing(w.item))
    }

    /**
     * A votes list read that starts after this call: the one asked for and not yet started if there is one (a burst of
     * `vote.result` items shares it), else a new one. A read already running started before the item arrived, so it is
     * not used: the next one starts when it ends.
     */
    private fun requestVoteRead(): Deferred<Boolean> = synchronized(lock) {
        voteReadNext ?: CompletableDeferred<Boolean>().also {
            voteReadNext = it
            if (!voteReading) {
                voteReading = true
                scope.launch { runVoteReads() }
            }
        }
    }

    /** Runs the reads asked for, one at a time, each bounded by [VOTE_LIST_WAIT_MS]. */
    private suspend fun runVoteReads() {
        while (true) {
            // [voteReading] is reset only here, in the block that finds nothing to read: once it is false another runner
            // may start, and this one never touches it again (the author's Opus review of #313, finding 1).
            val next = synchronized(lock) {
                voteReadNext.also { voteReadNext = null } ?: run {
                    voteReading = false
                    null
                }
            } ?: return
            try {
                next.complete(readVotes())
            } catch (e: CancellationException) {
                // The notifier's scope is going, or a stray cancellation came out of the read: this runner stops. The read
                // asked for next goes too (its items get the generic title), so that a later arrival asks for a new one and
                // starts a new runner, never waits on a read no runner will serve.
                synchronized(lock) {
                    voteReading = false
                    voteReadNext?.complete(false)
                    voteReadNext = null
                }
                throw e
            } finally {
                next.complete(false)
            }
        }
    }

    private suspend fun readVotes(): Boolean = withTimeoutOrNull(VOTE_LIST_WAIT_MS) {
        try {
            source.refreshVotes()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            false
        }
    } == true

    /** Under [lock]: the notification [item] had is still showing (not dismissed). */
    private fun stillShowing(item: FeedItem): Boolean = item.itemId in posted.keys && poster.isPosted(item.itemId)

    /**
     * Under [lock], the lock the cancels hold: posts [n] only if it still may be. An item that was being prepared while
     * the vault locked or the phone was wiped is not posted after its cancel; the cover that came up meanwhile gives
     * the public form.
     */
    private fun postLocked(item: FeedItem, n: LocalNotification) {
        if (!notifiable(item)) return
        val shown = if (source.coveredNow()) {
            n.publicForm(quiet = false)
        } else {
            coverDone = false
            n
        }
        posted[item.itemId] = shown.publicForm(quiet = true)
        poster.post(shown)
    }

    private fun notifiable(item: FeedItem): Boolean =
        on && source.open.value && item.status == FeedManager.STATUS_ACTIVE && !shownOnScreen(item)

    /** The Notifications screen, or the screen the item opens, is in front: the bell and the screen show it. */
    private fun shownOnScreen(item: FeedItem): Boolean = when (val v = visibility.value) {
        null -> false
        Visible.Notifications -> true
        is Visible.Target -> v.target == FeedKinds.target(item)
    }

    /** Read, archived or deleted here or on another device: its notification goes. */
    private fun onItems(items: List<FeedItem>) {
        val active = items.filter { it.status == FeedManager.STATUS_ACTIVE }.map { it.itemId }.toSet()
        synchronized(lock) {
            val gone = posted.keys.filter { it !in active }.also { posted.keys.removeAll(it.toSet()) }
            gone.forEach { poster.cancel(it) }
        }
    }

    /** The vault locked or is held, the mode is Off, or the phone was wiped: no notification names anything. */
    private fun cancelItems() {
        synchronized(lock) {
            val all = posted.keys.toList().also { posted.clear() }
            // Those still waiting for the votes list are not posted, even if the vault opens again before they would be.
            voteWaiting.clear()
            all.forEach { poster.cancel(it) }
            // Those a process before this one posted (the set above is memory only): at start the vault is not open
            // yet, so every item notification still showing is one that outlived the open vault (ANDROID-PLAN §4,
            // Notification modes 8). The vault's own state notices stay.
            runCatching { poster.cancelLeftovers(STATE_TAGS) }
        }
    }

    /**
     * The app lock's cover came up: what is showing is posted again in its public form ("New activity in VettID"), in
     * place and without a sound. One the member dismissed stays dismissed. Not undone when the cover goes: the names
     * come back with the item's next change.
     */
    private fun coverUp() {
        synchronized(lock) {
            // The cover went away meanwhile: a re-post now could replace a named one posted after it.
            if (!source.coveredNow()) {
                coverDone = false
                return
            }
            if (coverDone) return
            coverDone = true
            // Every tag still in [posted]: a dismissed one was taken out by [onDismissed]; a snoozed one is not active but is
            // here, and posting to its tag updates it without showing it, so the named original cannot come back.
            posted.values.toList().forEach { poster.post(it) }
        }
    }

    /** The member dismissed the notification [tag] (its delete intent): it is not re-posted by a cover-up. */
    fun onDismissed(tag: String?) {
        if (tag == null) return
        synchronized(lock) { posted.remove(tag) }
    }

    /**
     * Looks at the cover now (not at its timer): the app calls it when the member turns the screen on or unlocks the phone,
     * and every arrival does. If it is up and no cover-up ran for it, what is showing is re-posted in public form.
     */
    fun recheckCover() = coverUp()

    private fun cancelAll() {
        cancelItems()
        listOf(TAG_LOCKED, TAG_CHECK, TAG_ALARM).forEach { poster.cancel(it) }
    }

    /** "Your vault is locked"; (release L) "Your vault locked itself" for the idle lock ([idle]), in place of it. */
    private fun onLocked(locked: Boolean, idle: IdleLocked?) {
        if (!locked) {
            poster.cancel(TAG_LOCKED)
            return
        }
        if (!on) return
        val r = res()
        val (title, body) = if (idle == null) {
            r.getString(R.string.notify_locked_title) to r.getString(R.string.notify_locked_body)
        } else {
            val after = idle.afterSeconds
            val text = if (after != null) {
                r.getString(R.string.notify_idle_locked_body, durationText(after, r))
            } else {
                r.getString(R.string.notify_idle_locked_body_unknown)
            }
            r.getString(R.string.notify_idle_locked_title) to text
        }
        val public = r.getString(R.string.notify_public_attention)
        poster.post(LocalNotification(TAG_LOCKED, Channels.SECURITY, title, body, public, alertOnce = true))
    }

    /** Held or due (§3.6.3): one "Daily check due" with the counts only, updated in place; never names. */
    private fun onOwnerCheck(v: OwnerCheckView?) {
        val held = v != null && v.state != OwnerCheckState.OK
        if (!held || !on) {
            poster.cancel(TAG_CHECK)
            return
        }
        val r = res()
        poster.post(
            LocalNotification(
                TAG_CHECK, Channels.OWNER_CHECK, r.getString(R.string.notify_check_due_title), waitingText(v?.waiting, r),
                r.getString(R.string.notify_public_attention), alertOnce = true,
            ),
        )
    }

    /** The clone alarm while held (its feed item is not listed then): on Security. Otherwise its feed item notifies. */
    private fun onAlarm(a: CredentialAlarm?) {
        if (a == null || a.state == CredentialAlarm.STATE_RESOLVED) {
            poster.cancel(TAG_ALARM)
            return
        }
        val held = source.ownerCheck.value?.let { it.state != OwnerCheckState.OK } == true
        if (!on || !held) return
        val r = res()
        poster.post(
            LocalNotification(
                TAG_ALARM, Channels.SECURITY, r.getString(com.vettid.core.data.R.string.data_feed_credential_alarm), null,
                r.getString(R.string.notify_public_attention), alertOnce = true,
            ),
        )
    }

    companion object {
        /** How long a `vote.result` waits for the votes list read, and a read may take, before it is titled generically (M3). */
        const val VOTE_LIST_WAIT_MS = 3_000L
        private const val VOTE_RESULT = "vote.result"
        const val TAG_LOCKED = "vault.locked"
        const val TAG_CHECK = "owner_check.due"
        const val TAG_ALARM = "credential.alarm"
        private val STATE_TAGS = setOf(TAG_LOCKED, TAG_CHECK, TAG_ALARM)

        /**
         * [seconds] in words, as the screens say a duration (`Durations` in :core:ui), but one whole day is "1 day": whole days in days,
         * whole hours in hours, from 10 minutes in minutes rounded up, under that in seconds.
         */
        fun durationText(seconds: Long, r: Resources): String {
            val s = seconds.coerceAtLeast(0)
            val (res, n) = when {
                // One day is "1 day", as the idle lock's setting names it (approved by the owner 2026-10-24).
                s >= DAY && s % DAY == 0L -> R.plurals.notify_duration_days to s / DAY
                s >= HOUR && s % HOUR == 0L -> R.plurals.notify_duration_hours to s / HOUR
                s >= SECONDS_UP_TO -> R.plurals.notify_duration_minutes to (s + MINUTE - 1) / MINUTE
                else -> R.plurals.notify_duration_seconds to s
            }
            return r.getQuantityString(res, n.toInt(), n.toInt())
        }

        private const val MINUTE = 60L
        private const val HOUR = 3_600L
        private const val DAY = 86_400L
        private const val SECONDS_UP_TO = 600L

        /** "3 messages and 1 request are waiting"; unknown counts are "Something may be waiting", never "nothing new". */
        fun waitingText(w: WaitingCounts?, r: Resources): String {
            if (w == null) return r.getString(R.string.notify_check_unknown)
            val parts = listOfNotNull(
                w.messages.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notify_count_messages, it, it) },
                w.requests.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notify_count_requests, it, it) },
                w.calls.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notify_count_calls, it, it) },
                w.other.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.notify_count_other, it, it) },
            )
            return when (parts.size) {
                0 -> r.getString(R.string.notify_check_nothing_counted)
                1 -> r.getString(R.string.notify_check_waiting, parts[0])
                else -> r.getString(
                    R.string.notify_check_waiting,
                    r.getString(R.string.notify_and, parts.dropLast(1).joinToString(", "), parts.last()),
                )
            }
        }
    }
}
