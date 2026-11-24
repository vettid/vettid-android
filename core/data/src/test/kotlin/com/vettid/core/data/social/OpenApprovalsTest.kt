package com.vettid.core.data.social

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What the approvals indicator and the drawer badge count (ANDROID-PLAN §4 "The approvals indicator" 1, 3, 5). */
class OpenApprovalsTest {
    private val now: Instant = Instant.parse("2026-11-24T12:00:00Z")

    private fun auth(id: String, exp: Instant?, purpose: AuthPurpose = AuthPurpose.NONE, name: String? = "Alex") =
        Approval.Authentication(id, "c-$id", null, now, exp, connectionName = name, purpose = purpose)

    private fun grant(id: String, exp: Instant?) = Approval.GrantRequest(
        requestId = id, connectionId = "c", entries = emptyList(), uses = null, expiresIn = null, reason = null,
        receivedAt = now, exp = exp,
        connectionName = "Sam",
    )

    private fun location(id: String, exp: Instant?) = Approval.LocationRequest(id, "c", null, now, exp, connectionName = "Kim")

    private fun device(type: String, exp: Instant?) =
        Approval.DeviceRequest(type, "d1", "Laptop", "desktop", null, now, exp)

    private fun connection(state: RequestState, exp: Instant? = null) =
        Approval.ConnectionRequest(
            pendingId = "p-$state", inviteId = null, sas = "123456", remote = false, name = "Ann Lee", introducedBy = null,
            receivedAt = now, exp = exp, state = state, peerApproved = false,
        )

    private fun outgoing(state: RequestState) = Approval.OutgoingRequest(
        connectionId = "o-$state", sas = if (state == RequestState.WAITING) null else "123456", remote = false, name = "Bo",
        state = state, peerApproved = false, introducedBy = null, receivedAt = now, exp = null,
    )

    private fun sec(s: Long) = now.plusSeconds(s)

    @Test
    fun theCountIsTheEntriesWaitingForThisMembersDecision() {
        val list = listOf(
            location("open", sec(86_400)),
            location("expired", sec(-1)),
            location("at its deadline", now),
            grant("no deadline", null),
            connection(RequestState.PENDING),
            // An outgoing request whose safety code waits for this member's Approve counts (owner, GC #17794 a).
            outgoing(RequestState.PENDING),
            // An approved connection waiting for its peer, and an outgoing one before the handshake: not counted.
            connection(RequestState.APPROVED),
            outgoing(RequestState.WAITING),
            // An outgoing request this member approved, waiting for the other side: not counted.
            outgoing(RequestState.APPROVED),
        )
        assertEquals(4, OpenApprovalRules.of(list, now).count)
        assertEquals(OpenApprovals(), OpenApprovalRules.of(emptyList(), now))
    }

    @Test
    fun theLastTwoMinutesAreTimeCritical() {
        assertNull(OpenApprovalRules.of(listOf(location("l", sec(121))), now).critical)
        val at120 = OpenApprovalRules.of(listOf(location("l", sec(120))), now).critical
        assertEquals(CriticalApproval(sec(120), urgent = true, label = "Kim"), at120)
        assertNull("no deadline: never time-critical by the window", OpenApprovalRules.of(listOf(grant("g", null)), now).critical)
    }

    @Test
    fun challengesSessionsAndHeldRequestsAreTimeCriticalFromArrival() {
        for (p in AuthPurpose.entries) assertTrue(p.name, OpenApprovalRules.timeCritical(auth("a", sec(600), p), now))
        assertTrue(OpenApprovalRules.timeCritical(device(Approval.DeviceRequest.SESSION_PENDING, sec(600)), now))
        assertTrue(OpenApprovalRules.timeCritical(device(Approval.DeviceRequest.APPROVAL_PENDING, sec(300)), now))
        assertFalse(OpenApprovalRules.timeCritical(device(Approval.DeviceRequest.PAIR_PENDING, sec(600)), now))
        assertFalse(OpenApprovalRules.timeCritical(grant("g", sec(600)), now))
        // From arrival with no deadline known: the clock alone.
        assertEquals(CriticalApproval(null, urgent = true, label = null), OpenApprovalRules.of(listOf(auth("a", null)), now).critical)
    }

    @Test
    fun signInsAloneAreNeutralAndTheSoonestDeadlineIsShown() {
        val signin = auth("s", sec(500), AuthPurpose.SIGNIN, name = "Bank")
        assertEquals(CriticalApproval(sec(500), urgent = false, label = "Bank"), OpenApprovalRules.of(listOf(signin), now).critical)
        val approval = auth("p", sec(400), AuthPurpose.APPROVAL, name = "Shop")
        assertEquals(
            CriticalApproval(sec(400), urgent = true, label = "Shop"),
            OpenApprovalRules.of(listOf(signin, approval, location("far", sec(9_000))), now).critical,
        )
        // An urgent entry later than a sign-in: the sign-in's time, in the urgent colours.
        val later = auth("p", sec(590), AuthPurpose.APPROVAL)
        assertEquals(CriticalApproval(sec(500), urgent = true, label = "Bank"), OpenApprovalRules.of(listOf(signin, later), now).critical)
    }

    @Test
    fun urgencyAndTheSoonestDeadlineAreJudgedOverTheTimeCriticalEntriesOnly() {
        val signin = auth("s", sec(500), AuthPurpose.SIGNIN, name = "Bank")
        // A location request 300 s away is not time-critical yet: neither its deadline nor its urgency counts.
        assertEquals(
            CriticalApproval(sec(500), urgent = false, label = "Bank"),
            OpenApprovalRules.of(listOf(signin, location("l", sec(300))), now).critical,
        )
        // In its last 2 minutes it is: the soonest, and urgent.
        assertEquals(
            CriticalApproval(sec(100), urgent = true, label = "Kim"),
            OpenApprovalRules.of(listOf(signin, location("l", sec(100))), now).critical,
        )
    }

    /** A connection's asks within 10 minutes of the first are one batch, one entry (1), as the Approvals list shows them. */
    @Test
    fun aBatchCountsAsOne() {
        val a1 = auth("a1", sec(9_000)).copy(receivedAt = now, connectionId = "c")
        val a2 = grant("g2", sec(9_000)).copy(receivedAt = now.plusSeconds(300), connectionId = "c")
        val later = auth("a3", sec(9_000)).copy(receivedAt = now.plusSeconds(601), connectionId = "c")
        val other = auth("a4", sec(9_000)).copy(receivedAt = now.plusSeconds(60), connectionId = "d")
        assertEquals(1, OpenApprovalRules.of(listOf(a1, a2), now).count)
        assertEquals("a second batch 10 minutes after the first", 2, OpenApprovalRules.of(listOf(a1, a2, later), now).count)
        val all = listOf(a1, a2, later, other, location("l", sec(9_000)))
        assertEquals("another connection's ask, and an approval that is not an ask", 4, OpenApprovalRules.of(all, now).count)
    }

    /** A connection request names itself (its `name`), so its time left can be said (11). */
    @Test
    fun aConnectionRequestIsNamedByItsOwnName() {
        assertEquals("Ann Lee", OpenApprovalRules.of(listOf(connection(RequestState.PENDING, sec(100))), now).critical?.label)
    }

    @Test
    fun aDeviceRequestIsNamedByItsDevice() {
        assertEquals("Laptop", OpenApprovalRules.of(listOf(device(Approval.DeviceRequest.SESSION_PENDING, sec(60))), now).critical?.label)
    }

    @Test
    fun changesAtEachDeadlineAndTwoMinutesBefore() {
        assertEquals(listOf(sec(380), sec(500)), OpenApprovalRules.changesAt(listOf(location("l", sec(500)), grant("g", null))))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theClockWakesAtEachPointAndOnResume() = runTest {
        val virtual = { Instant.ofEpochMilli(now.toEpochMilli() + testScheduler.currentTime) }
        val resumed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val seen = mutableListOf<Instant>()
        val job = launch { deadlineClock(flowOf(listOf(sec(-5), sec(30), sec(10))), resumed, virtual).toList(seen) }
        advanceUntilIdle()
        assertEquals("now, then each point still to come in order (one already past adds nothing)", listOf(now, sec(10), sec(30)), seen)
        testScheduler.advanceTimeBy(5_000)
        resumed.emit(Unit)
        advanceUntilIdle()
        assertEquals(sec(35), seen.last())
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aResumeAfterDeepSleepStartsTheSleepsOverFromTheTimeThen() = runTest {
        // Deep sleep: the wall clock moves on, a delay does not.
        var slept = 0L
        val virtual = { Instant.ofEpochMilli(now.toEpochMilli() + testScheduler.currentTime + slept) }
        val resumed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val seen = mutableListOf<Instant>()
        val job = launch { deadlineClock(flowOf(listOf(sec(10), sec(30))), resumed, virtual).toList(seen) }
        testScheduler.runCurrent()
        assertEquals(listOf(now), seen)
        slept = 25_000
        resumed.emit(Unit)
        testScheduler.runCurrent()
        assertEquals("the time on return", sec(25), seen.last())
        // The next point is 5 s away now, not 10 s of a sleep begun before the deep sleep.
        testScheduler.advanceTimeBy(5_001)
        assertEquals(sec(30), seen.last())
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anEntryStopsCountingAtItsDeadlineWithoutARead() = runTest {
        val virtual = { Instant.ofEpochMilli(now.toEpochMilli() + testScheduler.currentTime) }
        val list = listOf(location("soon", sec(200)), grant("g", null))
        val counts = mutableListOf<OpenApprovals>()
        val c = launch {
            deadlineClock(flowOf(OpenApprovalRules.changesAt(list)), now = virtual).collect { counts += OpenApprovalRules.of(list, it) }
        }
        advanceUntilIdle()
        assertEquals(
            listOf(
                OpenApprovals(2),
                OpenApprovals(2, CriticalApproval(sec(200), urgent = true, label = "Kim")),
                OpenApprovals(1),
            ),
            counts,
        )
        c.cancel()
    }
}
