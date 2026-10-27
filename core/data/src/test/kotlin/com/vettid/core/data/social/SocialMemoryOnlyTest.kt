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
 * Owner decision 2026-10-27 (phone storage audit): the approvals the vault sent as events, the names requests showed
 * and the peers' declines are memory only, like every other vault content: never written to the phone, dropped at
 * lock, hold, wipe and process end, and a `social.bin` left by an earlier build is deleted at the next start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocialMemoryOnlyTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z")

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun manager(scope: CoroutineScope, store: InMemoryDeviceStateStore) = SocialManager(
        scope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        obsoleteStore = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    private suspend fun SocialManager.feed() {
        onEvent(event("device.session.pending", """{"request_id":"s1","device_id":"A","role":"agent","name":"Bot","seconds":3600,"exp":"2026-10-27T12:10:00.000Z"}"""))
        onEvent(event("connection.authenticate.pending", """{"connection_id":"c1","request_id":"r1","exp":"2026-10-27T12:10:00.000Z"}"""))
        onEvent(event("connection.request.pending", """{"pending_id":"p1","invite_id":"i1","sas":"042817","profile":{"first_name":"Alex","last_name":"Kim"},"remote":true}"""))
        onEvent(event("sync.event", """{"kind":"connection.request","pending_id":"p1","state":"peer_declined"}"""))
    }

    private fun TestScope.setup(store: InMemoryDeviceStateStore = InMemoryDeviceStateStore()): SocialManager =
        manager(backgroundScope, store)

    @Test
    fun nothingIsWrittenToThePhone() = runTest {
        val store = InMemoryDeviceStateStore()
        val m = setup(store)
        m.feed()
        runCurrent()
        assertEquals(2, m.approvals.value.size)
        assertEquals(1, m.peerDeclines.value.size)
        assertNull("no file", store.load())
        // A new process (no vault open yet) knows none of it.
        val again = setup(store)
        runCurrent()
        assertTrue(again.approvals.value.isEmpty())
        assertTrue(again.peerDeclines.value.isEmpty())
    }

    @Test
    fun aFileOfAnEarlierBuildIsDeletedAtTheNextStart() = runTest {
        val store = InMemoryDeviceStateStore()
        store.save("""{"events":[],"declines":[]}""".toByteArray())
        setup(store)
        assertNull(store.load())
    }

    @Test
    fun lockAndHoldDropThemAndAWipeToo() = runTest {
        val m = setup()
        m.feed()
        runCurrent()
        m.forgetOpenVault() // lock; the hold does the same for these
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
        assertTrue(m.peerDeclines.value.isEmpty())

        m.feed()
        runCurrent()
        assertEquals(2, m.approvals.value.size)
        m.clear() // wipe
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
        assertTrue(m.peerDeclines.value.isEmpty())
    }

    /** The name a request showed does not outlive the open vault: a decline after a lock is told without it. */
    @Test
    fun requestNamesAreDroppedToo() = runTest {
        val m = setup()
        m.onEvent(event("connection.request.pending", """{"pending_id":"p2","invite_id":"i1","sas":"042817","profile":{"first_name":"Alex","last_name":"Kim"},"remote":true}"""))
        m.forgetOpenVault()
        m.onEvent(event("sync.event", """{"kind":"connection.request","pending_id":"p2","state":"peer_declined"}"""))
        runCurrent()
        assertNull(m.peerDeclines.value.single().name)
    }
}
