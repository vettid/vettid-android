package com.vettid.core.data.social

import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.Message
import com.vettid.core.vault.MessagePage
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val asked = mutableListOf<String>()

    private fun TestScope.manager(): SocialManager {
        val m = SocialManager(
            backgroundScope,
            api = { throw VaultFailure(FailureKind.OTHER) },
            credential = credential,
            readPage = { id, _ ->
                asked += id
                if (id in failing) throw VaultFailure(FailureKind.NETWORK)
                MessagePage(listOf(Message(id, "m-$id", "in", "hi", "2026-10-04T12:00:00Z")), null)
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
            assertEquals(FailureKind.NETWORK, e.kind)
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
        assertEquals(FailureKind.NETWORK, m.reloadFailure.first { it != null }?.kind)
        assertFalse("c2" in m.loaded())
        failing.clear()
        m.refreshAllQuietly()
        assertNull(m.reloadFailure.first { it == null })
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
}
