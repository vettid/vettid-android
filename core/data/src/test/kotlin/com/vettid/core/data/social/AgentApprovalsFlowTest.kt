// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.PendingPage
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ANDROID-PLAN 0.1.138 §4 AA8: an agent's run and proxy approvals are read strictly from the vault's own text, through
 * events, the store and pending.list; a listed approval that differs is deny only; one referred again replaces its old one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentApprovalsFlowTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    /** The vault, as far as these decisions go: every call is recorded; [fail] is thrown by the next call. */
    private class Ops : DeviceDecisionOps {
        val calls = mutableListOf<String>()
        var fail: VaultFailure? = null
        var expiresAt: String? = "2026-11-02T15:40:00Z"
        var result: String? = "ok"

        private fun hit(s: String) {
            calls += s
            fail?.let { fail = null; throw it }
        }

        override suspend fun sessionApprove(requestId: String, seconds: Int?): String? {
            hit("session.approve:$requestId:$seconds")
            return expiresAt
        }

        override suspend fun sessionDeny(requestId: String) = hit("session.deny:$requestId")

        override suspend fun pairReject(pendingId: String) = hit("pair.reject:$pendingId")

        override suspend fun approvalDecide(approvalId: String, approve: Boolean): String? {
            hit("approval.decide:$approvalId:$approve")
            return result
        }
    }

    private val ops = Ops()
    private val read = mutableListOf<Pair<Set<String>, String?>>()

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        feedRead = { kinds, ref, _ -> read += kinds to ref },
        deviceOps = { ops },
        itemCache = { cache },
        deviceListed = { it in listedDevices },
        runCwdAllowed = { agent, item -> cwdAllowed && agent == "A" && item == "i1" },
    )

    /** What the item's settings say of a run's `cwd` (AA8): allowed only when they carry `run` without `folders`. */
    private var cwdAllowed = false

    /** The item cache and the devices `device.list` names, as the manager reads them. */
    private var cache = listOf(
        com.vettid.core.data.items.ItemSummary(
            "i1", 1, "Bank PIN", "other", com.vettid.core.data.items.Sensitivity.SECRET,
            fieldLabels = listOf(com.vettid.core.data.items.FieldLabel("f1", "PIN", "password")),
        ),
    )
    private var listedDevices = setOf("A")

    private fun event(type: String, body: String, ts: Instant = now) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = body.toByteArray()))

    private fun held(id: String, body: String, role: String = "agent", type: String = "agent.request") =
        """{"approval_id":"$id","device_id":"A","role":"$role","name":"Bot","type":"$type","body":$body,"exp":"2026-11-02T12:05:00.000Z"}"""

    private val use = """{"op":"item.use","item_id":"i1","field_id":"f1","action":"hmac-sha256","data":"AAEC"}"""

    private fun SocialManager.keys() = approvals.value.map { it.key }.sorted()

    private fun req(m: SocialManager, key: String) = m.approvals.value.single { it.key == key } as Approval.DeviceRequest

    // --- AA8: runs and proxies are read strictly, from the vault's own text ---

    private val sha = "1a2b3c4d5e6f7a8b".repeat(4)
    private fun runBody(argv: String = """["git","status"]""") =
        """{"op":"item.run","item_id":"i1","field_id":"f1","program":"/usr/bin/git","sha256":"$sha","argv":$argv,"how":"env","name":"GH_TOKEN"}"""

    private fun heldWith(id: String, body: String, top: String = "") =
        """{"approval_id":"$id","device_id":"A","role":"agent","name":"Bot","type":"agent.request","body":$body,"exp":"2026-11-02T12:05:00.000Z"$top}"""

    /** One `pending.list` page of approvals, each with its source text as a strict read gives it. */
    private fun listed(vararg bodies: String) = PendingPage(
        bodies.map { VaultJson.parseObject("""{"kind":"approval","body":$it}""".toByteArray()) },
        null,
        now,
        bodies.toList(),
    )

    @Test
    fun aRunEventIsReadStrictlyAndKeptAsTheVaultWroteIt() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        // A duplicate member: the lenient reading would keep the last one and look fine.
        m.onEvent(event("approval.pending", heldWith("h2", runBody().replace(""""how":"env"""", """"how":"stdin","how":"env""""))))
        runCurrent()
        val run = req(m, "device:approval.pending:h1").held as HeldRequest.ItemRun
        assertEquals(listOf("git", "status"), run.argv)
        assertTrue(run.approvable)
        // The approvals are made again from what was stored: still strict, so still deny only.
        assertEquals(HeldRequest.Unshown, req(m, "device:approval.pending:h2").held)
    }

    @Test
    fun aListedApprovalThatDiffersFromItsEventIsDenyOnly() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        m.onEvent(event("approval.pending", heldWith("h2", runBody())))
        runCurrent()
        m.refreshPendingFrom { listed(heldWith("h1", runBody()), heldWith("h2", runBody("""["git","push","--force"]"""))) }
        runCurrent()
        assertTrue("the same: still read", req(m, "device:approval.pending:h1").held is HeldRequest.ItemRun)
        assertEquals("another argv: deny only", HeldRequest.Unshown, req(m, "device:approval.pending:h2").held)
        // A lock forgets it with the lists.
        m.forgetPending()
        runCurrent()
        m.onEvent(event("approval.pending", heldWith("h2", runBody())))
        runCurrent()
        assertTrue(req(m, "device:approval.pending:h2").held is HeldRequest.ItemRun)
    }

    @Test
    fun aListedApprovalWithADuplicateIsDenyOnly() = runTest {
        val m = manager()
        val dup = heldWith("h1", runBody().replace(""""how":"env"""", """"how":"stdin","how":"env""""))
        m.refreshPendingFrom { PendingPage(listed(dup).asks, null, now, listOf(null)) }
        runCurrent()
        assertEquals(HeldRequest.Unshown, req(m, "device:approval.pending:h1").held)
    }

    @Test
    fun aRunReferredAgainReplacesTheOneItNamesForGood() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        runCurrent()
        m.onEvent(event("approval.pending", heldWith("h2", runBody(), ""","replaces":"h1","flags":["program_unrecorded"]""")))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.keys())
        assertEquals("h1", (req(m, "device:approval.pending:h2").held as HeldRequest.ItemRun).replaces)
        // A late copy of the old one does not bring it back.
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.keys())
        // Nor does a list that still has it, while the new one replaces it there too.
        m.refreshPendingFrom { listed(heldWith("h1", runBody()), heldWith("h3", runBody(), ""","replaces":"h2"""")) }
        runCurrent()
        assertEquals(listOf("device:approval.pending:h3"), m.keys())
    }

    @Test
    fun aListedUseOrRevealTheSameAsItsEventStaysApprovable() = runTest {
        val m = manager()
        val get = heldWith("h2", """{"op":"item.get","item_id":"i1","fields":["f1"]}""", ""","flags":["history"]""")
        m.onEvent(event("approval.pending", held("h1", use)))
        m.onEvent(event("approval.pending", get))
        runCurrent()
        m.refreshPendingFrom { listed(held("h1", use), get) }
        runCurrent()
        assertTrue(req(m, "device:approval.pending:h1").held is HeldRequest.ItemUse)
        assertTrue(req(m, "device:approval.pending:h2").held is HeldRequest.ItemGet)
    }

    @Test
    fun aReplacedApprovalCannotBeDecidedFromALateCopy() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        m.onEvent(event("approval.pending", heldWith("h2", runBody(), ""","replaces":"h1"""")))
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        runCurrent()
        val e = runCatching { m.declineDeviceRequest("device:approval.pending:h1") }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_FOUND, e?.kind)
        assertTrue("nothing sent for it", ops.calls.isEmpty())
    }

    @Test
    fun aListedApprovalReferredAgainReplacesOneTheListStillHas() = runTest {
        val m = manager()
        m.refreshPendingFrom { listed(heldWith("h1", runBody()), heldWith("h2", runBody(), ""","replaces":"h1"""")) }
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.keys())
    }

    @Test
    fun aDenyOnlyReferralStillReplacesTheOneItNames() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        m.onEvent(event("approval.pending", heldWith("h2", runBody(), ""","replaces":"h1","flags":["a_later_flag"]""")))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.keys())
        assertEquals(HeldRequest.Unshown, req(m, "device:approval.pending:h2").held)
    }

    @Test
    fun aListThatStillHasAReplacedApprovalDoesNotBringItBack() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        m.onEvent(event("approval.pending", heldWith("h2", runBody(), ""","replaces":"h1"""")))
        runCurrent()
        m.refreshPendingFrom { listed(heldWith("h1", runBody()), heldWith("h2", runBody(), ""","replaces":"h1"""")) }
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.listedNow().map { it.key })
        val e = runCatching { m.declineDeviceRequest("device:approval.pending:h1") }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_FOUND, e?.kind)
    }

    @Test
    fun aListedApprovalWithAnotherNameIsDenyOnly() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        runCurrent()
        m.refreshPendingFrom { listed(heldWith("h1", runBody()).replace(""""name":"Bot"""", """"name":"Other bot"""")) }
        runCurrent()
        assertEquals(HeldRequest.Unshown, req(m, "device:approval.pending:h1").held)
    }

    @Test
    fun aReplayAfterAReferralDoesNotBringBackTheOldApproval() = runTest {
        val m = manager()
        m.refreshPendingFrom { listed(heldWith("h1", runBody())) }
        runCurrent()
        // Newer than the list, so it is taken in (one not later is already reflected by the list).
        m.onEvent(event("approval.pending", heldWith("h2", runBody(), ""","replaces":"h1""""), ts = now.plusSeconds(1)))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.keys())
        assertEquals(emptyList<String>(), m.firstListedSince(0)?.map { it.key })
    }

    @Test
    fun aReplacesOnARevealRemovesNothing() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        m.onEvent(event("approval.pending", heldWith("h2", """{"op":"item.get","item_id":"i1"}""", ""","replaces":"h1"""")))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h1", "device:approval.pending:h2"), m.keys())
        assertEquals(HeldRequest.Unshown, req(m, "device:approval.pending:h2").held)
    }

    @Test
    fun aListedApprovalFromAnotherDeviceIsDenyOnly() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        runCurrent()
        m.refreshPendingFrom { listed(heldWith("h1", runBody()).replace(""""device_id":"A"""", """"device_id":"B"""")) }
        runCurrent()
        assertEquals(HeldRequest.Unshown, req(m, "device:approval.pending:h1").held)
    }

    @Test
    fun aRunIsApprovedWithApprovalDecide() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody())))
        runCurrent()
        m.approveDeviceRequest("device:approval.pending:h1", null)
        assertEquals(listOf("approval.decide:h1:true"), ops.calls)
    }

    @Test
    fun aRunWithACwdAndNoMemberFolderNeedsTheSettingsToAllowIt() = runTest {
        val m = manager()
        val withCwd = runBody().replace(""""how":"env"""", """"cwd":"/home/me","how":"env"""")
        m.onEvent(event("approval.pending", heldWith("h1", withCwd)))
        runCurrent()
        val refused = runCatching { m.approveDeviceRequest("device:approval.pending:h1", null) }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, refused?.kind)
        assertTrue("nothing sent", ops.calls.isEmpty())
        cwdAllowed = true
        m.approveDeviceRequest("device:approval.pending:h1", null)
        assertEquals(listOf("approval.decide:h1:true"), ops.calls)
    }

    @Test
    fun aRunForAnItemOrFieldNotInTheCacheIsNotApproved() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", heldWith("h1", runBody().replace(""""field_id":"f1"""", """"field_id":"f9""""))))
        runCurrent()
        val refused = runCatching { m.approveDeviceRequest("device:approval.pending:h1", null) }.exceptionOrNull() as? VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, refused?.kind)
    }

    /** AA9: a run in a member folder is approved on the approval's own `folders.cwd`, with no settings read. */
    @Test
    fun aMemberFolderRunNeedsNoSettingsRead() = runTest {
        val m = manager()
        val body = runBody().replace(""""how":"env"""", """"cwd":"/home/me/work","how":"env"""")
        m.onEvent(event("approval.pending", heldWith("h1", body, ""","flags":["member_folder"],"folders":{"cwd":{"path":"/home/me/work","write":false}}""")))
        runCurrent()
        m.approveDeviceRequest("device:approval.pending:h1", null)
        assertEquals(listOf("approval.decide:h1:true"), ops.calls)
    }
}
