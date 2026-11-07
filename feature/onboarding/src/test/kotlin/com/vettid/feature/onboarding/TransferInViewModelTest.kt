package com.vettid.feature.onboarding

import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TransferInViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.VAULT_ELSEWHERE))

    private fun qr(kind: InviteKind = InviteKind.APP, exp: Instant = Instant.now().plusSeconds(600)) = String(
        InviteQr(kind, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 }, exp.epochSecond)
            .marshal(),
    )

    @Test
    fun scanCompareApproveDone() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        val code = qr()
        vm.scanned(code)
        advanceTimeBy(100)
        assertEquals(code, vault.lastTransferCode)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        assertTrue(vm.uiState.value.secondsLeft in 590..600)
        assertFalse("not settling while the old phone can approve", vm.uiState.value.settling)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(1500)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        assertNull(vm.uiState.value.sas)
        assertTrue(vm.uiState.value.canUnlockLater)
        assertEquals(AppPhase.Setup(SetupStage.FINISHING), vault.phase.value)
        vm.finish()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
    }

    /** VAULT-MESSAGING 0.17.0 §6.7.1: a vault release before 0.17.0 gives no user_guid; the transfer completes with a warning. */
    @Test
    fun aTransferWithoutUserGuidCompletesAndWarns() = runTest {
        vault.transferGaveUserGuid = false
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(100)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(1500)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        assertFalse(vm.uiState.value.canUnlockLater)
        assertEquals(AppPhase.Setup(SetupStage.FINISHING), vault.phase.value)
    }

    @Test
    fun rejectedOnTheOldPhone() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.REJECTED, "rejected"))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.REJECTED, vm.uiState.value.step)
        vm.again()
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    private fun TestScope.pastDeadlineVm(): TransferInViewModel {
        val start = Instant.now()
        // Started 12 minutes ago: past the 11-minute deadline (the start published the waiting phase).
        vault.waitingTransfer = com.vettid.core.data.vault.TransferInWaiting("042817", start.minusSeconds(720))
        return TransferInViewModel(vault, vault).also {
            it.clock = object : Clock() {
                override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this
            }
        }
    }

    /**
     * (review) A start past the deadline shows the code with the countdown at 0 while the repository's settlement window
     * runs (about 90 s, in its wait), and the late approval then completes the move.
     */
    @Test
    fun aRestartPastTheDeadlineShowsCompareAtZeroThenDone() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        assertEquals(0L, vm.uiState.value.secondsLeft)
        assertTrue("the countdown is over: the screen hides the code and says it is checking", vm.uiState.value.settling)
        assertTrue("the settlement is the repository's wait", "awaitTransferIn" in vault.calls)
        advanceTimeBy(60_000) // still settling: nothing decided by the screen
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(1500)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    /** (VAULT-MESSAGING 0.85.2) No cancel, Back or new code is acted on while the settlement window runs. */
    @Test
    fun nothingCanBeAbandonedOrReStartedDuringTheWindow() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.settling)
        vm.cancel()
        vm.confirmCancel()
        assertTrue("Back is consumed: the screen stays and nothing is abandoned", vm.back())
        vm.again()
        vm.submitInput()
        advanceTimeBy(1_000)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertFalse(vm.uiState.value.confirmCancel)
        assertFalse("nothing was abandoned", "abandonTransferIn" in vault.calls)
        assertFalse("no new code was sent", "transferIn" in vault.calls)
    }

    /** (follow-up to #302) Back while settling is consumed, abandons nothing and keeps the step. */
    @Test
    fun backIsConsumedWhileSettling() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.settling)
        assertTrue(vm.back())
        advanceTimeBy(1_000)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertTrue(vm.uiState.value.settling)
        assertFalse("nothing was abandoned", "abandonTransferIn" in vault.calls)
    }

    /** (follow-up to #302) Once the settlement ends as TIMED_OUT or SESSION_ENDED, Back leaves the screen again. */
    @Test
    fun backLeavesAgainAfterATimedOutSettlement() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        assertTrue(vm.back())
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.NO_RESPONSE, MoveRepository.CODE_TRANSFER_DROPPED))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.TIMED_OUT, vm.uiState.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun backLeavesAgainAfterAnEndedSession() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        assertTrue(vm.back())
        vault.transferApproval.completeExceptionally(
            FakeVault.failure(FailureKind.RETRY, MoveRepository.CODE_TRANSFER_SESSION_ENDED),
        )
        advanceTimeBy(1500)
        assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
        assertFalse(vm.back())
    }

    /** (follow-up to #302) An open cancel dialog is closed when the countdown reaches zero: its confirm would do nothing. */
    @Test
    fun theCancelDialogClosesWhenTheWindowStarts() = runTest {
        val start = Instant.now()
        vault.waitingTransfer = com.vettid.core.data.vault.TransferInWaiting("042817", start.minusSeconds(540))
        val vm = TransferInViewModel(vault, vault)
        vm.clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        vm.paste()
        vm.setInput(qr())
        vm.submitInput()
        advanceTimeBy(100)
        vm.cancel()
        assertTrue(vm.uiState.value.confirmCancel)
        advanceTimeBy(62_000)
        assertTrue(vm.uiState.value.settling)
        assertFalse("the dialog was closed", vm.uiState.value.confirmCancel)
        vm.confirmCancel()
        assertFalse("nothing was abandoned", "abandonTransferIn" in vault.calls)
    }

    /**
     * (the recheck of #302) A session that ended is neither "dropped" nor "finishing": its own step, with a new code or a
     * recovery; the finishing retry is not offered.
     */
    @Test
    fun anEndedSessionHasItsOwnStepAndNoFinishingRetry() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(
            FakeVault.failure(FailureKind.RETRY, MoveRepository.CODE_TRANSFER_SESSION_ENDED),
        )
        advanceTimeBy(1500)
        assertEquals(TransferInStep.SESSION_ENDED, vm.uiState.value.step)
        assertNull(vm.uiState.value.sas)
        assertFalse(vm.uiState.value.settling)
        vm.retryFinish()
        advanceTimeBy(1500)
        assertEquals("no finishing retry from here", TransferInStep.SESSION_ENDED, vm.uiState.value.step)
        assertEquals("no second wait", 1, vault.calls.count { it == "awaitTransferIn" })
        vm.again()
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /** Any other RETRY (a failed take-over) is still the finishing step. */
    @Test
    fun anotherRetryFailureIsStillTheFinishingStep() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.RETRY, "enroll_not_made"))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.FINISHING_FAILED, vm.uiState.value.step)
    }

    /** (recheck of 278b0b0a) The countdown, and so `settling`, is from the stored start the repository judges by. */
    @Test
    fun theCountdownIsFromTheStoredStartNotFromBeforeTheClaim() = runTest {
        val start = Instant.now()
        vault.waitingTransfer = com.vettid.core.data.vault.TransferInWaiting("042817", start.minusSeconds(540))
        val vm = TransferInViewModel(vault, vault)
        vm.clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        vm.paste()
        vm.setInput(qr())
        vm.submitInput()
        advanceTimeBy(100)
        assertTrue("one minute left of the vault's ten: ${vm.uiState.value.secondsLeft}", vm.uiState.value.secondsLeft in 55..60)
        advanceTimeBy(62_000)
        assertTrue(vm.uiState.value.settling)
    }

    @Test
    fun aRestartPastTheDeadlineEndsTimedOutWhenTheSettlementDrops() = runTest {
        val vm = pastDeadlineVm()
        advanceTimeBy(100)
        assertEquals(0L, vm.uiState.value.secondsLeft)
        advanceTimeBy(90_000)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.NO_RESPONSE, MoveRepository.CODE_TRANSFER_DROPPED))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.TIMED_OUT, vm.uiState.value.step)
    }

    /**
     * (A6 audit N1; VAULT-MESSAGING 0.56.2 §11.14.10 rule 5) The repository's wait ends at the one deadline (after its
     * collect) and drops the handshake: the screen says so and offers a new code; nothing here extends the wait.
     */
    @Test
    fun timesOutAfterTheWindow() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.paste()
        vm.setInput(qr())
        vm.submitInput()
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.NO_RESPONSE, MoveRepository.CODE_TRANSFER_DROPPED))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.TIMED_OUT, vm.uiState.value.step)
        assertNull("the dropped code is not shown", vm.uiState.value.sas)
        assertEquals("one wait, never a second", 1, vault.calls.count { it == "awaitTransferIn" })
        assertFalse("no cancel to confirm: it was dropped", vm.back())
        vm.again()
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /** (A6 audit N1) The take-over after the approval failed: "Finishing the move" and Try again, never the code again. */
    @Test
    fun aFailedTakeOverIsTriedAgain() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.NETWORK))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.FINISHING_FAILED, vm.uiState.value.step)
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        vault.transferApproval = CompletableDeferred(Unit)
        vm.retryFinish()
        advanceTimeBy(1500)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        assertFalse("abandonTransferIn" in vault.calls)
    }

    /**
     * (A6 audit N1) A restart (the process killed, or the screen left) while the transfer waits for the old phone: the
     * screen shows the same code and waits again, without a new hs.init; the approval completes the move.
     */
    @Test
    fun aRestartWhileWaitingShowsTheCodeAndWaitsAgain() = runTest {
        val start = Instant.now()
        vault.waitingTransfer = com.vettid.core.data.vault.TransferInWaiting("042817", start.minusSeconds(120))
        val vm = TransferInViewModel(vault, vault)
        vm.clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        assertTrue("the window runs from the hs.init", vm.uiState.value.secondsLeft in 470..480)
        assertFalse("no new hs.init", "transferIn" in vault.calls)
        assertTrue("awaitTransferIn" in vault.calls)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(1500)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    @Test
    fun otherCodesAreRefusedBeforeAnythingIsSent() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr(InviteKind.CONNECTION))
        assertEquals(FailureKind.INVITE_INVALID, vm.uiState.value.inputProblem)
        vm.scanned("hello")
        assertEquals(FailureKind.INVITE_INVALID, vm.uiState.value.inputProblem)
        vm.scanned(qr(exp = Instant.now().minusSeconds(1)))
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.inputProblem)
        assertFalse("transferIn" in vault.calls)
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /** (A6 audit N1) Once the code is shown, Back asks first: the old phone may already have approved the move. */
    @Test
    fun cancellingDropsTheHandshake() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(100)
        assertTrue(vm.back())
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.confirmCancel)
        assertFalse("not without the confirmation", "abandonTransferIn" in vault.calls)
        vm.dismissCancel()
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertTrue(vm.back())
        vm.confirmCancel()
        advanceTimeBy(100)
        assertTrue("abandonTransferIn" in vault.calls)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertFalse(vm.back())
    }

    /**
     * (0.88.0, §6.7.1 step 2) Cancel or Back while CONNECTING with no hs.resp answered: dropped through the unanswered-only
     * path, back to the start; never the member's informed cancel (`abandonTransferIn`), which drops whatever was answered.
     */
    @Test
    fun aCancelWhileConnectingDropsOnlyAnUnansweredTransfer() = runTest {
        vault.transferHsResp = CompletableDeferred()
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(1_000)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        assertTrue(vm.back())
        advanceTimeBy(100)
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertTrue("abandonTransferInIfUnanswered" in vault.calls)
        assertFalse("abandonTransferIn" in vault.calls)
        // A late hs.resp changes nothing: the wait was cancelled.
        vault.transferHsResp!!.complete("042817")
        advanceUntilIdle()
        assertEquals(TransferInStep.INTRO, vm.uiState.value.step)
        assertFalse("awaitTransferIn" in vault.calls)
    }

    /**
     * (0.88.0, §6.7.1 step 2; review F1) The cancel meets an hs.resp being answered: its hs.fin went out, so the old phone
     * shows the code. The transfer is kept; the screen shows the code with the cancel's confirmation open, as a cancel in
     * COMPARE does. Keeping the code waits for the approval; confirming is the member's informed cancel.
     */
    @Test
    fun aCancelWhileConnectingThatMeetsAnAnswerShowsTheCodeAndAsks() = runTest {
        vault.transferHsResp = CompletableDeferred()
        vault.answeredMeanwhile = "042817"
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(1_000)
        vm.cancel()
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        assertTrue("asked, as in COMPARE", vm.uiState.value.confirmCancel)
        assertFalse("abandonTransferIn" in vault.calls)
        assertTrue("the wait goes on", "awaitTransferIn" in vault.calls)
        vm.dismissCancel()
        vault.transferApproval.complete(Unit)
        advanceTimeBy(1_000)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
        // The other answer: confirmed, the member's informed cancel.
        val v2 = FakeVault(AppPhase.Setup(SetupStage.VAULT_ELSEWHERE)).apply { transferHsResp = CompletableDeferred(); answeredMeanwhile = "042817" }
        val vm2 = TransferInViewModel(v2, v2)
        vm2.scan()
        vm2.scanned(qr())
        advanceTimeBy(1_000)
        vm2.cancel()
        advanceTimeBy(100)
        vm2.confirmCancel()
        advanceTimeBy(100)
        assertTrue("abandonTransferIn" in v2.calls)
        assertEquals(TransferInStep.INTRO, vm2.uiState.value.step)
    }

    /** (review F1) Source guard: CONNECTING's cancel goes through the unanswered-only drop, never `abandon()`. */
    @Test
    fun theConnectingCancelUsesTheUnansweredOnlyDrop() {
        val src = java.io.File("src/main/kotlin/com/vettid/feature/onboarding/TransferInViewModel.kt").readLines().map { it.trim() }
        assertTrue(src.any { it == "TransferInStep.CONNECTING -> cancelConnecting()" })
        val i = src.indexOfFirst { it == "private fun cancelConnecting() {" }
        assertTrue(i >= 0)
        val end = (i + 1 until src.size).first { src[it].startsWith("private ") || src[it].startsWith("override ") || src[it].startsWith("/**") }
        val body = src.subList(i, end)
        assertTrue(body.any { it.contains("before?.cancelAndJoin()") })
        assertTrue(body.any { it.contains("abandonUnanswered()") })
        assertFalse(body.any { it.contains("abandon()") || it.contains("abandonTransferIn()") || it.contains("drop()") })
    }

    /** §6.7.1 step 2 (0.10.6): a dropped hs.init is never answered; the new phone stops after 60 s. */
    @Test
    fun noHsRespWithin60SecondsSaysWhyAndOffersANewCode() = runTest {
        vault.transferHsResp = CompletableDeferred()
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(59_000)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        assertFalse("abandonTransferInIfUnanswered" in vault.calls)
        advanceTimeBy(1_100)
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
        assertFalse(vm.uiState.value.busy)
        advanceUntilIdle()
        // (0.88.0) Dropped only if unanswered, never by the member's cancel path.
        assertTrue("abandonTransferInIfUnanswered" in vault.calls)
        assertFalse("abandonTransferIn" in vault.calls)
        assertFalse("awaitTransferIn" in vault.calls)
        // A late hs.resp changes nothing: the wait was cancelled.
        vault.transferHsResp!!.complete("042817")
        advanceUntilIdle()
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
        vm.again()
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
    }

    /**
     * (0.88.0, §6.7.1 step 2) The screen's 60 s run out while an hs.resp is being answered: the repository finds its hs.fin
     * sent (a SAS) and keeps the transfer, so the screen shows that code and waits for the old phone, never "No answer".
     */
    @Test
    fun anHsRespAnsweredAsThe60SecondsRunOutIsKeptAndShown() = runTest {
        vault.transferHsResp = CompletableDeferred()
        vault.answeredMeanwhile = "042817"
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(60_100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.sas)
        assertTrue("abandonTransferInIfUnanswered" in vault.calls)
        assertFalse("abandonTransferIn" in vault.calls)
        advanceTimeBy(1_000)
        assertTrue("awaitTransferIn" in vault.calls)
        vault.transferApproval.complete(Unit)
        advanceTimeBy(1_000)
        assertEquals(TransferInStep.DONE, vm.uiState.value.step)
    }

    @Test
    fun theRepositorysOwnHsRespTimeoutSaysTheSame() = runTest {
        vault.fail["transferIn"] = FakeVault.failure(FailureKind.NO_RESPONSE, MoveRepository.CODE_HS_UNANSWERED)
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceUntilIdle()
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
    }

    @Test
    fun anHsRespWithin60SecondsShowsTheCode() = runTest {
        vault.transferHsResp = CompletableDeferred()
        val vm = TransferInViewModel(vault, vault)
        // The approval countdown is still running when this test ends, and `runTest` drains it: its clock follows the
        // test's virtual time, or it would spin through virtual seconds until 10 real minutes have passed.
        val start = Instant.now()
        vm.clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(45_000)
        vault.transferHsResp!!.complete("042817")
        advanceTimeBy(100)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
        advanceTimeBy(30_000)
        assertEquals(TransferInStep.COMPARE, vm.uiState.value.step)
    }

    @Test
    fun aFailureToConnectReturnsToTheCode() = runTest {
        vault.fail["transferIn"] = FakeVault.failure(FailureKind.NO_RESPONSE)
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceUntilIdle()
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.error)
    }
}
