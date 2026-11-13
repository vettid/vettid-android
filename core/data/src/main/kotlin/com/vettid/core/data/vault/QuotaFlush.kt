package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * (Show held as held, Android) Flushes the outbox once a mailbox's quota backoff has ended, so an entry held by it (a
 * `call.end`, the outbox's only member-facing entry) goes as soon as the relay allows. What goes is the device's lock gate's to
 * decide, by type (ANDROID-PLAN §4 "While pending": a pending `call.end` goes while a lock is pending, nothing else does); the
 * flush takes the stop count of the moment the backoff was set ([stopCount]), so a stop during the wait cancels that flush
 * (the device deposits nothing for it), and runs only while [live] (the session is still this device's). A restart of the
 * collection during the wait announces the rest of the backoff again ([com.vettid.core.vault.VaultDevice.quotaBackoffSet]),
 * which replaces the wait with one under the new stop count. The wait ends with the scope (the session's observer).
 */
internal class QuotaFlush(
    private val scope: CoroutineScope,
    private val stopCount: () -> Long,
    private val live: () -> Boolean,
    private val flush: suspend (seq: Long) -> Unit,
) {
    private var wait: Job? = null

    /** A backoff of [ms] was set: the outbox is flushed once it has ended (a little after, so it has). */
    @Synchronized
    fun backoffSet(ms: Long) {
        wait?.cancel()
        val seq = stopCount() // taken here, where the flush is asked for
        wait = scope.launch {
            delay(ms + MARGIN_MS)
            if (live()) runCatching { flush(seq) }
        }
    }

    internal companion object {
        /** After the backoff's end, so that the flush does not meet the backoff still running. */
        const val MARGIN_MS = 250L
    }
}
