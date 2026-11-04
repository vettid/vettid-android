// Event bodies and fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.Message
import com.vettid.core.vault.MessagePage
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** A read answered after a lock (or a clear) puts nothing back: not the old vault's names, not its older messages. */
@OptIn(ExperimentalCoroutinesApi::class)
class LockRaceTest {
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun msg(id: String) = Message("c1", id, "in", "hi", "2026-10-04T12:00:00Z")

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun o(s: String): JsonObject = VaultJson.json.parseToJsonElement(s).jsonObject

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    private suspend fun SocialManager.batch(rule: String, item: String) =
        onEvent(event("share.pending", """{"rule_id":"$rule","subject":{"connection_id":"c1"},"items":[{"item_id":"$item","name":"$item","category":"other","sensitivity":"data"}]}"""))

    private fun shareEntry(rule: String, item: String) =
        """{"rule_id":"$rule","subject":{"connection_id":"c1"},"item_id":"$item","name":"$item","category":"other","sensitivity":"data"}"""

    private suspend fun listRead(vararg entries: String): SocialManager.ListRead {
        val d = SharePending.decisions(
            pendingPage = { o("""{"pending":[${entries.joinToString(",")}]}""") },
            rulePage = { o("""{"rules":[{"rule_id":"r1","subject":{"connection_id":"c1"},"tags":["medical"]}]}""") },
            names = emptyMap(),
            t = now,
        )
        return SocialManager.ListRead(d, grantsOk = true, criticalOk = true, sharesOk = true)
    }

    private fun SocialManager.shareItems(rule: String) =
        approvals.value.filterIsInstance<Approval.ShareDecision>().firstOrNull { it.ruleId == rule }?.items?.map { it.itemId }

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        readPage = { _, _, before ->
            if (before == null) MessagePage(listOf(msg("m-new")), "b1") else MessagePage(listOf(msg("m-old")), null)
        },
        readConnections = { SocialManager.ConnectionRead(listOf(FakeSocial.connection("c1", "Sam")), null) },
        sendMessage = { _, _ -> com.vettid.core.vault.SentMessage("m-sent", "2026-10-04T12:00:00Z") },
        readRequests = {
            o("""{"incoming":[{"pending_id":"p1","invite_id":"i1","sas":"042817","profile":{"first_name":"Alex","last_name":"Kim"}}],"outgoing":[]}""")
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
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

    @Test
    fun aRequestListReadAcrossALockWritesNoNames() = runTest {
        val m = manager()
        m.requestsHook = { m.forgetOpenVault() }
        m.refreshRequests()
        m.requestsHook = null
        runCurrent()
        assertTrue("no request, no name", m.approvals.value.none { it is Approval.ConnectionRequest })
    }

    @Test
    fun aRequestListReadAcrossAHoldWritesNothing() = runTest {
        val m = manager()
        m.requestsHook = { m.forgetPending() }
        m.refreshRequests()
        m.requestsHook = null
        runCurrent()
        assertTrue(m.approvals.value.none { it is Approval.ConnectionRequest })
        m.refreshRequests() // after the hold the next read is taken in
        runCurrent()
        assertTrue(m.approvals.value.any { it is Approval.ConnectionRequest })
    }

    @Test
    fun anApprovalsReadAcrossAHoldWritesNothing() = runTest {
        val m = manager()
        val r = listRead(shareEntry("r1", "A"))
        m.approvalsHook = { m.forgetPending() }
        m.refreshApprovalsFrom(now) { r }
        m.approvalsHook = null
        runCurrent()
        assertNull("nothing of the stale read", m.shareItems("r1"))
    }

    @Test
    fun aStaleApprovalsReadAfterALockAndUnlockPrunesNoNewEvents() = runTest {
        val m = manager()
        val stale = listRead(shareEntry("r1", "A"))
        m.approvalsHook = {
            m.forgetOpenVault()
            m.reopen()
            // A grant request that arrived after the unlock: the stale list does not hold it, so a prune would drop it.
            runBlocking { m.onEvent(event("grant.pending", """{"request_id":"g1","connection_id":"c1","exp":"2026-10-08T13:00:00.000Z"}""")) }
        }
        m.refreshApprovalsFrom(now) { stale }
        m.approvalsHook = null
        runCurrent()
        assertNull("the stale list is not taken in", m.shareItems("r1"))
        assertTrue("the new grant request stays", m.approvals.value.any { it is Approval.GrantRequest && it.requestId == "g1" })
    }

    @Test
    fun aStalePruneAfterALockAndUnlockDropsNoNewGrant() = runTest {
        val m = manager()
        val stale = listRead(shareEntry("r1", "A"))
        // The list is taken in; then a lock, an unlock and a new grant request come before the prune runs.
        m.pruneHook = {
            m.forgetOpenVault()
            m.reopen()
            runBlocking { m.onEvent(event("grant.pending", """{"request_id":"g2","connection_id":"c1","exp":"2026-10-08T13:00:00.000Z"}""")) }
        }
        m.refreshApprovalsFrom(now) { stale }
        m.pruneHook = null
        runCurrent()
        assertTrue("the new grant request stays", m.approvals.value.any { it is Approval.GrantRequest && it.requestId == "g2" })
    }

    @Test
    fun aSentMessageAcrossALockIsNotWritten() = runTest {
        val m = manager()
        m.sendHook = { m.forgetOpenVault() }
        m.send("c1", "hello")
        m.sendHook = null
        runCurrent()
        assertTrue("no message, no thread entry", m.messages("c1").first().isEmpty())
    }

    @Test
    fun aMessageEventHandledAcrossALockIsNotWritten() = runTest {
        val m = manager()
        m.eventHook = { m.forgetOpenVault() }
        m.onEvent(event("message.new", """{"connection_id":"c1","message_id":"m9","direction":"in","text":"secret","sent_at":"2026-10-04T12:00:00Z"}"""))
        m.eventHook = null
        runCurrent()
        assertTrue(m.messages("c1").first().isEmpty())
    }

    @Test
    fun aMessageEventInTheSameSessionIsWritten() = runTest {
        val m = manager()
        m.onEvent(event("message.new", """{"connection_id":"c1","message_id":"m9","direction":"in","text":"hi","sent_at":"2026-10-04T12:00:00Z"}"""))
        assertEquals(listOf("m9"), m.messages("c1").first().map { it.messageId })
    }

    private val criticalBody =
        """{"request_id":"U1","connection_id":"c1","item_id":"IT","field_id":"F","name":"Key","label":"Seed","operation":"sign","payload":"aGVsbG8=","payload_sha256":"LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=","exp":"2026-10-08T13:00:00.000Z"}"""

    private fun TestScope.criticalManager(onRead: (SocialManager) -> Unit): SocialManager {
        lateinit var m: SocialManager
        m = SocialManager(
            backgroundScope,
            api = { throw VaultFailure(FailureKind.OTHER) },
            credential = credential,
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
            readCriticalUse = { onRead(m); o(criticalBody) },
        )
        return m
    }

    @Test
    fun aCriticalUseReadInTheSameSessionIsKept() = runTest {
        val m = criticalManager { }
        m.loadCriticalUse("U1")
        m.approvals.first { a -> a.any { it.key == "critical:U1" } }
    }

    @Test
    fun aCriticalUseReadAcrossALockKeepsNothing() = runTest {
        val m = criticalManager { it.forgetOpenVault() }
        m.loadCriticalUse("U1")
        runCurrent()
        assertTrue("nothing of the read is kept", m.approvals.value.none { it.key == "critical:U1" })
    }

    @Test
    fun overlappingApprovalsReadsDoNotDeadlock() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val m = SocialManager(
                scope,
                api = { throw VaultFailure(FailureKind.OTHER) },
                credential = credential,
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
            val r = runBlocking {
                m.batch("r1", "A")
                listRead(shareEntry("r1", "A"))
            }
            // Plain daemon threads: a thread blocked entering a monitor cannot be cancelled, so this must fail, not hang.
            val start = CountDownLatch(1)
            val threads = List(4) { n ->
                thread(isDaemon = true, name = "approvals-$n") {
                    start.await()
                    runBlocking {
                        repeat(300) {
                            m.refreshApprovalsFrom(now) { r }
                            m.refreshApprovalsFrom(now) { r }
                            m.batch("r1", "B")
                        }
                    }
                }
            }
            start.countDown()
            val bean = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (threads.any { it.isAlive } && System.nanoTime() < deadline) {
                bean.findDeadlockedThreads()?.let { ids ->
                    val stuck = bean.getThreadInfo(ids, 20).joinToString("\n") { "${it.threadName}: ${it.lockName} held by ${it.lockOwnerName}\n" + it.stackTrace.joinToString("\n") }
                    fail("deadlock:\n$stuck")
                }
                Thread.sleep(100)
            }
            assertTrue("every thread finished", threads.none { it.isAlive })
            // `approvals` is a combined flow on Dispatchers.Default here: wait for it to catch up, in real time.
            runBlocking {
                withTimeout(10_000) { m.approvals.first { m.shareItems("r1")?.toSet() == setOf("A", "B") } }
            }
        } finally {
            scope.cancel()
        }
    }
}
