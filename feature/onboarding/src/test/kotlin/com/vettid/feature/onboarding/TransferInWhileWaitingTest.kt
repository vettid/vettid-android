package com.vettid.feature.onboarding

import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.TransferInWaiting
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANDROID-PLAN 0.1.141 "Back while the transfer waits" and 0.1.142 "A different code while the transfer waits": while a
 * transfer to this phone is live, Back keeps the move screen, a confirmed cancel leaves only once its abandon is done, and a
 * different code asks first before minute 10 and is refused from it, nothing dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInWhileWaitingTest : TransferInWaitingFixture() {
    @Test
    fun backWhileTheTransferIsReadBackKeepsTheScreen() = runTest {
        val vm = waitingFor(60)
        assertTrue("reading back: consumed", vm.back())
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertTrue("before minute 10: the confirmation", vm.back())
        assertTrue(vm.uiState.value.confirmCancel)
        vm.dismissCancel()
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertFalse("abandonTransferIn" in vault.calls)
    }

    @Test
    fun aConfirmedCancelLeavesOnlyOnceTheAbandonIsDone() = runTest {
        val vm = waitingFor(60)
        advanceTimeBy(100)
        val gate = CompletableDeferred<Unit>()
        vault.abandonGate = gate
        vm.cancel()
        vm.confirmCancel()
        advanceTimeBy(100)
        assertTrue("abandonTransferIn" in vault.calls)
        // While the abandon is on its way: the waiting screen, Back held, no second cancel.
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertTrue(vm.back())
        vm.cancel()
        assertFalse(vm.uiState.value.confirmCancel)
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
    }

    /** The old phone approved as the member confirmed: the repository keeps the paired transfer, and the move goes on. */
    @Test
    fun anApprovalThatLandsDuringTheAbandonIsNotShownAsCancelled() = runTest {
        val vm = waitingFor(60)
        advanceTimeBy(100)
        vault.abandonKeepsPaired = true
        vm.cancel()
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals("the move goes on, not the first step", TransferInStep.COMPARE, vm.uiState.value.step)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    @Test
    fun aCancelRefusedAtMinuteTenReturnsToTheWaitingTransfer() = runTest {
        val vm = waitingFor(595)
        advanceTimeBy(100)
        vm.cancel()
        vault.abandonFailure = FakeVault.failure(FailureKind.BUSY, MoveRepository.CODE_TRANSFER_SETTLING)
        vm.confirmCancel()
        advanceTimeBy(100)
        val s = vm.uiState.value
        assertEquals("not the first step", TransferInStep.COMPARE, s.step)
        assertEquals("042817", s.sas)
        assertNotNull("nothing dropped", vault.waitingTransfer)
        // The wait goes on: past minute 10 the checking screen, and Back does nothing.
        advanceTimeBy(6_000)
        assertTrue(vm.uiState.value.settling)
        assertTrue(vm.back())
    }

    @Test
    fun aDifferentCodeBeforeMinuteTenAsksFirstAndKeepWaitingDropsNothing() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, other)
        assertTrue(vm.uiState.value.confirmCancel)
        assertEquals(other, vm.uiState.value.pendingCode)
        vm.dismissCancel()
        advanceTimeBy(100)
        assertEquals("back to the waiting transfer", TransferInStep.COMPARE, vm.uiState.value.step)
        assertNull(vm.uiState.value.pendingCode)
        assertFalse("abandonTransferIn" in vault.calls)
        assertFalse("transferIn" in vault.calls)
        assertNotNull(vault.waitingTransfer)
    }

    @Test
    fun cancelTheMoveAbandonsThenStartsTheNewCode() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, other)
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals(listOf("abandonTransferIn", "transferIn"), vault.calls.filter { it == "abandonTransferIn" || it == "transferIn" })
        assertEquals(other, vault.lastTransferCode)
    }

    @Test
    fun anAbandonThatFailsStartsNoNewCode() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, other)
        // As in the device: memory no longer holds the transfer when the drop's save fails and it throws.
        vault.abandonFailure = IllegalStateException("the drop's save failed")
        vault.abandonFailureDrops = true
        vm.confirmCancel()
        advanceTimeBy(100)
        assertFalse("transferIn" in vault.calls)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
    }

    @Test
    fun aConfirmationOpenAsMinuteTenPassesGoesBackToTheWaitingTransfer() = runTest {
        val vm = waitingFor(590)
        pasteBeforeTheReadBack(vm, other)
        assertTrue(vm.uiState.value.confirmCancel)
        advanceTimeBy(11_000)
        val s = vm.uiState.value
        assertFalse(s.confirmCancel)
        assertNull(s.pendingCode)
        assertEquals(TransferInStep.COMPARE, s.step)
        vm.confirmCancel() // too late: nothing
        advanceTimeBy(100)
        assertFalse("abandonTransferIn" in vault.calls)
        assertFalse("transferIn" in vault.calls)
    }

    @Test
    fun aDifferentCodeFromMinuteTenIsRefusedAndDropsNothing() = runTest {
        for (ago in listOf(630L, 650L)) {
            vault.calls.clear()
            val vm = waitingFor(ago)
            pasteBeforeTheReadBack(vm, other)
            val s = vm.uiState.value
            assertFalse(s.confirmCancel)
            assertEquals(FailureKind.BUSY, s.error)
            assertEquals(MoveRepository.CODE_TRANSFER_SETTLING, s.errorCode)
            assertFalse("abandonTransferIn" in vault.calls)
            assertFalse("transferIn" in vault.calls)
        }
    }

    /** While the abandon for a different code is on its way (from the paste step): Back is held, and the code goes on. */
    @Test
    fun backDuringTheAbandonForADifferentCodeIsHeld() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, other)
        val gate = CompletableDeferred<Unit>()
        vault.abandonGate = gate
        vm.confirmCancel()
        advanceTimeBy(100)
        assertTrue(vm.back())
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(other, vault.lastTransferCode)
    }

    @Test
    fun aDifferentCodeWhoseCancelIsRefusedAtMinuteTenIsNotUsed() = runTest {
        val vm = waitingFor(595)
        pasteBeforeTheReadBack(vm, other)
        vault.abandonFailure = FakeVault.failure(FailureKind.BUSY, MoveRepository.CODE_TRANSFER_SETTLING)
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertFalse("transferIn" in vault.calls)
        assertNotNull(vault.waitingTransfer)
    }

    @Test
    fun aDifferentCodeScannedBeforeTheReadBackAsksFirst() = runTest {
        val vm = waitingFor(120)
        vm.scan()
        vm.scanned(other)
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        vm.scanned(other) // the camera's next frame: nothing more
        advanceTimeBy(100)
        assertFalse("transferIn" in vault.calls)
    }

    @Test
    fun anInvalidCodeWhileATransferWaitsSaysSoAndChangesNothing() = runTest {
        val vm = waitingFor(120)
        vm.paste()
        vm.setInput("not a code")
        vm.submitInput()
        assertEquals(FailureKind.INVITE_INVALID, vm.uiState.value.inputProblem)
        advanceTimeBy(100)
        assertFalse("abandonTransferIn" in vault.calls)
        assertFalse("transferIn" in vault.calls)
        assertNotNull(vault.waitingTransfer)
    }

    @Test
    fun theReadBackShowsTheWaitingTransferAlsoFromTheScanStep() = runTest {
        val vm = waitingFor(120)
        vm.scan()
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
    }

    /** The read-back fails: the screen is at its first step, and Back goes to the shell, which reads the phase again. */
    @Test
    fun aFailedReadBackLeavesBackToThePhase() = runTest {
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        val vm = waitingFor(120)
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertFalse("the onboarding decides by the phase (OnboardingViewModelTest)", vm.back())
    }

    @Test
    fun theSameCodeGoesBackToItsMoveAndAsksNothing() = runTest {
        val own = code("https://relay.vettid.test")
        vault.openTransferCode = own
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, own)
        assertFalse(vm.uiState.value.confirmCancel)
        // (The #446 pair, L2) Back to its waiting transfer, with no new connection: no CONNECTING whose Cancel could drop it.
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        assertFalse("transferIn" in vault.calls)
        assertFalse("abandonTransferIn" in vault.calls)
    }

    /**
     * (The #446 pair, L2) The waiting transfer's own code, re-scanned after its read-back failed, connects again (the
     * repository recognises it): Cancel (and Back) during that connection shows the transfer with "Cancel the move?", and
     * drops nothing.
     */
    @Test
    fun cancelWhileConnectingWithAWaitingTransferAsksFirst() = runTest {
        for (viaBack in listOf(false, true)) {
            vault.calls.clear()
            vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
            vault.transferHsResp = CompletableDeferred()
            vault.openTransferCode = other
            val vm = readBackFailedThenPasted(other)
            vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x") // the code's own read fails too
            vm.submitInput()
            advanceTimeBy(100)
            assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
            vault.waitingFailure = null
            if (viaBack) assertTrue(vm.back()) else vm.cancel()
            advanceTimeBy(100)
            val st = vm.uiState.value
            assertEquals(TransferInStep.COMPARE, st.step)
            assertEquals("042817", st.sas)
            assertTrue("confirmed first", st.confirmCancel)
            assertFalse("nothing dropped", "abandonTransferIn" in vault.calls)
            vm.dismissCancel()
            assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        }
    }

    /** With no transfer waiting, Cancel while connecting drops the connection as before. */
    @Test
    fun cancelWhileConnectingWithNothingWaitingDrops() = runTest {
        vault.transferHsResp = CompletableDeferred()
        val vm = waitingFor(0)
        vault.waitingTransfer = null // nothing waits (read before the screen's first read-back runs)
        advanceTimeBy(100)
        vm.paste()
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        vm.cancel()
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertTrue("abandonTransferIn" in vault.calls)
    }

    /** (The #446 pair, L4) A refusal from minute 10, before the read-back: shown, and Back goes to the waiting transfer. */
    @Test
    fun backAfterARefusalBeforeTheReadBackGoesToTheWaitingTransfer() = runTest {
        val vm = waitingFor(630)
        pasteBeforeTheReadBack(vm, other)
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
        assertEquals("the busy text is shown", FailureKind.BUSY, vm.uiState.value.error)
        assertTrue(vm.back())
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertEquals("the waiting transfer, not the first step", TransferInStep.COMPARE, st.step)
        assertNull(st.error)
        assertNotNull(vault.waitingTransfer)
    }

    /** (The #446 pair, L4) The same with a code of the wrong kind. */
    @Test
    fun backAfterABadCodeBeforeTheReadBackGoesToTheWaitingTransfer() = runTest {
        val vm = waitingFor(120)
        val expired = String(
            InviteQr(
                InviteKind.APP, "https://relay-other.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 },
                ByteArray(32) { 2 }, start.minusSeconds(3_600).epochSecond,
            ).marshal(),
        )
        pasteBeforeTheReadBack(vm, expired)
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.inputProblem)
        assertTrue(vm.back())
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
    }

    /** (The #446 pair, L5) Approved during the abandon and already paired (no longer waiting): the move ends, not "cancelled". */
    @Test
    fun aTransferPairedDuringTheAbandonEndsDone() = runTest {
        val vm = waitingFor(60)
        advanceTimeBy(100)
        vault.phaseAfterRefresh = AppPhase.Setup(SetupStage.FINISHING)
        vm.cancel()
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        assertTrue("refresh" in vault.calls)
    }

    /** A done abandon with the phase still waiting is a cancel, as before. */
    @Test
    fun aDoneAbandonWithThePhaseUnchangedIsACancel() = runTest {
        val vm = waitingFor(60)
        advanceTimeBy(100)
        vm.cancel()
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
    }

    /** (The #446 pair, M14) While a different code waits behind the confirmation, a scanned frame starts nothing. */
    @Test
    fun aScanWhileTheConfirmationIsOpenStartsNothing() = runTest {
        val vm = waitingFor(120)
        vm.scan()
        vm.scanned(other)
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        val third = code("https://relay-third.vettid.test")
        vm.scanned(third)
        advanceTimeBy(100)
        assertEquals(other, vm.uiState.value.pendingCode)
        assertFalse("transferIn" in vault.calls)
    }

    /** A different code confirmed from the paste step starts from there. */
    @Test
    fun aConfirmedCodeStartsFromTheStepItCameFrom() = runTest {
        val vm = waitingFor(120)
        vault.transferHsResp = CompletableDeferred()
        pasteBeforeTheReadBack(vm, other)
        assertTrue(vm.uiState.value.confirmCancel)
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals(other, vault.lastTransferCode)
        vault.transferHsResp!!.completeExceptionally(FakeVault.failure(FailureKind.OTHER, "x"))
        advanceTimeBy(100)
        assertEquals("back to the paste step", TransferInStep.PASTE, vm.uiState.value.step)
    }

    /** A scanned different code from minute 10 is refused, nothing dropped (as the paste). */
    @Test
    fun aScannedDifferentCodeFromMinuteTenIsRefused() = runTest {
        val vm = waitingFor(630)
        vm.scan()
        vm.scanned(other)
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertFalse(st.confirmCancel)
        assertEquals(FailureKind.BUSY, st.error)
        assertFalse("abandonTransferIn" in vault.calls)
        assertFalse("transferIn" in vault.calls)
        assertNotNull(vault.waitingTransfer)
    }

    /**
     * (The #446 pair, High) After "Try again" or "Scan a new code" from an ended step, Back works: from the scan step to
     * the first step, and from there to the shell.
     */
    @Test
    fun backWorksAfterAgainFromEachEndedStep() = runTest {
        val endings = mapOf(
            TransferInStep.REJECTED to FakeVault.failure(FailureKind.REJECTED, "x"),
            TransferInStep.TIMED_OUT to FakeVault.failure(FailureKind.NO_RESPONSE, "x"),
            TransferInStep.SESSION_ENDED to FakeVault.failure(FailureKind.RETRY, MoveRepository.CODE_TRANSFER_SESSION_ENDED),
        )
        for ((ended, failure) in endings) {
            vault.transferApproval = CompletableDeferred()
            val vm = waitingFor(0)
            vault.waitingTransfer = null
            advanceTimeBy(100)
            vm.paste()
            vm.setInput(other)
            vm.submitInput()
            advanceTimeBy(100)
            assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
            vault.transferApproval.completeExceptionally(failure)
            advanceTimeBy(100)
            assertEquals(ended, vm.uiState.value.step)
            vm.again()
            assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
            assertTrue("$ended: Back from the scan step", vm.back())
            assertEquals("$ended: the first step", TransferInStep.INTRO, vm.uiState.value.step)
            assertFalse("$ended: then to the shell", vm.back())
        }
    }

    /** (The #446 pair, L3) A move that stopped before a restart is told also when the member went on to scan meanwhile. */
    @Test
    fun aStoppedMoveIsToldFromTheScanStepToo() = runTest {
        for (go in listOf<(TransferInViewModel) -> Unit>({ it.scan() }, { it.paste() })) {
            vault.transferStopped = true
            val vm = waitingFor(0)
            vault.waitingTransfer = null
            go(vm)
            advanceTimeBy(100)
            assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
            vm.again()
            assertTrue("Back works after", vm.back())
            assertEquals("Back went to the first step", TransferInStep.INTRO, vm.uiState.value.step)
        }
    }

    /**
     * (The #446 pair, L2) The waiting transfer's own code, past its exp (recognised by the repository), goes back to its
     * move, with no new connection.
     */
    @Test
    fun theSameExpiredCodeGoesBackToItsMove() = runTest {
        val expiredOwn = String(
            InviteQr(
                InviteKind.APP, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 },
                ByteArray(32) { 2 }, start.minusSeconds(60).epochSecond,
            ).marshal(),
        )
        vault.openTransferCode = expiredOwn
        val vm = waitingFor(620)
        pasteBeforeTheReadBack(vm, expiredOwn)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertFalse("transferIn" in vault.calls)
        assertFalse("abandonTransferIn" in vault.calls)
    }

    /** (The #446 review, L2) Cancel while connecting with the transfer still not read back: confirmed first, nothing dropped. */
    @Test
    fun cancelWhileConnectingUnreadAsksFirst() = runTest {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
        vault.transferHsResp = CompletableDeferred()
        vault.openTransferCode = other
        val vm = readBackFailedThenPasted(other)
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        vm.cancel()
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        assertFalse("nothing dropped unasked", "abandonTransferIn" in vault.calls)
    }

    /** (The #446 review, L1) From minute 10, Cancel while connecting shows the checking screen and no confirmation. */
    @Test
    fun cancelWhileConnectingFromMinuteTenOffersNoCancel() = runTest {
        vault.transferHsResp = CompletableDeferred()
        vault.openTransferCode = other
        val vm = readBackFailedThenPasted(other)
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(620)) // past minute 10
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vm.submitInput()
        advanceTimeBy(100)
        vault.waitingFailure = null
        // Every state the screen shows: a confirmation must never be offered past minute 10, not even for a moment.
        var offered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { if (it.confirmCancel) offered = true }
        }
        vm.cancel()
        advanceTimeBy(1_500)
        assertFalse("no \"Cancel the move?\" from minute 10", offered)
        val st = vm.uiState.value
        assertEquals(TransferInStep.COMPARE, st.step)
        assertTrue(st.settling)
        assertFalse(st.confirmCancel)
        assertFalse("abandonTransferIn" in vault.calls)
    }
}
