package com.vettid.core.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (#19721 b′) An unanswered request starts one probe of whether the vault is still there; never while the vault is not open
 * here or the app is not in front, never two at a time (the probe's own unanswered read counts for nothing), and at least a
 * minute apart. (Step 4, #19767) Two probes in a row that neither reached the vault nor found it locked make it silent;
 * anything heard from it, a probe that reached it or found it locked, and an opening start the count again.
 */
class VaultGoneWatchTest {
    private var now = 1_000_000L
    private var watching = true
    private var probes = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var found: VaultProbe? = VaultProbe.NO_ANSWER
    private var afterProbe: () -> Unit = {}

    private fun watch(scope: kotlinx.coroutines.CoroutineScope) = VaultGoneWatch(
        scope,
        watching = { watching },
        probe = {
            probes++
            gate?.await()
            afterProbe()
            found
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

    /** One probe a minute after the last, from a new unanswered request. */
    private fun kotlinx.coroutines.test.TestScope.probeOnce(w: VaultGoneWatch) {
        w.onNoAnswer("item.list")
        runCurrent()
        now += VaultGoneWatch.PROBE_EVERY_MS
    }

    @Test
    fun twoUnansweredProbesInARowMakeItSilentAndOneDoesNot() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        assertFalse(w.silent.value)
        probeOnce(w)
        assertEquals(2, probes)
        assertTrue(w.silent.value)
    }

    @Test
    fun aProbeThatReachesTheVaultOrFindsItLockedStartsTheCountAgain() = runTest {
        for (reached in listOf(VaultProbe.OPEN, VaultProbe.LOCKED)) {
            val w = watch(backgroundScope)
            found = VaultProbe.NO_ANSWER
            probeOnce(w)
            found = reached
            probeOnce(w)
            found = VaultProbe.NO_ANSWER
            probeOnce(w)
            assertFalse("after $reached", w.silent.value)
            probeOnce(w)
            assertTrue("after $reached", w.silent.value)
            // And a silent vault that a probe then reaches is no longer silent.
            found = reached
            probeOnce(w)
            assertFalse("reached: $reached", w.silent.value)
        }
    }

    @Test
    fun anythingHeardFromTheVaultEndsItAndStartsTheCountAgain() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        probeOnce(w)
        assertTrue(w.silent.value)
        w.heard()
        assertFalse(w.silent.value)
        probeOnce(w)
        assertFalse(w.silent.value) // one in a row again
        w.heard()
        probeOnce(w)
        assertFalse(w.silent.value)
    }

    @Test
    fun anOpeningStartsTheCountAgain() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        probeOnce(w)
        assertTrue(w.silent.value)
        w.reset()
        assertFalse(w.silent.value)
        probeOnce(w)
        assertFalse(w.silent.value)
    }

    @Test
    fun aProbeDuringWhichTheVaultWasHeardFromCountsForNothing() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        gate = CompletableDeferred()
        w.onNoAnswer("item.list")
        runCurrent()
        w.heard() // a message from the vault while the probe's read goes unanswered
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(2, probes)
        assertFalse(w.silent.value)
        // Nor does the one before it count: the next unanswered probe is the first in a row.
        gate = null
        now += VaultGoneWatch.PROBE_EVERY_MS
        probeOnce(w)
        assertFalse(w.silent.value)
    }

    /** The first probe, with nothing counted yet: a message during it still voids it (the quick path of [VaultGoneWatch.heard]). */
    @Test
    fun theFirstProbeDuringWhichTheVaultWasHeardFromCountsForNothing() = runTest {
        val w = watch(backgroundScope)
        gate = CompletableDeferred()
        w.onNoAnswer("item.list")
        runCurrent()
        w.heard()
        gate!!.complete(Unit)
        runCurrent()
        gate = null
        now += VaultGoneWatch.PROBE_EVERY_MS
        probeOnce(w)
        assertEquals(2, probes)
        assertFalse("one in a row only", w.silent.value)
    }

    /** A message after the request went unanswered but before its probe starts voids that probe too (the mark is the slot's). */
    @Test
    fun aMessageBeforeTheProbeStartsVoidsIt() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        w.onNoAnswer("item.list")
        w.heard() // the probe is launched, not run yet
        runCurrent()
        assertEquals(2, probes)
        assertFalse(w.silent.value)
        // The voided probe and the one before it are gone: one more unanswered probe is the first in a row.
        now += VaultGoneWatch.PROBE_EVERY_MS
        probeOnce(w)
        assertEquals(3, probes)
        assertFalse(w.silent.value)
    }

    /** A probe that could ask nobody (null) counts for nothing and resets nothing. */
    @Test
    fun aProbeThatAskedNobodyNeitherCountsNorResets() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        found = null
        probeOnce(w)
        assertFalse(w.silent.value)
        found = VaultProbe.NO_ANSWER
        probeOnce(w) // the second unanswered one in a row: the null between them reset nothing
        assertTrue(w.silent.value)
        found = null
        probeOnce(w)
        assertTrue("still silent", w.silent.value)
    }

    /** An unanswered probe that ends with the app no longer in front (or the vault closed) is not counted. */
    @Test
    fun anUnansweredProbeEndingOutOfFrontIsNotCounted() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        afterProbe = { watching = false }
        probeOnce(w)
        assertFalse(w.silent.value)
        afterProbe = {}
        watching = true
        probeOnce(w)
        assertTrue(w.silent.value)
        // An answer is taken whenever it ends: it says the vault is there.
        afterProbe = { watching = false }
        found = VaultProbe.OPEN
        probeOnce(w)
        assertFalse(w.silent.value)
    }

    @Test
    fun aProbeDuringWhichTheVaultOpenedAgainCountsForNothing() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        gate = CompletableDeferred()
        w.onNoAnswer("item.list")
        runCurrent()
        w.reset() // an unlock finished while it ran
        gate!!.complete(Unit)
        runCurrent()
        assertFalse(w.silent.value)
    }

    @Test
    fun aProbeNotRunCountsForNothing() = runTest {
        val w = watch(backgroundScope)
        probeOnce(w)
        w.onNoAnswer("item.list")
        watching = false // closed before it ran
        runCurrent()
        watching = true
        assertEquals(1, probes)
        assertFalse(w.silent.value)
    }
}
