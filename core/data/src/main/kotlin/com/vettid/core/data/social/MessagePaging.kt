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
    /**
     * The newest page ([page] oldest first, [nextBefore] as the vault answered) over [current] and its [paging]. Pages
     * are joined by the vault's order (a message's position), never by `sent_at`, which is the sender's clock: what
     * [current] holds before the page's first message is older, what it holds after the page's last one arrived since.
     */
    fun newest(page: List<MessageInfo>, nextBefore: String?, current: List<MessageInfo>, paging: MessagePaging): PagedMessages {
        val ids = page.mapTo(HashSet()) { it.messageId }
        val firstAt = page.firstOrNull()?.let { f -> current.indexOfFirst { it.messageId == f.messageId } } ?: -1
        // The last position in [current] of any message of the page (one deleted meanwhile is simply not in it).
        val lastAt = current.indexOfLast { it.messageId in ids }
        val arrived = when {
            page.isEmpty() -> current
            lastAt >= 0 -> current.subList(lastAt + 1, current.size)
            else -> emptyList()
        }
        return when {
            page.isEmpty() -> PagedMessages(arrived, MessagePaging())
            nextBefore == null -> PagedMessages(page + arrived, MessagePaging(null, paging.olderLoaded))
            !paging.olderLoaded || firstAt < 0 -> PagedMessages(page + arrived, MessagePaging(nextBefore))
            else -> {
                val older = current.subList(0, firstAt).filter { it.messageId !in ids }
                PagedMessages(older + page + arrived, paging)
            }
        }
    }

    /** An older page ([page] oldest first, answered for `before` = [MessagePaging.nextBefore]) put before [current]. */
    fun older(page: List<MessageInfo>, nextBefore: String?, current: List<MessageInfo>): PagedMessages {
        val have = current.mapTo(HashSet()) { it.messageId }
        return PagedMessages(page.filter { it.messageId !in have } + current, MessagePaging(nextBefore, olderLoaded = true))
    }
}
