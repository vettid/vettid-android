// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalParser
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.social.GrantEntry
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val request = Approval.ConnectionRequest("p1", "i1", "042817", false, "Morgan", null, now, null)
    private val auth = Approval.Authentication("a1", "c1", null, now, null, "Sam")
    private val grant = Approval.GrantRequest("g1", "c1", listOf(GrantEntry("category", "insurance", null, false)), 1, null, null, now, null)
    private val payload = "SGVsbG8sIFZldHRJRCE="
    private val critical = Approval.CriticalUse("u1", "c1", "Key", "Seed", "sign", "", ApprovalParser.payloadSha256(payload)!!, null, now, null)
    private val outgoing = Approval.OutgoingRequest(
        "c9", "315904", remote = false, name = "Jordan", state = RequestState.PENDING, peerApproved = false,
        introducedBy = null, receivedAt = now, exp = null,
    )
    private val device = Approval.DeviceRequest("approval.pending", "d1", "Laptop", "desktop", "item.reveal", now, null)
    private val social = FakeSocial().apply { approvals.value = listOf(request, auth, grant, critical, device) }

    private val items = com.vettid.core.testing.FakeItems().apply {
        add(com.vettid.core.testing.FakeItems.item("i1", "Passport", category = "identity_document"))
        add(com.vettid.core.testing.FakeItems.item("i2", "Health card", category = "insurance"))
        add(com.vettid.core.testing.FakeItems.item("i3", "Seed", com.vettid.core.data.items.Sensitivity.CRITICAL))
    }

    private fun detail(a: Approval) = ApprovalDetailViewModel(SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to a.key)), social, items)

    @Test
    fun listsWhatTheRepositoryHolds() = runTest {
        val vm = ApprovalsViewModel(social, social)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.approvals.size)
        assertTrue("refreshApprovals" in social.calls)
    }

    /** 0.10.5: the other member declined; the request leaves the list and the member is told once, until dismissed. */
    @Test
    fun showsAPeerDeclineOnceUntilDismissed() = runTest {
        social.approvals.value = listOf(request, outgoing)
        val vm = ApprovalsViewModel(social, social)
        advanceUntilIdle()
        social.peerDeclined("p1", "Morgan", outgoing = false)
        social.peerDeclined("c9", "Jordan", outgoing = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.approvals.isEmpty())
        assertEquals(listOf("p1" to false, "c9" to true), vm.uiState.value.peerDeclines.map { it.requestId to it.outgoing })
        assertEquals(listOf("Morgan", "Jordan"), vm.uiState.value.peerDeclines.map { it.name })
        vm.dismissPeerDecline("p1")
        advanceUntilIdle()
        assertEquals(listOf("c9"), vm.uiState.value.peerDeclines.map { it.requestId })
    }

    /** The copy of §15 item 18 (0.10.5), word for word, for each side. */
    @Test
    fun peerDeclineCopy() {
        assertEquals(R.string.approvals_peer_declined_outgoing, peerDeclineRes(outgoing = true))
        assertEquals(R.string.approvals_peer_declined_incoming, peerDeclineRes(outgoing = false))
        val xml = java.io.File("src/main/res/values/strings.xml").readText()
        fun string(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(xml)!!.groupValues[1]
        assertEquals("%1\$s declined your connection request", string("approvals_peer_declined_outgoing"))
        assertEquals("%1\$s declined the connection", string("approvals_peer_declined_incoming"))
    }

    @Test
    fun approvesAConnectionRequestAndClosesWithoutCallingItGone() = runTest {
        val vm = detail(request)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canApprove)
        vm.approve()
        advanceUntilIdle()
        assertTrue("approveConnection" in social.calls)
        assertTrue(vm.uiState.value.done)
        assertFalse(vm.uiState.value.gone)
    }

    @Test
    fun authenticationNeedsThePasswordAndAWrongOneIsCleared() = runTest {
        val vm = detail(auth)
        advanceUntilIdle()
        assertEquals(Needs.PASSWORD, vm.uiState.value.needs)
        assertFalse(vm.uiState.value.canApprove)
        social.fail["approveAuthentication"] = VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
        vm.setPassword("wrong")
        vm.approve()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.password)
        vm.setPassword("right")
        vm.approve()
        advanceUntilIdle()
        assertEquals("right", social.lastPassword)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aGrantWithNothingGrantableCanOnlyBeDenied() = runTest {
        val vm = detail(grant)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
        vm.approve()
        advanceUntilIdle()
        assertFalse("approveGrant" in social.calls)
        vm.deny()
        advanceUntilIdle()
        assertTrue("denyGrant" in social.calls)
    }

    @Test
    fun criticalUseAndDeviceRequests() = runTest {
        // A listed request has only the hash: the payload is fetched (critical-secret-use.get) and checked first.
        social.criticalPayloads["u1"] = payload
        val c = detail(critical)
        advanceUntilIdle()
        assertTrue("loadCriticalUse" in social.calls)
        assertTrue((c.uiState.value.approval as Approval.CriticalUse).payloadVerified)
        c.setPassword("pw")
        advanceUntilIdle()
        assertTrue(c.uiState.value.canApprove)
        c.approve()
        advanceUntilIdle()
        assertTrue("approveCriticalUse" in social.calls)

        val d = detail(device)
        advanceUntilIdle()
        assertFalse(d.uiState.value.canApprove)
        d.deny()
        advanceUntilIdle()
        assertTrue("declineDeviceRequest" in social.calls)
    }

    @Test
    fun aPayloadThatDoesNotMatchItsHashCannotBeApproved() = runTest {
        val bad = critical.copy(requestId = "u2", payload = "QmFk")
        social.approvals.value = listOf(bad)
        val c = detail(bad)
        advanceUntilIdle()
        c.setPassword("pw")
        advanceUntilIdle()
        assertFalse(c.uiState.value.canApprove)
        c.approve()
        advanceUntilIdle()
        assertFalse("approveCriticalUse" in social.calls)
    }

    @Test
    fun anOutgoingRequestIsApprovedOnceItsCodeIsKnown() = runTest {
        social.approvals.value = listOf(outgoing.copy(sas = null, state = RequestState.WAITING), request.copy(state = RequestState.APPROVED))
        val waiting = detail(outgoing)
        advanceUntilIdle()
        assertFalse(waiting.uiState.value.canApprove)
        assertFalse(detail(request).uiState.value.canApprove)
        social.approvals.value = listOf(outgoing)
        advanceUntilIdle()
        assertTrue(waiting.uiState.value.canApprove)
        waiting.approve()
        advanceUntilIdle()
        assertTrue("approveOutgoing" in social.calls)
        val d = detail(outgoing)
        d.deny()
        advanceUntilIdle()
        assertTrue("declineOutgoing" in social.calls)
    }

    @Test
    fun decidedElsewhereIsShownAsGone() = runTest {
        val vm = detail(request)
        advanceUntilIdle()
        social.approvals.value = emptyList()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
    }

    /** §10.12: ticked items are shared, unticked ones declined, in one decision. */
    @Test
    fun aShareDecisionIncludesTheTickedItemsAndDeclinesTheRest() = runTest {
        val share = Approval.ShareDecision(
            "r1", "c1", null,
            listOf(com.vettid.core.data.social.ShareItem("i1", "Allergies", "medical", "data"), com.vettid.core.data.social.ShareItem("i2", "Blood type", "medical", "data")),
            "tagged", now, tags = listOf("medical"),
        )
        social.approvals.value = listOf(share)
        val vm = detail(share)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canApprove)
        vm.toggleShareItem("i2")
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals(listOf("i1") to listOf("i2"), social.lastShare)
        val all = detail(share)
        social.approvals.value = listOf(share)
        all.toggleShareItem("i1")
        all.toggleShareItem("i2")
        advanceUntilIdle()
        assertFalse(all.uiState.value.canApprove)
    }

    /** §10.12: entries ticked one by one, a category answered with the member's item, uses and lifetime chosen. */
    @Test
    fun aGrantIsDecidedEntryByEntry() = runTest {
        val g = Approval.GrantRequest(
            "g2", "c1",
            listOf(GrantEntry("item", "i1", "Your passport", true), GrantEntry("category", "insurance", "Your insurance card", false), GrantEntry("item", "gone", null, false)),
            1, 604_800, "Booking", now, null,
        )
        social.approvals.value = listOf(g)
        val vm = detail(g)
        advanceUntilIdle()
        assertEquals(setOf(0), vm.uiState.value.grantIndexes)
        assertEquals(listOf("i1", "i2"), vm.uiState.value.answerable.map { it.itemId }.sorted().take(2))
        assertFalse(vm.uiState.value.answerable.any { it.itemId == "i3" }) // critical: never granted
        vm.answerGrantEntry(1, "i2")
        vm.toggleGrantEntry(2) // not available: never granted
        vm.setGrantUses(3)
        vm.setGrantExpiresIn(86_400)
        advanceUntilIdle()
        assertEquals(setOf(0, 1), vm.uiState.value.grantIndexes)
        vm.toggleGrantEntry(0)
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.social.GrantDecision(listOf(1), mapOf(1 to "i2"), 3, 86_400), social.lastGrant)
        assertTrue(vm.uiState.value.done)
    }

    /** §10.13: the result stays on screen; a backoff counts down. */
    @Test
    fun aCriticalUseShowsItsResult() = runTest {
        val c = critical.copy(payload = payload)
        social.approvals.value = listOf(c)
        social.criticalStatus = "unsuitable"
        val vm = detail(c)
        advanceUntilIdle()
        social.fail["approveCriticalUse"] = VaultFailure(FailureKind.BACKOFF, "backoff", retryAfterSeconds = 30)
        vm.setPassword("pw")
        vm.approve()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.retryUntil != null)
        vm.setPassword("pw")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
    }

    @Test
    fun aCriticalUseResultIsShownBeforeClosing() = runTest {
        val c = critical.copy(payload = payload)
        social.approvals.value = listOf(c)
        social.criticalStatus = "unsuitable"
        val vm = detail(c)
        advanceUntilIdle()
        vm.setPassword("pw")
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals("unsuitable", vm.uiState.value.criticalResult)
        assertFalse(vm.uiState.value.done)
        assertFalse(vm.uiState.value.gone)
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aFieldThatCannotHoldASeedCanOnlyBeDenied() = runTest {
        // VAULT-MESSAGING 0.21.0 §10.13: the kind is known before the password; a 0.21.0 vault never asks for these.
        val c = critical.copy(requestId = "u7", payload = payload, kind = "url")
        social.approvals.value = listOf(c)
        val vm = detail(c)
        advanceUntilIdle()
        vm.setPassword("pw")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
        vm.approve()
        advanceUntilIdle()
        assertFalse("approveCriticalUse" in social.calls)
        vm.deny()
        advanceUntilIdle()
        assertTrue("denyCriticalUse" in social.calls)
        // A suitable kind, or none from an older vault, is approved as before.
        val ok = critical.copy(requestId = "u8", payload = payload, kind = "password")
        social.approvals.value = listOf(ok)
        val vm2 = detail(ok)
        advanceUntilIdle()
        vm2.setPassword("pw")
        advanceUntilIdle()
        assertTrue(vm2.uiState.value.canApprove)
    }

    @Test
    fun aNamedLimitIsShownWithTheFailure() = runTest {
        val share = Approval.ShareDecision("r1", "c1", null, listOf(com.vettid.core.data.social.ShareItem("i1", "Passport", "identity_document", "data")), null, now)
        social.approvals.value = listOf(share)
        val limit = com.vettid.core.data.vault.VaultLimit("grants_given", 1_000)
        social.fail["decideShare"] = VaultFailure(FailureKind.LIMIT, "limit", limit = limit)
        val vm = detail(share)
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals(FailureKind.LIMIT, vm.uiState.value.error)
        assertEquals(limit, vm.uiState.value.limit)
    }

    // --- VAULT-MESSAGING 0.23.0 §10.4.1 ---

    /** A connection's asks within 10 minutes of the first are one entry; later ones start another; others stay single. */
    @Test
    fun aConnectionsAsksWithinTenMinutesAreOneEntry() = runTest {
        val a = auth.copy(receivedAt = now)
        val g = grant.copy(receivedAt = now.plusSeconds(120))
        val c = critical.copy(receivedAt = now.plusSeconds(599))
        val late = grant.copy(requestId = "g2", receivedAt = now.plusSeconds(600)) // the batch's 10 minutes are over
        val other = grant.copy(requestId = "g3", connectionId = "c2", receivedAt = now.plusSeconds(60))
        val share = Approval.ShareDecision("r1", "c1", null, emptyList(), null, now.plusSeconds(30))
        val rows = AskBatches.group(listOf(late, c, g, other, share, a, request).sortedByDescending { it.receivedAt })
        assertEquals(listOf("grant:g2", "batch:auth:a1", "grant:g3", "share:r1", "connection:p1"), rows.map { it.key })
        val b = rows[1] as ApprovalEntry.Batch
        assertEquals("c1", b.connectionId)
        assertEquals(listOf("auth:a1", "grant:g1", "critical:u1"), b.asks.map { it.key })
        assertEquals("Sam", b.connectionName)
        assertEquals(now.plusSeconds(599), b.receivedAt)
        // One ask alone is a single entry.
        assertEquals(listOf(ApprovalEntry.One(a)), AskBatches.group(listOf(a)))
        social.approvals.value = listOf(c, g, a)
        val vm = ApprovalsViewModel(social, social)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.entries.size)
    }

    /** A paused connection is offered to resume (or remove); resuming clears the pause, a mute stays (§10.4.1). */
    @Test
    fun aPausedConnectionCanBeResumedOrRemoved() = runTest {
        val sam = com.vettid.core.data.social.ConnectionInfo(
            "c1", "", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee",
            asks = com.vettid.core.data.social.AskState(muted = true, paused = true, cooldowns = 3),
        )
        val jo = sam.copy(id = "c2", asks = com.vettid.core.data.social.AskState())
        social.connections.value = listOf(sam, jo)
        val vm = ApprovalsViewModel(social, social)
        advanceUntilIdle()
        assertEquals(listOf("c1"), vm.uiState.value.paused.map { it.id })
        vm.resumeAsks("c1")
        advanceUntilIdle()
        assertTrue("asksResume:c1" in social.calls)
        assertTrue(vm.uiState.value.paused.isEmpty())
        assertEquals(com.vettid.core.data.social.AskState(muted = true), social.connections.value[0].asks)
        // Paused again, then removed after a confirmation.
        social.connections.value = listOf(sam, jo)
        advanceUntilIdle()
        vm.askRemove(vm.uiState.value.paused.single())
        advanceUntilIdle()
        assertEquals("c1", vm.uiState.value.confirmRemove?.id)
        vm.remove()
        advanceUntilIdle()
        assertTrue("remove" in social.calls)
        assertTrue(vm.uiState.value.paused.isEmpty())
    }

    /** §10.12 (0.23.0): why a share question's item is asked, and that a decline stops what another rule shares. */
    @Test
    fun aShareQuestionExplainsTheRuleThatAsksAndAnItemAlreadyShared() {
        val d = Approval.ShareDecision(
            "r2", "c1", null,
            listOf(
                com.vettid.core.data.social.ShareItem("i1", "A", "medical", "data", askRuleId = "r1", alsoIn = listOf("r1", "r3")),
                com.vettid.core.data.social.ShareItem("i2", "B", "x", "data", shared = true),
                com.vettid.core.data.social.ShareItem("i3", "C", "x", "data"),
            ),
            null, now,
        )
        assertEquals(
            listOf(ShareExplanations.Line.AsksFirst("r1"), ShareExplanations.Line.AlsoCovered("r3")),
            ShareExplanations.of(d.items[0], d),
        )
        assertEquals(listOf(ShareExplanations.Line.AlreadyShared), ShareExplanations.of(d.items[1], d))
        assertTrue(ShareExplanations.of(d.items[2], d).isEmpty())
    }
}
