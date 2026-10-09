package com.vettid.core.testing

import com.vettid.core.data.feed.FeedBadge
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * TEST ONLY. An in-memory feed (ANDROID-PLAN 0.1.23) for ViewModel tests: [seed] sets the items; calls are recorded in
 * [calls]; [fail] makes the next call of a name throw.
 */
class FakeFeed : FeedRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()

    override val items = MutableStateFlow<List<FeedItem>>(emptyList())
    override val load = MutableStateFlow(ListLoad.LOADED)
    override val badge = MutableStateFlow(FeedBadge())

    fun seed(list: List<FeedItem>) {
        items.value = list
        val unread = list.filter { it.status == FeedManager.STATUS_ACTIVE }
        badge.value = FeedBadge(unread.size, unread.any { it.priority == FeedManager.PRIORITY_URGENT })
    }

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    override suspend fun refresh() = call("refresh")

    override suspend fun setStatus(itemId: String, status: String) {
        call("setStatus:$itemId:$status")
        seed(items.value.map { if (it.itemId == itemId) it.copy(status = status) else it })
    }

    override fun markReadQuietly(itemId: String) {
        calls += "markRead:$itemId"
        seed(items.value.map { if (it.itemId == itemId) read(it) else it })
    }

    override suspend fun delete(itemId: String) {
        call("delete:$itemId")
        items.update { l -> l.filterNot { it.itemId == itemId } }
    }

    override suspend fun markAllRead() {
        call("markAllRead")
        seed(items.value.map { read(it) })
    }

    private fun read(i: FeedItem) = if (i.status == FeedManager.STATUS_ACTIVE) i.copy(status = FeedManager.STATUS_READ) else i
}
