package com.vettid.core.data.social

/**
 * Where a conversation's loaded history stands (VAULT-MESSAGING 0.32.0 §10.5.2): [nextBefore] the `before` of the next
 * older page, null when the start of the conversation is loaded (or nothing older was announced); [olderLoaded]
 * whether at least one older page was merged in, so that a reload of the newest page keeps it.
 */
internal data class MessagePaging(val nextBefore: String? = null, val olderLoaded: Boolean = false) {
    val hasOlder: Boolean get() = nextBefore != null
}

/** A conversation after a page was merged. */
internal data class PagedMessages(val messages: List<MessageInfo>, val paging: MessagePaging)

/**
 * Merging pages of `message.list` into what the conversation shows. The vault's page wins over the range it covers
 * (deletions and receipts included); messages that arrived meanwhile, newer than the page's newest, are kept; and older
 * pages the member scrolled to stay when the newest page is loaded again, as long as the new page joins them (its
 * oldest message is among them). A page that does not join them (more new messages arrived than a page holds) drops
 * them and continues from the page's own `next_before`, so that the list never has a hole.
 */
internal object MessagePages {
    private val ORDER = compareBy<MessageInfo>({ it.sentAt }, { it.messageId })

    /** The newest page ([page] oldest first, [nextBefore] as the vault answered) over [current] and its [paging]. */
    fun newest(page: List<MessageInfo>, nextBefore: String?, current: List<MessageInfo>, paging: MessagePaging): PagedMessages {
        val ids = page.mapTo(HashSet()) { it.messageId }
        val newestAt = page.maxOfOrNull { it.sentAt }
        val arrived = current.filter { it.messageId !in ids && (newestAt == null || it.sentAt > newestAt) }
        val sorted = current.sortedWith(ORDER)
        val join = page.firstOrNull()?.let { first -> sorted.indexOfFirst { it.messageId == first.messageId } } ?: -1
        return when {
            page.isEmpty() -> PagedMessages(arrived.sortedWith(ORDER), MessagePaging())
            nextBefore == null -> PagedMessages((page + arrived).sortedWith(ORDER), MessagePaging(null, paging.olderLoaded))
            !paging.olderLoaded || join < 0 -> PagedMessages((page + arrived).sortedWith(ORDER), MessagePaging(nextBefore))
            else -> {
                val older = sorted.subList(0, join).filter { it.messageId !in ids }
                PagedMessages((older + page + arrived).sortedWith(ORDER), paging)
            }
        }
    }

    /** An older page ([page] oldest first, answered for `before` = [MessagePaging.nextBefore]) added to [current]. */
    fun older(page: List<MessageInfo>, nextBefore: String?, current: List<MessageInfo>): PagedMessages {
        val have = current.mapTo(HashSet()) { it.messageId }
        val merged = (page.filter { it.messageId !in have } + current).sortedWith(ORDER)
        return PagedMessages(merged, MessagePaging(nextBefore, olderLoaded = true))
    }
}
