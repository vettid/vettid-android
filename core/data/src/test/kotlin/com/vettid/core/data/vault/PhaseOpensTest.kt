package com.vettid.core.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import com.vettid.core.data.vault.FailureKind
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

    /**
     * Rule 3 (the release gate): the opening is told before the phase shows it, so a reader of the open phase reads the
     * gate's check with it (no frame of the shell, no ring); a phase that stays open, or a close, tells nothing.
     */
    @Test
    fun theOpeningIsToldBeforeThePhaseShowsIt() = runBlocking {
        val told = mutableListOf<AppPhase>()
        lateinit var phase: PhaseHolder
        phase = PhaseHolder(AppPhase.Locked, onOpen = { told += phase.value })
        val seen = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) {
            phase.asStateFlow().collect { if (it == AppPhase.Unlocked) seen += told.size }
        }
        phase.value = AppPhase.Unlocked
        assertEquals("told while the phase was still locked", listOf<AppPhase>(AppPhase.Locked), told)
        assertEquals("before the phase showed", listOf(1), seen)
        phase.value = AppPhase.Unlocked
        phase.value = AppPhase.Locked
        assertEquals(1, told.size)
        phase.value = AppPhase.Unlocked
        assertEquals(2, told.size)
        job.cancel()
    }

    /** A lock's late step never undoes an opening published since: it runs only while the vault is not open. */
    @Test
    fun aStepUnlessOpenRunsOnlyWhileTheVaultIsNotOpen() {
        val phase = PhaseHolder(AppPhase.Locked)
        var ran = 0
        phase.unlessOpen { ran++ }
        assertEquals(1, ran)
        phase.value = AppPhase.Unlocked
        phase.unlessOpen { ran++ }
        assertEquals(1, ran)
    }

    /**
     * (ANDROID-PLAN 0.1.134) An opening that only a status read which could not be made interrupted (Unlocked, Unreachable
     * only, Unlocked) carries on; any other phase between, or none open before, makes it a new opening.
     */
    @Test
    fun anOpeningCarriesOnOnlyAcrossUnreachable() {
        val told = mutableListOf<Boolean>()
        val phase = PhaseHolder(AppPhase.Starting, onOpen = { told += it })
        val unreachable = AppPhase.Unreachable(FailureKind.NETWORK)
        phase.value = unreachable
        phase.value = AppPhase.Unlocked // a cold start that first could not reach the service
        phase.value = unreachable
        phase.value = unreachable
        phase.value = AppPhase.Unlocked // a blip
        phase.value = AppPhase.Locked
        phase.value = unreachable
        phase.value = AppPhase.Unlocked // locked between: new
        phase.value = AppPhase.Locked
        phase.value = AppPhase.Unlocked
        assertEquals(listOf(false, true, false, false), told)
    }
}
