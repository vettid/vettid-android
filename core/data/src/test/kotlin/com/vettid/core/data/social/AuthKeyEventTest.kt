// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.CredentialRequired
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * `connection.authenticate.key {connection_id, key}` (VAULT-MESSAGING 0.88.1 §10.4): the vault followed a rotation chain
 * and moved its pin. The app re-reads the authentication list (authoritative), off the event collector and conflated,
 * and leaves `keyChanged` to the verdicts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthKeyEventTest {
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val k0 = Base64s.encodeStd(ByteArray(32) { 0x10 })
    private val k1 = Base64s.encodeStd(ByteArray(32) { 0x11 })
    private val k2 = Base64s.encodeStd(ByteArray(32) { 0x12 })

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    private fun keyEvent(conn: String, key: String) = event("connection.authenticate.key", """{"connection_id":"$conn","key":"$key"}""")

    private fun result(conn: String, key: String, changed: Boolean) =
        event("connection.authenticate.result", """{"connection_id":"$conn","request_id":"R","authenticated":true,"key":"$key","key_changed":$changed}""")

    private fun denied(conn: String) =
        event("connection.authenticate.result", """{"connection_id":"$conn","request_id":"R","authenticated":false,"reason":"denied"}""")

    /** What `authenticate.list` answers next (null: it failed); every read is counted. */
    private var states: List<AuthenticationState>? = emptyList()
    private var reads = 0

    /** When set, a read waits for it (a read in flight); [readFailure], when set, is thrown by the read. */
    private var gate: CompletableDeferred<Unit>? = null
    private var readFailure: Exception? = null

    private val listed = listOf(FakeSocial.connection("c1", "Sam"), FakeSocial.connection("c2", "Kim"))

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        readConnections = {
            reads++
            gate?.await()
            readFailure?.let { throw it }
            val s = states
            if (s == null) SocialManager.ConnectionRead(listed, null, IllegalStateException("authenticate.list failed")) else SocialManager.ConnectionRead(listed, s)
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
        readContext = EmptyCoroutineContext, // the re-read job stays on the test's scheduler
    )

    private fun pinned(conn: String, key: String) = AuthenticationState(conn, now, "authenticated", now, key = key)

    /** Hands a `.key` event to the manager and lets its re-read job run. */
    private suspend fun TestScope.key(m: SocialManager, conn: String, key: String) {
        m.onEvent(keyEvent(conn, key))
        runCurrent()
    }

    /** c1 had an unverified key change to [k1] (the vault re-pinned there), shown to the member. */
    private suspend fun SocialManager.withKeyChange() {
        states = listOf(pinned("c1", k0), pinned("c2", k0))
        refresh()
        onEvent(result("c1", k1, changed = true))
        assertTrue(authentication.value.getValue("c1").keyChanged)
        states = listOf(pinned("c1", k1), pinned("c2", k0)) // the vault re-pinned c1 to k1
    }

    @Test
    fun aKeyEventReReadsTheListAndTakesItsKey() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k0))
        m.refresh()
        states = listOf(pinned("c1", k1))
        key(m, "c1", k1)
        assertEquals(2, reads)
        assertEquals(k1, m.authentication.value.getValue("c1").key)
        assertFalse(m.authentication.value.getValue("c1").keyChanged)
    }

    /** The list is authoritative: read after the event, it may already be past the event's key. */
    @Test
    fun theListWinsOverTheEventsKey() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k2))
        key(m, "c1", k1)
        assertEquals(k2, m.authentication.value.getValue("c1").key)
    }

    /**
     * A `.key` event starts from the vault's current pin, which a key change had already moved to the unverified key: it
     * verifies nothing about that change, so the warning stays (only a later verdict decides it again).
     */
    @Test
    fun aKeyEventAfterAKeyChangeKeepsTheWarning() = runTest {
        val m = manager()
        m.withKeyChange()
        states = listOf(pinned("c1", k2), pinned("c2", k0))
        key(m, "c1", k2)
        val s = m.authentication.value.getValue("c1")
        assertEquals("re-read", k2, s.key)
        assertTrue("the unverified change is still shown", s.keyChanged)
    }

    @Test
    fun aKeyChangeAfterAKeyEventIsShown() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k1))
        key(m, "c1", k1)
        assertFalse(m.authentication.value.getValue("c1").keyChanged)
        m.onEvent(result("c1", k2, changed = true))
        assertTrue(m.authentication.value.getValue("c1").keyChanged)
        assertEquals(k2, m.authentication.value.getValue("c1").key)
    }

    /** The vault followed a response's `rotations`: `key_changed: false`, the later verdict, clears it (as before). */
    @Test
    fun aLaterVerdictWithoutAChangeClearsTheWarning() = runTest {
        val m = manager()
        m.withKeyChange()
        key(m, "c1", k2)
        m.onEvent(result("c1", k2, changed = false))
        assertFalse(m.authentication.value.getValue("c1").keyChanged)
    }

    /** Review finding 4: a refusal from the party the warning is about does not erase the warning. */
    @Test
    fun aDeniedResultAfterAKeyChangeKeepsTheWarning() = runTest {
        val m = manager()
        m.withKeyChange()
        m.onEvent(denied("c1"))
        val s = m.authentication.value.getValue("c1")
        assertEquals("denied", s.lastResult)
        assertTrue(s.keyChanged)
        assertEquals(k1, s.key)
    }

    @Test
    fun anUnknownConnectionMakesNoStateAndTouchesNoOther() = runTest {
        val m = manager()
        m.withKeyChange()
        val before = m.authentication.value
        key(m, "c9", k2)
        assertNull(m.authentication.value["c9"])
        assertEquals(before, m.authentication.value)
    }

    @Test
    fun aMalformedKeyEventIsIgnoredWithoutARead() = runTest {
        val m = manager()
        for (body in listOf(
            """{"connection_id":"c1","key":"${Base64s.encodeStd(ByteArray(31))}"}""",
            """{"connection_id":"c1"}""",
            """{"key":"$k1"}""",
        )) {
            m.onEvent(event("connection.authenticate.key", body))
        }
        runCurrent()
        assertEquals(0, reads)
    }

    @Test
    fun aKeyEventReadAcrossALockWritesNothing() = runTest {
        val m = manager()
        m.withKeyChange()
        states = listOf(pinned("c1", k2))
        m.keyEventHook = { m.forgetOpenVault() }
        key(m, "c1", k2)
        m.keyEventHook = null
        assertTrue("nothing of the old session is put back", m.authentication.value.isEmpty())
        assertTrue(m.connections.value.isEmpty())
    }

    /** A lock and an unlock between the event and its write: the new session's states stay as the new session read them. */
    @Test
    fun aKeyEventReadAcrossALockAndUnlockWritesNothing() = runTest {
        val m = manager()
        m.withKeyChange()
        val stale = listOf(pinned("c1", k2))
        states = stale
        m.keyEventHook = {
            m.forgetOpenVault()
            m.reopen()
            states = listOf(pinned("c1", k0))
            runBlocking { m.refresh() }
            states = stale
        }
        key(m, "c1", k2)
        m.keyEventHook = null
        assertEquals(k0, m.authentication.value.getValue("c1").key)
    }

    @Test
    fun aKeyEventReadAcrossAHoldWritesNothing() = runTest {
        val m = manager()
        m.withKeyChange()
        val held = m.authentication.value
        states = listOf(pinned("c1", k2))
        m.keyEventHook = { m.forgetPending() }
        key(m, "c1", k2)
        m.keyEventHook = null
        assertEquals("as before the hold", held, m.authentication.value)
        // An event taken after the hold began is taken in.
        key(m, "c1", k2)
        assertEquals(k2, m.authentication.value.getValue("c1").key)
        assertTrue(m.authentication.value.getValue("c1").keyChanged)
    }

    /** Review finding 8: an event's epochs are the ones it was taken at, even if its re-read starts after a lock. */
    @Test
    fun aKeyEventTakenBeforeALockWritesNothingEvenIfItsReadStartsAfter() = runTest {
        val m = manager()
        m.withKeyChange()
        states = listOf(pinned("c1", k2))
        m.onEvent(keyEvent("c1", k2)) // taken; its job has not run yet
        m.forgetOpenVault()
        m.reopen()
        runCurrent()
        assertTrue(m.authentication.value.isEmpty())
    }

    /** Review finding 1: a background re-read never puts up "This phone can't use your vault". */
    @Test
    fun aCredentialRequiredReReadRaisesNoScreenAndWritesNothing() = runTest {
        CredentialRequired.clear()
        try {
            val m = manager()
            m.withKeyChange()
            val before = m.authentication.value
            readFailure = VaultOpException("connection.list", "credential_required")
            key(m, "c1", k2)
            assertNull("no credential screen", CredentialRequired.state.value)
            assertEquals(before, m.authentication.value)
            // The slot is free again: the next event reads.
            readFailure = null
            states = listOf(pinned("c1", k2), pinned("c2", k0))
            key(m, "c1", k2)
            assertEquals(k2, m.authentication.value.getValue("c1").key)
        } finally {
            CredentialRequired.clear()
        }
    }

    /** Review finding 9: an `authenticate.list` that failed writes nothing, not even the connections it came with. */
    @Test
    fun aFailedAuthenticateListWritesNothing() = runTest {
        val m = manager()
        m.withKeyChange()
        m.seedConnections(emptyList())
        val before = m.authentication.value
        states = null
        key(m, "c1", k2)
        assertEquals(before, m.authentication.value)
        assertTrue("the connections half is not taken in either", m.connections.value.isEmpty())
    }

    /** A read that throws anything else: nothing is written and nothing reaches the event collector. */
    @Test
    fun aFailedReadWritesNothing() = runTest {
        val m = manager()
        m.withKeyChange()
        val before = m.authentication.value
        readFailure = java.io.IOException("no answer")
        key(m, "c1", k2) // does not throw
        assertEquals(before, m.authentication.value)
    }

    /** Review finding 2: a burst of `.key` events costs one read in flight and one more after it, never more. */
    @Test
    fun aBurstOfKeyEventsCostsAtMostTwoReads() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k2), pinned("c2", k0))
        val g = CompletableDeferred<Unit>()
        gate = g
        repeat(20) { m.onEvent(keyEvent("c1", k2)) }
        runCurrent()
        assertEquals("one read in flight", 1, reads)
        g.complete(Unit)
        runCurrent()
        assertEquals("one more for the events that came during it", 2, reads)
        assertEquals(k2, m.authentication.value.getValue("c1").key)
        // Quiet again: the next event reads once.
        gate = null
        key(m, "c1", k2)
        assertEquals(3, reads)
    }

    /** Review finding 2: while a re-read waits on the vault, later events are handled at once. */
    @Test
    fun aReReadInFlightDoesNotHoldUpOtherEvents() = runTest {
        val m = manager()
        m.withKeyChange()
        val g = CompletableDeferred<Unit>()
        gate = g
        m.onEvent(keyEvent("c1", k2))
        runCurrent()
        // The read waits; the collector goes on.
        m.onEvent(event("message.new", """{"connection_id":"c1","message_id":"m9","direction":"in","text":"hi","sent_at":"2026-11-06T12:00:00Z"}"""))
        assertEquals(listOf("m9"), m.messages("c1").first().map { it.messageId })
        m.onEvent(result("c1", k1, changed = false))
        assertFalse(m.authentication.value.getValue("c1").keyChanged)
        g.complete(Unit)
        runCurrent()
    }

    /**
     * Review finding 3: a `.result` is built from the state held at its write, under the lock. A list read taken in
     * between (here a refresh that moved the key) is not undone by a result that carries no key.
     */
    @Test
    fun aResultIsBuiltFromTheStateAtItsWrite() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k0))
        m.refresh()
        m.resultHook = {
            states = listOf(pinned("c1", k1))
            runBlocking { m.refresh() }
        }
        m.onEvent(denied("c1"))
        m.resultHook = null
        val s = m.authentication.value.getValue("c1")
        assertEquals("the list read's key stays", k1, s.key)
        assertEquals("denied", s.lastResult)
    }
}
