package com.vettid.feature.onboarding

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.ReleaseLogEntry
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.ReleaseSecurity
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.data.vault.UpdateProgress
import com.vettid.core.data.vault.UpdateStep
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeReleaseUpdates
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

/** The update screen and the update's own screens (owner decision 2026-10-09). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseUpdateViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked)

    private fun rel(n: Long) = ReleaseView(n, "%02d".format(n).repeat(48), "active", null, "https://vettid.org/r/$n")

    private val offer = ReleaseUpdateOffer(rel(4), rel(5))

    @Test
    fun theUpdateStartsOnlyWithTheMembersApprovalAndPin() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateViewModel(updates, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertEquals(1, updates.refreshed)
        assertTrue(updates.calls.isEmpty())
        assertFalse(vm.uiState.value.submitAllowed)
        vm.approve()
        assertTrue(updates.calls.isEmpty())
        vm.setPin("24 68")
        assertTrue(vm.uiState.value.submitAllowed)
        assertTrue(updates.calls.isEmpty())
        vm.approve()
        assertEquals(listOf("start:2468:5"), updates.calls)
        assertEquals("", vm.uiState.value.pin)
    }

    private val available = ReleaseNotes.Available(
        "vettid.org",
        ReleaseLogEntry(5, "Faster unlocks.", listOf("Unlock takes one round trip."), ReleaseSecurity.NONE, null),
        emptyList(),
    )

    @Test
    fun whatsNewLoadsForTheOfferAndNeverHoldsTheApprovalUp() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val notes = FakeReleaseNotes(available).apply { gate = CompletableDeferred() }
        val vm = ReleaseUpdateViewModel(updates, vault, notes)
        advanceUntilIdle()
        // Still loading: approving is possible all the same.
        assertNull(vm.uiState.value.notes)
        vm.setPin("2468")
        assertTrue(vm.uiState.value.submitAllowed)
        notes.gate!!.complete(available)
        advanceUntilIdle()
        assertEquals(available, vm.uiState.value.notes)
        assertEquals(listOf(5L), notes.asked)
    }

    @Test
    fun unavailableNotesStillAllowTheUpdate() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateViewModel(updates, vault, FakeReleaseNotes(ReleaseNotes.Unavailable))
        advanceUntilIdle()
        assertEquals(ReleaseNotes.Unavailable, vm.uiState.value.notes)
        vm.setPin("2468")
        vm.approve()
        assertEquals(listOf("start:2468:5"), updates.calls)
    }

    @Test
    fun aNewerOfferLoadsItsOwnNotes() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val notes = FakeReleaseNotes(available)
        val vm = ReleaseUpdateViewModel(updates, vault, notes)
        advanceUntilIdle()
        updates.offer.value = offer.copy() // the same release again: nothing new
        advanceUntilIdle()
        notes.answer = ReleaseNotes.Unavailable
        updates.offer.value = ReleaseUpdateOffer(rel(4), rel(6))
        advanceUntilIdle()
        assertEquals(listOf(5L, 6L), notes.asked)
        assertEquals(ReleaseNotes.Unavailable, vm.uiState.value.notes)
    }

    @Test
    fun theOwnerCheckComesFirst() = runTest {
        val updates = FakeReleaseUpdates(offer)
        vault.ownerCheck.value =
            OwnerCheckView(OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null)
        val vm = ReleaseUpdateViewModel(updates, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkDue)
        vm.setPin("2468")
        vm.approve()
        assertTrue(updates.calls.isEmpty())
    }

    @Test
    fun noOfferNothingToApprove() = runTest {
        val updates = FakeReleaseUpdates(null)
        val vm = ReleaseUpdateViewModel(updates, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        vm.setPin("2468")
        vm.approve()
        assertTrue(updates.calls.isEmpty())
    }

    @Test
    fun aWrongPinWaitsOutTheBackoffBeforeTryingAgain() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateFlowViewModel(updates)
        updates.progress.value = UpdateProgress(UpdateStep.BAD_PIN, rel(4), rel(5), failure = FailureKind.BACKOFF, waitSeconds = 3)
        advanceTimeBy(100)
        assertEquals(3L, vm.uiState.value.waitSeconds)
        // The PIN field is closed while the backoff runs: nothing typed is taken, nothing is sent.
        vm.setPin("2468")
        assertEquals("", vm.uiState.value.pin)
        vm.retry()
        assertTrue(updates.calls.isEmpty())
        advanceTimeBy(3_100)
        assertEquals(0L, vm.uiState.value.waitSeconds)
        vm.setPin("2468")
        vm.retry()
        assertEquals(listOf("retry:2468"), updates.calls)
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun returningToTheOldReleaseAsksFirst() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateFlowViewModel(updates)
        updates.progress.value =
            UpdateProgress(UpdateStep.NEW_RELEASE_FAILED, rel(4), rel(5), failure = FailureKind.NO_RESPONSE, canAbandon = true)
        advanceUntilIdle()
        vm.setPin("2468")
        vm.askAbandon()
        assertTrue(vm.uiState.value.abandonConfirm)
        assertTrue(updates.calls.isEmpty())
        vm.confirmAbandon()
        assertEquals(listOf("abandon:2468"), updates.calls)
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun finishingDropsThePin() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateFlowViewModel(updates)
        updates.progress.value = UpdateProgress(UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.NETWORK)
        advanceUntilIdle()
        vm.setPin("2468")
        vm.finish()
        assertEquals("", vm.uiState.value.pin)
        assertEquals(listOf("finish"), updates.calls)
    }

    // --- (VAULT-MESSAGING §11.14.9 step 2; chosen) which PIN the vault expects, read when the keypad opens ---

    /** Read when the PIN field becomes usable: at once when it is, else when the hold lifts; not at "Approve". */
    @Test
    fun thePinsGenerationIsReadWhenTheKeypadOpens() = runTest {
        val first = FakeReleaseUpdates(offer).apply { pinGeneration = 3 }
        val open = ReleaseUpdateViewModel(first, vault, FakeReleaseNotes())
        advanceUntilIdle()
        first.pinGeneration = 4
        open.setPin("2468")
        open.approve()
        assertEquals(listOf<Int?>(3), first.generations)

        val updates = FakeReleaseUpdates(offer).apply { pinGeneration = 3 }
        updates.changeHold.value = FailureKind.CHANGE_CHECKING
        val held = ReleaseUpdateViewModel(updates, vault, FakeReleaseNotes())
        advanceUntilIdle()
        assertFalse(held.uiState.value.pinAllowed)
        updates.pinGeneration = 4
        updates.changeHold.value = null
        advanceUntilIdle()
        assertTrue(held.uiState.value.pinAllowed)
        updates.pinGeneration = 5
        held.setPin("1357")
        held.approve()
        assertEquals(listOf("start:1357:5"), updates.calls)
        assertEquals(listOf<Int?>(4), updates.generations)
    }

    /** Digits typed before a hold go with it: the field closes and empties, and the PIN is typed again after it. */
    @Test
    fun aHoldEmptiesTheKeypad() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateViewModel(updates, vault, FakeReleaseNotes())
        advanceUntilIdle()
        vm.setPin("2468")
        updates.changeHold.value = FailureKind.CHANGE_CHECKING
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.pin)
        updates.changeHold.value = null
        advanceUntilIdle()
        vm.approve()
        assertTrue(updates.calls.isEmpty())
    }

    /** The update's own keypad: read when a step waits for the PIN, for "Try again" and for "Return to release N". */
    @Test
    fun theUpdatesKeypadIsReadWhenAStepWaitsForThePin() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateFlowViewModel(updates)
        updates.progress.value = UpdateProgress(UpdateStep.APPROVING, rel(4), rel(5))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pinAllowed)
        updates.pinGeneration = 2
        updates.progress.value =
            UpdateProgress(UpdateStep.NEW_RELEASE_FAILED, rel(4), rel(5), failure = FailureKind.NO_RESPONSE, canAbandon = true)
        advanceUntilIdle()
        updates.pinGeneration = 3
        vm.setPin("2468")
        vm.retry()
        // Read again as the keypad empties (it stays open: the fake's step does not move).
        updates.pinGeneration = 4
        vm.setPin("2468")
        vm.askAbandon()
        vm.confirmAbandon()
        assertEquals(listOf("retry:2468", "abandon:2468"), updates.calls)
        assertEquals(listOf<Int?>(2, 3), updates.generations)
    }

    /** A reading is used once: two PINs handed over on the same open keypad each go with their own reading. */
    @Test
    fun aReadingIsUsedOnce() = runTest {
        val updates = FakeReleaseUpdates(offer)
        val vm = ReleaseUpdateFlowViewModel(updates)
        updates.progress.value = UpdateProgress(UpdateStep.FAILED, rel(4), rel(5), failure = FailureKind.NETWORK)
        advanceUntilIdle()
        updates.pinGeneration = 1
        vm.setPin("2468")
        vm.retry()
        vm.setPin("2468")
        vm.retry()
        assertEquals(listOf<Int?>(0, 1), updates.generations)
    }
}
