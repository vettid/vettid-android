package com.vettid.core.data.feed

/**
 * A change [FeedManager] applied here while a full read over a list shown runs: the item's `seq` as applied, and whether it
 * is the member's own (made on that copy, not answered yet; a delete as the highest `seq`, never undone by an answer).
 */
internal class LocalChange(val seq: Long, val own: Boolean) {
    /**
     * Whether it stands over the read's answer, which lists the item at [listed] (null: not listed) and was made at
     * [read]. Not listed: the item is gone (deleted elsewhere, past retention), unless what was applied is newer than
     * the answer. Listed: a live change stands when newer than the answer's copy; the member's own when the answer's
     * copy is still the one it was made on (or older), so a newer change elsewhere wins.
     */
    fun stands(listed: Long?, read: Long): Boolean = when {
        listed == null -> seq > read
        own -> seq >= listed
        else -> seq > listed
    }
}
