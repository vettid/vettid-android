package com.vettid.core.data.feed

import com.vettid.core.data.social.Approval
import com.vettid.core.vault.FeedItem
import java.time.Instant

/**
 * (Owner decisions a, 2026-11-15; ANDROID-PLAN 0.1.132, Notifications 7) The asks notified once when a passed check ends
 * the held or due state in an open session: nothing missed then is replayed (0.1.23), except an ask still waiting of
 * exactly these three kinds. Pairings are left out: the vault does not list them in `pending.list`, and deciding one does
 * not mark its feed item, so one still waiting cannot be told from one already done.
 */
internal object WaitingAsks {
    const val SIGN_IN = "connection.authenticate.requested"
    const val SESSION = "device.session.pending"
    const val HELD = "approval.pending"

    /** Exactly these kinds, and no other (a sign-in of any purpose). */
    val KINDS = setOf(SIGN_IN, SESSION, HELD)

    /**
     * Of [items], the asks still waiting at [now]: active (not read, archived or deleted), and listed in [approvals] (the
     * `pending.list` answer read after the check) with no `exp` or one still ahead.
     */
    fun of(items: Collection<FeedItem>, approvals: List<Approval>, now: Instant): List<FeedItem> {
        val byKey = approvals.associateBy { it.key }
        return items.filter { it.kind in KINDS && it.status == FeedManager.STATUS_ACTIVE && waiting(it, byKey, now) }
            .sortedBy { it.seq }
    }

    private fun waiting(item: FeedItem, byKey: Map<String, Approval>, now: Instant): Boolean {
        val ref = item.ref?.takeIf { it.isNotEmpty() } ?: return false
        val key = if (item.kind == SIGN_IN) "auth:$ref" else "device:${item.kind}:$ref"
        return byKey[key]?.let { ask -> ask.exp?.isAfter(now) ?: true } == true
    }
}
