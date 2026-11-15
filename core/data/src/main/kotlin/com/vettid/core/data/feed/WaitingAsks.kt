package com.vettid.core.data.feed

import com.vettid.core.data.social.Approval
import com.vettid.core.vault.FeedItem
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * (Owner decision a, 2026-11-15; ANDROID-PLAN "Notification modes" 7's exception) The asks that are notified once when a
 * hold ends by a passed check: nothing missed while held is replayed (0.1.23), except an ask that expires and is still
 * waiting, which would otherwise go by unseen. A sign-in, a device session, a held approval and a pairing.
 */
internal object WaitingAsks {
    const val SIGN_IN = "connection.authenticate.requested"
    const val SESSION = "device.session.pending"
    const val HELD = "approval.pending"
    const val PAIRING = "device.pair.pending"

    val KINDS = setOf(SIGN_IN, SESSION, HELD, PAIRING)

    /** The vault drops an undecided pairing after 10 minutes (§6.7); it is not in `pending.list`, so its item is all there is. */
    private val PAIRING_LIFE: Duration = Duration.ofMinutes(10)

    /**
     * Of [items], the asks still waiting at [now]: active (not read, archived or deleted), and listed in [approvals] (as
     * read after the hold) with no `exp` or one still ahead; a pairing, by its item, within its 10 minutes.
     */
    fun of(items: Collection<FeedItem>, approvals: List<Approval>, now: Instant): List<FeedItem> {
        val byKey = approvals.associateBy { it.key }
        return items.filter { it.kind in KINDS && it.status == FeedManager.STATUS_ACTIVE && waiting(it, byKey, now) }
            .sortedBy { it.seq }
    }

    private fun waiting(item: FeedItem, byKey: Map<String, Approval>, now: Instant): Boolean {
        val ref = item.ref?.takeIf { it.isNotEmpty() } ?: return false
        return if (item.kind == PAIRING) {
            at(item)?.plus(PAIRING_LIFE)?.isAfter(now) == true
        } else {
            val key = if (item.kind == SIGN_IN) "auth:$ref" else "device:${item.kind}:$ref"
            byKey[key]?.let { ask -> ask.exp?.isAfter(now) ?: true } == true
        }
    }

    private fun at(item: FeedItem): Instant? = try {
        Instant.parse(item.at)
    } catch (_: DateTimeParseException) {
        null
    }
}
