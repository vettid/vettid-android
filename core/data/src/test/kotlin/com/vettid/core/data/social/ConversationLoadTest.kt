package com.vettid.core.data.social

import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.Message
import com.vettid.core.vault.MessagePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A thread whose newest page was never read is "not loaded", not "empty"; one thread's failure stops no other; and a
 * quiet reload that fails is signalled (no silent failure).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationLoadTest {
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    /** Connections whose page fails; the others answer one message. */
    private val failing = mutableSetOf<String>()

    /** What a failing thread answers: a thread's own error by default. */
    private var failWith = FailureKind.OTHER
    private var failCode: String? = null
    private val asked = mutableListOf<String>()
    private val first1 = java.util.concurrent.atomic.AtomicBoolean(true)

    /** Completed when the held c1 read reaches the vault. */
    private val heldStarted = CompletableDeferred<Unit>()

    private fun refuse(id: String, held: Set<String>) {
        val kind = if (id in held) FailureKind.OWNER_CHECK_REQUIRED else failWith.takeIf { id in failing }
        if (kind != null) throw VaultFailure(kind, failCode)
    }

    private fun TestScope.manager(
        held: Set<String> = emptySet(),
        gate: CompletableDeferred<Unit>? = null,
        oldGate: CompletableDeferred<Unit>? = null,
    ): SocialManager {
        val m = SocialManager(
            backgroundScope,
            api = { throw VaultFailure(FailureKind.OTHER) },
            credential = credential,
            readPage = { id, _, _ ->
                val holdThis = id == "c1" && oldGate != null && first1.compareAndSet(true, false)
                synchronized(asked) { asked += id }
                gate?.await()
                if (holdThis) {
                    heldStarted.complete(Unit)
                    oldGate?.await()
                }
                refuse(id, held)
                // The held (pre-lock) read answers another message than the new session's.
                val mid = if (holdThis) "m-old" else "m-$id"
                MessagePage(listOf(Message(id, mid, "in", "hi", "2026-10-04T12:00:00Z")), null)
            },
        )
        m.seedConnections(listOf("Sam", "Alex", "Jo").mapIndexed { i, n -> FakeSocial.connection("c${i + 1}", n) })
        return m
    }

    private fun SocialManager.loaded() = conversations.value.filter { it.loaded }.map { it.connection.id }.sorted()

    @Test
    fun beforeAnyLoadNoThreadIsLoaded() = runTest {
        val m = manager()
        runCurrent()
        assertEquals(3, m.conversations.value.size)
        assertTrue(m.loaded().isEmpty())
    }

    @Test
    fun aFailedThreadIsNotLoadedAndTheOthersStillLoad() = runTest {
        val m = manager()
        failing += "c1"
        try {
            m.refreshConversations()
            fail("the failure is thrown")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        runCurrent()
        assertEquals(listOf("c1", "c2", "c3"), asked)
        assertEquals(listOf("c2", "c3"), m.loaded())
        assertNull(m.conversations.value.single { it.connection.id == "c1" }.last)
    }

    @Test
    fun aLoadedThreadWithNoMessagesIsLoadedAndEmpty() = runTest {
        val m = manager()
        m.load("c2")
        runCurrent()
        assertEquals(listOf("c2"), m.loaded())
    }

    @Test
    fun theClearForgetsWhatWasLoaded() = runTest {
        val m = manager()
        m.refreshConversations()
        runCurrent()
        assertEquals(3, m.loaded().size)
        m.forgetOpenVault()
        m.seedConnections(listOf(FakeSocial.connection("c1", "Sam")))
        runCurrent()
        assertTrue(m.loaded().isEmpty())
    }

    @Test
    fun aQuietReloadFailureIsSignalledAndAGoodReloadClearsIt() = runTest {
        val m = manager()
        failing += "c2"
        m.refreshAllQuietly()
        // The reads run on the IO dispatcher (vaultGuard), so the test waits for the signal itself.
        assertEquals(FailureKind.OTHER, m.reloadFailure.first { it != null }?.kind)
        // Once the summaries caught up with the pages that did load: c1 and c3 are loaded, c2 is not.
        m.conversations.first { s -> s.count { it.loaded } == 2 }
        assertEquals(listOf("c1", "c3"), m.loaded())
        failing.clear()
        m.refreshAllQuietly()
        assertNull(m.reloadFailure.first { it == null })
        m.conversations.first { s -> s.all { it.loaded } }
        assertEquals(listOf("c1", "c2", "c3"), m.loaded())
    }

    @Test
    fun aLockClearsTheSignal() = runTest {
        val m = manager()
        failing += "c2"
        m.refreshAllQuietly()
        m.reloadFailure.first { it != null }
        m.forgetOpenVault()
        assertNull(m.reloadFailure.value)
    }

    @Test
    fun aHeldFailureWinsOverAnotherOne() = runTest {
        val m = manager(held = setOf("c3"))
        failing += "c1"
        try {
            m.refreshConversations()
            fail("thrown")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.reloadFailure.value?.kind)
    }

    @Test
    fun everyCallerOfARefreshSetsAndClearsTheSameFailure() = runTest {
        val m = manager()
        failing += "c2"
        try {
            m.refreshConversations()
        } catch (_: VaultFailure) {
            // noted below
        }
        assertEquals(FailureKind.OTHER, m.reloadFailure.value?.kind)
        failing.clear()
        m.refreshConversations()
        assertNull(m.reloadFailure.value)
    }

    @Test
    fun aSingleLoadThatCompletesTheListClearsTheFailure() = runTest {
        val m = manager()
        failing += "c2"
        try {
            m.refreshConversations()
        } catch (_: VaultFailure) {
            // noted
        }
        failing.clear()
        m.load("c2")
        assertNull(m.reloadFailure.value)
    }

    @Test
    fun aReloadInFlightAcrossALockWritesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val m = manager(gate = gate)
        failing += "c2"
        val job = launch { runCatching { m.refreshConversations() } }
        runCurrent()
        m.forgetOpenVault()
        m.seedConnections(listOf(FakeSocial.connection("c1", "Sam")))
        gate.complete(Unit)
        job.join()
        assertNull("no failure after the clear", m.reloadFailure.value)
        assertTrue("and no page written back", m.loaded().isEmpty())
    }

    @Test
    fun anOldReadThatLandsAfterTheNewSessionLoadedTheSameIdLeavesTheNewDataAlone() = runTest {
        val old = CompletableDeferred<Unit>()
        val m = manager(oldGate = old)
        val job = launch { runCatching { m.load("c1") } } // the pre-lock read, held at the vault
        runCurrent()
        heldStarted.await() // the read is at the vault now
        m.forgetOpenVault()
        m.seedConnections(listOf(FakeSocial.connection("c1", "Sam")))
        m.load("c1") // the new session's own page (not held: the old read took the gate)
        // `conversations` is a combined flow: right after the reseed it can still emit the old three rows, so wait for the one.
        val before = m.conversations.first { s -> s.singleOrNull()?.loaded == true }.single().last
        assertEquals("m-c1", before?.messageId)
        old.complete(Unit)
        job.join()
        runCurrent()
        val row = m.conversations.first { it.size == 1 }.single()
        assertTrue("still loaded", row.loaded)
        assertEquals("m-c1", row.last?.messageId)
        assertEquals("the old page is absent", listOf("m-c1"), m.messages("c1").first().map { it.messageId })
    }

    @Test
    fun aFailureEveryThreadWouldMeetStopsTheRequests() = runTest {
        val m = manager()
        failing += "c1"
        failWith = FailureKind.NETWORK
        try {
            m.refreshConversations()
            fail("thrown")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NETWORK, e.kind)
        }
        assertEquals("no more requests after the transport failed", listOf("c1"), asked)
        assertEquals(FailureKind.NETWORK, m.reloadFailure.value?.kind)
    }

    @Test
    fun aRestartingVaultOnTheFirstThreadStillLoadsTheOthers() = runTest {
        val m = manager()
        failing += "c1"
        failWith = FailureKind.VAULT_UNAVAILABLE
        try {
            m.refreshConversations()
            fail("thrown")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.VAULT_UNAVAILABLE, e.kind)
        }
        assertEquals(listOf("c1", "c2", "c3"), asked)
        m.conversations.first { s -> s.count { it.loaded } == 2 }
        assertEquals(listOf("c2", "c3"), m.loaded())
    }

    @Test
    fun aPlainNoAnswerDoesNotStopButALockPendingDoes() = runTest {
        val m = manager()
        failing += "c1"
        failWith = FailureKind.NO_RESPONSE
        runCatching { m.refreshConversations() }
        assertEquals(listOf("c1", "c2", "c3"), asked)
        asked.clear()
        failWith = FailureKind.OTHER // through the API a pending lock arrives as OTHER with the code
        failCode = com.vettid.core.data.vault.MoveRepository.CODE_LOCK_PENDING
        runCatching { m.refreshConversations() }
        assertEquals(listOf("c1"), asked)
    }

    @Test
    fun aPageReadAcrossALockInTheGapWritesNothing() = runTest {
        val m = manager()
        m.pageHook = { m.forgetOpenVault() }
        m.seedConnections(listOf(FakeSocial.connection("c1", "Sam")))
        m.load("c1")
        m.pageHook = null
        runCurrent()
        assertTrue("not loaded", m.loaded().isEmpty())
        assertTrue("no messages", m.messages("c1").first().isEmpty())
    }

    @Test
    fun aLoadOfARowThatWasLoadedBeforeKeepsTheReloadFailure() = runTest {
        val m = manager()
        m.refreshConversations() // every row loaded
        failing += "c1"
        runCatching { m.refreshConversations() } // c1's reload fails: noted, though its row stays loaded
        assertEquals(FailureKind.OTHER, m.reloadFailure.value?.kind)
        failing.clear()
        m.load("c2") // opening one conversation
        assertEquals("the card stays", FailureKind.OTHER, m.reloadFailure.value?.kind)
    }

    @Test
    fun aHeldAnswerStopsTheRequestsToo() = runTest {
        val m = manager(held = setOf("c1"))
        try {
            m.refreshConversations()
            fail("thrown")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        assertEquals(listOf("c1"), asked)
    }

    @Test
    fun aThreadsOwnErrorDoesNotStopTheOthers() = runTest {
        val m = manager()
        failing += "c1"
        failWith = FailureKind.NOT_FOUND
        try {
            m.refreshConversations()
        } catch (_: VaultFailure) {
            // noted
        }
        assertEquals(listOf("c1", "c2", "c3"), asked)
    }

    @Test
    fun aServiceThreadIsNeverRequestedAndNeverFailsTheList() = runTest {
        val m = manager()
        m.seedConnections(listOf(FakeSocial.connection("c1", "Bank").copy(service = true), FakeSocial.connection("c2", "Alex")))
        failing += "c1"
        m.refreshConversations()
        assertEquals(listOf("c2"), asked)
        assertNull(m.reloadFailure.value)
    }

    @Test
    fun reloadingIsTrueWhileAReloadRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val m = manager(gate = gate)
        val job = launch { runCatching { m.refreshConversations() } }
        runCurrent()
        assertTrue(m.reloading.value)
        gate.complete(Unit)
        job.join()
        assertFalse(m.reloading.value)
    }
}
