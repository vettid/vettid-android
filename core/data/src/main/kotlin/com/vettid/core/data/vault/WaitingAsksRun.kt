package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * (ANDROID-PLAN 0.1.132; owner decisions of 2026-11-15) The waiting-asks replay after a check passed with the vault open:
 * it needs a loaded feed and a `pending.list` read begun after the check, merged and published. Both reads are tried again,
 * with [waits] between tries (the last wait repeats), until both are had or the session ends ([open] false: a lock, and so
 * an unlock, or a new hold). The spec counts the first successful read before the next lock, so no number of failures ends
 * it. An older vault without `pending.list` ([unsupported]) merges nothing, and nothing is replayed.
 *
 * [replay] is false while the feed is not loaded (it keeps the window); [end] closes the window when nothing is replayed.
 */
internal class WaitingAsksRun(
    private val open: () -> Boolean,
    private val feedLoaded: () -> Boolean,
    private val readFeed: suspend () -> Unit,
    private val listed: () -> Boolean,
    private val unsupported: () -> Boolean,
    private val readList: suspend () -> Unit,
    private val replay: () -> Boolean,
    private val end: () -> Unit,
    private val waits: LongArray = WAITS,
) {
    /** Runs until the replay is done or given up; true when it ran. */
    suspend fun run(): Boolean {
        var tries = 0
        var outcome: Boolean? = null
        while (outcome == null) {
            // The hold's end reads the list too, so the first try waits a moment.
            delay(waits[minOf(tries, waits.size - 1)])
            tries++
            outcome = tryOnce()
        }
        if (!outcome) end()
        return outcome
    }

    /** One try: true, replayed; false, given up; null, tried again after the next wait. */
    private suspend fun tryOnce(): Boolean? {
        if (!open()) return false
        if (!feedLoaded()) attempt(readFeed)
        if (open() && !listed() && !unsupported()) attempt(readList)
        return when {
            !open() -> false // a read that ended with the session counts for nothing
            !listed() && unsupported() -> false
            listed() && replay() -> true
            else -> null
        }
    }

    /** One read; a failure is only a reason to try again later (a cancellation ends the run). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(read: suspend () -> Unit) {
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // tried again after the next wait
        }
    }

    companion object {
        /** The waits before each try: a moment, then backing off to a minute, which repeats. */
        val WAITS = longArrayOf(2_000L, 5_000L, 15_000L, 60_000L)
    }
}
