// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ANDROID-PLAN 0.1.145: a desktop's held block or removal names its connection from `connection.list` (active or stale, with
 * names the app holds), and is approved only then; otherwise it stays deny-only, enforced where it is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // the name key folds case with ICU
@Config(sdk = [36])
class DesktopHeldConnectionFlowTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private class Ops : DeviceDecisionOps {
        val calls = mutableListOf<String>()

        override suspend fun sessionApprove(requestId: String, seconds: Int?): String? = null

        override suspend fun sessionDeny(requestId: String) = Unit

        override suspend fun pairReject(pendingId: String) = Unit

        override suspend fun approvalDecide(approvalId: String, approve: Boolean): String? {
            calls += "approval.decide:$approvalId:$approve"
            return "ok"
        }
    }

    private val ops = Ops()
    private var listed = listOf<ConnectionInfo>()

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        deviceOps = { ops },
        readConnections = { SocialManager.ConnectionRead(listed, null) },
    )

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    private fun held(id: String, type: String, body: String) =
        """{"approval_id":"$id","device_id":"desk1","role":"desktop","name":"Laptop","type":"$type","body":$body,"exp":"2026-11-26T12:05:00.000Z"}"""

    private fun req(m: SocialManager, id: String) = m.approvals.value.single { it.key == "device:approval.pending:$id" } as Approval.DeviceRequest

    @Test
    fun aListedConnectionIsNamedAndTheRequestApproved() = runTest {
        listed = listOf(
            FakeSocial.connection("c1", "Sam", last = "Lee"),
            FakeSocial.connection("c2", "Ada", state = ConnectionState.STALE, last = "Ng"),
            FakeSocial.connection("svc", "Shop").copy(firstName = null, lastName = null, service = true),
        )
        val m = manager()
        m.refresh()
        m.onEvent(event("approval.pending", held("h1", "block.add", """{"connection_id":"c1","note":"spam"}""")))
        m.onEvent(event("approval.pending", held("h2", "connection.remove", """{"connection_id":"c2"}""")))
        m.onEvent(event("approval.pending", held("h3", "connection.remove", """{"connection_id":"svc"}""")))
        runCurrent()
        assertEquals("Sam Lee", req(m, "h1").heldConnection)
        assertFalse(req(m, "h1").heldService)
        assertEquals("a stale connection is named too", "Ada Ng", req(m, "h2").heldConnection)
        assertEquals("a service by its name", "Shop", req(m, "h3").heldConnection)
        assertTrue(req(m, "h3").heldService)
        // The entry is the desktop's: what names an entry generically (the approvals indicator) names the desktop.
        assertNull(req(m, "h1").connectionName)
        assertEquals("Laptop", OpenApprovalRules.of(m.approvals.value.filter { it.key.endsWith(":h1") }, now).critical?.label)
        m.approveDeviceRequest("device:approval.pending:h1", null)
        assertEquals(listOf("approval.decide:h1:true"), ops.calls)
    }

    @Test
    fun aListedEntryThatDiffersFromItsEventIsTheDesktopDenyOnlyRow() = runTest {
        listed = listOf(FakeSocial.connection("c1", "Sam", last = "Lee"), FakeSocial.connection("c2", "Ada", last = "Ng"))
        val m = manager()
        m.refresh()
        m.onEvent(event("approval.pending", held("h1", "block.add", """{"connection_id":"c1"}""")))
        runCurrent()
        val other = held("h1", "block.add", """{"connection_id":"c2"}""")
        m.refreshPendingFrom { PendingPage(listOf(VaultJson.parseObject("""{"kind":"approval","body":$other}""".toByteArray())), null, now, listOf(other)) }
        runCurrent()
        assertEquals(HeldRequest.DesktopStepUp, req(m, "h1").held)
        assertNull(req(m, "h1").heldConnection)
        val refused = runCatching { m.approveDeviceRequest("device:approval.pending:h1", null) }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, refused?.kind)
        assertTrue(ops.calls.isEmpty())
    }

    /** Block with a note, block without, removal: the three forms, each against each kind of listed connection. */
    private val forms = listOf(
        "block.add" to """{"connection_id":"%s","note":"spam"}""",
        "block.add" to """{"connection_id":"%s"}""",
        "connection.remove" to """{"connection_id":"%s"}""",
    )

    @Test
    fun everyFormIsNamedAndApprovedForEveryListedKind() = runTest {
        listed = listOf(
            FakeSocial.connection("act", "Sam", last = "Lee"),
            FakeSocial.connection("stale", "Ada", state = ConnectionState.STALE, last = "Ng"),
            FakeSocial.connection("svc", "Shop").copy(firstName = null, lastName = null, service = true),
        )
        val want = mapOf("act" to ("Sam Lee" to false), "stale" to ("Ada Ng" to false), "svc" to ("Shop" to true))
        val m = manager()
        m.refresh()
        var n = 0
        for ((type, body) in forms) for ((cid, named) in want) {
            val id = "h${n++}"
            m.onEvent(event("approval.pending", held(id, type, body.format(cid))))
            runCurrent()
            assertEquals("$type $cid", named.first, req(m, id).heldConnection)
            assertEquals("$type $cid", named.second, req(m, id).heldService)
            m.approveDeviceRequest("device:approval.pending:$id", null)
            assertEquals("$type $cid", "approval.decide:$id:true", ops.calls.last())
        }
    }

    @Test
    fun anUnresolvedConnectionIsNeverApprovedInAnyForm() = runTest {
        listed = listOf(
            FakeSocial.connection("pending", "Pat", state = ConnectionState.PENDING),
            FakeSocial.connection("blocked", "Bo", state = ConnectionState.BLOCKED),
            FakeSocial.connection("nameless", "Nell").copy(firstName = null, lastName = null),
        )
        val m = manager()
        m.refresh()
        var n = 0
        for ((type, body) in forms) for (cid in listOf("unknown", "pending", "blocked", "nameless")) {
            val id = "u${n++}"
            m.onEvent(event("approval.pending", held(id, type, body.format(cid))))
            runCurrent()
            assertNull("$type $cid", req(m, id).heldConnection)
            val refused = runCatching { m.approveDeviceRequest("device:approval.pending:$id", null) }.exceptionOrNull() as? VaultFailure
            assertEquals("$type $cid", FailureKind.NOT_SUPPORTED, refused?.kind)
        }
        assertTrue("nothing was sent", ops.calls.isEmpty())
    }

    @Test
    fun namesakesCarryTheirOwnDatesAndTheSameDayIsRefused() = runTest {
        val zone = java.time.ZoneId.systemDefault()
        fun day(c: ConnectionInfo) = c.createdAt!!.atZone(zone).toLocalDate()
        val a = FakeSocial.connection("c1", "Sam", last = "Lee").copy(createdAt = Instant.parse("2026-11-03T12:00:00Z"))
        val b = FakeSocial.connection("c2", "Sam", last = "Lee").copy(createdAt = Instant.parse("2026-11-10T12:00:00Z"))
        listed = listOf(a, b)
        val m = manager()
        m.refresh()
        m.onEvent(event("approval.pending", held("h1", "block.add", """{"connection_id":"c1"}""")))
        m.onEvent(event("approval.pending", held("h2", "connection.remove", """{"connection_id":"c2"}""")))
        runCurrent()
        assertEquals(day(a), req(m, "h1").heldConnectedOn)
        assertEquals(day(b), req(m, "h2").heldConnectedOn)
        m.approveDeviceRequest("device:approval.pending:h1", null)
        m.approveDeviceRequest("device:approval.pending:h2", null)
        assertEquals(listOf("approval.decide:h1:true", "approval.decide:h2:true"), ops.calls)
        // Connected the same day: neither can be told apart, so both are unnamed and refused.
        listed = listOf(a, b.copy(createdAt = a.createdAt))
        m.refresh()
        m.onEvent(event("approval.pending", held("h3", "block.add", """{"connection_id":"c1"}""")))
        runCurrent()
        assertNull(req(m, "h3").heldConnection)
        assertNull(req(m, "h3").heldConnectedOn)
        val refused = runCatching { m.approveDeviceRequest("device:approval.pending:h3", null) }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, refused?.kind)
        assertEquals(2, ops.calls.size)
    }

    @Test
    fun anUnresolvedConnectionIsNeverApproved() = runTest {
        listed = listOf(
            FakeSocial.connection("pending", "Pat", state = ConnectionState.PENDING),
            FakeSocial.connection("blocked", "Bo", state = ConnectionState.BLOCKED),
            // A person whose names the app does not hold yet.
            FakeSocial.connection("nameless", "Nell").copy(firstName = null, lastName = null),
        )
        val m = manager()
        m.refresh()
        for ((id, cid) in listOf("h1" to "unknown", "h2" to "pending", "h3" to "blocked", "h4" to "nameless")) {
            m.onEvent(event("approval.pending", held(id, "block.add", """{"connection_id":"$cid"}""")))
        }
        runCurrent()
        for (id in listOf("h1", "h2", "h3", "h4")) {
            assertNull(id, req(m, id).heldConnection)
            val refused = runCatching { m.approveDeviceRequest("device:approval.pending:$id", null) }.exceptionOrNull() as? VaultFailure
            assertEquals(id, FailureKind.NOT_SUPPORTED, refused?.kind)
        }
        assertTrue("nothing was sent", ops.calls.isEmpty())
        // Deny is always offered and sent.
        m.declineDeviceRequest("device:approval.pending:h1")
        assertEquals(listOf("approval.decide:h1:false"), ops.calls)
    }

    @Test
    fun aConnectionThatLeavesTheListUnnamesTheRequest() = runTest {
        listed = listOf(FakeSocial.connection("c1", "Sam", last = "Lee"))
        val m = manager()
        m.refresh()
        m.onEvent(event("approval.pending", held("h1", "connection.remove", """{"connection_id":"c1"}""")))
        runCurrent()
        assertEquals("Sam Lee", req(m, "h1").heldConnection)
        listed = emptyList()
        m.refresh()
        runCurrent()
        assertNull(req(m, "h1").heldConnection)
        val refused = runCatching { m.approveDeviceRequest("device:approval.pending:h1", null) }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, refused?.kind)
    }
}
