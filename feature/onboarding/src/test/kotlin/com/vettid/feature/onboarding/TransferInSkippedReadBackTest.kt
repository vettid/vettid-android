package com.vettid.feature.onboarding

import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.TransferInWaiting
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #446 recheck's follow-ups (#18128): a connection's confirmation through the abandon, the wait behind a code check's
 * text on a skipped read-back, and a stopped move read while a code is checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInSkippedReadBackTest : TransferInWaitingFixture() {
    /**
     * (#18128 1) A confirmation opened while connecting (the transfer not read back) whose abandon is refused at minute 10
     * goes back to the waiting transfer, not the first step, and drops nothing.
     */
    @Test
    fun aConnectingConfirmWhoseAbandonIsRefusedGoesBackToTheWaitingTransfer() = runTest {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
        vault.transferHsResp = CompletableDeferred()
        vault.openTransferCode = other
        val vm = readBackFailedThenPasted(other)
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vm.submitInput()
        advanceTimeBy(100)
        vm.cancel()
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        vault.waitingFailure = null
        vault.abandonFailure = FakeVault.failure(FailureKind.BUSY, MoveRepository.CODE_TRANSFER_SETTLING)
        vm.confirmCancel()
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertEquals("the waiting transfer, not the first step", TransferInStep.COMPARE, st.step)
        assertEquals("042817", st.sas)
        assertTrue("abandonTransferIn" in vault.calls)
        assertNotNull("nothing dropped", vault.waitingTransfer)
    }

    /** (#18128 1) The same confirmation, with the abandon done and nothing left: the first step, as before. */
    @Test
    fun aConnectingConfirmWhoseAbandonIsDoneLeaves() = runTest {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
        vault.transferHsResp = CompletableDeferred()
        vault.openTransferCode = other
        val vm = readBackFailedThenPasted(other)
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vm.submitInput()
        advanceTimeBy(100)
        vm.cancel()
        advanceTimeBy(100)
        vault.waitingFailure = null
        vm.confirmCancel()
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertNull(vault.waitingTransfer)
    }

    /**
     * (#18128 2) A refusal from minute 10 on a skipped read-back keeps its busy text, and the waiting transfer's approval is
     * waited for meanwhile: one that lands ends the move.
     */
    @Test
    fun anApprovalAfterARefusalOnASkippedReadBackEndsTheMove() = runTest {
        val vm = waitingFor(630)
        pasteBeforeTheReadBack(vm, other)
        assertEquals(FailureKind.BUSY, vm.uiState.value.error)
        assertEquals("the text stays", TransferInStep.PASTE, vm.uiState.value.step)
        assertTrue("the approval is waited for", "awaitTransferIn" in vault.calls)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    /** (#18128 2) The same after an expired code of the wrong transfer; and Back still goes to the waiting transfer. */
    @Test
    fun anExpiredCodeOnASkippedReadBackStillWaitsAndBackGoesToTheTransfer() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther)
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.inputProblem)
        assertTrue("awaitTransferIn" in vault.calls)
        assertTrue(vm.back())
        advanceTimeBy(100)
        assertEquals("Back goes to the transfer", TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    /** (#18128 2) A second refusal starts no second wait. */
    @Test
    fun aSecondRefusalStartsNoSecondWait() = runTest {
        val vm = waitingFor(630)
        pasteBeforeTheReadBack(vm, other)
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals(1, vault.calls.count { it == "awaitTransferIn" })
    }

    /** (#18128 2) Without a skipped read-back (the transfer on screen, then left for a code), no wait behind the text. */
    @Test
    fun noWaitBehindARefusalWithoutASkippedReadBack() = runTest {
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        val vm = waitingFor(630)
        advanceTimeBy(100)
        vault.waitingFailure = null
        vm.paste()
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals(FailureKind.BUSY, vm.uiState.value.error)
        assertFalse("awaitTransferIn" in vault.calls)
        assertTrue(vm.back())
        assertEquals("Back goes to the first step", TransferInStep.INTRO, vm.uiState.value.step)
    }

    /**
     * (#18128 3) A stopped move read while a code is being checked: not shown over the check, and told once the check
     * starts nothing (an expired code), read once.
     */
    @Test
    fun aStoppedMoveReadDuringACheckIsToldWhenTheCheckStartsNothing() = runTest {
        vault.transferStopped = true
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        pasteBeforeTheReadBack(vm, expiredOther)
        assertFalse("read", vault.transferStopped)
        assertEquals("not over the check", TransferInStep.PASTE, vm.uiState.value.step)
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
        // Told once: a later check that starts nothing tells nothing.
        vault.openCodeGate = null
        vm.again()
        vm.scanned(expiredOther)
        advanceTimeBy(100)
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.inputProblem)
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /** (#18128 3) A code started meanwhile supersedes it: the move is not told over (or after) the new transfer. */
    @Test
    fun aStoppedMoveReadDuringACheckIsSupersededByAStartedCode() = runTest {
        vault.transferStopped = true
        vault.transferHsResp = CompletableDeferred()
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        pasteBeforeTheReadBack(vm, other)
        assertFalse("read", vault.transferStopped)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        vault.transferHsResp!!.completeExceptionally(FakeVault.failure(FailureKind.INVITE_INVALID, "x"))
        advanceTimeBy(100)
        assertEquals("never told after the code", TransferInStep.PASTE, vm.uiState.value.step)
        // Nor by a later check that starts nothing: the started code ended it.
        vm.setInput(expiredOther)
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.inputProblem)
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
    }

    /** (#18128 3) Nor over the ending of a code started meanwhile (not answered: nothing waits any more). */
    @Test
    fun aStoppedMoveReadAfterACodeEndedIsNotToldOverTheEnding() = runTest {
        vault.transferStopped = true
        vault.transferHsResp = CompletableDeferred()
        val read = CompletableDeferred<Unit>()
        vault.stoppedGate = read
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        advanceTimeBy(100)
        vm.paste()
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        vault.transferHsResp!!.completeExceptionally(FakeVault.failure(FailureKind.NO_RESPONSE, MoveRepository.CODE_HS_UNANSWERED))
        advanceTimeBy(100)
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
        read.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
    }

    /** (#18128 3) A stopped move whose read ends after a code started connecting is not told, then or later. */
    @Test
    fun aStoppedMoveReadAfterACodeStartedIsNotTold() = runTest {
        vault.transferStopped = true
        vault.transferHsResp = CompletableDeferred()
        val read = CompletableDeferred<Unit>()
        vault.stoppedGate = read
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        advanceTimeBy(100) // the read-back finds nothing; the stopped move's read waits
        vm.paste()
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        read.complete(Unit)
        advanceTimeBy(100)
        assertEquals("not over the connection", TransferInStep.CONNECTING, vm.uiState.value.step)
        vault.transferHsResp!!.completeExceptionally(FakeVault.failure(FailureKind.INVITE_INVALID, "x"))
        advanceTimeBy(100)
        vm.setInput(expiredOther)
        vm.submitInput()
        advanceTimeBy(100)
        assertEquals("nor after it", TransferInStep.PASTE, vm.uiState.value.step)
    }

    /** (#18128 3) A check the member left (Back to the first step) that starts nothing tells it there. */
    @Test
    fun aStoppedMoveIsToldWhenTheMemberLeftTheCheck() = runTest {
        vault.transferStopped = true
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        pasteBeforeTheReadBack(vm, expiredOther)
        assertTrue(vm.back())
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
    }

    /** (#18128 3) The same with a valid code whose check finds nothing waiting. */
    @Test
    fun aStoppedMoveIsToldWhenTheMemberLeftAValidCodesCheck() = runTest {
        vault.transferStopped = true
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        val check = CompletableDeferred<Unit>()
        vault.waitingGates.addLast(CompletableDeferred(Unit)) // the screen's first read-back
        vault.waitingGates.addLast(check) // the code's check
        pasteBeforeTheReadBack(vm, other)
        assertFalse("read", vault.transferStopped)
        assertTrue(vm.back())
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        check.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
        assertFalse("nothing started", "transferIn" in vault.calls)
    }

    /** (#18128, the recheck's mutant at the "left meanwhile" check) A valid code's check the member left starts nothing. */
    @Test
    fun aCheckLeftMeanwhileAsksNothingAndStartsNothing() = runTest {
        val vm = readBackFailedThenPasted(other) // a transfer waits, not read back
        vault.openCodeGate = CompletableDeferred()
        vm.submitInput()
        advanceTimeBy(100)
        assertTrue(vm.back())
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        vault.openCodeGate!!.complete(Unit)
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertEquals(TransferInStep.INTRO, st.step)
        assertFalse("no confirmation on the first step", st.confirmCancel)
        assertNull(st.pendingCode)
        assertFalse("transferIn" in vault.calls)
    }

    /** (#18128, the recheck's mutant at DONE) An approval while "Cancel the move?" waits on a different code closes it. */
    @Test
    fun anApprovalClosesTheConfirmationOfADifferentCode() = runTest {
        val vm = waitingFor(60)
        advanceTimeBy(100)
        vm.scan()
        vm.scanned(other)
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        assertEquals(other, vm.uiState.value.pendingCode)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertEquals(TransferInStep.DONE, st.step)
        assertFalse(st.confirmCancel)
        assertNull("the different code is not kept", st.pendingCode)
    }

    /** (#18128) An ending of the wait (here a rejection) closes an open confirmation. */
    @Test
    fun aRejectionClosesTheConfirmation() = runTest {
        val vm = waitingFor(60)
        advanceTimeBy(100)
        vm.cancel()
        assertTrue(vm.uiState.value.confirmCancel)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.REJECTED, "rejected"))
        advanceTimeBy(100)
        assertEquals(TransferInStep.REJECTED, vm.uiState.value.step)
        assertFalse(vm.uiState.value.confirmCancel)
    }

    /** (#18128) A connection that fails closes the confirmation opened while it ran. */
    @Test
    fun aFailedConnectionClosesItsConfirmation() = runTest {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
        vault.transferHsResp = CompletableDeferred()
        vault.openTransferCode = other
        val vm = readBackFailedThenPasted(other)
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vm.submitInput()
        advanceTimeBy(100)
        vm.cancel()
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        vault.transferHsResp!!.completeExceptionally(FakeVault.failure(FailureKind.OTHER, "relay"))
        advanceTimeBy(100)
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
        assertFalse(vm.uiState.value.confirmCancel)
    }

    /**
     * (#18128, the recheck's mutant in `cancelConnecting`) The connection reached its comparison while Cancel read the
     * transfer: confirmed only before minute 10 and while the countdown runs.
     */
    @Test
    fun cancelWhenTheConnectionReachedItsComparisonMeanwhile() = runTest {
        // (stored start of the comparison, start Cancel reads, a confirmation expected)
        for ((shown, read, asked) in listOf(Triple(120L, 120L, true), Triple(120L, 620L, false), Triple(620L, 120L, false))) {
            vault.calls.clear()
            vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
            vault.transferHsResp = CompletableDeferred()
            vault.openTransferCode = other
            val vm = readBackFailedThenPasted(other)
            vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
            vm.submitInput()
            advanceTimeBy(100)
            vault.waitingFailure = null
            val gate = CompletableDeferred<Unit>()
            vault.waitingGates.addLast(gate)
            vm.cancel()
            advanceTimeBy(100)
            vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(shown))
            vault.transferHsResp!!.complete("042817")
            advanceTimeBy(1_100)
            assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
            vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(read))
            gate.complete(Unit)
            advanceTimeBy(100)
            assertEquals("shown $shown, read $read", asked, vm.uiState.value.confirmCancel)
            assertFalse("abandonTransferIn" in vault.calls)
        }
    }

    /**
     * (#18128, review H1) A different code's confirmation, opened while the wait behind an expired code's text runs, is
     * closed at minute 10 by its own closer: back to the waiting transfer, whatever the wait's start (off a whole second).
     */
    @Test
    fun aConfirmationOverTheWaitBehindTheTextClosesAtMinuteTen() = runTest {
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther)
        advanceTimeBy(400) // the wait behind the text starts off a whole second
        vault.openCodeGate = null
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.inputProblem)
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        assertEquals(other, vm.uiState.value.pendingCode)
        advanceTimeBy(478_000) // just before minute 10: still open
        assertTrue("open until minute 10", vm.uiState.value.confirmCancel)
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
        assertFalse("no countdown on the paste step", vm.uiState.value.settling)
        advanceTimeBy(2_000)
        val st = vm.uiState.value
        assertEquals("the waiting transfer", TransferInStep.COMPARE, st.step)
        assertEquals("042817", st.sas)
        assertFalse(st.confirmCancel)
        assertNotNull("nothing dropped", vault.waitingTransfer)
        assertFalse("abandonTransferIn" in vault.calls)
    }

    /**
     * (#18128, review L1; the round's review, L1) A code whose check cannot read the transfer while the wait behind the text
     * runs: nothing is started over that wait (the repository would refuse it), the busy text shows at once, and the wait
     * goes on to the move's end.
     */
    @Test
    fun aCodeWhoseReadFailsOverTheWaitBehindTheTextStartsNothing() = runTest {
        vault.transferHsResp = CompletableDeferred()
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther)
        assertEquals(1, vault.calls.count { it == "awaitTransferIn" })
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x") // the check cannot read the transfer
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertEquals(TransferInStep.PASTE, st.step)
        assertEquals(FailureKind.BUSY, st.error)
        assertEquals(MoveRepository.CODE_TRANSFER_SETTLING, st.errorCode)
        assertFalse(st.busy)
        assertFalse("transferIn" in vault.calls)
        assertEquals("the same wait", 1, vault.calls.count { it == "awaitTransferIn" })
        vault.waitingFailure = null
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    /** (#18128, review L2) A held stopped move is not told once a check sees a live transfer (the read-back had failed). */
    @Test
    fun aHeldStoppedMoveIsNotToldOverALiveTransfer() = runTest {
        for (code in listOf(expiredOther, other)) {
            vault.calls.clear()
            vault.transferStopped = true
            val gate = CompletableDeferred<Unit>()
            if (code == expiredOther) {
                vault.openCodeGate = gate // its read comes after the own-code question
            } else {
                vault.waitingGates.addLast(CompletableDeferred(Unit)) // the read-back, which fails
                vault.waitingGates.addLast(gate) // the valid code's read
            }
            vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
            val vm = waitingFor(630)
            pasteBeforeTheReadBack(vm, code)
            assertFalse("read", vault.transferStopped)
            vault.waitingFailure = null
            gate.complete(Unit)
            advanceTimeBy(100)
            assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
            vault.openCodeGate = null
            vault.waitingTransfer = null // it ended: the stopped move was the earlier one's, and it is gone
            vm.setInput(expiredOther)
            vm.submitInput()
            advanceTimeBy(100)
            assertEquals("never after the live transfer", TransferInStep.PASTE, vm.uiState.value.step)
        }
    }

    /** (#18128, review L3) The open transfer's own expired code, left while checked, with nothing waiting then: told. */
    @Test
    fun aStoppedMoveIsToldWhenTheMemberLeftTheOwnCodesCheck() = runTest {
        val expiredOwn = String(
            InviteQr(
                InviteKind.APP, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 },
                ByteArray(32) { 2 }, start.minusSeconds(60).epochSecond,
            ).marshal(),
        )
        vault.openTransferCode = expiredOwn
        vault.transferStopped = true
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        pasteBeforeTheReadBack(vm, expiredOwn)
        assertTrue(vm.back())
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
    }

    /** (#18128, review N1) An ending over a refusal's text clears that text; a finishing failure keeps its own. */
    @Test
    fun anEndingClearsTheRefusalsText() = runTest {
        val vm = waitingFor(630)
        pasteBeforeTheReadBack(vm, other)
        assertEquals(FailureKind.BUSY, vm.uiState.value.error)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.NO_RESPONSE, "timeout"))
        advanceTimeBy(100)
        val st = vm.uiState.value
        assertEquals(TransferInStep.TIMED_OUT, st.step)
        assertNull(st.error)
        assertNull(st.errorCode)
    }

    /** The open transfer's own code, expired (recognised by the repository). */
    private val expiredOwn = String(
        InviteQr(
            InviteKind.APP, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 },
            ByteArray(32) { 2 }, start.minusSeconds(60).epochSecond,
        ).marshal(),
    )

    /**
     * (#452's pair, L1) A different code checked as the move lands behind the text (nothing waits any more, DONE not yet
     * written): the wait ends first, and the code starts nothing over the finished move.
     */
    @Test
    fun aCodeCheckedAsTheMoveLandsBehindTheTextStartsNothing() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther)
        assertTrue("awaitTransferIn" in vault.calls)
        vault.waitingTransfer = null // the approval landed: the repository cleared the pending transfer
        vault.transferInFailure = FakeVault.failure(FailureKind.VAULT_EXISTS, "vault_exists") // as the real one refuses
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        assertNull(vm.uiState.value.error)
        assertFalse("no new connection over the finished move", "transferIn" in vault.calls)
    }

    /** (The round's review, L1) The same with the check's read failing: a failed read counts as none, and the wait ends first. */
    @Test
    fun aCodeWhoseReadFailsAsTheMoveLandsStartsNothing() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther)
        assertTrue("awaitTransferIn" in vault.calls)
        vault.waitingTransfer = null
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vault.transferInFailure = FakeVault.failure(FailureKind.VAULT_EXISTS, "vault_exists")
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        assertNull(vm.uiState.value.error)
        assertFalse("no new connection over the finished move", "transferIn" in vault.calls)
    }

    /**
     * (The round's review, nit 2) A wait behind the text that outlives the bound: nothing is started over it; the busy text
     * shows, and its approval still ends the move.
     */
    @Test
    fun aWaitBehindThatOutlivesTheBoundStartsNothing() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther)
        vault.waitingTransfer = null
        vault.transferInFailure = FakeVault.failure(FailureKind.VAULT_EXISTS, "vault_exists")
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(5_100) // past the 5 s the code waits for it
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
        assertEquals(FailureKind.BUSY, vm.uiState.value.error)
        assertEquals(MoveRepository.CODE_TRANSFER_SETTLING, vm.uiState.value.errorCode)
        assertFalse(vm.uiState.value.busy)
        assertFalse("transferIn" in vault.calls)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    /**
     * (#452's pair, L2) A Cancel whose read was still on its way when the member confirmed the abandon does nothing: no
     * second confirmation and no wait left behind.
     */
    @Test
    fun aLateCancelReadDuringTheConfirmedAbandonDoesNothing() = runTest {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(120))
        vault.transferHsResp = CompletableDeferred()
        vault.openTransferCode = other
        val vm = readBackFailedThenPasted(other)
        vault.waitingFailure = FakeVault.failure(FailureKind.OTHER, "x")
        vm.submitInput()
        advanceTimeBy(100)
        vm.cancel() // read fails: confirmed first
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        vault.waitingFailure = null
        val lateRead = CompletableDeferred<Unit>()
        vault.waitingGates.addLast(lateRead)
        vm.cancel() // a second Cancel, its read held
        advanceTimeBy(100)
        val abandon = CompletableDeferred<Unit>()
        vault.abandonGate = abandon
        vm.confirmCancel()
        advanceTimeBy(100)
        lateRead.complete(Unit)
        advanceTimeBy(100)
        assertFalse("no second confirmation", vm.uiState.value.confirmCancel)
        abandon.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertFalse("no wait left behind", "awaitTransferIn" in vault.calls)
    }

    /** (#452's pair, L3) A stopped move whose read ends after a live transfer was seen (a confirmation open) is not told. */
    @Test
    fun aStoppedMoveReadAsALiveTransferIsSeenIsNotTold() = runTest {
        vault.transferStopped = true
        val read = CompletableDeferred<Unit>()
        vault.stoppedGate = read
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        advanceTimeBy(100) // nothing waits; the stopped move's read is held
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(60))
        vm.paste()
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        assertTrue("a different code asks first", vm.uiState.value.confirmCancel)
        read.complete(Unit)
        advanceTimeBy(100)
        assertEquals(TransferInStep.PASTE, vm.uiState.value.step)
        assertTrue("the confirmation stays", vm.uiState.value.confirmCancel)
    }

    /** (#452's pair, L3) Nor over a started code's error once that code is back on the paste step. */
    @Test
    fun aStoppedMoveReadAfterAStartedCodeFailedIsNotTold() = runTest {
        vault.transferStopped = true
        vault.transferHsResp = CompletableDeferred()
        val read = CompletableDeferred<Unit>()
        vault.stoppedGate = read
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        advanceTimeBy(100)
        vm.paste()
        vm.setInput(other)
        vm.submitInput()
        advanceTimeBy(100)
        vault.transferHsResp!!.completeExceptionally(FakeVault.failure(FailureKind.OTHER, "relay"))
        advanceTimeBy(100)
        assertEquals(FailureKind.OTHER, vm.uiState.value.error)
        read.complete(Unit)
        advanceTimeBy(100)
        assertEquals("the code's error stays", TransferInStep.PASTE, vm.uiState.value.step)
        assertEquals(FailureKind.OTHER, vm.uiState.value.error)
    }

    /**
     * (#452's pair, L4) The own code's check left meanwhile that sees the transfer: the stopped move held is superseded,
     * not told by a later check that finds nothing.
     */
    @Test
    fun aLiveTransferSeenByALeftOwnCodeCheckSupersedesTheStoppedMove() = runTest {
        vault.openTransferCode = expiredOwn
        vault.transferStopped = true
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        pasteBeforeTheReadBack(vm, expiredOwn)
        assertTrue(vm.back())
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(60))
        gate.complete(Unit)
        advanceTimeBy(100)
        vault.openCodeGate = null
        vault.waitingTransfer = null
        vm.scan()
        vm.scanned(expiredOther)
        advanceTimeBy(100)
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /** (#452's pair, nit) The own code going back to its move supersedes the stopped move held during its check. */
    @Test
    fun theOwnCodeBackToItsMoveSupersedesTheStoppedMove() = runTest {
        vault.openTransferCode = expiredOwn
        vault.transferStopped = true
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        val vm = waitingFor(0)
        vault.waitingTransfer = null
        pasteBeforeTheReadBack(vm, expiredOwn)
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(60))
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals("back to its move", TransferInStep.COMPARE, vm.uiState.value.step)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.REJECTED, "rejected"))
        advanceTimeBy(100)
        vault.openCodeGate = null
        vault.waitingTransfer = null
        vm.again()
        vm.scanned(expiredOther)
        advanceTimeBy(100)
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /** (#452's pair, L5) An expired code's check that ends on an ending (not a first step) starts no hidden wait. */
    @Test
    fun anExpiredCodeCheckEndingOnAnEndingStartsNoWait() = runTest {
        val vm = waitingFor(120)
        pasteBeforeTheReadBack(vm, expiredOther) // the wait behind the text
        val gate = CompletableDeferred<Unit>()
        vault.openCodeGate = gate
        vm.submitInput() // the same expired code again, its check held
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.OTHER, "takeover"))
        advanceTimeBy(100)
        assertEquals(TransferInStep.FINISHING_FAILED, vm.uiState.value.step)
        gate.complete(Unit)
        advanceTimeBy(100)
        assertEquals(1, vault.calls.count { it == "awaitTransferIn" })
    }
}
