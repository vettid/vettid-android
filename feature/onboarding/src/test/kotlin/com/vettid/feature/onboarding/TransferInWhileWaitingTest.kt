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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ANDROID-PLAN 0.1.141 "Back while the transfer waits" and 0.1.142 "A different code while the transfer waits": while a
 * transfer to this phone is live, Back keeps the move screen, a confirmed cancel leaves only once its abandon is done, and a
 * different code asks first before minute 10 and is refused from it, nothing dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferInWhileWaitingTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.TRANSFER_WAITING))
    private val start: Instant = Instant.parse("2026-11-24T12:00:00Z")

    private fun code(relay: String) = String(
        InviteQr(
            InviteKind.APP, relay, "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 },
            start.plusSeconds(3_600).epochSecond,
        ).marshal(),
    )

    private val other = code("https://relay-other.vettid.test")

    /** A transfer waiting since [agoSeconds] before the test's start, and a screen on the test's clock. */
    private fun TestScope.waitingFor(agoSeconds: Long): TransferInViewModel {
        vault.waitingTransfer = TransferInWaiting("042817", start.minusSeconds(agoSeconds))
        val vm = TransferInViewModel(vault, vault)
        vm.clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        return vm
    }

    /** Pasted at the first step, before the screen has read the transfer back (as after a restart). */
    private fun TestScope.pasteBeforeTheReadBack(vm: TransferInViewModel, text: String) {
        vm.paste()
        vm.setInput(text)
        vm.submitInput()
        advanceTimeBy(100)
    }

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
        assertTrue("transferIn" in vault.calls)
        assertFalse("abandonTransferIn" in vault.calls)
    }
}
