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
import kotlinx.coroutines.launch
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

        m.reopen() // the next unlock
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
        m.reopen() // the next unlock
        m.onEvent(event("sync.event", """{"kind":"connection.request","pending_id":"p2","state":"peer_declined"}"""))
        runCurrent()
        assertNull(m.peerDeclines.value.single().name)
    }

    // --- the event-only exception is gone (owner decision 2026-10-30): the three kinds are memory only too ---

    private fun session(id: String, exp: String) =
        event("device.session.pending", """{"request_id":"$id","device_id":"A","role":"agent","name":"Bot","seconds":3600,"exp":"$exp"}""")

    private fun withLeftover(scope: CoroutineScope, leftover: com.vettid.core.vault.DeviceStateStore) = SocialManager(
        scope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        obsoletePendingStore = leftover,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private class Throwing(val inner: InMemoryDeviceStateStore = InMemoryDeviceStateStore()) : com.vettid.core.vault.DeviceStateStore {
        var fail = false

        override fun load(): ByteArray? = inner.load()

        override fun save(state: ByteArray) = inner.save(state)

        override fun clear() = if (fail) throw java.io.IOException("cannot delete") else inner.clear()
    }

    @Test
    fun theThreeEventOnlyAsksAreMemoryOnlyAndGoWithTheProcess() = runTest {
        val leftover = InMemoryDeviceStateStore()
        val m = withLeftover(backgroundScope, leftover)
        m.onEvent(session("s1", "2026-10-27T12:10:00.000Z"))
        m.onEvent(event("connection.authenticate.pending", """{"connection_id":"c1","request_id":"r1","exp":"2026-10-27T12:10:00.000Z"}"""))
        m.onEvent(event("approval.pending", """{"approval_id":"h1","device_id":"A","role":"agent","name":"Bot","type":"agent.request","body":{},"exp":"2026-10-27T12:10:00.000Z"}"""))
        runCurrent()
        assertEquals(3, m.approvals.value.size)
        assertNull("nothing is written", leftover.load())
        val again = withLeftover(backgroundScope, leftover) // a new process
        runCurrent()
        assertTrue(again.approvals.value.isEmpty())
    }

    @Test
    fun aLockDropsThemFromMemory() = runTest {
        val m = setup()
        m.onEvent(session("s2", "2026-10-27T12:10:00.000Z"))
        runCurrent()
        assertEquals(1, m.approvals.value.size)
        m.forgetOpenVault()
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
    }

    /** The pending-asks.bin of an earlier build is deleted at the next start. */
    @Test
    fun aLeftoverPendingAsksFileIsDeletedAtStart() = runTest {
        val leftover = InMemoryDeviceStateStore().also { it.save("sealed bytes".toByteArray()) }
        withLeftover(backgroundScope, leftover)
        assertNull(leftover.load())
    }

    /** Best effort: a delete that failed is reported, tried again at each lock, and never thrown (the lock itself succeeds). */
    @Test
    fun aLeftoverThatCannotBeDeletedIsRetriedAtEachLockAndNeverThrown() = runTest {
        val leftover = Throwing().also { it.inner.save("sealed bytes".toByteArray()); it.fail = true }
        val reported = mutableListOf<Throwable>()
        val m = SocialManager(
            backgroundScope,
            api = { throw VaultFailure(FailureKind.OTHER) },
            credential = credential,
            obsoletePendingStore = leftover,
            onLeftoverFailed = { reported += it },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        assertEquals("reported at start", 1, reported.size)
        assertTrue("still there", leftover.inner.load() != null)
        m.onEvent(session("s3", "2026-10-27T12:10:00.000Z"))
        m.forgetOpenVault() // does not throw
        m.forgetPending()
        assertTrue("the lock itself succeeded", m.approvals.value.isEmpty())
        assertEquals("tried again at each lock", 3, reported.size)
        leftover.fail = false
        m.forgetOpenVault()
        assertNull(leftover.inner.load())
        m.forgetOpenVault() // nothing left to delete
        assertEquals(3, reported.size)
    }

    /** An event after the lock and before reopen() updates memory and is shown after reopen(). */
    @Test
    fun anEventWhileClosedUpdatesMemoryAndIsShownAfterReopen() = runTest {
        val m = setup()
        m.forgetOpenVault()
        m.onEvent(session("s20", "2026-10-27T12:10:00.000Z"))
        runCurrent()
        assertTrue(m.approvals.value.any { it.key.contains("s20") })
        m.reopen()
        runCurrent()
        assertTrue(m.approvals.value.any { it.key.contains("s20") })
    }

    /** A `pending.list` read that began before a lock cannot put its asks back into memory. */
    @Test
    fun aListMergeRacingALockLeavesNothingInMemory() = runTest {
        val m = setup()
        val started = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val ask = com.vettid.core.vault.VaultJson.parseObject(
            """{"kind":"session","body":{"request_id":"s9","device_id":"A","role":"agent","name":"Bot","seconds":3600,"exp":"2026-10-27T12:10:00.000Z"}}""".toByteArray(),
        )
        val reader = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            m.refreshPendingFrom {
                started.countDown()
                gate.await()
                com.vettid.core.vault.PendingPage(listOf(ask), null, now.plusSeconds(5))
            }
        }
        started.await()
        m.forgetOpenVault() // the lock, while the read is on its way
        gate.countDown()
        reader.join()
        assertTrue(m.approvals.value.isEmpty())
    }

    /** A read that begins after the lock merges nothing; the next unlock opens it again. */
    @Test
    fun aListReadThatBeginsAfterTheLockMergesNothing() = runTest {
        val m = setup()
        m.forgetOpenVault()
        val ask = com.vettid.core.vault.VaultJson.parseObject(
            """{"kind":"session","body":{"request_id":"s10","device_id":"A","role":"agent","name":"Bot","seconds":3600,"exp":"2026-10-27T12:10:00.000Z"}}""".toByteArray(),
        )
        m.refreshPendingFrom { com.vettid.core.vault.PendingPage(listOf(ask), null, now.plusSeconds(5)) }
        m.onEvent(session("s11", "2026-10-27T12:10:00.000Z")) // an event right after the lock: memory only
        runCurrent()
        assertTrue("the list merged nothing", m.approvals.value.none { it.key.contains("s10") })
        m.reopen()
        m.refreshPendingFrom { com.vettid.core.vault.PendingPage(listOf(ask), null, now.plusSeconds(6)) }
        runCurrent()
        assertTrue(m.approvals.value.any { it.key.contains("s10") })
    }
}
