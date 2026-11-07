// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.test.TestScope
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

/**
 * `connection.authenticate.key {connection_id, key}` (VAULT-MESSAGING 0.88.1 §10.4): the vault followed a rotation chain
 * and moved its pin. The app re-reads the authentication list (authoritative) and leaves `keyChanged` to the verdicts.
 */
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

    /** What `authenticate.list` answers next; every read is counted. */
    private var states: List<AuthenticationState> = emptyList()
    private var reads = 0

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        readConnections = {
            reads++
            SocialManager.ConnectionRead(listOf(FakeSocial.connection("c1", "Sam"), FakeSocial.connection("c2", "Kim")), states)
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun pinned(conn: String, key: String) = AuthenticationState(conn, now, "authenticated", now, key = key)

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
        m.onEvent(keyEvent("c1", k1))
        assertEquals(2, reads)
        assertEquals(k1, m.authentication.value.getValue("c1").key)
        assertFalse(m.authentication.value.getValue("c1").keyChanged)
    }

    /** The list is authoritative: read after the event, it may already be past the event's key. */
    @Test
    fun theListWinsOverTheEventsKey() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k2))
        m.onEvent(keyEvent("c1", k1))
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
        m.onEvent(keyEvent("c1", k2))
        val s = m.authentication.value.getValue("c1")
        assertEquals("re-read", k2, s.key)
        assertTrue("the unverified change is still shown", s.keyChanged)
    }

    @Test
    fun aKeyChangeAfterAKeyEventIsShown() = runTest {
        val m = manager()
        states = listOf(pinned("c1", k1))
        m.onEvent(keyEvent("c1", k1))
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
        m.onEvent(keyEvent("c1", k2))
        m.onEvent(result("c1", k2, changed = false))
        assertFalse(m.authentication.value.getValue("c1").keyChanged)
    }

    @Test
    fun anUnknownConnectionMakesNoStateAndTouchesNoOther() = runTest {
        val m = manager()
        m.withKeyChange()
        val before = m.authentication.value
        m.onEvent(keyEvent("c9", k2))
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
        assertEquals(0, reads)
    }

    @Test
    fun aKeyEventReadAcrossALockWritesNothing() = runTest {
        val m = manager()
        m.withKeyChange()
        states = listOf(pinned("c1", k2))
        m.keyEventHook = { m.forgetOpenVault() }
        m.onEvent(keyEvent("c1", k2))
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
            kotlinx.coroutines.runBlocking { m.refresh() }
            states = stale
        }
        m.onEvent(keyEvent("c1", k2))
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
        m.onEvent(keyEvent("c1", k2))
        m.keyEventHook = null
        assertEquals("as before the hold", held, m.authentication.value)
        // An event taken after the hold began is taken in.
        m.onEvent(keyEvent("c1", k2))
        assertEquals(k2, m.authentication.value.getValue("c1").key)
        assertTrue(m.authentication.value.getValue("c1").keyChanged)
    }
}
