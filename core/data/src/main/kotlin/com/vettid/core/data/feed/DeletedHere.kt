package com.vettid.core.data.feed

import com.vettid.core.vault.FeedItem

/**
 * Items the member deleted on this phone ([FeedManager.delete]), by the deletes counted when each was deleted. An item id is a
 * ULID and a deleted item never comes back (VAULT-MESSAGING §10.9: a later ask of its batch, or a newer guide version, is a
 * new item), so a copy of one that is not deleted is from before the delete: a late event, an event held through a read, a
 * catch-up's or a full read's answer. The feed neither lists nor notifies it, and its cursor moves on. An id goes once the
 * cursor has passed its deletion (an answer or an event in order with it deleted: anything older is then behind the cursor)
 * or a full read begun after the delete has applied; all go at a clear and an opening. Not thread-safe: the feed uses it under
 * its lock.
 */
internal class DeletedHere {
    private val ids = HashMap<String, Long>()

    /** The deletes made on this phone so far; a read takes it when it begins. */
    var deletes = 0L
        private set

    val size: Int get() = ids.size

    fun add(itemId: String) {
        ids[itemId] = ++deletes
    }

    /** Whether [item] is an older copy of one deleted here, to be left out. */
    fun olderCopy(item: FeedItem): Boolean = item.itemId in ids && item.status != FeedManager.STATUS_DELETED

    /** [item]'s deletion, once the [cursor] has passed it: nothing older can come in order, so its entry goes. */
    fun passed(item: FeedItem, cursor: Long) {
        if (item.status == FeedManager.STATUS_DELETED && item.seq <= cursor) ids.remove(item.itemId)
    }

    /**
     * A read's answer [changed] applied, the cursor now [cursor]: the deletions it passed end their entries, and a [full] read
     * begun when [startDeletes] deletes had been made answers for each of them.
     */
    fun readApplied(changed: List<FeedItem>, cursor: Long, full: Boolean, startDeletes: Long) {
        changed.forEach { passed(it, cursor) }
        if (full) ids.values.removeAll { it <= startDeletes }
    }

    fun clear() = ids.clear()
}
