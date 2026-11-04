// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A connection asks where the member is (ANDROID-PLAN §4 "Location", L1b; VAULT-MESSAGING §10.16, §10.4.2): `location.request.pending`
 * becomes an Approvals entry, `pending.list` brings it back, `sync.event{location.request.closed}` ends it, and **Not now** sends
 * nothing and keeps it away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRequestsTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val read = mutableListOf<Pair<Set<String>, String?>>()

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        // Any call to the vault would fail: Not now sends nothing.
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        feedRead = { kinds, ref, _ -> read += kinds to ref },
    )

    private fun event(type: String, body: String, ts: Instant = now) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = body.toByteArray()))

    private val exp = "2026-11-05T12:00:00.000Z"
    private fun pending(id: String = "r1", note: String? = "Hi", conn: String = "c1", expires: String = exp) =
        """{"request_id":"$id","connection_id":"$conn"${note?.let { ""","note":"$it"""" } ?: ""},"exp":"$expires"}"""

    private fun loc(m: SocialManager) = m.approvals.value.filterIsInstance<Approval.LocationRequest>()

    @Test
    fun aRequestBecomesAnEntryWithItsNoteAndTwentyFourHours() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", pending()))
        runCurrent()
        val a = loc(m).single()
        assertEquals("location:r1", a.key)
        assertEquals("Hi", a.note)
        assertEquals("c1", a.connectionId)
        assertEquals(Instant.parse(exp), a.exp)
        assertTrue(a.needsDecision)
    }

    @Test
    fun aRequestThatDoesNotReadIsNotShown() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", """{"request_id":"r1","connection_id":"c1","note":5,"exp":"$exp"}"""))
        m.onEvent(event("location.request.pending", """{"request_id":"r2","connection_id":"c1"}""")) // no exp
        m.onEvent(event("location.request.pending", pending("r3", note = "x".repeat(257))))
        runCurrent()
        assertTrue(loc(m).isEmpty())
    }

    @Test
    fun anExpiredRequestIsNotListed() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", pending(expires = "2026-11-04T11:59:00.000Z")))
        runCurrent()
        assertTrue(loc(m).isEmpty())
    }

    @Test
    fun theClosingEventEndsItAndReadsItsFeedItem() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", pending(), now.minusSeconds(60)))
        runCurrent()
        m.onEvent(event("sync.event", """{"kind":"location.request.closed","request_id":"r1","reason":"answered"}""", now.plusSeconds(1)))
        runCurrent()
        assertTrue(loc(m).isEmpty())
        assertEquals(setOf("location.request") to "r1", read.single())
    }

    @Test
    fun theListBringsRequestsBackAndReplacesWhatItNoLongerNames() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", pending("old"), now.minusSeconds(60)))
        runCurrent()
        m.refreshPendingFrom {
            PendingPage(listOf(VaultJson.parseObject("""{"kind":"location","body":${pending("r2")}}""".toByteArray())), null, now)
        }
        runCurrent()
        assertEquals(listOf("location:r2"), loc(m).map { it.key })
    }

    @Test
    fun notNowSendsNothingAndTheRequestStaysAwayEvenWhenTheListReturnsIt() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", pending()))
        runCurrent()
        m.dismissLocationRequest("r1")
        runCurrent()
        assertTrue(loc(m).isEmpty())
        // the vault still lists it until it expires; the member said Not now
        m.refreshPendingFrom {
            PendingPage(listOf(VaultJson.parseObject("""{"kind":"location","body":${pending()}}""".toByteArray())), null, now.plusSeconds(5))
        }
        runCurrent()
        assertTrue(loc(m).isEmpty())
        assertEquals(setOf("location.request") to "r1", read.single())
    }

    @Test
    fun theRequestIsDroppedAtLockAndNeverKeptAcrossIt() = runTest {
        val m = manager()
        m.onEvent(event("location.request.pending", pending()))
        runCurrent()
        m.forgetOpenVault()
        runCurrent()
        assertTrue(loc(m).isEmpty())
        assertNull(m.approvals.value.firstOrNull())
    }

    @Test
    fun theFeedItemsKindsMapToTheEntryBothWays() {
        assertEquals(setOf("location.request") to "r1", com.vettid.core.data.feed.FeedAsks.of("location:r1"))
        assertEquals("location:r1", com.vettid.core.data.feed.FeedAsks.approvalKey("location.request", "r1"))
    }
}
