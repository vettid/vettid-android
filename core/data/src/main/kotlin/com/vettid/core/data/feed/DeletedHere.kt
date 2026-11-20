package com.vettid.core.data.feed

import com.vettid.core.vault.FeedItem

/**
 * Items the member deleted on this phone ([FeedManager.delete]), by the deletes counted when each was deleted. An item id is a
 * ULID and a deleted item never comes back (VAULT-MESSAGING §10.9 and §10.4.1 Batching: a later ask of a deleted item's
 * batch starts a new one; §10.9 Guides: a newer guide version is a new item; §10.10: missed calls likewise), so a copy of one
 * that is not deleted is from before the delete: a late event, an event held through a read or the release check, a
 * catch-up's or a full read's answer. The feed neither lists nor notifies it, and its cursor moves on. An entry goes only
 * when a read has settled it, never on an event (a read already on its way may still bring an older copy): once a read's
 * answer with the deletion has applied, or a read begun after the delete has. All go at a lock's
 * clear and at an opening; a soft clear keeps them, as the opening may continue. Not thread-safe: the feed uses it under its
 * lock.
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

    /**
     * A read's answer [changed] applied (the cursor is then at its `seq` or past it): the deletions it brought end their
     * entries, since anything older is behind the cursor, and a read begun when [startDeletes] deletes had been made answers
     * for each of them (reads run one at a time, so none older is on its way).
     */
    fun readApplied(changed: List<FeedItem>, startDeletes: Long) {
        changed.forEach { if (it.status == FeedManager.STATUS_DELETED) ids.remove(it.itemId) }
        ids.values.removeAll { it <= startDeletes }
    }

    /** Moved by [clear] (a lock's clear, an opening), not by a soft clear: a delete answered across one adds nothing. */
    var epoch = 0
        private set

    fun clear() {
        ids.clear()
        epoch++
    }
}
