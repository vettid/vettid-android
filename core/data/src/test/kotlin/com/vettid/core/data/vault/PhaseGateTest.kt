package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhaseGate] (#242 follow-up, #244): a lock that is not this phone's (the vault's notice, refused deposits) during the
 * vault calls of an unlock's end, enrolment, a transfer to this phone or a recovered credential ends Locked, never with
 * the phase that work computed; the notice also ends a start or an unreachable phase.
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
        assertFalse(f.gate.publishFinishing(mark))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    /**
     * B1, in the real order: the unlock (or refresh, or the wait for the transfer) takes its mark, the notice comes
     * during its vault call, and only then does the transfer complete. FINISHING goes out with the caller's mark; one
     * taken where the transfer completes is after the notice, and would have let FINISHING over Locked.
     */
    @Test
    fun aNoticeDuringTheCallBeforeATransferCompletesEndsLocked() {
        val f = Fixture(AppPhase.Locked)
        val callers = f.gate.mark()
        f.gate.lockedByVault()
        val tooLate = f.gate.mark()
        assertFalse(f.gate.publishAfter(callers, finishing))
        assertEquals(AppPhase.Locked, f.phase.value)
        // What the mark taken inside the transfer's completion did:
        assertTrue(f.gate.publishAfter(tooLate, finishing))
    }

    @Test
    fun aNoticeDuringAWaitedTransferEndsLocked() {
        val f = Fixture(AppPhase.Setup(SetupStage.TRANSFER_WAITING))
        val mark = f.gate.mark()
        f.gate.lockedByVault()
        assertFalse(f.gate.publishFinishing(mark))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aNoticeDuringARecoveredCredentialEndsLocked() {
        val f = Fixture(AppPhase.Setup(SetupStage.RECOVERING))
        val mark = f.gate.mark()
        f.gate.lockedByVault()
        assertFalse(f.gate.publishFinishing(mark))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    /** B2: the start's own refresh is dropped by the notice (the generation moved): Locked, not "Opening VettID" for good. */
    @Test
    fun aNoticeAtTheStartEndsLocked() {
        val f = Fixture(AppPhase.Starting)
        val refresh = f.gate.mark()
        assertTrue(f.gate.lockedByVault())
        assertFalse(f.gate.publishAfter(refresh, AppPhase.Unlocked))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aNoticeWhileUnreachableEndsLocked() {
        val f = Fixture(AppPhase.Unreachable(FailureKind.NETWORK))
        assertTrue(f.gate.lockedByVault())
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun aRefusalDuringAnUnlocksEndEndsLocked() {
        for (computed in listOf(AppPhase.Unlocked, finishing)) {
            val f = Fixture(AppPhase.Locked)
            val mark = f.gate.mark()
            f.gate.refused()
            assertFalse("$computed", f.gate.publishAfter(mark, computed))
            assertEquals(AppPhase.Locked, f.phase.value)
        }
    }

    @Test
    fun aRefusalOverTheOpenAppLocksIt() {
        val f = Fixture(AppPhase.Unlocked)
        assertTrue(f.gate.refused())
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    /** S2: a refusal after a wipe: SignedOut stays (the generation moves all the same). */
    @Test
    fun aRefusalAfterAWipeLeavesSignedOut() {
        val f = Fixture(AppPhase.SignedOut)
        assertFalse(f.gate.refused())
        assertEquals(AppPhase.SignedOut, f.phase.value)
        assertEquals(1, f.generation)
    }

    @Test
    fun aRefusalDuringEnrolmentKeepsFinishingOut() {
        val f = Fixture(AppPhase.Setup(SetupStage.ENROLLING))
        val mark = f.gate.mark()
        f.gate.refused()
        assertFalse(f.gate.publishFinishing(mark))
        assertEquals(AppPhase.Setup(SetupStage.ENROLLING), f.phase.value)
    }

    @Test
    fun aPendingLockKeepsWhatItPublished() {
        val f = Fixture(AppPhase.Locked)
        val mark = f.gate.mark()
        f.lockPending = true
        assertFalse(f.gate.publishAfter(mark, finishing))
        assertFalse(f.gate.publishAfter(mark, AppPhase.Unlocked))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    /** S3: a refresh that finds the lock pending says Locked: published, whatever the phase was. */
    @Test
    fun lockedIsPublishedOverAPendingLock() {
        val f = Fixture(AppPhase.Starting)
        val mark = f.gate.mark()
        f.lockPending = true
        assertTrue(f.gate.publishAfter(mark, AppPhase.Locked))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    /**
     * N1: a start with a pending lock (it holds the phone, and publishes no phase) whose `evaluate` failed says
     * Unreachable: published, or the phase stays Starting with nothing retrying. SignedOut too; nothing that opens.
     */
    @Test
    fun overAPendingLockOnlyWhatOpensIsRefused() {
        for (v in listOf(AppPhase.Unreachable(FailureKind.NETWORK), AppPhase.SignedOut, AppPhase.Locked)) {
            val f = Fixture(AppPhase.Starting)
            f.lockPending = true
            assertTrue("$v", f.gate.publishAfter(f.gate.mark(), v))
            assertEquals(v, f.phase.value)
        }
        for (v in listOf(AppPhase.Unlocked, finishing, AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL))) {
            val f = Fixture(AppPhase.Starting)
            f.lockPending = true
            assertFalse("$v", f.gate.publishAfter(f.gate.mark(), v))
            assertEquals(AppPhase.Starting, f.phase.value)
        }
    }

    /** S1: FINISHING and the other setup stages only over a setup phase. */
    @Test
    fun setupStagesOnlyOverASetupPhase() {
        for (p in listOf(AppPhase.Locked, AppPhase.SignedOut, AppPhase.Unlocked)) {
            val f = Fixture(p)
            assertFalse("$p", f.gate.publishFinishing(f.gate.mark()))
            assertFalse("$p", f.gate.setupStage(AppPhase.Setup(SetupStage.ENROLLING)))
            assertEquals(p, f.phase.value)
        }
        val f = Fixture(AppPhase.Setup(SetupStage.NEW_VAULT))
        assertTrue(f.gate.setupStage(AppPhase.Setup(SetupStage.ENROLLING)))
        assertTrue(f.gate.publishFinishing(f.gate.mark()))
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
