// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
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
 * VAULT-MESSAGING 0.10.5 (§6.4, §10.1, §15 item 18): the other member's decline
 * (`sync.event{connection.request, state: "peer_declined"}`, and on the accepter's
 * side `connection.event{failed, reason: "declined"}`) ends the request and is
 * told once, with the name the request showed. The member's own decline is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerDeclineTest {
    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val store = InMemoryDeviceStateStore()

    /** Nothing here calls the credential; a call would fail the test. */
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun manager(scope: CoroutineScope) = SocialManager(
        scope,
        api = { throw VaultFailure(FailureKind.OTHER) }, // no vault: list refreshes fail quietly
        credential = credential,
        store = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    // The manager's flows run in backgroundScope, which runCurrent() (not advanceUntilIdle()) drives.
    private fun TestScope.setup(): SocialManager = manager(backgroundScope)

    private suspend fun SocialManager.outgoing(id: String, name: String) =
        onEvent(event("connection.request.outgoing", """{"connection_id":"$id","sas":"123456","name":"$name","state":"approved","remote":false}"""))

    private suspend fun SocialManager.incoming(id: String, name: String) =
        onEvent(event("connection.request.pending", """{"pending_id":"$id","invite_id":"i1","sas":"042817","profile":{"first_name":"${name.substringBefore(' ')}","last_name":"${name.substringAfter(' ')}"},"remote":true}"""))

    private suspend fun SocialManager.failed(id: String, reason: String?) =
        onEvent(event("connection.event", """{"connection_id":"$id","event":"failed"${reason?.let { ""","reason":"$it"""" } ?: ""}}"""))

    private suspend fun SocialManager.sync(key: String, id: String, state: String) =
        onEvent(event("sync.event", """{"kind":"connection.request","$key":"$id","state":"$state"}"""))

    @Test
    fun theAccepterIsToldOnceWhicheverEventComesFirst() = runTest {
        val m = setup()
        m.outgoing("c1", "Morgan Lee")
        m.outgoing("c2", "Sam")
        runCurrent()
        assertEquals(2, m.approvals.value.size)

        // failed{reason: declined} first, then the sync.event.
        m.failed("c1", "declined")
        m.sync("connection_id", "c1", "peer_declined")
        // The sync.event first, then failed.
        m.sync("connection_id", "c2", "peer_declined")
        m.failed("c2", "declined")
        runCurrent()

        assertTrue(m.approvals.value.isEmpty())
        assertEquals(listOf(PeerDecline("c1", "Morgan Lee", true, now), PeerDecline("c2", "Sam", true, now)), m.peerDeclines.value)
        assertEquals(RequestEnd.PEER_DECLINED, m.requestEnds.value["c1"])
        assertEquals(RequestEnd.PEER_DECLINED, m.requestEnds.value["c2"])
    }

    @Test
    fun theInviterIsToldWithTheRequestsNameUntilDismissedAndItSurvivesARestart() = runTest {
        val m = setup()
        m.incoming("p1", "Alex Kim")
        runCurrent()
        m.sync("pending_id", "p1", "peer_declined")
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
        assertEquals(listOf(PeerDecline("p1", "Alex Kim", false, now)), m.peerDeclines.value)

        // Kept (encrypted file) until the member dismisses it.
        val again = manager(backgroundScope)
        assertEquals(listOf(PeerDecline("p1", "Alex Kim", false, now)), again.peerDeclines.value)
        again.dismissPeerDecline("p1")
        assertTrue(again.peerDeclines.value.isEmpty())
        assertTrue(manager(backgroundScope).peerDeclines.value.isEmpty())
    }

    @Test
    fun theNameIsKeptAfterTheRequestLeftTheList() = runTest {
        val m = setup()
        m.outgoing("c3", "Jordan")
        // A bare failed (an older ordering) ends the request; the peer's decline that follows still names it.
        m.failed("c3", null)
        runCurrent()
        assertEquals(RequestEnd.FAILED, m.requestEnds.value["c3"])
        assertTrue(m.peerDeclines.value.isEmpty())
        m.sync("connection_id", "c3", "peer_declined")
        runCurrent()
        assertEquals("Jordan", m.peerDeclines.value.single().name)
        assertEquals(RequestEnd.PEER_DECLINED, m.requestEnds.value["c3"])
        // And a request this app never saw is told without a name.
        m.sync("pending_id", "p9", "peer_declined")
        runCurrent()
        assertNull(m.peerDeclines.value.last().name)
    }

    @Test
    fun theMembersOwnDeclineAndOtherEndsAreUnchanged() = runTest {
        val m = setup()
        m.outgoing("c4", "Morgan")
        m.incoming("p4", "Alex Kim")
        m.outgoing("c5", "Sam")
        runCurrent()
        // Declined on another of this member's devices (0.10.4), an expiry and a failure: no peer decline.
        m.sync("pending_id", "p4", "declined")
        m.sync("connection_id", "c4", "expired")
        m.failed("c5", null)
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
        assertTrue(m.peerDeclines.value.isEmpty())
        assertEquals(mapOf("p4" to RequestEnd.DECLINED, "c4" to RequestEnd.EXPIRED, "c5" to RequestEnd.FAILED), m.requestEnds.value)
    }
}
