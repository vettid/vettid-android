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
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ANDROID-PLAN 0.1.145: a desktop's held block or removal names its connection from `connection.list` (active or stale, with
 * names the app holds), and is approved only then; otherwise it stays deny-only, enforced where it is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
