package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * VAULT-MESSAGING 0.45.0 §11.14.9 step 2: "Finish the move" locks and unlocks with a PIN typed on its own prompt, so it
 * is held while a PIN or password change is unresolved or the PIN uncertain: the gate's text, and nothing is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishMoveHoldTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    /** After `no_user_guid` the PIN field takes nothing and the move is not tried again: no PIN can open the vault here. */
    @Test
    fun noSecondTryWithoutAUsableUserGuid() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        assertFalse(vm.uiState.value.pinAllowed)
        assertEquals(1, vault.finishMoveTaken.size)
        vm.setPin("40281795")
        assertEquals("", vm.uiState.value.pin)
        assertTrue("still said", vm.uiState.value.noUserGuid)
        vm.finish()
        advanceUntilIdle()
        assertEquals("not tried again", 1, vault.finishMoveTaken.size)
    }

    /**
     * The open app's view model outlives a move: a phone that had no user_guid when it was made and stores one with the move
     * is not told it cannot open its vault when the dialog opens; a refusal of the move's own unlock stays.
     */
    @Test
    fun theUserGuidIsReadEachTimeTheMoveOpens() = runTest {
        vault.userGuidUsable = false
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        vault.userGuidUsable = true // the move stored one
        vm.opened()
        assertFalse(vm.uiState.value.noUserGuid)
        assertNull(vm.uiState.value.failure)
        assertTrue(vm.uiState.value.pinAllowed)
        // A refusal of the move's own unlock is kept through later openings.
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        vm.opened()
        assertTrue(vm.uiState.value.noUserGuid)
    }

    /** Known at once from the user_guid check: the words, no PIN, nothing sent (as the unlock screen). */
    @Test
    fun noUserGuidIsKnownWhenTheMoveOpens() = runTest {
        vault.userGuidUsable = false
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        assertFalse(vm.uiState.value.pinAllowed)
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertTrue(vault.finishMoveTaken.isEmpty())
        assertFalse(vault.calls.any { it == "lock" || it == "unlock" })
    }

    @Test
    fun finishTheMoveIsHeldWhileAChangeIsUnresolved() = runTest {
        vault.changeHold.value = FailureKind.CHANGE_UNDECIDED
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertEquals(FailureKind.CHANGE_UNDECIDED, vm.uiState.value.held)
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertFalse("nothing sent", vault.calls.any { it == "lock" || it == "unlock" || it == "finishMove" })
        vault.changeHold.value = null
        advanceUntilIdle()
        assertNull(vm.uiState.value.held)
    }

    /**
     * (§11.14.9 step 2; chosen) Which PIN the vault expects is read when the PIN field becomes usable: a change sent or
     * decided while the member types sends nothing ("earlier request" text), the field empties, and the PIN typed again
     * goes with a fresh reading.
     */
    @Test
    fun aPinTakenBeforeAChangeSendsNothingAndIsAskedAgain() = runTest {
        vault.pinGeneration = 5
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinAllowed)
        vm.setPin("40281795")
        vault.pinGeneration = 6 // a PIN change began while the member typed
        vm.finish()
        advanceUntilIdle()
        assertEquals(FailureKind.CREDENTIAL_UNSETTLED, vm.uiState.value.failure)
        assertEquals("", vm.uiState.value.pin)
        assertFalse("nothing sent", vault.calls.any { it == "lock" || it == "unlock" })
        vm.setPin("13579246")
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf<Int?>(5, 6), vault.finishMoveTaken)
        assertTrue(vm.uiState.value.done)
        assertEquals(listOf("lock", "unlock"), vault.calls.filter { it == "lock" || it == "unlock" })
    }

    /** Digits typed before a hold go with it: the field empties, and the reading is taken again once the hold lifts. */
    @Test
    fun aHoldEmptiesThePinAndTheReadingIsTakenAfterIt() = runTest {
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vault.changeHold.value = FailureKind.CHANGE_CHECKING
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.pin)
        vault.pinGeneration = 9 // the change decided
        vault.changeHold.value = null
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf<Int?>(9), vault.finishMoveTaken)
    }

    /**
     * A phone with no usable user_guid can never finish the move: the unlock screen's recover-or-erase text, not "not yet",
     * and it stays (typing does not clear it: no PIN can change it).
     */
    @Test
    fun aPhoneWithoutAUserGuidIsToldItCannotOpenTheVault() = runTest {
        vault.unlockResults += UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID)
        val vm = FinishMoveViewModel(vault)
        advanceUntilIdle()
        vm.setPin("40281795")
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noUserGuid)
        assertEquals(FailureKind.NOT_SUPPORTED, vm.uiState.value.failure)
        vm.setPin("4")
        assertTrue(vm.uiState.value.noUserGuid)
        assertEquals(FailureKind.NOT_SUPPORTED, vm.uiState.value.failure)
    }

    /** Only the unlock guard's own refusal is "cannot open": another code of the kind, or the code with another kind, is not. */
    @Test
    fun anotherRefusalKeepsItsOwnTextAndCanBeTriedAgain() = runTest {
        for (r in listOf(
            UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, "unsupported_type"),
            UnlockAttempt.Failed(FailureKind.OTHER, UnlockAttempt.CODE_NO_USER_GUID),
        )) {
            val v = FakeVault(AppPhase.Unlocked)
            v.unlockResults += r
            val vm = FinishMoveViewModel(v)
            advanceUntilIdle()
            vm.setPin("40281795")
            vm.finish()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.noUserGuid)
            assertEquals(r.kind, vm.uiState.value.failure)
            assertTrue("tried again", vm.uiState.value.pinAllowed)
            // Typing again clears the failure.
            vm.setPin("4")
            assertNull(vm.uiState.value.failure)
        }
    }
}
