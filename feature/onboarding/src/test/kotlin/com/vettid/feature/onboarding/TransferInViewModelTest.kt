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

    /** §6.7.1 step 2 (0.10.6): a dropped hs.init is never answered; the new phone stops after 60 s. */
    @Test
    fun noHsRespWithin60SecondsSaysWhyAndOffersANewCode() = runTest {
        vault.transferHsResp = CompletableDeferred()
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(59_000)
        assertEquals(TransferInStep.CONNECTING, vm.uiState.value.step)
        assertFalse("abandonTransferIn" in vault.calls)
        advanceTimeBy(1_100)
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
        assertFalse(vm.uiState.value.busy)
        advanceUntilIdle()
        assertTrue("abandonTransferIn" in vault.calls)
        assertFalse("awaitTransferIn" in vault.calls)
        // A late hs.resp changes nothing: the wait was cancelled.
        vault.transferHsResp!!.complete("042817")
        advanceUntilIdle()
        assertEquals(TransferInStep.NOT_ANSWERED, vm.uiState.value.step)
        vm.again()
        assertEquals(TransferInStep.SCAN, vm.uiState.value.step)
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
