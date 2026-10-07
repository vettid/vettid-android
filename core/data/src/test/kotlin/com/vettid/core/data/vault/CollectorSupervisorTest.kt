package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pixel 10, 2026-10-07: the collection ended on `timestamp_stale` after the phone froze the app in the background,
 * and nothing started it again until a cold start. The supervisor restarts it after each end, with backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectorSupervisorTest {
    @Test
    fun anEndedCollectionIsRestartedWithBackoff() = runTest {
        val ended = MutableStateFlow<String?>(null)
        var restarts = 0
        val sup = CollectorSupervisor(backgroundScope, restart = {
            restarts++
            ended.value = null // start() clears the end
        }, backoffMs = listOf(1_000L, 5_000L), nowMs = { testScheduler.currentTime })
        sup.watch(ended)
        runCurrent()
        assertEquals(0, restarts) // running: nothing to do
        ended.value = "timestamp_stale"
        runCurrent()
        advanceTimeBy(999)
        assertEquals(0, restarts)
        advanceTimeBy(2)
        assertEquals(1, restarts)
        // It ends again at once: the next wait is longer, and the last one repeats.
        ended.value = "mailbox_unknown"
        runCurrent()
        advanceTimeBy(4_999)
        assertEquals(1, restarts)
        advanceTimeBy(2)
        assertEquals(2, restarts)
        ended.value = "mailbox_unknown"
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(3, restarts)
    }

    @Test
    fun aCollectionThatRanForAWhileStartsTheBackoffOver() = runTest {
        val ended = MutableStateFlow<String?>(null)
        var restarts = 0
        val sup = CollectorSupervisor(backgroundScope, restart = {
            restarts++
            ended.value = null
        }, backoffMs = listOf(1_000L, 60_000L), stableMs = 10_000L, nowMs = { testScheduler.currentTime })
        sup.watch(ended)
        ended.value = "timestamp_stale"
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(1, restarts)
        advanceTimeBy(20_000) // ran for longer than stableMs
        ended.value = "timestamp_stale"
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(2, restarts)
    }
}
