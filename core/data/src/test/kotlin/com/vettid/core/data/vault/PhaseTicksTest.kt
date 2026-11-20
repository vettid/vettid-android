package com.vettid.core.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [PhaseHolder.ticks]: each phase with the number of closes so far, so a lock the phase collector was not told of on its own
 * (a lock, an unlock and a lock again, conflated into one Locked) still comes to it as a new value and runs the lock's clears.
 */
class PhaseTicksTest {
    private val blip = AppPhase.Unreachable(FailureKind.NETWORK)

    @Test
    fun aMoveIntoACloseCountsAndNothingElseDoes() {
        val phase = PhaseHolder(AppPhase.Locked)
        assertEquals(PhaseTick(AppPhase.Locked, 0, 0), phase.ticks.value)
        phase.value = AppPhase.Unlocked
        phase.value = blip // a blip closes nothing
        phase.value = AppPhase.Unlocked
        assertEquals(0, phase.ticks.value.closes)
        phase.value = AppPhase.Locked
        assertEquals(1, phase.ticks.value.closes)
        phase.value = AppPhase.SignedOut // from one close to another: the same close
        assertEquals("the blip's return is the same opening", PhaseTick(AppPhase.SignedOut, 1, 1), phase.ticks.value)
        phase.value = AppPhase.Unlocked
        phase.value = AppPhase.Deleting()
        assertEquals(2, phase.ticks.value.closes)
    }

    @Test
    fun aConflatedLockUnlockLockIsANewValue() = runBlocking {
        val phase = PhaseHolder(AppPhase.Unlocked)
        phase.value = AppPhase.Locked
        val first = phase.ticks.value
        phase.value = AppPhase.Unlocked
        phase.value = AppPhase.Locked
        // The phase alone is the same value (a StateFlow would deliver nothing more); the tick is not.
        assertEquals(first.phase, phase.ticks.value.phase)
        assertNotEquals(first, phase.ticks.value)
    }

    @Test
    fun aFollowerSeesEveryClose() = runBlocking {
        val phase = PhaseHolder(AppPhase.Unlocked)
        val seen = mutableListOf<PhaseTick>()
        val job = launch(Dispatchers.Unconfined) { phase.ticks.collect { seen += it } }
        phase.value = AppPhase.Locked
        phase.value = AppPhase.Unlocked
        phase.value = AppPhase.Locked
        job.cancel()
        assertEquals(2, seen.count { it.phase == AppPhase.Locked })
        assertEquals(listOf(1, 2), seen.filter { it.phase == AppPhase.Locked }.map { it.closes })
    }
}
