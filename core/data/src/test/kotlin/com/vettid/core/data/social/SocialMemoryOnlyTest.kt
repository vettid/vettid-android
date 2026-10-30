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

    // --- the narrow exception: the three event-only asks, encrypted, until their exp (owner decision 2026-10-27) ---

    private fun managerAt(scope: CoroutineScope, pending: com.vettid.core.vault.DeviceStateStore, at: Instant) = SocialManager(
        scope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        pendingStore = pending,
        clock = Clock.fixed(at, ZoneOffset.UTC),
    )

    private fun session(id: String, exp: String) =
        event("device.session.pending", """{"request_id":"$id","device_id":"A","role":"agent","name":"Bot","seconds":3600,"exp":"$exp"}""")

    @Test
    fun theThreeEventOnlyAsksSurviveAProcessRestartUntilTheirExp() = runTest {
        val file = InMemoryDeviceStateStore()
        val m = managerAt(backgroundScope, file, now)
        m.restorePending()
        m.onEvent(session("s1", "2026-10-27T12:10:00.000Z"))
        m.onEvent(event("connection.authenticate.pending", """{"connection_id":"c1","request_id":"r1","exp":"2026-10-27T12:10:00.000Z"}"""))
        m.onEvent(event("approval.pending", """{"approval_id":"h1","device_id":"A","role":"agent","name":"Bot","type":"agent.request","body":{},"exp":"2026-10-27T12:10:00.000Z"}"""))
        runCurrent()
        assertTrue(file.load() != null)

        val again = managerAt(backgroundScope, file, now.plusSeconds(60))
        runCurrent()
        assertTrue("nothing before the vault is open", again.approvals.value.isEmpty())
        again.restorePending()
        runCurrent()
        assertTrue(again.approvals.value.any { it.key.contains("s1") })
        assertTrue(String(file.load()!!).contains("s1"))
    }

    @Test
    fun anAskPastItsExpIsNeverShownAndLeavesTheFile() = runTest {
        val file = InMemoryDeviceStateStore()
        managerAt(backgroundScope, file, now).also { it.restorePending() }.onEvent(session("s2", "2026-10-27T12:10:00.000Z"))
        runCurrent()
        val later = managerAt(backgroundScope, file, now.plusSeconds(601))
        later.restorePending()
        runCurrent()
        assertTrue(later.approvals.value.isEmpty())
        assertNull("deleted when it would be empty", file.load())
    }

    @Test
    fun lockHoldAndWipeClearTheFileEvenBeforeItWasRead() = runTest {
        val file = InMemoryDeviceStateStore()
        managerAt(backgroundScope, file, now).also { it.restorePending() }.onEvent(session("s3", "2026-10-27T12:10:00.000Z"))
        runCurrent()
        // A new process that is locked at once never reads it.
        managerAt(backgroundScope, file, now).forgetOpenVault()
        assertNull(file.load())

        for (clear in listOf<(SocialManager) -> Unit>({ it.forgetOpenVault() }, { it.forgetPending() }, { it.clear() })) {
            val m = managerAt(backgroundScope, file, now)
            m.restorePending()
            m.onEvent(session("s4", "2026-10-27T12:10:00.000Z"))
            runCurrent()
            assertTrue(file.load() != null)
            clear(m)
            assertNull(file.load())
        }
    }

    @Test
    fun onlyThoseThreeTypesAreEverWrittenAndAtMost32() = runTest {
        val file = InMemoryDeviceStateStore()
        val m = managerAt(backgroundScope, file, now)
        m.restorePending()
        m.onEvent(event("grant.pending", """{"request_id":"g1","exp":"2026-10-27T12:10:00.000Z"}"""))
        m.onEvent(event("connection.request.pending", """{"pending_id":"p1","invite_id":"i1","sas":"042817","profile":{"first_name":"Alex","last_name":"Kim"},"remote":true}"""))
        runCurrent()
        assertNull("no other type is persisted", file.load())
        repeat(40) { m.onEvent(session("n$it", "2026-10-27T12:10:00.000Z")) }
        runCurrent()
        val text = String(file.load()!!)
        assertEquals(32, Regex("device.session.pending").findAll(text).count())
        assertTrue(!text.contains("g1") && !text.contains("p1"))
    }

    private class Throwing(val inner: InMemoryDeviceStateStore = InMemoryDeviceStateStore()) : com.vettid.core.vault.DeviceStateStore {
        var fail = false

        override fun load(): ByteArray? = if (fail) throw java.security.InvalidKeyException("key") else inner.load()

        override fun save(state: ByteArray) = if (fail) throw java.io.IOException("disk full") else inner.save(state)

        override fun clear() = if (fail) throw java.security.ProviderException("provider") else inner.clear()
    }

    @Test
    fun aFailingFileNeverEscapesAndTheAskStaysInMemory() = runTest {
        val file = Throwing().also { it.fail = true }
        val m = managerAt(backgroundScope, file, now)
        m.restorePending()
        m.onEvent(session("s5", "2026-10-27T12:10:00.000Z"))
        runCurrent()
        assertTrue(m.approvals.value.any { it.key.contains("s5") })
        // Clearing fails too: said to the lock's steps (retry, then end the process; review of #204 item 2), after the
        // memory is dropped.
        org.junit.Assert.assertThrows(java.security.ProviderException::class.java) { m.forgetPending() }
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
    }

    /** Events that came before the read are written at its end, even when the file already held all of them. */
    @Test
    fun askedBeforeTheReadAreWrittenAtItsEnd() = runTest {
        val file = InMemoryDeviceStateStore()
        managerAt(backgroundScope, file, now).also { it.restorePending() }.onEvent(session("s6", "2026-10-27T12:10:00.000Z"))
        val m = managerAt(backgroundScope, file, now)
        m.onEvent(session("s6", "2026-10-27T12:10:00.000Z")) // already in the file
        m.onEvent(session("s7", "2026-10-27T12:10:00.000Z")) // fresh, memory only until settled
        runCurrent()
        m.restorePending()
        val text = String(file.load()!!)
        assertTrue(text.contains("s6") && text.contains("s7"))
    }

    /** A hold between the read and the restore's edit cannot be undone: the read is under the same lock. */
    @Test
    fun aHoldAfterTheRestoreLeavesNothingBehind() = runTest {
        val file = InMemoryDeviceStateStore()
        managerAt(backgroundScope, file, now).also { it.restorePending() }.onEvent(session("s8", "2026-10-27T12:10:00.000Z"))
        val m = managerAt(backgroundScope, file, now)
        val t = Thread { repeat(50) { m.forgetPending() } }
        t.start()
        m.restorePending()
        t.join()
        m.forgetPending()
        assertTrue(m.approvals.value.isEmpty())
        assertNull(file.load())
    }

    /** Owner 2026-10-28: only the fields the screens and decisions need are written, never the held request's body. */
    @Test
    fun onlyTheAllowListedFieldsAreWrittenAndSurviveARoundTrip() = runTest {
        val file = InMemoryDeviceStateStore()
        val m = managerAt(backgroundScope, file, now)
        m.restorePending()
        m.onEvent(event("approval.pending", """{"approval_id":"h9","device_id":"A","role":"agent","name":"Bot","type":"agent.request","body":{"cmd":"rm -rf SECRETPAYLOAD"},"flags":["shell"],"extra":"EXTRAFIELD","exp":"2026-10-27T12:10:00.000Z"}"""))
        m.onEvent(event("connection.authenticate.pending", """{"connection_id":"c1","request_id":"r9","context":"login","purpose":"sign in","secret":"EXTRAFIELD","exp":"2026-10-27T12:10:00.000Z"}"""))
        runCurrent()
        val text = String(file.load()!!)
        assertTrue(text.contains("h9") && text.contains("agent.request") && text.contains("sign in"))
        assertTrue(!text.contains("SECRETPAYLOAD") && !text.contains("EXTRAFIELD") && !text.contains("flags"))
        val again = managerAt(backgroundScope, file, now.plusSeconds(1))
        again.restorePending()
        runCurrent()
        val d = again.approvals.value.filterIsInstance<Approval.DeviceRequest>().single()
        assertEquals("Bot", d.deviceName)
        assertEquals("agent.request", d.requestType)
        assertEquals("A", d.deviceId)
        assertEquals(2, again.approvals.value.size)
    }

    /** Review of #204 (4): a file that cannot be read is deleted at the restore, and the unlock goes on. */
    @Test
    fun anUnreadableFileIsDeletedAtTheRestore() = runTest {
        val file = InMemoryDeviceStateStore().also { it.save("not json at all".toByteArray()) }
        val m = managerAt(backgroundScope, file, now)
        m.restorePending()
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
        assertNull(file.load())
    }

    /** Review of #204 (1): a `pending.list` read that began before a lock cannot put its asks back into memory or the file. */
    @Test
    fun aListMergeRacingALockLeavesNothingInMemoryOrOnDisk() = runTest {
        val file = InMemoryDeviceStateStore()
        val m = managerAt(backgroundScope, file, now)
        m.restorePending()
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
        assertNull(file.load())
    }
}
