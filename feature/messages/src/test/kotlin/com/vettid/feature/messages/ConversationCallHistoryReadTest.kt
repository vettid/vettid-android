package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistoryEntry
import com.vettid.core.data.calls.CallOutcome
import com.vettid.core.testing.FakeAttachments
import com.vettid.core.testing.FakeCalls
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** A conversation's call lines when the vault's call list does not read (the owner's "no silent failures"). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCallHistoryReadTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c2", "Alex")), emptyMap()) }

    private fun conversation(calls: FakeCalls) =
        ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social, FakeAttachments(), calls)

    /** A re-read of the calls that fails keeps the lines shown (no silent emptying); with none read yet, none are shown. */
    @Test
    fun aFailedReadKeepsTheCallsShown() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        calls.entries = listOf(CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(30)))
        calls.someNotShown = true
        val vm = conversation(calls)
        advanceUntilIdle()
        assertEquals(listOf("k1"), vm.uiState.value.calls.map { it.callId })
        calls.historyFails = true
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY // a call ended: read again, and again a moment later
        advanceUntilIdle()
        assertEquals("both failed reads keep the lines", listOf("k1"), vm.uiState.value.calls.map { it.callId })
        assertTrue("and the note with them", vm.uiState.value.callsNotShown)
        calls.historyFails = false
        calls.entries = calls.entries + CallHistoryEntry("k2", "c2", false, CallOutcome.MISSED, t.plusSeconds(60))
        calls.someNotShown = false
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertEquals(listOf("k1", "k2"), vm.uiState.value.calls.map { it.callId })
        assertFalse(vm.uiState.value.callsNotShown)
    }

    @Test
    fun aFailedFirstReadShowsNothing() = runTest {
        val calls = FakeCalls()
        calls.entries = listOf(CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, Instant.parse("2026-10-12T10:00:00Z")))
        calls.historyFails = true
        val vm = conversation(calls)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.calls.isEmpty())
        assertFalse(vm.uiState.value.callsNotShown)
    }

    /** The realistic case: the read at a call's end works, the one a moment later does not; the first read's lines stay. */
    @Test
    fun onlyTheSecondReadFailing() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        val vm = conversation(calls)
        advanceUntilIdle()
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.entries = listOf(CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(30)))
        calls.availability.value = CallAvailability.READY
        runCurrent() // the first read works
        assertEquals(listOf("k1"), vm.uiState.value.calls.map { it.callId })
        calls.historyFails = true
        advanceUntilIdle() // the read a moment later fails
        assertEquals(listOf("k1"), vm.uiState.value.calls.map { it.callId })
    }

    /** A lock after failed reads still clears the lines (nothing of the vault's history in memory while locked). */
    @Test
    fun aLockAfterFailedReadsStillClearsTheLines() = runTest {
        val calls = FakeCalls()
        calls.entries = listOf(CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, Instant.parse("2026-10-12T10:00:00Z")))
        val vm = conversation(calls)
        advanceUntilIdle()
        calls.historyFails = true
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertEquals(listOf("k1"), vm.uiState.value.calls.map { it.callId })
        calls.availability.value = CallAvailability.CLOSED // locked
        advanceUntilIdle()
        assertTrue(vm.uiState.value.calls.isEmpty())
    }

    /** (Owner-approved 2026-11-15) The note: after a failed read with lines kept, until a read works; never with none read. */
    @Test
    fun theOutOfDateNoteFollowsTheReads() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        calls.entries = listOf(CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(30)))
        val vm = conversation(calls)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsStale)
        calls.historyFails = true
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertTrue("a failed read after one that worked", vm.uiState.value.callsStale)
        assertEquals(listOf("k1"), vm.uiState.value.calls.map { it.callId })
        calls.historyFails = false
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertFalse("a read that works clears it", vm.uiState.value.callsStale)
        calls.historyFails = true
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callsStale)
        calls.availability.value = CallAvailability.CLOSED // locked
        advanceUntilIdle()
        assertFalse("a lock clears it with the lines", vm.uiState.value.callsStale)
        calls.availability.value = CallAvailability.READY // unlocked, and the first read fails: nothing was read since
        advanceUntilIdle()
        assertFalse("none read since the lock: no note", vm.uiState.value.callsStale)
        assertTrue(vm.uiState.value.calls.isEmpty())
    }

    @Test
    fun noNoteWhenNothingWasEverRead() = runTest {
        val calls = FakeCalls()
        calls.historyFails = true
        val vm = conversation(calls)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsStale)
    }

    /** Within one READY: the read at a call's end and the one a moment later, in both orders. */
    @Test
    fun theTwoReadsOfOneReadyInBothOrders() = runTest {
        val t = Instant.parse("2026-10-12T10:00:00Z")
        val calls = FakeCalls()
        calls.entries = listOf(CallHistoryEntry("k1", "c2", true, CallOutcome.TALKED, t, Duration.ofSeconds(30)))
        val vm = conversation(calls)
        advanceUntilIdle()
        // The first fails, the second works: the note comes, then goes.
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.historyFails = true
        calls.availability.value = CallAvailability.READY
        runCurrent()
        assertTrue(vm.uiState.value.callsStale)
        calls.historyFails = false
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsStale)
        // The first works, the second fails: the note comes, with the first read's lines.
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.entries = calls.entries + CallHistoryEntry("k2", "c2", false, CallOutcome.MISSED, t.plusSeconds(60))
        calls.availability.value = CallAvailability.READY
        runCurrent()
        assertFalse(vm.uiState.value.callsStale)
        calls.historyFails = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callsStale)
        assertEquals(listOf("k1", "k2"), vm.uiState.value.calls.map { it.callId })
        // During a call it stays, with the lines.
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callsStale)
        // A lock clears it with the lines.
        calls.availability.value = CallAvailability.CLOSED
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsStale)
        assertTrue(vm.uiState.value.calls.isEmpty())
    }

    /** A history that read empty keeps no call line: a failed read after it shows no note. */
    @Test
    fun noNoteWithoutACallLine() = runTest {
        val calls = FakeCalls() // no entries
        val vm = conversation(calls)
        advanceUntilIdle()
        calls.historyFails = true
        calls.availability.value = CallAvailability.IN_CALL
        advanceUntilIdle()
        calls.availability.value = CallAvailability.READY
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsStale)
    }
}
