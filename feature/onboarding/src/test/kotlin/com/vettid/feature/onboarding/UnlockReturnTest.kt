package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveUnconfirmed
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** ANDROID-PLAN 0.1.54 point 3: "Return to release N" on the unlock screen, from the stored marker. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockReturnTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Locked)
    private val failed = MoveUnconfirmed(4, "04".repeat(48), 5, "05".repeat(48), failed = true)

    private fun vm() = UnlockViewModel(vault, vault, vault, FakeReleaseNotes())

    @Test
    fun beforeFailedTheScreenUnlocksAsUsual() = runTest {
        vault.moveUnconfirmed.value = failed.copy(failed = false)
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.returnOffered)
        vm.askReturn()
        assertFalse(vm.uiState.value.returnConfirm)
        vm.setPin("40281795")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AppPhase.Unlocked, vault.phase.value)
        assertTrue(vault.calls.none { it.startsWith("returnToPreviousRelease") })
    }

    @Test
    fun returnConfirmedWithThePinTypedSendsItOnlyToTheWayBack() = runTest {
        vault.moveUnconfirmed.value = failed
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.returnOffered)
        vm.setPin("40281795")
        vm.askReturn()
        assertTrue(vm.uiState.value.returnConfirm)
        vm.confirmReturn()
        advanceUntilIdle()
        assertEquals(listOf("returnToPreviousRelease:40281795"), vault.calls.filter { it.startsWith("return") })
        assertTrue(vault.calls.none { it == "unlock" })
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun returnConfirmedWithoutAPinAsksForItFirst() = runTest {
        vault.moveUnconfirmed.value = failed
        // Still routed to N after the retries: no ordinary PIN field, but the way back asks for its own PIN.
        vault.fail["preflight"] = FakeVault.failure(FailureKind.ROUTE_PENDING)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(FailureKind.ROUTE_PENDING, vm.uiState.value.preflightError)
        vm.askReturn()
        vm.confirmReturn()
        assertTrue(vm.uiState.value.returnAsked)
        assertFalse(vm.uiState.value.returnSubmitAllowed)
        vm.setPin("40281795")
        assertTrue(vm.uiState.value.returnSubmitAllowed)
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("returnToPreviousRelease:40281795"), vault.calls.filter { it.startsWith("return") })
        assertFalse(vm.uiState.value.returnAsked)
    }

    @Test
    fun aClearedMarkerTakesTheOfferAway() = runTest {
        vault.moveUnconfirmed.value = failed
        val vm = vm()
        advanceUntilIdle()
        vm.askReturn()
        vault.moveUnconfirmed.value = null // `vault.release`, `wrong_release`, `410`, N gone from the manifest
        advanceUntilIdle()
        assertFalse(vm.uiState.value.returnOffered)
        assertFalse(vm.uiState.value.returnConfirm)
        vm.confirmReturn()
        assertTrue(vault.calls.none { it.startsWith("returnToPreviousRelease") })
    }

    @Test
    fun dismissingSendsNothing() = runTest {
        vault.moveUnconfirmed.value = failed
        val vm = vm()
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.askReturn()
        vm.dismissReturn()
        vm.confirmReturn()
        assertTrue(vault.calls.none { it.startsWith("returnToPreviousRelease") })
    }
}
