package com.vettid.core.data.feed

import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.FeedItem

/** What a read's answer came to ([FeedManager]'s `applyRead`). */
internal sealed interface Applied {
    /** A clear came since the read began: nothing applied. */
    data object Cleared : Applied

    /** A read across an owner-check lift: nothing applied; a full read again. */
    data object Lifted : Applied

    class Read(val arrived: List<FeedItem>) : Applied
}

/**
 * What a read had when it began ([FeedManager]'s `readAfter`): its generation, the lifts and the blips' snapshots so far,
 * whether it is quiet, and the deletes made here ([DeletedHere.deletes]).
 */
internal data class ReadStart(val gen: Int, val lifts: Int, val keptAt: Int, val quiet: Boolean, val deletes: Long)

/**
 * At most this many pages a read ([readPages]): the feed keeps at most 1,000 live items (§10.9); deleted ones come too, so a
 * few pages more.
 */
private const val MAX_PAGES = 8

/**
 * `feed.list{after_seq}` paged from [from] until a page is shorter than the limit ([FeedManager.PAGE], at most [MAX_PAGES]
 * pages): the items and the answer's `seq`.
 */
internal suspend fun readPages(ops: suspend () -> FeedOps, from: Long): Pair<List<FeedItem>, Long> {
    var after = from
    val changed = ArrayList<FeedItem>()
    var seq = from
    var pages = 0
    while (pages++ < MAX_PAGES) {
        val page = vaultGuard(resend = true) { ops().list(after, FeedManager.PAGE) }
        seq = maxOf(seq, page.seq)
        changed += page.items
        if (page.items.size < FeedManager.PAGE) break
        after = page.items.last().seq
    }
    return changed to seq
}
