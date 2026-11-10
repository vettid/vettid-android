package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.PairRole
import com.vettid.core.data.devices.PairingCode
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import com.vettid.core.data.devices.PendingPairing
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakePairing
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PairViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val exp = Instant.parse("2026-10-26T12:10:00Z")
    private val pairing = FakePairing()
    private val pending = PendingPairing("p1", "Claude", "123456", exp)

    private fun vm(held: Boolean = false, desktop: Boolean = false) =
        PairViewModel(SavedStateHandle(mapOf(PairRoute.HELD to held, PairRoute.DESKTOP to desktop)), pairing, FakeVault())

    @Test
    fun theButtonOpensANewCodeAndAFeedItemOpensWhatIsHeld() = runTest {
        vm()
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start"), pairing.calls)
        pairing.calls.clear()
        val held = vm(held = true)
        advanceUntilIdle()
        // Never a new code from a feed item.
        assertEquals(emptyList<String>(), pairing.calls)
        assertTrue(held.uiState.value.held)
    }

    @Test
    fun theSheetOpensOnlyOverAComparisonAndPairsWithTheChosenAccess() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.codesMatch()
        assertFalse(vm.uiState.value.sheet)
        pairing.state.value = PairingState(PairingStage.Compare(pending))
        advanceUntilIdle()
        vm.codesMatch()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sheet)
        // One hour is preselected (decision 5).
        assertEquals(SessionChoice.HOUR_1, vm.uiState.value.session)
        vm.pair()
        advanceUntilIdle()
        assertEquals("approve:3600", pairing.calls.last())
        assertEquals("bound to the comparison the sheet showed (GC #7370)", "p1", pairing.approvedId)
        vm.choose(SessionChoice.NONE)
        vm.pair()
        advanceUntilIdle()
        assertEquals("approve:null", pairing.calls.last())
    }

    @Test
    fun everyChoiceIsAWholeNumberOfSecondsTheVaultTakes() {
        assertEquals(
            listOf(null, 900, 3600, 14_400, 28_800, 86_400),
            SessionChoice.entries.map { it.seconds },
        )
        assertTrue(SessionChoice.entries.mapNotNull { it.seconds }.all { it in 60..86_400 })
    }

    @Test
    fun theSheetClosesWhenThePairingMovesOnButStaysForADailyCheck() = runTest {
        val vm = vm()
        pairing.state.value = PairingState(PairingStage.Compare(pending))
        advanceUntilIdle()
        vm.codesMatch()
        pairing.state.value = PairingState(PairingStage.Compare(pending), error = FailureKind.OWNER_CHECK_REQUIRED)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sheet)
        pairing.state.value = PairingState(PairingStage.Paired("Claude", null))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.sheet)
    }

    @Test
    fun theyDontMatchRejectsAndCancelAndLeavingLeaveTheVaultTold() = runTest {
        val vm = vm()
        pairing.state.value = PairingState(PairingStage.Compare(pending))
        advanceUntilIdle()
        vm.reject()
        advanceUntilIdle()
        assertTrue(pairing.calls.contains("reject"))
        pairing.calls.clear()
        vm.cancel()
        assertEquals(listOf("leave"), pairing.calls)
    }

    @Test
    fun aNewCodeAfterExpiryStartsAgain() = runTest {
        val vm = vm()
        pairing.state.value = PairingState(PairingStage.CodeExpired("p1", exp))
        advanceUntilIdle()
        pairing.calls.clear()
        vm.newCode()
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start"), pairing.calls)
        assertFalse(vm.uiState.value.copied)
    }

    @Test
    fun theDailyCheckPassingClearsARefusedApproval() = runTest {
        val vault = FakeVault()
        val vm = PairViewModel(SavedStateHandle(mapOf(PairRoute.HELD to true)), pairing, vault)
        advanceUntilIdle()
        pairing.state.value = PairingState(PairingStage.Compare(pending), error = FailureKind.OWNER_CHECK_REQUIRED)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pairing.error != null)
    }

    @Test
    fun aPairingTheLockTookAwayIsInterruptedNotAnEndlessSpinner() = runTest {
        val vm = vm()
        advanceUntilIdle()
        // Before anything was shown, nothing to interrupt.
        assertFalse(vm.uiState.value.interrupted)
        pairing.state.value = PairingState(PairingStage.Code(PairingCode("p1", "L", exp)))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.interrupted)
        // clear(): back to nothing, without an error and not busy.
        pairing.state.value = PairingState()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.interrupted)
        // Leaving by the member's own answer is not an interruption.
        vm.done()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.interrupted)
    }

    @Test
    fun theSheetBelongsToTheComparisonItWasOpenedFor() = runTest {
        val vm = vm()
        pairing.state.value = PairingState(PairingStage.Compare(pending))
        advanceUntilIdle()
        vm.codesMatch()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sheet)
        // Another pairing's comparison does not open with it.
        pairing.state.value = PairingState(PairingStage.Compare(PendingPairing("p2", "Other", "654321", exp)))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.sheet)
    }

    @Test
    fun aCodeNeverReachesATextForm() {
        val code = PairingCode("p", "SECRETLINKVALUE", exp)
        assertFalse(code.toString().contains("SECRETLINKVALUE"))
        assertFalse(pending.toString().contains("123456"))
    }

    @Test
    fun thePairADesktopButtonStartsADesktopPairingAndNewCodeKeepsTheRole() = runTest {
        val vm = vm(desktop = true)
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start:desktop"), pairing.calls)
        assertEquals(PairRole.DESKTOP, pairing.state.value.role)
        pairing.calls.clear()
        pairing.state.value = PairingState(PairingStage.CodeExpired("p1", exp), role = PairRole.DESKTOP)
        vm.newCode()
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start:desktop"), pairing.calls)
    }

    @Test
    fun aPairingHeldForTheOtherRoleIsReplacedByTheRepositoryInOneCall() = runTest {
        pairing.state.value = PairingState(PairingStage.Code(PairingCode("p1", "x", exp)), role = PairRole.AGENT)
        vm(desktop = true)
        advanceUntilIdle()
        // No cancel then start from the screen: the repository switches in `start`.
        assertEquals(listOf("acknowledge", "start:desktop"), pairing.calls)
    }

    @Test
    fun newCodeAfterALockKeepsTheDesktopRoleOfTheRoute() = runTest {
        val vm = vm(desktop = true)
        advanceUntilIdle()
        pairing.state.value = PairingState(PairingStage.Code(PairingCode("p1", "x", exp)), role = PairRole.DESKTOP)
        advanceUntilIdle()
        // The lock clears the repository: nothing, and the role of nothing is AGENT.
        pairing.state.value = PairingState()
        advanceUntilIdle()
        // The screen still says what it showed.
        assertEquals(PairRole.DESKTOP, vm.uiState.value.pairing.role)
        assertTrue(vm.uiState.value.interrupted)
        pairing.calls.clear()
        vm.newCode()
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start:desktop"), pairing.calls)
    }

    @Test
    fun newCodeOnAHeldScreenKeepsTheRoleItLastShowed() = runTest {
        val vm = vm(held = true)
        pairing.state.value = PairingState(PairingStage.Compare(pending), role = PairRole.DESKTOP)
        advanceUntilIdle()
        pairing.state.value = PairingState()
        advanceUntilIdle()
        pairing.calls.clear()
        vm.newCode()
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start:desktop"), pairing.calls)
        // And an agent's held pairing starts an agent's.
        val agent = vm(held = true)
        pairing.state.value = PairingState(PairingStage.Compare(pending), role = PairRole.AGENT)
        advanceUntilIdle()
        pairing.state.value = PairingState()
        advanceUntilIdle()
        pairing.calls.clear()
        agent.newCode()
        advanceUntilIdle()
        assertEquals(listOf("acknowledge", "start"), pairing.calls)
    }

    @Test
    fun aDesktopApprovalSendsTheChosenSessionOnlyAndTheMismatchRejects() = runTest {
        val vm = vm(desktop = true)
        pairing.state.value = PairingState(PairingStage.Compare(pending), role = PairRole.DESKTOP)
        advanceUntilIdle()
        vm.codesMatch()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sheet)
        vm.pair()
        advanceUntilIdle()
        // The repository's approve takes the session and nothing else: no grants exist to send.
        assertEquals("approve:3600", pairing.calls.last())
        vm.reject()
        advanceUntilIdle()
        assertEquals("reject", pairing.calls.last())
    }
}
