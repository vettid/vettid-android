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
import java.time.Instant

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
        assertEquals(AppPhase.Setup(SetupStage.FINISHING), vault.phase.value)
        vm.finish()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
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

    @Test
    fun timesOutAfterTheWindow() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.paste()
        vm.setInput(qr())
        vm.submitInput()
        advanceTimeBy(100)
        vault.transferApproval.completeExceptionally(FakeVault.failure(FailureKind.NO_RESPONSE))
        advanceTimeBy(1500)
        assertEquals(TransferInStep.TIMED_OUT, vm.uiState.value.step)
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

    @Test
    fun cancellingDropsTheHandshake() = runTest {
        val vm = TransferInViewModel(vault, vault)
        vm.scan()
        vm.scanned(qr())
        advanceTimeBy(100)
        assertTrue(vm.back())
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
