package com.vettid.feature.settings

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.TransferOfferView
import com.vettid.core.data.vault.TransferPendingView
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TransferOutViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked).apply {
        offer = TransferOfferView("01JTRANSFER000000000000000", "{}", "link", Instant.now().plusSeconds(600))
    }
    private val pending = TransferPendingView("01JTRANSFER000000000000000", "Pixel 10 Pro", "042817")

    @Test
    fun showCompareApproveMovesTheVault() = runTest {
        val vm = TransferOutViewModel(vault)
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

    @Test
    fun anApprovalTheVaultHasNotConfirmedYetSaysTheVaultMoved() = runTest {
        vault.transferConfirmed = false
        val vm = TransferOutViewModel(vault)
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
        val vm = TransferOutViewModel(vault)
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
        val vm = TransferOutViewModel(vault)
        vm.create()
        advanceTimeBy(100)
        vault.pendingTransfer.complete(null)
        advanceTimeBy(100)
        assertEquals(TransferOutStep.EXPIRED, vm.uiState.value.step)
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun aWrongPinKeepsTheTransferOpen() = runTest {
        val vm = TransferOutViewModel(vault)
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

    @Test
    fun leavingCancelsTheOpenTransfer() = runTest {
        val vm = TransferOutViewModel(vault)
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
        val vm = TransferOutViewModel(vault)
        vm.create()
        advanceTimeBy(100)
        assertEquals(TransferOutStep.INTRO, vm.uiState.value.step)
        assertEquals("exists", vm.uiState.value.errorCode)
    }
}
