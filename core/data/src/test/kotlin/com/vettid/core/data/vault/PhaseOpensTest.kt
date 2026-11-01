package com.vettid.core.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PhaseHolder.opens]: every move into Unlocked from another phase, whatever closed the vault, counted before the phase shows,
 * so a follower that sees the vault open twice in a row (the closed value between conflated away) tells it was closed between.
 */
class PhaseOpensTest {
    @Test
    fun everyOpeningCountsAndNothingElseDoes() {
        val phase = PhaseHolder(AppPhase.Starting)
        assertEquals(0, phase.opens.value)
        phase.value = AppPhase.Unlocked
        assertEquals(1, phase.opens.value)
        // Still open: no new opening.
        phase.value = AppPhase.Unlocked
        assertEquals(1, phase.opens.value)
        // Any close, then open again: the vault's own lock notice, a refusal, a status read all publish Locked.
        phase.value = AppPhase.Locked
        assertEquals(1, phase.opens.value)
        phase.value = AppPhase.Unlocked
        assertEquals(2, phase.opens.value)
        phase.value = AppPhase.SignedOut
        phase.value = AppPhase.Unlocked
        assertEquals(3, phase.opens.value)
    }

    @Test
    fun anOpeningIsCountedBeforeThePhaseShowsIt() = runBlocking {
        val phase = PhaseHolder(AppPhase.Locked)
        val seen = mutableListOf<Int>()
        // Unconfined: the collector runs inside the phase's publish, the moment the new phase is set, so it reads the count
        // before publish returns. Counted after the phase was set, it would read 0 here.
        val job = launch(Dispatchers.Unconfined) {
            phase.asStateFlow().collect { if (it == AppPhase.Unlocked) seen += phase.opens.value }
        }
        phase.value = AppPhase.Unlocked
        assertEquals(listOf(1), seen)
        job.cancel()
    }
}
