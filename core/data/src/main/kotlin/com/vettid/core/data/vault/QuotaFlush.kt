package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * (Show held as held, Android) Flushes the outbox once a mailbox's quota backoff has ended, so an entry held by it (a
 * `call.end`, the outbox's only member-facing entry) goes as soon as the relay allows. The flush passes the same gate as
 * every other: [admits] (nothing but the lock while one is pending, nor while this phone is held locked) and [live] (the
 * session is still this device's, and it was not stopped); a later backoff replaces the wait. A gate still shut when the
 * backoff ends is looked at again every [RECHECK_MS] while the session lives, so a lock that is confirmed later does not
 * leave the entry for some unrelated flush. The wait ends with the scope (the session's observer).
 */
internal class QuotaFlush(
    private val scope: CoroutineScope,
    private val admits: () -> Boolean,
    private val live: () -> Boolean,
    private val flush: suspend () -> Unit,
) {
    private var wait: Job? = null

    /** A backoff of [ms] was set: the outbox is flushed once it has ended (a little after, so it has). */
    @Synchronized
    fun backoffSet(ms: Long) {
        wait?.cancel()
        wait = scope.launch {
            delay(ms + MARGIN_MS)
            while (live() && !admits()) delay(RECHECK_MS)
            if (live()) runCatching { flush() }
        }
    }

    internal companion object {
        /** After the backoff's end, so that the flush does not meet the backoff still running. */
        const val MARGIN_MS = 250L

        /** How often a shut gate (a pending lock) is looked at again once the backoff has ended. */
        const val RECHECK_MS = 5_000L
    }
}
