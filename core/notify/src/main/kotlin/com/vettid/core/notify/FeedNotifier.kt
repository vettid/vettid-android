package com.vettid.core.notify

import android.content.res.Resources
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /** The vault locked, from another device or by itself (not by the member on this phone): "Your vault is locked". */
    val lockedElsewhere: StateFlow<Boolean>
    val ownerCheck: StateFlow<OwnerCheckView?>
    val alarm: StateFlow<CredentialAlarm?>

    fun names(): FeedNames

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
    private val posted = LinkedHashSet<String>()
    private val lock = Any()

    private val on: Boolean get() = settings.value.mode != NotificationMode.OFF

    fun start() {
        scope.launch { source.arrivals.collect { onArrival(it) } }
        scope.launch { source.items.collect { onItems(it) } }
        scope.launch { source.open.collect { open -> if (!open) cancelItems() } }
        scope.launch { settings.map { it.mode }.distinctUntilChanged().collect { if (it == NotificationMode.OFF) cancelAll() } }
        scope.launch { source.lockedElsewhere.collect { onLocked(it) } }
        scope.launch { source.ownerCheck.collect { onOwnerCheck(it) } }
        scope.launch { source.alarm.collect { onAlarm(it) } }
    }

    private suspend fun onArrival(item: FeedItem) {
        val channel = NotificationMapping.channel(item)?.takeIf { notifiable(item) } ?: return
        val names = source.names()
        val previews = settings.value.previews
        val text = if (previews == NotificationPreviews.NAMES_AND_TEXT && item.kind == "message.received") {
            val c = item.connectionId
            val m = item.ref
            if (c != null && m != null) runCatching { source.messageText(c, m) }.getOrNull() else null
        } else {
            null
        }
        val n = NotificationContent.of(item, channel, previews, names, res(), text)
        synchronized(lock) { posted += item.itemId }
        poster.post(n)
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
        val gone = synchronized(lock) { posted.filter { it !in active }.also { posted.removeAll(it.toSet()) } }
        gone.forEach { poster.cancel(it) }
    }

    /** The vault locked or is held, the mode is Off, or the phone was wiped: no notification names anything. */
    private fun cancelItems() {
        val all = synchronized(lock) { posted.toList().also { posted.clear() } }
        all.forEach { poster.cancel(it) }
    }

    private fun cancelAll() {
        cancelItems()
        listOf(TAG_LOCKED, TAG_CHECK, TAG_ALARM).forEach { poster.cancel(it) }
    }

    private fun onLocked(locked: Boolean) {
        if (!locked) {
            poster.cancel(TAG_LOCKED)
            return
        }
        if (!on) return
        val r = res()
        poster.post(
            LocalNotification(
                TAG_LOCKED, Channels.SECURITY, r.getString(R.string.notify_locked_title), r.getString(R.string.notify_locked_body),
                r.getString(R.string.notify_public_attention), alertOnce = true,
            ),
        )
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
        const val TAG_LOCKED = "vault.locked"
        const val TAG_CHECK = "owner_check.due"
        const val TAG_ALARM = "credential.alarm"

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
