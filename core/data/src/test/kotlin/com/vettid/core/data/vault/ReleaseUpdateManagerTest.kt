package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-step release update (owner decision 2026-10-09): lock → unlock with the approval → moved → unlock again. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseUpdateManagerTest {
    private fun rel(n: Long, status: String = "active") =
        ReleaseView(n, "%02d".format(n).repeat(48), status, null, "https://vettid.org/r/$n")

    private val offer = ReleaseUpdateOffer(rel(4, "deprecated"), rel(5))

    /** One unlock as the ops saw it. */
    private data class Call(val pin: String, val approve: Long?, val abandon: Boolean)

    private class Ops : ReleaseUpdateOps {
        val log = mutableListOf<String>()
        val unlocks = mutableListOf<Call>()
        val results = ArrayDeque<UpdateStepResult>()
        var offer: ReleaseUpdateOffer? = null
        var due = false
        var lockFails: VaultFailure? = null
        var abandonable = true

        /** Unlocks that report `release_starting` through the API client's callback before answering. */
        var startingCallbacks = 0

        /** Runs right after such a report, while the unlock still waits. */
        var whileStarting: () -> Unit = {}

        override suspend fun offer() = offer

        override fun ownerCheckDue() = due

        var changeUnresolved = false

        override fun changeUnresolved() = changeUnresolved

        override suspend fun lock() {
            log += "lock"
            lockFails?.let { throw it }
        }

        /** What [pinGeneration] reads; [taken] records the reading each unlock's PIN came with. */
        var generation = 0
        val taken = mutableListOf<Int>()

        override fun pinGeneration() = generation

        override suspend fun unlock(pin: TakenPin, approve: ReleaseView?, abandon: Boolean, onStarting: (Long) -> Unit): UpdateStepResult {
            // As the vault manager: nothing is sent with a PIN taken before which PIN the vault expects moved.
            taken += pin.generation
            if (pin.generation != generation) {
                log += "refused"
                return UpdateStepResult.Failed(FailureKind.CREDENTIAL_UNSETTLED, ReleaseUpdateManager.CODE_PIN_TAKEN_BEFORE)
            }
            log += "unlock"
            unlocks += Call(pin.pin, approve?.number, abandon)
            if (startingCallbacks > 0) {
                startingCallbacks--
                onStarting(5)
                whileStarting()
            }
            return results.removeFirstOrNull() ?: UpdateStepResult.Opened
        }

        override fun canAbandon() = abandonable
    }

    private fun TestScope.manager(ops: Ops) = ReleaseUpdateManager(backgroundScope, ops)

    @Test
    fun lockThenApproveThenMovedThenReopenWithTheSamePin() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        assertTrue(m.start("2468", offer))
        runCurrent()
        assertEquals(listOf("lock", "unlock", "unlock"), ops.log)
        assertEquals(listOf(Call("2468", 5, false), Call("2468", null, false)), ops.unlocks)
        val p = m.progress.value!!
        assertEquals(UpdateStep.DONE, p.step)
        assertTrue(p.vaultOpen)
        assertNull(m.offer.value)
        m.finish()
        assertNull(m.progress.value)
    }

    @Test
    fun theApprovalIsSentOnlyOnceAndOnlyForTheOfferedRelease() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Moved // routed to the old release again: it reports the move once more
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        assertEquals(1, ops.unlocks.count { it.approve != null })
        assertEquals(5L, ops.unlocks.first().approve)
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
    }

    @Test
    fun nothingTouchesTheVaultWithoutStart() = runTest {
        val ops = Ops()
        ops.offer = offer
        val m = manager(ops)
        m.refreshOffer()
        m.retry("2468")
        m.abandon("2468")
        m.finish()
        runCurrent()
        assertEquals(offer, m.offer.value)
        assertTrue(ops.log.isEmpty())
    }

    @Test
    fun theOwnerCheckComesFirst() = runTest {
        val ops = Ops()
        ops.due = true
        val m = manager(ops)
        assertFalse(m.start("2468", offer))
        runCurrent()
        assertNull(m.progress.value)
        assertTrue(ops.log.isEmpty())
    }

    @Test
    fun releaseStartingIsWaitedForAndTriedAgain() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Failed(FailureKind.VAULT_UNAVAILABLE, ReleaseUpdateManager.CODE_RELEASE_STARTING, 30)
        ops.results += UpdateStepResult.Failed(FailureKind.VAULT_UNAVAILABLE, ReleaseUpdateManager.CODE_RELEASE_STARTING, 30)
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        assertEquals(UpdateStep.REOPENING, m.progress.value!!.step)
        assertTrue(m.progress.value!!.starting)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(UpdateStep.REOPENING, m.progress.value!!.step)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
        assertFalse(m.progress.value!!.starting)
        assertEquals(4, ops.unlocks.size)
    }

    @Test
    fun startingReportedByTheApiClientShowsWhileItWaits() = runTest {
        val ops = Ops()
        ops.startingCallbacks = 1
        ops.results += UpdateStepResult.Moved
        val m = manager(ops)
        var seen: UpdateProgress? = null
        ops.whileStarting = { seen = m.progress.value }
        m.start("2468", offer)
        runCurrent()
        assertTrue(seen!!.starting)
        assertEquals(UpdateStep.APPROVING, seen!!.step)
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
        assertFalse(m.progress.value!!.starting)
    }

    @Test
    fun aReleaseThatNeverStartsIsAFailureNotAnEndlessWait() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        repeat(ReleaseUpdateManager.MAX_START_RETRIES + 1) {
            ops.results += UpdateStepResult.Failed(FailureKind.VAULT_UNAVAILABLE, ReleaseUpdateManager.CODE_RELEASE_STARTING, 10)
        }
        val m = manager(ops)
        m.start("2468", offer)
        // backgroundScope work does not count for advanceUntilIdle: move the clock past every wait.
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        val p = m.progress.value!!
        assertEquals(UpdateStep.NEW_RELEASE_FAILED, p.step)
        assertEquals(ReleaseUpdateManager.CODE_RELEASE_STARTING, p.failureCode)
        assertTrue(p.canAbandon)
    }

    @Test
    fun eachRefusalLeavesTheVaultOpenOnItsRelease() = runTest {
        for (code in listOf("target", "downgrade", "approval", "seal_key", "pending", "write")) {
            val ops = Ops()
            ops.results += UpdateStepResult.Refused(code)
            val m = manager(ops)
            m.start("2468", offer)
            runCurrent()
            val p = m.progress.value!!
            assertEquals(code, UpdateStep.REFUSED, p.step)
            assertEquals(code, p.refusal)
            assertTrue(p.vaultOpen)
            // The refusing unlock opened the vault; nothing more is sent.
            assertEquals(listOf("lock", "unlock"), ops.log)
            assertEquals(offer, ReleaseUpdateOffer(p.from, p.to))
        }
    }

    @Test
    fun aWrongPinLeavesTheVaultLockedAndTheNextPinApprovesAgain() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.BadPin(0)
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        m.start("1111", offer)
        runCurrent()
        assertEquals(UpdateStep.BAD_PIN, m.progress.value!!.step)
        assertEquals(FailureKind.BAD_PIN, m.progress.value!!.failure)
        assertFalse(m.progress.value!!.vaultOpen)
        m.retry("2468")
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
        assertEquals(listOf(Call("1111", 5, false), Call("2468", 5, false), Call("2468", null, false)), ops.unlocks)
        // Locked once only: the wrong PIN left it locked.
        assertEquals(1, ops.log.count { it == "lock" })
    }

    @Test
    fun theEnclaveBackoffIsReported() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Backoff(90)
        val m = manager(ops)
        m.start("1111", offer)
        runCurrent()
        val p = m.progress.value!!
        assertEquals(UpdateStep.BAD_PIN, p.step)
        assertEquals(FailureKind.BACKOFF, p.failure)
        assertEquals(90L, p.waitSeconds)
    }

    @Test
    fun aLockThatFailsChangesNothing() = runTest {
        val ops = Ops()
        ops.lockFails = VaultFailure(FailureKind.NETWORK)
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        val p = m.progress.value!!
        assertEquals(UpdateStep.FAILED, p.step)
        assertTrue(p.vaultOpen)
        assertTrue(ops.unlocks.isEmpty())
        m.finish()
        assertEquals(offer, ReleaseUpdateOffer(p.from, p.to))
    }

    @Test
    fun aFailureBeforeTheMoveIsTriedAgainWithTheApproval() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Failed(FailureKind.NETWORK, null)
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        assertEquals(UpdateStep.FAILED, m.progress.value!!.step)
        assertFalse(m.progress.value!!.vaultOpen)
        m.retry("2468")
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
        assertEquals(listOf(5L, 5L, null), ops.unlocks.map { it.approve })
    }

    @Test
    fun aNewReleaseThatDoesNotOpenOffersTheWayBack() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Failed(FailureKind.NO_RESPONSE, null)
        ops.results += UpdateStepResult.Abandoned
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        val p = m.progress.value!!
        assertEquals(UpdateStep.NEW_RELEASE_FAILED, p.step)
        assertTrue(p.canAbandon)
        m.abandon("2468")
        runCurrent()
        assertEquals(UpdateStep.ABANDONED, m.progress.value!!.step)
        assertTrue(m.progress.value!!.vaultOpen)
        assertEquals(Call("2468", null, true), ops.unlocks.last())
    }

    @Test
    fun noWayBackOnceTheMoveCannotBeAbandoned() = runTest {
        val ops = Ops()
        ops.abandonable = false
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Failed(FailureKind.NO_RESPONSE, null)
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        assertFalse(m.progress.value!!.canAbandon)
        m.abandon("2468")
        runCurrent()
        assertEquals(2, ops.unlocks.size)
        assertEquals(UpdateStep.NEW_RELEASE_FAILED, m.progress.value!!.step)
    }

    @Test
    fun theNewReleaseIsTriedAgainWithoutAnApproval() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Failed(FailureKind.NO_RESPONSE, null)
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        m.retry("2468")
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
        assertEquals(listOf(5L, null, null), ops.unlocks.map { it.approve })
    }

    @Test
    fun aSecondStartWhileOneRunsIsRefused() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        val m = manager(ops)
        assertTrue(m.start("2468", offer))
        assertFalse(m.start("2468", offer))
        runCurrent()
        assertEquals(1, ops.log.count { it == "lock" })
    }

    @Test
    fun anOpenWithoutUpdateMemberIsNotTakenForAMove() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Opened
        val m = manager(ops)
        m.start("2468", offer)
        runCurrent()
        assertEquals(UpdateStep.REFUSED, m.progress.value!!.step)
        assertEquals(ReleaseUpdateManager.CODE_NOT_MOVED, m.progress.value!!.refusal)
    }

    @Test
    fun aFailedRefreshKeepsTheLastOffer() = runTest {
        val ops = object : ReleaseUpdateOps by Ops() {
            var calls = 0

            override suspend fun offer(): ReleaseUpdateOffer? = if (calls++ == 0) offer else throw VaultFailure(FailureKind.NETWORK)
        }
        val m = ReleaseUpdateManager(backgroundScope, ops)
        m.refreshOffer()
        m.refreshOffer()
        assertEquals(offer, m.offer.value)
    }

    @Test
    fun theOfferIsKnownOnlyOnceRead() = runTest {
        val ops = object : ReleaseUpdateOps by Ops() {
            var calls = 0

            override suspend fun offer(): ReleaseUpdateOffer? = if (calls++ == 0) throw VaultFailure(FailureKind.NETWORK) else null
        }
        val m = ReleaseUpdateManager(backgroundScope, ops)
        assertFalse(m.offerKnown.value)
        // A failed read: still not known (a null offer then says nothing about a posted notification).
        m.refreshOffer()
        assertFalse(m.offerKnown.value)
        m.refreshOffer()
        assertTrue(m.offerKnown.value)
        assertNull(m.offer.value)
    }

    /**
     * VAULT-MESSAGING 0.44.0 §11.14.9 (H1): while a PIN or password change is undecided the update sends nothing: it
     * does not start, and a retry or a return to the previous release is held with "still checking". Its lock and
     * unlock would send a PIN that may be the wrong one of two.
     */
    @Test
    fun noUpdateStepIsSentWhileAChangeIsUnresolved() = runTest {
        val ops = Ops()
        ops.changeUnresolved = true
        val m = manager(ops)
        assertTrue(m.changeUnresolved())
        assertFalse(m.start("2468", offer))
        runCurrent()
        assertTrue(ops.log.isEmpty())
        // A failed update (the PIN step) and a new release that did not open: held too.
        ops.changeUnresolved = false
        ops.results += UpdateStepResult.BadPin(0)
        assertTrue(m.start("2468", offer))
        runCurrent()
        assertEquals(UpdateStep.BAD_PIN, m.progress.value!!.step)
        val sent = ops.log.size
        ops.changeUnresolved = true
        m.retry("1357")
        runCurrent()
        assertEquals("nothing sent", sent, ops.log.size)
        assertEquals(FailureKind.CHANGE_UNRESOLVED, m.progress.value!!.failure)
        assertEquals(UpdateStep.BAD_PIN, m.progress.value!!.step)
        ops.changeUnresolved = false
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Failed(FailureKind.NETWORK, null)
        m.retry("1357")
        runCurrent()
        assertEquals(UpdateStep.NEW_RELEASE_FAILED, m.progress.value!!.step)
        val sent2 = ops.log.size
        ops.changeUnresolved = true
        m.abandon("1357")
        m.retry("1357")
        runCurrent()
        assertEquals(sent2, ops.log.size)
        assertEquals(FailureKind.CHANGE_UNRESOLVED, m.progress.value!!.failure)
    }

    // --- (VAULT-MESSAGING §11.14.9 step 2; chosen) a PIN taken before which PIN the vault expects moved ---

    /**
     * A PIN change (or anything that moves which PIN the vault expects) while the member typed: the approval's unlock
     * sends nothing, the step fails with the "earlier request" text and `pin_taken_before_change`, and the PIN typed again
     * with a fresh reading goes as usual.
     */
    @Test
    fun aPinTakenBeforeAChangeSendsNothingAndIsAskedAgain() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val reading = m.pinGeneration
        ops.generation++ // a PIN change began while the PIN was typed
        assertTrue(m.start("2468", offer, reading))
        runCurrent()
        assertEquals(listOf("lock", "refused"), ops.log)
        assertTrue("no unlock sent", ops.unlocks.isEmpty())
        val p = m.progress.value!!
        assertEquals(UpdateStep.FAILED, p.step)
        assertEquals(FailureKind.CREDENTIAL_UNSETTLED, p.failure)
        assertEquals(ReleaseUpdateManager.CODE_PIN_TAKEN_BEFORE, p.failureCode)
        assertTrue(p.waiting)
        m.retry("1357", m.pinGeneration)
        runCurrent()
        assertEquals(listOf(Call("1357", 5, false)), ops.unlocks)
        assertEquals(listOf(0, 1), ops.taken)
    }

    /** The reopen after `moved` goes with the approval's reading: a change decided in between stops it too. */
    @Test
    fun theReopenAfterAMoveChecksTheSameReading() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        val m = manager(ops)
        ops.whileStarting = { ops.generation++ }
        ops.startingCallbacks = 1 // the approval's unlock: a change decided while it is on its way
        assertTrue(m.start("2468", offer, m.pinGeneration))
        runCurrent()
        assertEquals(listOf("lock", "unlock", "refused"), ops.log)
        val p = m.progress.value!!
        assertEquals(UpdateStep.NEW_RELEASE_FAILED, p.step)
        assertEquals(ReleaseUpdateManager.CODE_PIN_TAKEN_BEFORE, p.failureCode)
        // Typed again: the reopen goes, and so does the way back.
        ops.results += UpdateStepResult.Opened
        m.retry("2468", m.pinGeneration)
        runCurrent()
        assertEquals(UpdateStep.DONE, m.progress.value!!.step)
    }

    /** Without a reading from the keypad, the PIN's generation is read as it is handed over. */
    @Test
    fun withoutAReadingThePinIsTakenAtHandover() = runTest {
        val ops = Ops()
        ops.generation = 7
        val m = manager(ops)
        assertTrue(m.start("2468", offer))
        runCurrent()
        assertEquals(listOf(7), ops.taken)
        assertEquals(UpdateStep.REFUSED, m.progress.value!!.step)
    }

    /** "Return to release N" with a PIN taken before a change sends nothing either. */
    @Test
    fun anAbandonWithAPinTakenBeforeAChangeSendsNothing() = runTest {
        val ops = Ops()
        ops.results += UpdateStepResult.Moved
        ops.results += UpdateStepResult.Failed(FailureKind.NETWORK, null)
        val m = manager(ops)
        assertTrue(m.start("2468", offer, m.pinGeneration))
        runCurrent()
        assertEquals(UpdateStep.NEW_RELEASE_FAILED, m.progress.value!!.step)
        val reading = m.pinGeneration
        ops.generation++
        m.abandon("2468", reading)
        runCurrent()
        assertEquals("refused", ops.log.last())
        assertTrue(ops.unlocks.none { it.abandon })
        assertEquals(ReleaseUpdateManager.CODE_PIN_TAKEN_BEFORE, m.progress.value!!.failureCode)
    }
}
