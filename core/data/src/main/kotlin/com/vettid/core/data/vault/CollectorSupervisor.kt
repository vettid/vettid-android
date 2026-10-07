package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the mailbox collector running for as long as the session lives. A collection ends on a terminal relay error
 * (`VaultDevice.collectionEnded`); before this watch nothing started it again until the next cold start, so the
 * vault's answers and events stayed unread in the mailbox (Pixel 10, 2026-10-07: a long-poll signed before the
 * phone froze in the background reached the relay minutes later, `timestamp_stale`), while sending still worked.
 *
 * Each end is answered by [restart] after a backoff ([backoffMs], the last value repeating), so that an error that
 * persists (a mailbox the relay no longer knows) does not loop. A collection that ran for [stableMs] since the last
 * restart starts the backoff over.
 */
class CollectorSupervisor(
    private val scope: CoroutineScope,
    private val restart: () -> Unit,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val stableMs: Long = STABLE_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Watches [ended] (null while the collection runs) until the returned job is cancelled. */
    fun watch(ended: StateFlow<String?>): Job = scope.launch {
        var restarts = 0
        var lastRestart = Long.MIN_VALUE
        ended.collect { code ->
            if (code == null) return@collect
            if (lastRestart != Long.MIN_VALUE && nowMs() - lastRestart >= stableMs) restarts = 0
            delay(backoffMs.getOrElse(restarts) { backoffMs.lastOrNull() ?: 0L })
            restarts++
            lastRestart = nowMs()
            restart()
        }
    }

    companion object {
        val DEFAULT_BACKOFF_MS = listOf(1_000L, 5_000L, 15_000L, 60_000L, 300_000L)
        const val STABLE_MS = 600_000L
    }
}
