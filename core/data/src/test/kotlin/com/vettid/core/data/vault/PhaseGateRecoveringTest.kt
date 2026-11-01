package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhaseGate.publishRecovering] (#244 nit N3): a recovery registration's RECOVERING goes out only over the onboarding's
 * phases, with no lock pending and nothing that moved the generation since its mark. A lock, a wipe or a reset during its
 * vault calls wins; the registration's own reset, before its mark, does not.
 */
class PhaseGateRecoveringTest {
    private class Fixture(initial: AppPhase) {
        val phase = PhaseHolder(initial)
        var generation = 0
        var lockPending = false
        val gate = PhaseGate(phase, { generation }, { generation++ }, { lockPending })
    }

    private val recovering = AppPhase.Setup(SetupStage.RECOVERING)

    @Test
    fun fromTheWelcomeScreenOrASetupStageWithNothingBetweenItIsPublished() {
        for (start in listOf(AppPhase.SignedOut, AppPhase.Setup(SetupStage.VAULT_ELSEWHERE), recovering)) {
            val f = Fixture(start)
            val mark = f.gate.mark()
            assertTrue(start.toString(), f.gate.publishRecovering(mark))
            assertEquals(recovering, f.phase.value)
        }
    }

    /** `registerClaimed` resets the record (the generation moves), then takes its mark: its own reset never refuses it. */
    @Test
    fun theFlowsOwnResetBeforeItsMarkDoesNotRefuseIt() {
        val f = Fixture(AppPhase.SignedOut)
        f.generation++ // resetLocal
        val mark = f.gate.mark()
        assertTrue(f.gate.publishRecovering(mark))
        assertEquals(recovering, f.phase.value)
    }

    @Test
    fun aWipeDuringTheVaultCallsLeavesSignedOut() {
        val f = Fixture(AppPhase.Setup(SetupStage.VAULT_ELSEWHERE))
        val mark = f.gate.mark()
        // The wipe moves the generation and publishes SignedOut, the same phase the flow may have started from.
        f.generation++
        f.phase.value = AppPhase.SignedOut
        assertFalse(f.gate.publishRecovering(mark))
        assertEquals(AppPhase.SignedOut, f.phase.value)
    }

    @Test
    fun anotherResetDuringTheVaultCallsRefusesIt() {
        val f = Fixture(AppPhase.SignedOut)
        val mark = f.gate.mark()
        f.generation++ // a start over, "Erase", a new setup
        assertFalse(f.gate.publishRecovering(mark))
        assertEquals(AppPhase.SignedOut, f.phase.value)
    }

    @Test
    fun theVaultsNoticeDuringTheVaultCallsEndsLocked() {
        val f = Fixture(AppPhase.Setup(SetupStage.VAULT_ELSEWHERE))
        val mark = f.gate.mark()
        assertTrue(f.gate.lockedByVault())
        assertFalse(f.gate.publishRecovering(mark))
        assertEquals(AppPhase.Locked, f.phase.value)
    }

    @Test
    fun neverOverAPendingLock() {
        val f = Fixture(AppPhase.SignedOut)
        val mark = f.gate.mark()
        f.lockPending = true
        assertFalse(f.gate.publishRecovering(mark))
        assertEquals(AppPhase.SignedOut, f.phase.value)
    }

    /** Outside the onboarding (an open app, the unlock screen, a deletion, a start), even with the same generation. */
    @Test
    fun neverOverAPhaseTheRecoveryFlowDoesNotRunIn() {
        val others = listOf(
            AppPhase.Unlocked, AppPhase.Locked, AppPhase.Starting, AppPhase.Deleting(), AppPhase.Unreachable(FailureKind.NETWORK),
        )
        for (p in others) {
            val f = Fixture(p)
            val mark = f.gate.mark()
            assertFalse(p.toString(), f.gate.publishRecovering(mark))
            assertEquals(p, f.phase.value)
        }
    }
}
