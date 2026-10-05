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

    private fun detail(a: Approval) = ApprovalDetailViewModel(SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to a.key)), social)

    @Test
    fun listsWhatTheRepositoryHolds() = runTest {
        val vm = ApprovalsViewModel(social)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.approvals.size)
        assertTrue("refreshApprovals" in social.calls)
    }

    /** 0.10.5: the other member declined; the request leaves the list and the member is told once, until dismissed. */
    @Test
    fun showsAPeerDeclineOnceUntilDismissed() = runTest {
        social.approvals.value = listOf(request, outgoing)
        val vm = ApprovalsViewModel(social)
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
}
