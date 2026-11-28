package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (#19721 b′) The vault-gone probe runs only with the vault open here, an activity started and no owner-check screen probing;
 * its lock is applied only with the vault still open, no unlock under way or answered since the probe began, and no screen
 * probing.
 */
class VaultGoneGateTest {
    private var phase: AppPhase = AppPhase.Unlocked
    private var started = true
    private var inFlight = false
    private var openings = 0L
    private var screen = false

    private val gate = VaultGoneGate({ phase }, { started }, { inFlight }, { openings }, { screen })

    @Test
    fun itWatchesOnlyWhileOpenInFrontAndNoScreenProbes() {
        assertEquals(true, gate.watching())
        phase = AppPhase.Locked
        assertEquals("locked", false, gate.watching())
        phase = AppPhase.Unlocked
        started = false
        assertEquals("a process a push started", false, gate.watching())
        started = true
        screen = true
        assertEquals("the owner check's screen probes", false, gate.watching())
    }

    @Test
    fun itsLockIsAppliedOnlyWithNoUnlockSinceItBegan() {
        val mark = gate.begin()
        assertEquals(true, gate.mayLock(mark))
        inFlight = true
        assertEquals("an unlock under way", false, gate.mayLock(mark))
        inFlight = false
        openings++
        assertEquals("an unlock answered since the probe began", false, gate.mayLock(mark))
        assertEquals("a probe begun after it", true, gate.mayLock(gate.begin()))
        phase = AppPhase.Locked
        assertEquals("no longer open", false, gate.mayLock(gate.begin()))
        phase = AppPhase.Unlocked
        screen = true
        assertEquals("the screen's own probe runs", false, gate.mayLock(gate.begin()))
    }

    /** (#19721 step 4) What a probe counts for: each fact on its own. */
    @Test
    fun whatAProbeCountsFor() {
        // The vault answered, or a lock was found: as found, whatever the API did.
        for (found in listOf(VaultProbe.OPEN, VaultProbe.LOCKED)) {
            assertEquals(found, VaultGoneGate.counted(ProbeFacts(found, apiAnswered = false)))
            assertEquals(found, VaultGoneGate.counted(ProbeFacts(found, apiAnswered = true)))
        }
        // No answer, the member API answered and its word was taken: counted.
        assertEquals(VaultProbe.NO_ANSWER, VaultGoneGate.counted(ProbeFacts(VaultProbe.NO_ANSWER, apiAnswered = true)))
        // It asked nobody: the API's read failed, or the lock it found could not be applied.
        assertNull(VaultGoneGate.counted(ProbeFacts(VaultProbe.NO_ANSWER, apiAnswered = false)))
        assertNull(VaultGoneGate.counted(ProbeFacts(VaultProbe.NO_ANSWER, apiAnswered = true, refused = true)))
        // Stale (a lock, a wipe or an unlock during it): nothing, even with the API's answer.
        assertNull(VaultGoneGate.counted(ProbeFacts(VaultProbe.NO_ANSWER, apiAnswered = true, stale = true)))
        // Stale before found: a stale probe that found the vault open or locked counts for nothing either.
        assertNull(VaultGoneGate.counted(ProbeFacts(VaultProbe.OPEN, apiAnswered = true, stale = true)))
        assertNull(VaultGoneGate.counted(ProbeFacts(VaultProbe.LOCKED, apiAnswered = true, stale = true)))
    }
}
