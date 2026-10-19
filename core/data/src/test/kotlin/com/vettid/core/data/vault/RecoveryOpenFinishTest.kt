package com.vettid.core.data.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The end of a recovery the vault answered `ok` (release R): the open app, setup finished on a recovered phone, and
 * never over a lock asked for meanwhile.
 */
class RecoveryOpenFinishTest {
    private val finishing = AppPhase.Setup(SetupStage.FINISHING)

    /** A vault and an account record as [VaultManager] has them, with the steps taken in [steps]. */
    private class Ports(
        var unlocked: AppPhase,
        var gateOpen: Boolean = true,
    ) : RecoveryOpenFinish.Ports {
        val steps = mutableListOf<String>()
        var phase: AppPhase = AppPhase.Setup(SetupStage.RECOVERING)
        var overtaken = RecoveryOpenFinish.Overtaken.NO

        /** Run once, after [afterUnlocked]: a lock asked for while the phase was computed. */
        var onAfterUnlocked: () -> Unit = {}

        /** Run when [finishSetup] is called, before its gate: a lock asked for between the phase and the finish. */
        var onFinishSetup: () -> Unit = {}

        override fun overtaken() = overtaken

        override suspend fun afterUnlocked(): AppPhase {
            steps += "afterUnlocked"
            return unlocked.also { onAfterUnlocked() }
        }

        override fun publish(phase: AppPhase) {
            steps += "publish $phase"
            this.phase = phase
        }

        override suspend fun finishSetup(): Boolean {
            steps += "finishSetup"
            onFinishSetup()
            // As FinishSetupGate.
            val allowed = gateOpen && FinishSetupGate.allows(phase, overtaken == RecoveryOpenFinish.Overtaken.LOCK_PENDING, false)
            if (allowed) phase = AppPhase.Unlocked
            return allowed
        }
    }

    private fun failure(p: Ports): VaultFailure = assertThrows(VaultFailure::class.java) { runBlocking { RecoveryOpenFinish.run(p) } }

    /** A recovered phone is a new install: the unlock ends in FINISHING, and setup is finished here. */
    @Test
    fun aRecoveredPhoneFinishesSetupAndOpens() = runBlocking {
        val p = Ports(unlocked = finishing)
        RecoveryOpenFinish.run(p)
        assertEquals(listOf("afterUnlocked", "publish $finishing", "finishSetup"), p.steps)
        assertEquals(AppPhase.Unlocked, p.phase)
    }

    /** A phone whose setup was complete opens at once; setup is not finished twice. */
    @Test
    fun aSetUpPhoneOpensWithoutFinishingSetup() = runBlocking {
        val p = Ports(unlocked = AppPhase.Unlocked)
        RecoveryOpenFinish.run(p)
        assertEquals(listOf("afterUnlocked", "publish ${AppPhase.Unlocked}"), p.steps)
        assertEquals(AppPhase.Unlocked, p.phase)
    }

    /** A lock pending before anything: Locked, nothing of the open computed or published, and the caller is told. */
    @Test
    fun aPendingLockComesFirst() {
        val p = Ports(unlocked = finishing).apply { overtaken = RecoveryOpenFinish.Overtaken.LOCK_PENDING }
        val e = failure(p)
        assertEquals(MoveRepository.CODE_LOCK_PENDING, e.code)
        assertEquals(listOf("publish ${AppPhase.Locked}"), p.steps)
        assertEquals(AppPhase.Locked, p.phase)
    }

    /** A lock asked for while the phase was computed: its Locked is never overwritten with the open phase. */
    @Test
    fun aLockDuringTheUnlocksEndIsNotOverwritten() {
        val p = Ports(unlocked = finishing)
        p.onAfterUnlocked = { p.overtaken = RecoveryOpenFinish.Overtaken.LOCK_PENDING }
        val e = failure(p)
        assertEquals(MoveRepository.CODE_LOCK_PENDING, e.code)
        assertEquals(listOf("afterUnlocked", "publish ${AppPhase.Locked}"), p.steps)
        assertEquals(AppPhase.Locked, p.phase)
    }

    /** A lock asked for after the phase and before the finish: the gate refuses, the phone is Locked, and it says so. */
    @Test
    fun aLockBeforeTheFinishIsAFailureNotASilentStop() {
        val p = Ports(unlocked = finishing)
        p.onFinishSetup = { p.overtaken = RecoveryOpenFinish.Overtaken.LOCK_PENDING }
        val e = failure(p)
        assertEquals(MoveRepository.CODE_LOCK_PENDING, e.code)
        assertEquals(AppPhase.Locked, p.phase)
    }

    /** The gate refused for another reason ("Finish the move" running): not opened, and a failure. */
    @Test
    fun aRefusedFinishIsAFailure() {
        val p = Ports(unlocked = finishing, gateOpen = false)
        val e = failure(p)
        assertEquals(MoveRepository.CODE_NOT_OPENED, e.code)
        assertEquals(finishing, p.phase)
    }

    /** A wipe or a confirmed lock since the open began: what it published stands. */
    @Test
    fun aWipeMeanwhilePublishesNothing() {
        val p = Ports(unlocked = finishing)
        p.onAfterUnlocked = { p.overtaken = RecoveryOpenFinish.Overtaken.OTHER }
        p.phase = AppPhase.SignedOut
        val e = failure(p)
        assertEquals(MoveRepository.CODE_NOT_OPENED, e.code)
        assertEquals(listOf("afterUnlocked"), p.steps)
        assertEquals(AppPhase.SignedOut, p.phase)
    }

    /** The unlock did not end where an `ok` leaves it (the credential not here): published, never opened, a failure. */
    @Test
    fun anUnlockThatDidNotEndInSetupIsAFailure() {
        val needs = AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        val p = Ports(unlocked = needs)
        val e = failure(p)
        assertEquals(MoveRepository.CODE_NOT_OPENED, e.code)
        assertEquals(needs, p.phase)
    }
}
