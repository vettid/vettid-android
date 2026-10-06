package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckOutcome
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import com.vettid.core.testing.FakeVault
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
import java.time.Duration
import java.time.Instant

/** The owner-check screen (VAULT-MESSAGING §3.6.5, §3.6.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerCheckViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked).apply {
        ownerCheck.value = OwnerCheckView(
            OwnerCheckState.HELD, Instant.now().minusSeconds(600), 86_400, failures = 1, hold = true, holdOffUntil = null,
            waiting = WaitingCounts(3, 1, 0, 2),
        )
    }

    private fun vm(mode: OwnerCheckMode = OwnerCheckMode.GATED) = OwnerCheckViewModel(vault, vault).also { it.start(mode) }

    @Test
    fun bothEntriesTogetherThenForgotten() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(WaitingCounts(3, 1, 0, 2), vm.uiState.value.view?.waiting)
        vm.setPin("97531")
        vm.setPassword("pw")
        assertFalse(vm.uiState.value.submitAllowed) // a PIN is 6–32 digits
        vm.setPin("975310")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("check"), vault.calls.filter { it == "check" })
        assertEquals("975310", vault.lastPin)
        assertEquals("pw", vault.lastPassword)
        assertNull(vault.lastHoldOff)
        val s = vm.uiState.value
        assertTrue(s.passed)
        assertEquals("", s.pin)
        assertEquals("", s.password)
    }

    @Test
    fun wrongEntriesSayWhichAndHowManyAreLeft() = runTest {
        vault.checkResults += OwnerCheckOutcome.BadPin(8)
        vault.checkResults += OwnerCheckOutcome.BadPassword(7)
        val vm = vm()
        vm.setPin("111111")
        vm.setPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertEquals(OwnerCheckMessage.BadPin(8), vm.uiState.value.message)
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.password)
        vm.setPin("975310")
        vm.setPassword("nope")
        vm.submit()
        advanceUntilIdle()
        assertEquals(OwnerCheckMessage.BadPassword(7), vm.uiState.value.message)
        assertFalse(vm.uiState.value.passed)
    }

    @Test
    fun aBackoffRunsDown() = runTest {
        vault.checkResults += OwnerCheckOutcome.Backoff(3)
        val vm = vm()
        vm.setPin("975310")
        vm.setPassword("pw")
        vm.submit()
        advanceTimeBy(100)
        assertEquals(3, vm.uiState.value.waitSeconds)
        assertFalse(vm.uiState.value.submitAllowed)
        advanceTimeBy(3_100)
        assertEquals(0, vm.uiState.value.waitSeconds)
    }

    @Test
    fun theHoldGoesOffOnlyWithACheck() = runTest {
        val vm = vm(OwnerCheckMode.HOLD_OFF)
        vm.setHoldOff(HoldOffChoice.THREE_DAYS)
        vm.setPin("975310")
        vm.setPassword("pw")
        vm.submit()
        advanceUntilIdle()
        val until = vault.lastHoldOff!!.until!!
        val d = Duration.between(Instant.now(), until)
        assertTrue(d > Duration.ofDays(2) && d <= Duration.ofDays(3))
        assertFalse(vault.ownerCheck.value!!.hold)

        val forever = vm(OwnerCheckMode.HOLD_OFF)
        forever.setHoldOff(HoldOffChoice.UNTIL_TURNED_ON)
        forever.setPin("975310")
        forever.setPassword("pw")
        forever.submit()
        advanceUntilIdle()
        assertNull(vault.lastHoldOff!!.until)
    }

    @Test
    fun theUnlockChecksAnswerIsShownOnce() = runTest {
        vault.unlockCheckOutcome.value = OwnerCheckOutcome.BadPassword(9)
        val vm = vm()
        assertEquals(OwnerCheckMessage.BadPassword(9), vm.uiState.value.message)
        assertNull(vault.unlockCheckOutcome.value)
    }

    @Test
    fun theLockActionLocksTheVault() = runTest {
        val vm = vm()
        vm.setPin("975310")
        vm.lockVault()
        advanceUntilIdle()
        assertEquals(AppPhase.Locked, vault.phase.value)
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun aFailureIsShown() = runTest {
        vault.checkResults += OwnerCheckOutcome.Failed(FailureKind.NETWORK, null)
        val vm = vm()
        vm.setPin("975310")
        vm.setPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertEquals(OwnerCheckMessage.Failed(FailureKind.NETWORK, null), vm.uiState.value.message)
    }
}
