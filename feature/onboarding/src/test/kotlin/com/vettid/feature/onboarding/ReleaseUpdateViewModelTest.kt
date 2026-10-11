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
        vm.setPin("2468")
        vm.retry()
        assertTrue(updates.calls.isEmpty())
        advanceTimeBy(3_100)
        assertEquals(0L, vm.uiState.value.waitSeconds)
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
}
