package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhaseGate] (#242 follow-up): a lock that is not this phone's (the vault's notice, refused deposits) during the vault
 * calls of an unlock's end, enrolment, a transfer to this phone or a recovered credential ends Locked, never with the
 * phase that work computed.
 */
class PhaseGateTest {
    private class Fixture(initial: AppPhase) {
        val phase = PhaseHolder(initial)
        var generation = 0
        var lockPending = false
        val gate = PhaseGate(phase, { generation }, { generation++ }, { lockPending })
    }

    private val finishing = AppPhase.Setup(SetupStage.FINISHING)

    @Test
    fun aNoticeDuringAnUnlocksEndEndsLocked() {
        // The phase is still Locked while the unlock answers: the notice publishes nothing, but moves the generation.
        val f = Fixture(AppPhase.Locked)
        val mark = f.gate.mark()
        assertFalse(f.gate.lockedByVault())
        assertFalse(f.gate.publishAfter(mark, AppPhase.Unlocked))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aNoticeDuringEnrolmentEndsLocked() {
        val f = Fixture(AppPhase.Setup(SetupStage.ENROLLING))
        val mark = f.gate.mark()
        assertTrue(f.gate.lockedByVault())
        assertFalse(f.gate.publishAfter(mark, finishing))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aNoticeDuringATransferInEndsLocked() {
        val f = Fixture(AppPhase.Setup(SetupStage.NEW_VAULT))
        val mark = f.gate.mark()
        f.gate.lockedByVault()
        assertFalse(f.gate.publishAfter(mark, finishing))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aNoticeDuringARecoveredCredentialEndsLocked() {
        val f = Fixture(AppPhase.Setup(SetupStage.RECOVERING))
        val mark = f.gate.mark()
        f.gate.lockedByVault()
        assertFalse(f.gate.publishAfter(mark, finishing))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aRefusalDuringAnyOfThemEndsLocked() {
        for (start in listOf(AppPhase.Locked, AppPhase.Setup(SetupStage.ENROLLING), AppPhase.Setup(SetupStage.RECOVERING))) {
            for (computed in listOf(AppPhase.Unlocked, finishing)) {
                val f = Fixture(start)
                val mark = f.gate.mark()
                f.gate.refused()
                assertFalse("$start → $computed", f.gate.publishAfter(mark, computed))
                assertEquals(AppPhase.Locked, f.phase.value)
            }
        }
    }

    @Test
    fun aPendingLockKeepsWhatItPublished() {
        val f = Fixture(AppPhase.Locked)
        val mark = f.gate.mark()
        f.lockPending = true
        assertFalse(f.gate.publishAfter(mark, finishing))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun nothingBetweenPublishes() {
        val f = Fixture(AppPhase.Setup(SetupStage.ENROLLING))
        assertTrue(f.gate.publishAfter(f.gate.mark(), finishing))
        assertEquals(finishing, f.phase.value)
    }

    @Test
    fun aNoticeAfterAWipeLeavesSignedOut() {
        val f = Fixture(AppPhase.SignedOut)
        assertFalse(f.gate.lockedByVault())
        assertEquals(AppPhase.SignedOut, f.phase.value)
        // Moved all the same: what was on its way publishes nothing.
        assertEquals(1, f.generation)
    }

    @Test
    fun aNoticeOverTheOpenAppLocksIt() {
        val f = Fixture(AppPhase.Unlocked)
        assertTrue(f.gate.lockedByVault())
        assertEquals(AppPhase.Locked, f.phase.value)
    }
}
