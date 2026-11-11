// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * (GC #7478; VAULT-MESSAGING 0.88.7 §3.4.1 "Stale is not final") The peer's rekey or reconnect makes a `stale` connection
 * active again: its `connection.event` (`rekeyed` or `reconnected`) shows it active at once, and the `connection.list`
 * re-read that follows confirms it, or corrects it if the vault still says `stale`; a re-read alone does the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveAgainTest {
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private val now = Instant.parse("2026-11-10T12:00:00Z")

    /** What `connection.list` answers next; [gate], when set, holds the read (a read in flight). */
    private var listed = listOf(FakeSocial.connection("c1", "Sam", state = ConnectionState.STALE), FakeSocial.connection("c2", "Kim", state = ConnectionState.STALE))
    private var gate: CompletableDeferred<Unit>? = null

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        readConnections = {
            gate?.await()
            SocialManager.ConnectionRead(listed, emptyList())
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
        readContext = EmptyCoroutineContext,
    )

    private fun event(conn: String, what: String) =
        VaultMessage(Inner(id = Ulid.new(), type = "connection.event", ts = now, body = """{"connection_id":"$conn","event":"$what"}""".toByteArray()))

    private fun SocialManager.stateOf(id: String) = connections.value.single { it.id == id }.state

    private fun stale(id: String) = FakeSocial.connection(id, id, state = ConnectionState.STALE)

    private fun active(id: String) = FakeSocial.connection(id, id)

    @Test
    fun aReconnectOrARekeyShowsAStaleConnectionActiveAtOnceAndTheReReadConfirmsIt() = runTest {
        for (what in listOf("reconnected", "rekeyed")) {
            listed = listOf(stale("c1"), stale("c2"))
            val m = manager()
            m.refresh()
            assertEquals(ConnectionState.STALE, m.stateOf("c1"))
            val read = CompletableDeferred<Unit>()
            gate = read
            listed = listOf(active("c1"), stale("c2")) // what the vault says now
            val job = launch { m.onEvent(event("c1", what)) }
            runCurrent()
            assertEquals("$what: active at once, before the re-read", ConnectionState.ACTIVE, m.stateOf("c1"))
            assertEquals("$what: only that connection", ConnectionState.STALE, m.stateOf("c2"))
            read.complete(Unit)
            job.join()
            assertEquals("$what: confirmed", ConnectionState.ACTIVE, m.stateOf("c1"))
            gate = null
        }
    }

    @Test
    fun theReReadCorrectsItWhileTheVaultStillSaysStale() = runTest {
        val m = manager()
        m.refresh()
        val read = CompletableDeferred<Unit>()
        gate = read
        val job = launch { m.onEvent(event("c1", "rekeyed")) }
        runCurrent()
        assertEquals(ConnectionState.ACTIVE, m.stateOf("c1"))
        read.complete(Unit) // the list still says stale
        job.join()
        assertEquals("corrected", ConnectionState.STALE, m.stateOf("c1"))
    }

    @Test
    fun aListReReadAloneClearsIt() = runTest {
        val m = manager()
        m.refresh()
        assertEquals(ConnectionState.STALE, m.stateOf("c1"))
        listed = listOf(active("c1"), stale("c2"))
        m.refresh()
        assertEquals(ConnectionState.ACTIVE, m.stateOf("c1"))
    }

    /** As vettid-ios's `aPendingOrOtherConnectionIsLeftAloneAtARekeyOrReconnect`: only a `stale` connection is revived. */
    @Test
    fun aPendingOrOtherConnectionIsLeftAloneAtARekeyOrReconnect() = runTest {
        val states = mapOf("p" to ConnectionState.PENDING, "b" to ConnectionState.BLOCKED, "o" to ConnectionState.OTHER)
        listed = states.map { (id, st) -> FakeSocial.connection(id, id, state = st) }
        val m = manager()
        m.refresh()
        for ((id, st) in states) {
            for (what in listOf("rekeyed", "reconnected")) {
                val read = CompletableDeferred<Unit>()
                gate = read
                val job = launch { m.onEvent(event(id, what)) }
                runCurrent()
                assertEquals("$id $what: left alone before the read lands", st, m.stateOf(id))
                read.complete(Unit)
                job.join()
                gate = null
            }
        }
    }

    /** The other `connection.event`s change no state before the re-read. */
    @Test
    fun anotherEventChangesNoState() = runTest {
        val m = manager()
        m.refresh()
        for (what in listOf("profile", "added", "stale", "failed", "removed")) {
            val read = CompletableDeferred<Unit>()
            gate = read
            val job = launch { m.onEvent(event("c1", what)) }
            runCurrent()
            assertEquals(what, ConnectionState.STALE, m.stateOf("c1"))
            read.complete(Unit)
            job.join()
            gate = null
        }
    }
}
