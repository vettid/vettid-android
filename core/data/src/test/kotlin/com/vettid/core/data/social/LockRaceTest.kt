package com.vettid.core.data.social

import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.Message
import com.vettid.core.vault.MessagePage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** A read answered after a lock (or a clear) puts nothing back: not the old vault's names, not its older messages. */
@OptIn(ExperimentalCoroutinesApi::class)
class LockRaceTest {
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun msg(id: String) = Message("c1", id, "in", "hi", "2026-10-04T12:00:00Z")

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        readPage = { _, _, before ->
            if (before == null) MessagePage(listOf(msg("m-new")), "b1") else MessagePage(listOf(msg("m-old")), null)
        },
        readConnections = { SocialManager.ConnectionRead(listOf(FakeSocial.connection("c1", "Sam")), null) },
    )

    @Test
    fun aConnectionListReadAcrossALockWritesNoNames() = runTest {
        val m = manager()
        m.refreshHook = { m.forgetOpenVault() }
        m.refresh()
        m.refreshHook = null
        assertTrue("no connection, no name", m.connections.value.isEmpty())
        m.refresh() // the next session reads for itself
        assertEquals(listOf("c1"), m.connections.value.map { it.id })
    }

    @Test
    fun aConnectionListReadAcrossAClearWritesNoNames() = runTest {
        val m = manager()
        m.refreshHook = { m.clear() }
        m.refresh()
        assertTrue(m.connections.value.isEmpty())
    }

    @Test
    fun anOlderPageReadAcrossALockWritesNothingIntoTheNewSession() = runTest {
        val m = manager()
        m.seedConnections(listOf(FakeSocial.connection("c1", "Sam")))
        m.load("c1") // nextBefore b1: older messages are available
        // The lock comes while the older page is read; the new session loads c1 again (the same position b1).
        m.olderHook = {
            m.forgetOpenVault()
            m.seedConnections(listOf(FakeSocial.connection("c1", "Sam")))
            runBlocking { m.load("c1") }
        }
        m.loadOlder("c1")
        m.olderHook = null
        runCurrent()
        assertEquals("the old older page is absent", listOf("m-new"), m.messages("c1").first().map { it.messageId })
    }
}
