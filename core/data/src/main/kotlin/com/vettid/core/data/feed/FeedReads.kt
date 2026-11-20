package com.vettid.core.data.feed

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
