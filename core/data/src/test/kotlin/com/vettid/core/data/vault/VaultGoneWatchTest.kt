package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * (#19721 b′) An unanswered request starts one probe of whether the vault is still there; never while the vault is not open
 * here or the app is not in front, never two at a time (the probe's own unanswered read counts for nothing), and at least a
 * minute apart.
 */
class VaultGoneWatchTest {
    private var now = 1_000_000L
    private var watching = true
    private var probes = 0
    private var gate: CompletableDeferred<Unit>? = null

    private fun watch(scope: kotlinx.coroutines.CoroutineScope) = VaultGoneWatch(
        scope,
        watching = { watching },
        probe = {
            probes++
            gate?.await()
            false
        },
        elapsedMs = { now },
        ignored = setOf("vault.owner-check"),
    )

    @Test
    fun anIgnoredTypeStartsNoProbe() = runTest {
        val w = watch(backgroundScope)
        w.onNoAnswer("vault.owner-check")
        runCurrent()
        assertEquals(0, probes)
    }

    @Test
    fun noProbeWhenTheVaultClosesBeforeItRuns() = runTest {
        val w = watch(backgroundScope)
        w.onNoAnswer("item.list")
        watching = false // a lock between the signal and the probe's start
        runCurrent()
        assertEquals(0, probes)
    }

    @Test
    fun oneUnansweredRequestStartsOneProbe() = runTest {
        val w = watch(backgroundScope)
        w.onNoAnswer("item.list")
        runCurrent()
        assertEquals(1, probes)
    }

    @Test
    fun noProbeWhileTheVaultIsNotOpenOrTheAppIsBehind() = runTest {
        val w = watch(backgroundScope)
        watching = false
        w.onNoAnswer("item.list")
        runCurrent()
        assertEquals(0, probes)
    }

    @Test
    fun oneProbeAtATimeAndAMinuteApart() = runTest {
        val w = watch(backgroundScope)
        gate = CompletableDeferred()
        w.onNoAnswer("item.list")
        runCurrent()
        now += VaultGoneWatch.PROBE_EVERY_MS + 1 // even past the minute: the first still runs
        w.onNoAnswer("vault.status") // the probe's own read, unanswered
        runCurrent()
        assertEquals(1, probes)
        gate!!.complete(Unit)
        runCurrent()
        // Within a minute of the last probe: none.
        gate = null
        val w2 = watch(backgroundScope)
        w2.onNoAnswer("a")
        runCurrent()
        now += VaultGoneWatch.PROBE_EVERY_MS - 1
        w2.onNoAnswer("b")
        runCurrent()
        assertEquals(2, probes)
        now += 1
        w2.onNoAnswer("c")
        runCurrent()
        assertEquals(3, probes)
    }
}
