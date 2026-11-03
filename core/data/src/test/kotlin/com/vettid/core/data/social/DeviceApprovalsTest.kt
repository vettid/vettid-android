// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.FeedItem
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
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ANDROID-PLAN 0.1.116 §4 "Approvals for devices and agents (AA5)": deciding a session request or a held approval sends
 * the right vault call, an error answer takes the entry away, and what the app knows only from a feed item can only be
 * denied (or, for a listed device's session request, allowed with a default length).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceApprovalsTest {
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
    )

    /** The item cache and the devices `device.list` names, as the manager reads them. */
    private var cache = listOf(
        com.vettid.core.data.items.ItemSummary(
            "i1", 1, "Bank PIN", "other", com.vettid.core.data.items.Sensitivity.SECRET,
            fieldLabels = listOf(com.vettid.core.data.items.FieldLabel("f1", "PIN", "password")),
        ),
    )
    private var listedDevices = setOf("A")

    private fun event(type: String, body: String, ts: Instant = now) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = body.toByteArray()))

    private val exp = "2026-11-02T12:10:00.000Z"
    private fun session(id: String = "s1", device: String = "A", seconds: Int = 3600) =
        """{"request_id":"$id","device_id":"$device","role":"agent","name":"Bot","seconds":$seconds,"exp":"$exp"}"""

    private fun held(id: String, body: String, role: String = "agent", type: String = "agent.request") =
        """{"approval_id":"$id","device_id":"A","role":"$role","name":"Bot","type":"$type","body":$body,"exp":"2026-11-02T12:05:00.000Z"}"""

    private val use = """{"op":"item.use","item_id":"i1","field_id":"f1","action":"hmac-sha256","data":"AAEC"}"""

    private fun feed(kind: String, ref: String, device: String? = "A", at: Instant = now, status: String = "active") =
        FeedItem("f-$ref", 1, kind, at.toString(), status, deviceId = device, ref = ref)

    private fun SocialManager.keys() = approvals.value.map { it.key }.sorted()

    private fun req(m: SocialManager, key: String) = m.approvals.value.single { it.key == key } as Approval.DeviceRequest

    // --- what an event carries ---

    @Test
    fun aSessionRequestKeepsTheLengthAskedFor() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session(seconds = 5400)))
        runCurrent()
        assertEquals(5400, req(m, "device:device.session.pending:s1").seconds)
    }

    // --- approve and deny, and what is sent ---

    @Test
    fun allowSendsTheChosenLengthAndTheEntryGoes() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session()))
        runCurrent()
        val d = m.approveDeviceRequest("device:device.session.pending:s1", 14_400)
        runCurrent()
        assertEquals(listOf("session.approve:s1:14400"), ops.calls)
        assertEquals(Instant.parse("2026-11-02T15:40:00Z"), d.expiresAt)
        assertTrue(m.keys().isEmpty())
        // Its feed item is read.
        assertEquals(setOf("device.session.pending") to "s1", read.single())
    }

    @Test
    fun denyOfASessionRequestSendsDeviceSessionDeny() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session()))
        runCurrent()
        m.declineDeviceRequest("device:device.session.pending:s1")
        runCurrent()
        assertEquals(listOf("session.deny:s1"), ops.calls)
        assertTrue(m.keys().isEmpty())
    }

    @Test
    fun approvingAnItemUseSendsApprovalDecideTrueAndReturnsTheResult() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", held("h1", use)))
        runCurrent()
        ops.result = "forbidden"
        val d = m.approveDeviceRequest("device:approval.pending:h1", null)
        assertEquals(listOf("approval.decide:h1:true"), ops.calls)
        assertEquals("forbidden", d.result)
        runCurrent()
        assertTrue(m.keys().isEmpty())
    }

    @Test
    fun denyOfAHeldApprovalSendsApprovalDecideFalse() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", held("h1", use)))
        runCurrent()
        m.declineDeviceRequest("device:approval.pending:h1")
        assertEquals(listOf("approval.decide:h1:false"), ops.calls)
    }

    @Test
    fun theCatalogAndAnItemGetAreApprovableToo() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", held("h1", """{"op":"catalog"}""")))
        m.onEvent(event("approval.pending", held("h2", """{"op":"item.get","item_id":"i1"}""")))
        runCurrent()
        m.approveDeviceRequest("device:approval.pending:h1", null)
        m.approveDeviceRequest("device:approval.pending:h2", null)
        assertEquals(listOf("approval.decide:h1:true", "approval.decide:h2:true"), ops.calls)
    }

    @Test
    fun whatTheAppCannotShowIsNeverApprovedOnlyDenied() = runTest {
        val m = manager()
        val cases = mapOf(
            "run" to held("h1", """{"op":"item.run","item_id":"i1","field_id":"f1","program":"git"}"""),
            "proxy" to held("h2", """{"op":"item.proxy","item_id":"i1","field_id":"f1","host":"api.example.com"}"""),
            "desktop" to held("h3", """{"x":1}""", role = "desktop", type = "item.put"),
            "other" to held("h4", """{"op":"item.delete"}"""),
            "badData" to held("h5", """{"op":"item.use","item_id":"i1","field_id":"f1","action":"hmac-sha256","data":"%%%"}"""),
        )
        cases.values.forEach { m.onEvent(event("approval.pending", it)) }
        runCurrent()
        for (id in listOf("h1", "h2", "h3", "h4", "h5")) {
            try {
                m.approveDeviceRequest("device:approval.pending:$id", null)
                fail("$id must not be approvable")
            } catch (e: VaultFailure) {
                assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
            }
        }
        assertTrue("nothing was sent", ops.calls.isEmpty())
        // They can be denied.
        m.declineDeviceRequest("device:approval.pending:h1")
        m.declineDeviceRequest("device:approval.pending:h3")
        assertEquals(listOf("approval.decide:h1:false", "approval.decide:h3:false"), ops.calls)
    }

    @Test
    fun onlyNotFoundTakesTheEntryAway() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session()))
        runCurrent()
        ops.fail = VaultFailure(FailureKind.NOT_FOUND, "not_found")
        try {
            m.approveDeviceRequest("device:device.session.pending:s1", 3600)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_FOUND, e.kind)
        }
        runCurrent()
        assertTrue("the entry is gone", m.keys().isEmpty())
    }

    @Test
    fun aTransientErrorAnswerKeepsTheEntry() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session()))
        m.onEvent(event("approval.pending", held("h1", use)))
        runCurrent()
        for (code in listOf("internal", "bad_request", "forbidden", "rate_limited")) {
            ops.fail = VaultFailure(FailureKind.OTHER, code)
            try {
                m.approveDeviceRequest("device:device.session.pending:s1", 3600)
                fail()
            } catch (e: VaultFailure) {
                assertEquals(code, e.code)
            }
            ops.fail = VaultFailure(FailureKind.OTHER, code)
            try {
                m.declineDeviceRequest("device:approval.pending:h1")
                fail()
            } catch (e: VaultFailure) {
                assertEquals(FailureKind.OTHER, e.kind)
            }
            runCurrent()
            assertEquals("$code: both stay", 2, m.keys().size)
        }
    }

    @Test
    fun anItemTheCacheCannotNameIsNotApprovedByTheCoreEither() = runTest {
        val m = manager()
        m.onEvent(event("approval.pending", held("h1", use)))
        m.onEvent(event("approval.pending", held("h2", """{"op":"item.use","item_id":"i1","field_id":"f9","action":"hmac-sha256","data":"AAEC"}""")))
        m.onEvent(event("approval.pending", held("h3", """{"op":"item.get","item_id":"gone"}""")))
        m.onEvent(event("approval.pending", held("h4", """{"op":"item.get","item_id":"i1","fields":["f1","f9"]}""")))
        runCurrent()
        for (id in listOf("h2", "h3", "h4")) {
            try {
                m.approveDeviceRequest("device:approval.pending:$id", null)
                fail("$id")
            } catch (e: VaultFailure) {
                assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
            }
        }
        // An empty cache (not loaded) names nothing.
        cache = emptyList()
        try {
            m.approveDeviceRequest("device:approval.pending:h1", null)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        }
        assertTrue(ops.calls.isEmpty())
        cache = listOf(
            com.vettid.core.data.items.ItemSummary(
                "i1", 1, "Bank PIN", "other", com.vettid.core.data.items.Sensitivity.SECRET,
                fieldLabels = listOf(com.vettid.core.data.items.FieldLabel("f1", "PIN", "password")),
            ),
        )
        m.approveDeviceRequest("device:approval.pending:h1", null)
        assertEquals(listOf("approval.decide:h1:true"), ops.calls)
    }

    @Test
    fun aFeedOnlySessionRequestOfADeviceNotInTheListIsNotAllowed() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("device.session.pending", "s1", device = "Z"), feed("device.session.pending", "s2", device = null)))
        runCurrent()
        for (id in listOf("s1", "s2")) {
            try {
                m.approveDeviceRequest("device:device.session.pending:$id", 3600)
                fail(id)
            } catch (e: VaultFailure) {
                assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
            }
        }
        assertTrue(ops.calls.isEmpty())
        // The device list names it: allowed. It can always be denied.
        listedDevices = setOf("Z")
        m.approveDeviceRequest("device:device.session.pending:s1", 3600)
        m.declineDeviceRequest("device:device.session.pending:s2")
        assertEquals(listOf("session.approve:s1:3600", "session.deny:s2"), ops.calls)
    }

    @Test
    fun aPairingEntryStaysAfterAMergedListAndOthersGo() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("device.pair.pending", "p1", device = null), feed("approval.pending", "h1")))
        runCurrent()
        assertEquals(2, m.keys().size)
        m.refreshPendingFrom { PendingPage(emptyList(), null, now) }
        runCurrent()
        // The vault does not list pairings: its entry stays; the approval, absent from the list, is gone.
        assertEquals(listOf("device:device.pair.pending:p1"), m.keys())
    }

    @Test
    fun aFailureThatIsNotAnAnswerKeepsTheEntry() = runTest {
        val m = manager()
        m.onEvent(event("device.session.pending", session()))
        runCurrent()
        ops.fail = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        try {
            m.approveDeviceRequest("device:device.session.pending:s1", 3600)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OWNER_CHECK_REQUIRED, e.kind)
        }
        assertEquals(1, m.keys().size)
    }

    // --- known only from the feed item (decisions 10 and 14a) ---

    @Test
    fun anAskKnownOnlyFromItsFeedItemIsDenyOnlyAndAnApprovalCannotBeApproved() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("approval.pending", "h1"), feed("device.session.pending", "s1")))
        runCurrent()
        assertEquals(listOf("device:approval.pending:h1", "device:device.session.pending:s1"), m.keys())
        val a = req(m, "device:approval.pending:h1")
        assertTrue(a.fromFeed)
        assertNull(a.held)
        assertEquals("A", a.deviceId)
        try {
            m.approveDeviceRequest(a.key, null)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        }
        m.declineDeviceRequest(a.key)
        assertEquals(listOf("approval.decide:h1:false"), ops.calls)
        runCurrent()
        // Denied: it does not come back from the feed item.
        assertEquals(listOf("device:device.session.pending:s1"), m.keys())
    }

    @Test
    fun aSessionRequestKnownFromItsFeedItemCanBeAllowedWithTheChosenLength() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("device.session.pending", "s1")))
        runCurrent()
        m.approveDeviceRequest("device:device.session.pending:s1", 3600)
        assertEquals(listOf("session.approve:s1:3600"), ops.calls)
        runCurrent()
        assertTrue(m.keys().isEmpty())
    }

    @Test
    fun anEntryFromAFeedItemIsHiddenAfterTheVaultDropsTheAsk() = runTest {
        val m = manager()
        // A session request and a pairing live 10 minutes, a held approval 5.
        m.setFeedItems(
            listOf(
                feed("device.session.pending", "s-old", at = now.minusSeconds(601)),
                feed("device.session.pending", "s-new", at = now.minusSeconds(599)),
                feed("device.pair.pending", "p-old", at = now.minusSeconds(601)),
                feed("device.pair.pending", "p-new", at = now.minusSeconds(599)),
                feed("approval.pending", "h-old", at = now.minusSeconds(301)),
                feed("approval.pending", "h-new", at = now.minusSeconds(299)),
            ),
        )
        runCurrent()
        assertEquals(
            listOf("device:approval.pending:h-new", "device:device.pair.pending:p-new", "device:device.session.pending:s-new"),
            m.keys(),
        )
    }

    @Test
    fun aReadItemStaysAnAskAnArchivedOneDoesNot() = runTest {
        val m = manager()
        m.setFeedItems(
            listOf(
                feed("approval.pending", "h-read", status = "read"),
                feed("approval.pending", "h-arch", status = "archived"),
                feed("approval.pending", "h-del", status = "deleted"),
                feed("connection.request", "x"),
                feed("approval.pending", ""),
            ),
        )
        runCurrent()
        assertEquals(listOf("device:approval.pending:h-read"), m.keys())
    }

    @Test
    fun aPairingKnownOnlyFromItsFeedItemOffersNothing() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("device.pair.pending", "p1", device = null)))
        runCurrent()
        val p = req(m, "device:device.pair.pending:p1")
        assertTrue(p.isPairing && p.fromFeed)
        try {
            m.approveDeviceRequest(p.key, 3600)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        }
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun theEventWinsOverTheFeedItem() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("approval.pending", "h1")))
        m.onEvent(event("approval.pending", held("h1", use)))
        runCurrent()
        val a = req(m, "device:approval.pending:h1")
        assertFalse(a.fromFeed)
        assertTrue(a.held is HeldRequest.ItemUse)
    }

    @Test
    fun aMergedPendingListIsAuthoritativeSoTheFeedItemMakesNoEntry() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("approval.pending", "h1"), feed("device.session.pending", "s1")))
        runCurrent()
        assertEquals(2, m.keys().size)
        // The list names s1 only (h1 was decided elsewhere): the list's body replaces the feed item's.
        m.refreshPendingFrom {
            PendingPage(listOf(VaultJson.parseObject("""{"kind":"session","body":${session()}}""".toByteArray())), null, now)
        }
        runCurrent()
        assertEquals(listOf("device:device.session.pending:s1"), m.keys())
        assertFalse(req(m, "device:device.session.pending:s1").fromFeed)
    }

    @Test
    fun aClosingEventHidesTheFeedOnlyEntry() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("approval.pending", "h1"), feed("device.session.pending", "s1", device = "A")))
        runCurrent()
        m.onEvent(event("sync.event", """{"kind":"approval.decided","approval_id":"h1","approved":false}""", now.plusSeconds(1)))
        m.onEvent(event("sync.event", """{"kind":"device.session","device_id":"A"}""", now.plusSeconds(1)))
        runCurrent()
        assertTrue(m.keys().isEmpty())
    }

    @Test
    fun endingAccessOrRemovingTheDeviceTakesItsFeedOnlyEntriesToo() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("approval.pending", "h1", device = "A"), feed("approval.pending", "h2", device = "B")))
        runCurrent()
        m.dropDeviceRequests("A")
        runCurrent()
        assertEquals(listOf("device:approval.pending:h2"), m.keys())
    }

    @Test
    fun aLockForgetsTheFeedClosuresAndTheListState() = runTest {
        val m = manager()
        m.setFeedItems(listOf(feed("approval.pending", "h1")))
        runCurrent()
        m.declineDeviceRequest("device:approval.pending:h1")
        runCurrent()
        assertTrue(m.keys().isEmpty())
        m.forgetOpenVault()
        m.reopen()
        runCurrent()
        // The feed item is still there (a new unlock re-reads the feed); the ask is shown again until the list says.
        assertEquals(listOf("device:approval.pending:h1"), m.keys())
    }
}
