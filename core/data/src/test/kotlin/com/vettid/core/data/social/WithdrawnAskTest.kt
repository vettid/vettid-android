// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.feed.WithdrawnBatch
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * VAULT-MESSAGING 0.80.0 §10.4 "Withdrawing a challenge", ANDROID-PLAN 0.1.101: `connection.authenticate.decided` with
 * `withdrawn: true` closes the ask and is told apart from a decision; one ask of a batch leaves the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WithdrawnAskTest {
    private val base = Instant.parse("2026-10-30T12:00:00Z")
    private fun at(sec: Long): Instant = base.plusSeconds(sec)

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private val told = mutableListOf<WithdrawnBatch>()

    /** A clock the test moves: an ask's arrival time is the app's clock at the event. */
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val clock = MovingClock(base)

    private fun TestScope.manager(failure: FailureKind = FailureKind.OTHER) = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(failure) },
        credential = credential,
        clock = clock,
        feedWithdrawn = { told += it },
    )

    private fun event(type: String, body: String, ts: Instant): VaultMessage {
        clock.now = maxOf(base, ts)
        return msg(type, body, ts)
    }

    private fun msg(type: String, body: String, ts: Instant) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = body.toByteArray()))

    private fun decided(fields: String, ts: Instant) = event("sync.event", """{"kind":"connection.authenticate.decided",$fields}""", ts)

    private fun ask(id: String, conn: String = "c1", exp: String = "2026-10-30T13:00:00.000Z") =
        """{"connection_id":"$conn","request_id":"$id","purpose":"signin","exp":"$exp"}"""

    private fun SocialManager.keys() = approvals.value.map { it.key }.sorted()

    @Test
    fun aFlaggedEventClosesTheAskAndIsNotedAsWithdrawn() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        runCurrent()
        assertEquals(listOf("auth:r1"), m.keys())
        m.onEvent(decided(""""request_id":"r1","approved":false,"withdrawn":true""", at(5)))
        runCurrent()
        assertEquals(emptyList<String>(), m.keys())
        assertEquals(setOf("r1"), m.withdrawn.value)
        // The only ask of its batch: nothing is left open, so the feed item is read.
        assertEquals(1, told.size)
        assertEquals(0, told.single().remaining)
        assertEquals("c1", told.single().connectionId)
    }

    @Test
    fun withoutTheFlagItClosesAsBeforeAndIsNotWithdrawn() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        m.onEvent(decided(""""request_id":"r1","approved":false""", at(5)))
        runCurrent()
        assertEquals(emptyList<String>(), m.keys())
        assertTrue(m.withdrawn.value.isEmpty())
        assertTrue(told.isEmpty())
    }

    @Test
    fun aFlagForAnUnknownRequestDoesNothing() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        m.onEvent(decided(""""request_id":"nope","approved":false,"withdrawn":true""", at(5)))
        runCurrent()
        assertEquals(listOf("auth:r1"), m.keys())
        assertTrue(m.withdrawn.value.isEmpty())
        assertTrue(told.isEmpty())
    }

    @Test
    fun aStringOrApprovedFlagIsNotAWithdrawal() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        m.onEvent(event("connection.authenticate.pending", ask("r2"), at(1)))
        m.onEvent(decided(""""request_id":"r1","approved":false,"withdrawn":"true"""", at(5)))
        m.onEvent(decided(""""request_id":"r2","approved":true,"withdrawn":true""", at(5)))
        runCurrent()
        assertTrue(m.withdrawn.value.isEmpty())
        assertTrue(told.isEmpty())
    }

    @Test
    fun oneWithdrawnAskOfABatchLeavesTheOthersAndTheEarliestDeadline() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1", exp = "2026-10-30T12:30:00.000Z"), at(1)))
        m.onEvent(event("connection.authenticate.pending", ask("r2", exp = "2026-10-30T12:20:00.000Z"), at(30)))
        m.onEvent(event("connection.authenticate.pending", ask("r3", exp = "2026-10-30T12:40:00.000Z"), at(60)))
        // Another connection's ask is not part of the batch.
        m.onEvent(event("connection.authenticate.pending", ask("x1", conn = "c2", exp = "2026-10-30T12:05:00.000Z"), at(60)))
        runCurrent()
        m.onEvent(decided(""""request_id":"r3","approved":false,"withdrawn":true""", at(100)))
        runCurrent()
        assertEquals(listOf("auth:r1", "auth:r2", "auth:x1"), m.keys())
        val b = told.single()
        assertEquals(2, b.remaining)
        assertEquals(Instant.parse("2026-10-30T12:20:00Z"), b.openUntil)
        assertEquals(setOf("auth:r1", "auth:r2", "auth:r3"), b.askKeys)
        assertEquals("auth:r3", b.askKey)
        // The notification may go by itself only at the latest deadline of the asks left.
        assertEquals(Instant.parse("2026-10-30T12:30:00Z"), b.closeAt)
        // The next withdrawal leaves one; the last leaves none.
        m.onEvent(decided(""""request_id":"r2","approved":false,"withdrawn":true""", at(110)))
        m.onEvent(decided(""""request_id":"r1","approved":false,"withdrawn":true""", at(120)))
        runCurrent()
        assertEquals(listOf(1, 0), told.drop(1).map { it.remaining })
        assertEquals(setOf("r1", "r2", "r3"), m.withdrawn.value)
        assertEquals(listOf("auth:x1"), m.keys())
    }

    @Test
    fun anAskOutsideTheTenMinuteWindowIsAnotherBatch() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        m.onEvent(event("connection.authenticate.pending", ask("r2"), at(1 + 11 * 60)))
        runCurrent()
        m.onEvent(decided(""""request_id":"r2","approved":false,"withdrawn":true""", at(800)))
        runCurrent()
        assertEquals(0, told.single().remaining)
        assertEquals(listOf("auth:r1"), m.keys())
    }

    @Test
    fun aFlagThatComesAfterANotFoundIsStillNoted() = runTest {
        val m = manager(FailureKind.NOT_FOUND)
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        runCurrent()
        // The member's deny is answered not_found: the ask is dropped here, nothing says why yet.
        try {
            m.denyAuthentication("r1")
            org.junit.Assert.fail("not_found must be thrown")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_FOUND, e.kind)
        }
        runCurrent()
        assertEquals(emptyList<String>(), m.keys())
        assertTrue(m.withdrawn.value.isEmpty())
        // The sync event comes a moment later: the screen that is still open switches to the withdrawn notice.
        m.onEvent(decided(""""request_id":"r1","approved":false,"withdrawn":true""", at(5)))
        runCurrent()
        assertEquals(setOf("r1"), m.withdrawn.value)
    }

    @Test
    fun theLockForgetsWhatWasWithdrawn() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        m.onEvent(decided(""""request_id":"r1","approved":false,"withdrawn":true""", at(5)))
        runCurrent()
        m.forgetOpenVault()
        assertTrue(m.withdrawn.value.isEmpty())
    }

    @Test
    fun anOpenAskWithoutADeadlineKeepsTheNotificationFromTimingOut() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", ask("r1"), at(1)))
        m.onEvent(event("connection.authenticate.pending", """{"connection_id":"c1","request_id":"r2","purpose":"signin"}""", at(2)))
        m.onEvent(event("connection.authenticate.pending", ask("r3"), at(3)))
        runCurrent()
        m.onEvent(decided(""""request_id":"r3","approved":false,"withdrawn":true""", at(10)))
        runCurrent()
        assertEquals(null, told.single().closeAt)
        assertEquals(Instant.parse("2026-10-30T13:00:00Z"), told.single().openUntil)
    }

    @Test
    fun theWithdrawnSetIsBounded() = runTest {
        val m = manager()
        repeat(300) { i -> m.onEvent(event("connection.authenticate.pending", ask("r$i", conn = "c$i"), at(1))) }
        runCurrent()
        repeat(300) { i -> m.onEvent(decided(""""request_id":"r$i","approved":false,"withdrawn":true""", at(10))) }
        runCurrent()
        assertEquals(256, m.withdrawn.value.size)
        assertTrue("r299" in m.withdrawn.value)
        assertTrue("r0" !in m.withdrawn.value)
    }
}
