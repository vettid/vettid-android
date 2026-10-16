package com.vettid.feature.settings

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SignerClass
import com.vettid.core.data.vault.TransferOfferView
import com.vettid.core.data.vault.TransferPendingView
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class TransferOutViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** When each test starts, by the view model's clock; the transfer code is valid for 10 minutes from then. */
    private val start = Instant.parse("2026-10-11T12:00:00Z")
    private val vault = FakeVault(AppPhase.Unlocked).apply {
        offer = TransferOfferView("01JTRANSFER000000000000000", "{}", "link", start.plusSeconds(CODE_SECONDS))
    }
    private val pending = TransferPendingView("01JTRANSFER000000000000000", "Pixel 10 Pro", "042817")

    /**
     * The view model's clock, following the test's virtual time. Its countdown compares this clock with the deadline
     * and waits with `delay`; with the system clock the two disagree, and a countdown left running at the end of a
     * test (`runTest` drains what is scheduled) spins through virtual seconds until the real deadline has passed.
     */
    private class VirtualClock(private val start: Instant, private val scheduler: TestCoroutineScheduler) : Clock() {
        override fun instant(): Instant = start.plusMillis(scheduler.currentTime)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun TestScope.viewModel() = TransferOutViewModel(vault, vault).also { it.clock = VirtualClock(start, testScheduler) }

    @Test
    fun showCompareApproveMovesTheVault() = runTest {
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.SHOWING, vm.uiState.value.step)
        assertEquals("link", vm.uiState.value.offer?.link)
        vault.pendingTransfer.complete(pending)
        advanceTimeBy(100)
        assertEquals(TransferOutStep.COMPARE, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.pending?.sas)
        vm.codesMatch()
        assertEquals(TransferOutStep.APPROVE, vm.uiState.value.step)
        vm.setPin("975310")
        assertFalse(vm.uiState.value.canApprove)
        vm.setPassword("correct horse battery")
        assertTrue(vm.uiState.value.canApprove)
        vm.askApprove(true)
        assertTrue(vm.uiState.value.confirmApprove)
        assertFalse("transferApprove" in vault.calls)
        vm.approve()
        advanceTimeBy(100)
        assertEquals(pending.transferId, vault.lastTransferApproved)
        assertEquals("975310", vault.lastPin)
        assertEquals("correct horse battery", vault.lastPassword)
        // The vault's device.unlinked{transferred} followed: this phone erased itself (welcome screen).
        assertEquals(AppPhase.SignedOut, vault.phase.value)
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.password)
        vm.leave()
    }

    /** Release R (§11.14.10): after the approval this phone keeps its keys for up to 7 days, and the step says so. */
    @Test
    fun aReleaseRApprovalSaysThisPhoneKeepsItsKeys() = runTest {
        vault.transferConfirmed = false
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(pending)
        advanceTimeBy(100)
        vm.codesMatch()
        vm.setPin("975310")
        vm.setPassword("correct horse battery")
        vault.transferKeptSince.value = java.time.Instant.parse("2026-10-16T10:00:00Z")
        vm.approve()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.MOVED, vm.uiState.value.step)
        assertTrue(vm.uiState.value.keptKeys)
    }

    @Test
    fun anApprovalTheVaultHasNotConfirmedYetSaysTheVaultMoved() = runTest {
        vault.transferConfirmed = false
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(pending)
        advanceTimeBy(100)
        vm.codesMatch()
        vm.setPin("975310")
        vm.setPassword("correct horse battery")
        vm.approve()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.MOVED, vm.uiState.value.step)
        assertEquals(AppPhase.Unlocked, vault.phase.value) // not wiped on the {} alone
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.password)
        vm.leave()
        assertFalse("transferReject" in vault.calls) // leaving after the approval rejects nothing
    }

    @Test
    fun aMismatchRejects() = runTest {
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(pending)
        advanceTimeBy(100)
        vm.askReject(true)
        assertTrue(vm.uiState.value.confirmReject)
        vm.reject()
        advanceTimeBy(100)
        assertEquals(pending.transferId, vault.lastTransferRejected)
        assertEquals(TransferOutStep.REJECTED, vm.uiState.value.step)
        assertNull(vault.lastTransferApproved)
    }

    @Test
    fun theCodeExpiresWithoutAScan() = runTest {
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(null)
        advanceTimeBy(100)
        assertEquals(TransferOutStep.EXPIRED, vm.uiState.value.step)
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun theCountdownEndsTheCodeAtItsDeadline() = runTest {
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.SHOWING, vm.uiState.value.step)
        assertEquals(CODE_SECONDS, vm.uiState.value.secondsLeft)
        advanceTimeBy((CODE_SECONDS - 2) * 1000)
        assertEquals(TransferOutStep.SHOWING, vm.uiState.value.step)
        assertTrue(vm.uiState.value.secondsLeft in 1..2)
        advanceTimeBy(3000)
        assertEquals(TransferOutStep.EXPIRED, vm.uiState.value.step)
        assertEquals(0L, vm.uiState.value.secondsLeft)
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun aWrongPinKeepsTheTransferOpen() = runTest {
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(pending)
        advanceTimeBy(100)
        vm.codesMatch()
        vm.setPin("111111")
        vm.setPassword("pw")
        vault.fail["transferApprove"] = FakeVault.failure(FailureKind.BAD_PIN, "bad_pin", retry = 30)
        vm.approve()
        advanceTimeBy(1500)
        assertEquals(TransferOutStep.APPROVE, vm.uiState.value.step)
        assertEquals(FailureKind.BAD_PIN, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.pin)
        assertTrue(vm.uiState.value.waitSeconds in 1..30)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        vm.leave()
    }

    private suspend fun TestScope.atCompare(vm: TransferOutViewModel, p: TransferPendingView) {
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(p)
        advanceTimeBy(100)
        assertEquals(TransferOutStep.COMPARE, vm.uiState.value.step)
    }

    /**
     * VAULT-MESSAGING 0.27.0 §6.7.1, §11.7.1; ANDROID-PLAN 0.1.36 §7: a new phone whose VettID has another signer is
     * named before the PIN is asked for, and the approval carries the class the vault named.
     */
    @Test
    fun aChangeOfSignerIsConfirmedBeforeThePin() = runTest {
        vault.transferSignerRequired = "google_play"
        val vm = viewModel()
        atCompare(vm, pending.copy(signer = "google_play", signerChanged = true, currentSigner = SignerClass.VETTID))
        vm.codesMatch()
        assertEquals(TransferOutStep.SIGNER, vm.uiState.value.step)
        assertFalse(vm.uiState.value.signerConfirmed)
        // Nothing is approved from the confirmation step, whatever is typed.
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.approve()
        advanceTimeBy(100)
        assertFalse("transferApprove" in vault.calls)
        vm.confirmSigner()
        assertEquals(TransferOutStep.APPROVE, vm.uiState.value.step)
        assertTrue(vm.uiState.value.signerConfirmed)
        vm.askApprove(true)
        vm.approve()
        advanceTimeBy(100)
        assertEquals(listOf<String?>("google_play"), vault.transferSigners)
        assertEquals(vault.offer.transferId, vault.lastTransferApproved)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
    }

    /** Cancel on the confirmation rejects the transfer (after the usual confirmation), so the code cannot be used. */
    @Test
    fun theChangeOfSignerCanBeRefused() = runTest {
        val vm = viewModel()
        atCompare(vm, pending.copy(signer = "development", signerChanged = true, currentSigner = SignerClass.VETTID))
        vm.codesMatch()
        assertEquals(TransferOutStep.SIGNER, vm.uiState.value.step)
        vm.askReject(true)
        assertTrue(vm.uiState.value.confirmReject)
        vm.reject()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.REJECTED, vm.uiState.value.step)
        assertEquals(vault.offer.transferId, vault.lastTransferRejected)
        assertFalse("transferApprove" in vault.calls)
    }

    /** The same signer, or a vault from before 0.27.0: no confirmation and no `signer` in the approval. */
    @Test
    fun theSameSignerAsksNothingAndSendsNoSigner() = runTest {
        vault.transferSignerClass = "vettid"
        val vm = viewModel()
        atCompare(vm, pending.copy(signer = "vettid", signerChanged = false))
        vm.codesMatch()
        assertEquals(TransferOutStep.APPROVE, vm.uiState.value.step)
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.approve()
        advanceTimeBy(100)
        assertEquals(listOf<String?>(null), vault.transferSigners)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
    }

    @Test
    fun aPendingWithoutSignerMembersIsApprovedAsBefore() = runTest {
        val vm = viewModel()
        atCompare(vm, pending)
        vm.codesMatch()
        assertEquals(TransferOutStep.APPROVE, vm.uiState.value.step)
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.approve()
        advanceTimeBy(100)
        assertEquals(listOf<String?>(null), vault.transferSigners)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
    }

    /**
     * `signer_unconfirmed` (the vault's binding changed after its pending said "unchanged"): not a wrong PIN, no
     * wait; the confirmation is shown and the next approval names the class. The transfer stays open throughout.
     */
    @Test
    fun anUnconfirmedSignerAsksForTheConfirmationAndRetries() = runTest {
        vault.transferSignerRequired = "google_play"
        val vm = viewModel()
        atCompare(vm, pending.copy(signer = "google_play", signerChanged = false))
        vm.codesMatch()
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.approve()
        advanceTimeBy(100)
        val s = vm.uiState.value
        assertEquals(TransferOutStep.SIGNER, s.step)
        assertTrue(s.signerUnconfirmed)
        assertNull(s.error)
        assertEquals(0L, s.waitSeconds)
        assertEquals("", s.pin)
        assertEquals("", s.password)
        assertFalse(s.busy)
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertNull(vault.lastTransferRejected)
        vm.confirmSigner()
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.approve()
        advanceTimeBy(100)
        assertEquals(listOf(null, "google_play"), vault.transferSigners)
        assertEquals(AppPhase.SignedOut, vault.phase.value)
    }

    /** A vault that wants a confirmation but never named a class: there is nothing to confirm with; no loop. */
    @Test
    fun anUnconfirmedSignerWithoutAClassIsAPlainFailure() = runTest {
        vault.transferSignerRequired = "google_play"
        val vm = viewModel()
        atCompare(vm, pending)
        vm.codesMatch()
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.approve()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.APPROVE, vm.uiState.value.step)
        assertEquals(FailureKind.OTHER, vm.uiState.value.error)
        assertEquals("signer_unconfirmed", vm.uiState.value.errorCode)
        assertEquals(1, vault.transferSigners.size)
        vm.leave()
    }

    /** The approval window still runs on the confirmation step. */
    @Test
    fun theConfirmationStepExpiresWithTheTransfer() = runTest {
        val vm = viewModel()
        atCompare(vm, pending.copy(signer = "google_play", signerChanged = true))
        vm.codesMatch()
        assertEquals(TransferOutStep.SIGNER, vm.uiState.value.step)
        advanceTimeBy(601_000)
        assertEquals(TransferOutStep.EXPIRED, vm.uiState.value.step)
        assertFalse(vm.uiState.value.signerConfirmed)
    }

    @Test
    fun leavingCancelsTheOpenTransfer() = runTest {
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        vm.leave()
        advanceTimeBy(100)
        assertEquals(vault.offer.transferId, vault.lastTransferRejected)
        assertEquals(TransferOutStep.INTRO, vm.uiState.value.step)
    }

    @Test
    fun anOpenTransferIsReported() = runTest {
        vault.fail["transferCreate"] = FakeVault.failure(FailureKind.OTHER, "exists")
        val vm = viewModel()
        vm.create()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.INTRO, vm.uiState.value.step)
        assertEquals("exists", vm.uiState.value.errorCode)
    }

    private companion object {
        const val CODE_SECONDS = 600L
    }
}
