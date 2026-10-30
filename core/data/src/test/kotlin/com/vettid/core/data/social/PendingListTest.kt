// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * VAULT-MESSAGING 0.70.0 §10.4.2 `pending.list`: the list replaces the stored asks of its kinds by ask id, and events are
 * judged against the first page's `ts`, with the orderings that overlapping reads and late events produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingListTest {
    private val base = Instant.parse("2026-10-29T12:00:00Z")
    private fun at(sec: Long): Instant = base.plusSeconds(sec)

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        clock = Clock.fixed(base, ZoneOffset.UTC),
    )

    private fun event(type: String, body: String, ts: Instant) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = body.toByteArray()))

    private fun sync(kind: String, fields: String, ts: Instant) = event("sync.event", """{"kind":"$kind",$fields}""", ts)

    private val exp = "2026-10-29T13:00:00.000Z"
    private fun auth(id: String) = """{"connection_id":"c1","request_id":"$id","exp":"$exp"}"""
    private fun session(id: String, device: String) = """{"request_id":"$id","device_id":"$device","role":"agent","name":"Bot","seconds":3600,"exp":"$exp"}"""
    private fun approval(id: String, device: String = "A") = """{"approval_id":"$id","device_id":"$device","role":"agent","name":"Bot","type":"agent.request","body":{},"exp":"$exp"}"""

    private fun ask(kind: String, body: String) = VaultJson.parseObject("""{"kind":"$kind","body":$body}""".toByteArray())

    private fun page(ts: Instant, vararg asks: JsonObject, next: String? = null) = PendingPage(asks.toList(), next, ts)

    private fun SocialManager.keys() = approvals.value.map { it.key }.sorted()

    @Test
    fun listReplacesTheKindsById() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", auth("r1"), at(1)))
        m.onEvent(event("connection.authenticate.pending", auth("r2"), at(1)))
        m.onEvent(event("device.session.pending", session("s0", "Z"), at(1)))
        runCurrent()
        m.refreshPendingFrom { page(at(10), ask("authenticate", auth("r2")), ask("session", session("s1", "A")), ask("approval", approval("h1"))) }
        runCurrent()
        // r1 is gone (decided elsewhere, expired or withdrawn); s0 too; the rest is listed.
        assertEquals(listOf("auth:r2", "device:approval.pending:h1", "device:device.session.pending:s1"), m.keys())
    }

    @Test
    fun everyPageIsReadAndOnlyTheFirstTsIsTheCut() = runTest {
        val m = manager()
        val asked = mutableListOf<String?>()
        m.refreshPendingFrom { after ->
            asked += after
            when (after) {
                null -> page(at(10), ask("authenticate", auth("r1")), next = "p2")
                "p2" -> page(at(50), ask("authenticate", auth("r2")), next = "p3")
                else -> page(at(60), ask("approval", approval("h1")))
            }
        }
        runCurrent()
        assertEquals(listOf(null, "p2", "p3"), asked)
        assertEquals(listOf("auth:r1", "auth:r2", "device:approval.pending:h1"), m.keys())
        // The cut is 10 (the first page): an event at 20 is later and applies.
        m.onEvent(event("connection.authenticate.pending", auth("r3"), at(20)))
        runCurrent()
        assertTrue("auth:r3" in m.keys())
    }

    @Test
    fun aListThatNeverReachesItsEndIsNotMergedAndThrows() = runTest {
        val m = manager()
        m.refreshPendingFrom { page(at(5), ask("authenticate", auth("r9"))) }
        runCurrent()
        var calls = 0
        try {
            m.refreshPendingFrom { calls++; page(at(10), ask("authenticate", auth("r1")), next = "same") }
            org.junit.Assert.fail("a partial list must not be merged")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        // Started over once (two pages each), then given up; r9, which is not on page 1, survives.
        assertEquals(4, calls)
        runCurrent()
        assertEquals(listOf("auth:r9"), m.keys())
        assertEquals(FailureKind.OTHER, m.pendingReadFailure.value?.kind)
    }

    @Test
    fun aStaleCursorStartsTheReadOverOnce() = runTest {
        val m = manager()
        var first = true
        m.refreshPendingFrom { after ->
            when {
                after == null && first -> page(at(10), ask("authenticate", auth("r1")), next = "c")
                after == null -> page(at(20), ask("authenticate", auth("r2")), next = "d")
                after == "c" -> { first = false; page(at(10), ask("authenticate", auth("r1")), next = "c") }
                else -> page(at(20), ask("approval", approval("h1")))
            }
        }
        runCurrent()
        // The second, whole read counts, with its own first-page ts.
        assertEquals(listOf("auth:r2", "device:approval.pending:h1"), m.keys())
        m.onEvent(event("connection.authenticate.pending", auth("mid"), at(15)))
        runCurrent()
        assertEquals(2, m.keys().size)
    }

    @Test
    fun unknownAndUnshownKindsAreSkipped() = runTest {
        val m = manager()
        m.refreshPendingFrom {
            page(
                at(10),
                ask("action", """{"invocation_id":"i1","connection_id":"c1","action_id":"a","sensitivity":"x","params":{},"exp":"$exp"}"""),
                ask("location", """{"request_id":"l1","connection_id":"c1","exp":"$exp"}"""),
                ask("somethingnew", """{"x":1}"""),
                ask("authenticate", """{"no_request_id":true}"""),
                ask("authenticate", auth("r1")),
            )
        }
        runCurrent()
        assertEquals(listOf("auth:r1"), m.keys())
    }

    @Test
    fun anOlderReleaseKeepsTheLiveEventsUntilTheNextUnlock() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", auth("r1"), at(1)))
        var calls = 0
        val unsupported: suspend (String?) -> PendingPage = { calls++; throw VaultFailure(FailureKind.NOT_SUPPORTED, "unsupported_type") }
        m.refreshPendingFrom(unsupported) // not a failure
        m.refreshPendingFrom(unsupported) // not asked again
        assertEquals(1, calls)
        runCurrent()
        assertEquals(listOf("auth:r1"), m.keys())
        // Live events still apply as before (no cut).
        m.onEvent(sync("connection.authenticate.decided", """"request_id":"r1","approved":true""", at(2)))
        runCurrent()
        assertEquals(emptyList<String>(), m.keys())
        m.forgetOpenVault() // the lock; the next unlock reads again
        m.refreshPendingFrom(unsupported)
        assertEquals(2, calls)
    }

    @Test
    fun otherFailuresAreThrownAndChangeNothing() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", auth("r1"), at(1)))
        try {
            m.refreshPendingFrom { throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required") }
            org.junit.Assert.fail("a held vault must be told")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        runCurrent()
        assertEquals(listOf("auth:r1"), m.keys())
    }

    @Test
    fun eventsNotLaterThanTheCutAreIgnoredCreatingAndClosing() = runTest {
        val m = manager()
        m.refreshPendingFrom { page(at(10), ask("authenticate", auth("r1")), ask("approval", approval("h1"))) }
        // A creation as old as the cut or older is already reflected: ignored. A later one is shown.
        m.onEvent(event("connection.authenticate.pending", auth("old"), at(10)))
        m.onEvent(event("connection.authenticate.pending", auth("new"), at(11)))
        // A closing event as old as the cut is reflected (the ask is in the list, so it is still open); a later one closes.
        m.onEvent(sync("connection.authenticate.decided", """"request_id":"r1","approved":false""", at(10)))
        m.onEvent(sync("approval.decided", """"approval_id":"h1","approved":true""", at(11)))
        runCurrent()
        assertEquals(listOf("auth:new", "auth:r1"), m.keys())
    }

    @Test
    fun aLateCloseOfAnEarlierSessionDoesNotEndTheNewerListedRequest() = runTest {
        val m = manager()
        m.refreshPendingFrom { page(at(10), ask("session", session("s2", "A"))) }
        m.onEvent(sync("device.session", """"device_id":"A","expires_at":"2026-10-29T12:00:05.000Z"""", at(8)))
        runCurrent()
        assertEquals(listOf("device:device.session.pending:s2"), m.keys())
        // A close after the list ends the device's request; another device's stays.
        m.onEvent(event("device.session.pending", session("s3", "B"), at(11)))
        m.onEvent(sync("device.session", """"device_id":"A"""", at(12)))
        runCurrent()
        assertEquals(listOf("device:device.session.pending:s3"), m.keys())
    }

    @Test
    fun aSessionCloseDoesNotEndARequestCreatedAfterIt() = runTest {
        val m = manager()
        // Events in no particular order (§8.4): the new request arrives first, the close of the earlier session after.
        m.onEvent(event("device.session.pending", session("s9", "A"), at(20)))
        m.onEvent(sync("device.session", """"device_id":"A"""", at(15)))
        runCurrent()
        assertEquals(listOf("device:device.session.pending:s9"), m.keys())
    }

    @Test
    fun aCloseOnItsWayDuringAReadIsNotUndoneByTheOlderList() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        launch { m.refreshPendingFrom { gate.await(); page(at(10), ask("authenticate", auth("r1")), ask("session", session("s1", "A"))) } }
        runCurrent()
        m.onEvent(sync("connection.authenticate.decided", """"request_id":"r1","approved":true""", at(12)))
        m.onEvent(sync("device.session", """"device_id":"A"""", at(13)))
        m.onEvent(event("connection.authenticate.pending", auth("fresh"), at(14)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("auth:fresh"), m.keys())
    }

    @Test
    fun aFailedReadDuringAnotherNeverChangesTheCut() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        launch { m.refreshPendingFrom { gate.await(); page(at(10), ask("authenticate", auth("r1"))) } }
        runCurrent()
        try {
            m.refreshPendingFrom { throw VaultFailure(FailureKind.NO_RESPONSE) }
        } catch (_: VaultFailure) {
            // told to the caller
        }
        // No cut yet: an event at 5 still applies.
        m.onEvent(event("connection.authenticate.pending", auth("early"), at(5)))
        gate.complete(Unit)
        runCurrent()
        // The merged read set the cut to 10, and the failed one did not lower it afterwards either.
        try {
            m.refreshPendingFrom { throw VaultFailure(FailureKind.NO_RESPONSE) }
        } catch (_: VaultFailure) {
            // told to the caller
        }
        m.onEvent(event("connection.authenticate.pending", auth("late"), at(9)))
        m.onEvent(event("connection.authenticate.pending", auth("later"), at(11)))
        runCurrent()
        // "early" (ts 5, before the list at 10, not in it) is gone; "late" (9) is reflected; "later" (11) is shown.
        assertEquals(listOf("auth:later", "auth:r1"), m.keys())
    }

    @Test
    fun anOlderListNeverReplacesANewerOne() = runTest {
        val m = manager()
        val slow = CompletableDeferred<Unit>()
        launch { m.refreshPendingFrom { slow.await(); page(at(10), ask("authenticate", auth("old"))) } }
        runCurrent()
        m.refreshPendingFrom { page(at(20), ask("authenticate", auth("new"))) }
        runCurrent()
        assertEquals(listOf("auth:new"), m.keys())
        slow.complete(Unit)
        runCurrent()
        assertEquals(listOf("auth:new"), m.keys())
        // The cut stays 20: an event at 15 is reflected.
        m.onEvent(event("connection.authenticate.pending", auth("mid"), at(15)))
        runCurrent()
        assertEquals(listOf("auth:new"), m.keys())
    }

    @Test
    fun aListThatStartedEarlierButCameBackLaterWithAnOlderTsIsDropped() = runTest {
        val m = manager()
        m.refreshPendingFrom { page(at(20), ask("authenticate", auth("new"))) }
        m.refreshPendingFrom { page(at(10), ask("authenticate", auth("old"))) }
        runCurrent()
        assertEquals(listOf("auth:new"), m.keys())
    }

    @Test
    fun anEventNewerThanTheListSurvivesTheMerge() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        launch { m.refreshPendingFrom { gate.await(); page(at(10), ask("authenticate", auth("r1"))) } }
        runCurrent()
        m.onEvent(event("approval.pending", approval("h9"), at(12)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("auth:r1", "device:approval.pending:h9"), m.keys())
    }

    @Test
    fun aLockStartsAgain() = runTest {
        val m = manager()
        m.refreshPendingFrom { page(at(10), ask("authenticate", auth("r1"))) }
        m.forgetOpenVault()
        m.reopen() // the next unlock opens it again
        // No cut after the lock: an event at 5 applies.
        m.onEvent(event("connection.authenticate.pending", auth("r2"), at(5)))
        runCurrent()
        assertTrue("auth:r2" in m.keys())
    }

    @Test
    fun aCloseThatCameFirstRefusesItsCreationOutsideAnyRead() = runTest {
        val m = manager()
        m.onEvent(sync("connection.authenticate.decided", """"request_id":"r1","approved":true""", at(12)))
        m.onEvent(event("connection.authenticate.pending", auth("r1"), at(11)))
        m.onEvent(sync("device.session", """"device_id":"A"""", at(12)))
        m.onEvent(event("device.session.pending", session("s1", "A"), at(11)))
        // A session request after the close is a new one.
        m.onEvent(event("device.session.pending", session("s2", "A"), at(13)))
        runCurrent()
        assertEquals(listOf("device:device.session.pending:s2"), m.keys())
    }

    @Test
    fun aLockMidReadDiscardsThatRead() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        launch { m.refreshPendingFrom { gate.await(); page(at(10), ask("authenticate", auth("r1"))) } }
        runCurrent()
        m.forgetOpenVault()
        gate.complete(Unit)
        runCurrent()
        assertEquals(emptyList<String>(), m.keys())
        m.reopen() // the next unlock opens it again
        // No cut was set: an event at 5 applies.
        m.onEvent(event("connection.authenticate.pending", auth("r2"), at(5)))
        runCurrent()
        assertEquals(listOf("auth:r2"), m.keys())
    }

    @Test
    fun anEarlierStartedReadWithTheNewerTsLosesToALaterStartedOneThatMergedFirst() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        launch { m.refreshPendingFrom { gate.await(); page(at(30), ask("authenticate", auth("a"))) } }
        runCurrent()
        m.refreshPendingFrom { page(at(20), ask("authenticate", auth("b"))) }
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("auth:b"), m.keys())
        // The cut is the merged list's 20, not the dropped list's 30.
        m.onEvent(event("connection.authenticate.pending", auth("c"), at(25)))
        runCurrent()
        assertEquals(listOf("auth:b", "auth:c"), m.keys())
    }

    @Test
    fun unsupportedDuringAnotherReadKeepsTheLiveEventsAndClearsTheNotice() = runTest {
        val m = manager()
        m.onEvent(event("connection.authenticate.pending", auth("live"), at(1)))
        try {
            m.refreshPendingFrom { throw VaultFailure(FailureKind.NETWORK) }
        } catch (_: VaultFailure) {
            // noted
        }
        assertEquals(FailureKind.NETWORK, m.pendingReadFailure.value?.kind)
        val gate = CompletableDeferred<Unit>()
        val unsupported = VaultFailure(FailureKind.NOT_SUPPORTED, "unsupported_type")
        launch { m.refreshPendingFrom { gate.await(); throw unsupported } }
        runCurrent()
        m.refreshPendingFrom { throw unsupported }
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("auth:live"), m.keys())
        assertEquals(null, m.pendingReadFailure.value)
    }

    @Test
    fun aStaleFailureDoesNotOverwriteANewerSuccess() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        var thrown: VaultFailure? = null
        launch {
            try {
                m.refreshPendingFrom { gate.await(); throw VaultFailure(FailureKind.NETWORK) }
            } catch (e: VaultFailure) {
                thrown = e
            }
        }
        runCurrent()
        m.refreshPendingFrom { page(at(10), ask("authenticate", auth("r1"))) }
        gate.complete(Unit)
        runCurrent()
        // The older read still reports its failure to its caller, but the notice stays cleared by the newer merge.
        assertEquals(FailureKind.NETWORK, thrown?.kind)
        assertEquals(null, m.pendingReadFailure.value)
        assertEquals(listOf("auth:r1"), m.keys())
    }

    @Test
    fun aFailedPendingReadStillReadsTheOtherListsThenThrows() = runTest {
        val m = manager()
        var listsRead = false
        try {
            m.refreshAllLists(
                base,
                pendingRead = { throw VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required") },
                lists = { listsRead = true; SocialManager.ListRead(emptyList(), grantsOk = true, criticalOk = true, sharesOk = true) },
            )
            org.junit.Assert.fail("the failure must reach the screen")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        assertTrue(listsRead)
    }

    @Test
    fun aSessionEventWithExpiresAtClosesOnlyTheRequestNotTheHeldApprovals() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session("s1", "A"), at(1)))
        m.onEvent(event("approval.pending", approval("h1", "A"), at(1)))
        // A deny of a renewal keeps the session and its expires_at: only the request closes.
        m.onEvent(sync("device.session", """"device_id":"A","expires_at":"2026-10-29T14:00:00.000Z"""", at(2)))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h1"), m.keys())
        // The held approval closes with its own approval.decided (0.77.0).
        m.onEvent(sync("approval.decided", """"approval_id":"h1","approved":false""", at(3)))
        runCurrent()
        assertEquals(emptyList<String>(), m.keys())
    }

    /**
     * The creation must be judged when its write runs, not before: an unrelated write holds the lock (blocked in the edit
     * hook), the merge of a list at 12 queues, then K (ts 10) arrives; judged on arrival (cut 5) it would pass and be
     * written after the merge.
     */
    @Test
    fun aCreationQueuedBehindAMergeIsJudgedAgainstTheNewCut() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val inEdit = java.util.concurrent.CountDownLatch(1)
        val gated = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            val m = SocialManager(
                scope,
                api = { throw VaultFailure(FailureKind.OTHER) },
                credential = credential,
                clock = Clock.fixed(base, ZoneOffset.UTC),
            )
            m.editHook = {
                if (gated.getAndSet(false)) {
                    inEdit.countDown()
                    gate.await()
                }
            }
            kotlinx.coroutines.runBlocking {
                m.refreshPendingFrom { page(at(5)) }
                gated.set(true)
                // An unrelated write holds the lock (blocked inside its edit).
                val other = scope.launch { m.onEvent(event("connection.authenticate.pending", auth("y"), at(6))) }
                inEdit.await()
                // The merge of a list at 12 (without K) queues on the lock first...
                val merge = scope.launch { m.refreshPendingFrom { page(at(12)) } }
                kotlinx.coroutines.delay(200)
                // ...then K (ts 10) arrives: judged now (cut 5) it would pass, and be written after the merge.
                val create = scope.launch { m.onEvent(event("connection.authenticate.pending", auth("K"), at(10))) }
                kotlinx.coroutines.delay(200)
                gate.countDown()
                other.join(); merge.join(); create.join()
                m.onEvent(sync("connection.authenticate.decided", """"request_id":"K","approved":true""", at(11)))
                kotlinx.coroutines.delay(200)
                assertEquals(emptyList<String>(), m.keys())
            }
        } finally {
            scope.cancel()
        }
    }
}
