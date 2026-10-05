// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.social.AcceptedConnection
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.RequestEnd
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.InviteTtl
import com.vettid.core.data.social.OutstandingInvite
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial()

    private fun link(kind: InviteKind = InviteKind.CONNECTION, exp: Long = Instant.now().plusSeconds(600).epochSecond) =
        InviteQr(kind, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32), ByteArray(32), exp).link()

    @Test
    fun listsFavouritesFirstTogglesAFavouriteAndCancelsAnInvite() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Zed"), FakeSocial.connection("c2", "amy"), FakeSocial.connection("c3", "Bo", favorite = true)))
        social.outstanding = listOf(OutstandingInvite("i1", null, remote = true))
        val vm = ConnectionsViewModel(social, social)
        advanceUntilIdle()
        assertEquals(listOf("c3", "c2", "c1"), vm.uiState.value.connections.map { it.id })
        assertEquals(1, vm.uiState.value.invites.size)
        vm.setFavorite("c1", true)
        advanceUntilIdle()
        assertEquals(listOf("c3", "c1", "c2"), vm.uiState.value.connections.map { it.id })
        vm.askCancel(vm.uiState.value.invites.single())
        vm.confirmCancel()
        advanceUntilIdle()
        assertTrue("cancelInvite" in social.calls)
        assertTrue(vm.uiState.value.invites.isEmpty())
    }

    @Test
    fun inviteShowsTheCodeThenTheRequestThenTheConnection() = runTest {
        social.ttls = listOf(InviteTtl.TEN_MINUTES, InviteTtl.ONE_HOUR)
        val vm = InviteViewModel(social, social)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.ttls.size)
        vm.create()
        advanceUntilIdle()
        assertEquals(InviteStep.SHOWING, vm.uiState.value.step)
        val inviteId = vm.uiState.value.invite!!.inviteId

        // Someone else's request (another invite) does not show here.
        social.approvals.value = listOf(Approval.ConnectionRequest("p0", "other", "111111", false, "X", null, Instant.now(), null))
        advanceUntilIdle()
        assertEquals(InviteStep.SHOWING, vm.uiState.value.step)

        social.approvals.value = listOf(Approval.ConnectionRequest("p1", inviteId, "042817", false, "Morgan", null, Instant.now(), null))
        advanceUntilIdle()
        assertEquals(InviteStep.REQUEST, vm.uiState.value.step)
        assertEquals("042817", vm.uiState.value.request?.sas)

        vm.approve()
        advanceUntilIdle()
        assertTrue("approveConnection" in social.calls)
        assertEquals(InviteStep.CONNECTING, vm.uiState.value.step)
        // Approved here by the member, not automatically.
        assertEquals(false, vm.uiState.value.autoApproved)
        social.seed(listOf(FakeSocial.connection("c9", "Morgan")))
        advanceUntilIdle()
        assertEquals(InviteStep.CONNECTED, vm.uiState.value.step)
        assertEquals("c9", vm.uiState.value.connectionId)
    }

    @Test
    fun inviteExpiresAndCanBeDeclinedOrBlocked() = runTest {
        social.invite = social.invite.copy(exp = Instant.now().plusSeconds(60))
        val vm = InviteViewModel(social, social)
        vm.create()
        advanceUntilIdle()
        vm.tick(Instant.now().plusSeconds(61))
        assertEquals(InviteStep.EXPIRED, vm.uiState.value.step)

        vm.again()
        vm.create()
        advanceUntilIdle()
        val id = vm.uiState.value.invite!!.inviteId
        social.approvals.value = listOf(Approval.ConnectionRequest("p1", id, "042817", true, null, null, Instant.now(), null))
        advanceUntilIdle()
        vm.askBlock(true)
        vm.block()
        advanceUntilIdle()
        assertTrue("blockConnectionRequest" in social.calls)
        assertEquals(InviteStep.CHOOSE, vm.uiState.value.step)
    }

    private fun outgoing(id: String, sas: String?, state: RequestState) = Approval.OutgoingRequest(
        id, sas, remote = false, name = "Inviter", state = state, peerApproved = false, introducedBy = null,
        receivedAt = Instant.now(), exp = null,
    )

    @Test
    fun acceptShowsTheCodeThenBothApproveAndReportsBadLinks() = runTest {
        val vm = AcceptViewModel(SavedStateHandle(), social, social)
        vm.setInput("not a link")
        vm.accept()
        advanceUntilIdle()
        assertEquals(FailureKind.INVITE_INVALID, vm.uiState.value.error)
        assertEquals(AcceptStep.INPUT, vm.uiState.value.step)

        social.acceptResult = AcceptedConnection("c5", "Inviter")
        vm.setInput(link())
        vm.accept()
        advanceUntilIdle()
        // 0.10.3: the accept answers "waiting"; the code follows once the handshake has run.
        assertEquals(AcceptStep.WAITING, vm.uiState.value.step)
        assertEquals("Inviter", vm.uiState.value.name)
        assertNull(vm.uiState.value.sas)
        social.approvals.value = listOf(outgoing("c5", null, RequestState.WAITING))
        advanceUntilIdle()
        assertEquals(AcceptStep.WAITING, vm.uiState.value.step)
        social.approvals.value = listOf(outgoing("c5", "123456", RequestState.PENDING))
        advanceUntilIdle()
        assertEquals(AcceptStep.COMPARE, vm.uiState.value.step)
        assertEquals("123456", vm.uiState.value.sas)

        vm.approve()
        advanceUntilIdle()
        assertTrue("approveOutgoing" in social.calls)
        assertEquals(AcceptStep.APPROVED, vm.uiState.value.step)
        // Active once the inviter approved too.
        social.seed(listOf(FakeSocial.connection("c5", "Inviter", state = ConnectionState.ACTIVE)))
        advanceUntilIdle()
        assertEquals(AcceptStep.CONNECTED, vm.uiState.value.step)
        assertEquals("Inviter", vm.uiState.value.connectionName)
    }

    @Test
    fun aScannedLinkIsAcceptedAtOnceAndAnOpenedOneWaitsForTheMember() = runTest {
        social.acceptResult = AcceptedConnection("c6", "Inviter")
        val vm = AcceptViewModel(SavedStateHandle(mapOf(AcceptRoute.ARG to link())), social, social)
        advanceUntilIdle()
        assertEquals(AcceptStep.WAITING, vm.uiState.value.step)

        val opened = AcceptViewModel(SavedStateHandle(mapOf(AcceptRoute.ARG to "vettid://connect#${link()}", AcceptRoute.ARG_OPENED to true)), social, social)
        advanceUntilIdle()
        assertEquals(AcceptStep.INPUT, opened.uiState.value.step)
        assertTrue(opened.uiState.value.fromLink)
        assertEquals(1, social.calls.count { it == "acceptInvite" })
        opened.accept()
        advanceUntilIdle()
        assertEquals(AcceptStep.WAITING, opened.uiState.value.step)
    }

    @Test
    fun acceptSaysWhenAlreadyConnectedAndWhenTheRequestEnds() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Sam")))
        social.acceptResult = AcceptedConnection("c1", exists = true)
        val vm = AcceptViewModel(SavedStateHandle(), social, social)
        vm.setInput(link())
        vm.accept()
        advanceUntilIdle()
        assertEquals(AcceptStep.EXISTS, vm.uiState.value.step)
        assertEquals("Sam", vm.uiState.value.connectionName)

        // Declining the outgoing request (codes differ): the screen says it ended, as before 0.10.5 (the vault
        // now tells the inviter's vault; nothing changes here, and it is not a peer's decline).
        social.acceptResult = AcceptedConnection("c7", "Inviter")
        val d = AcceptViewModel(SavedStateHandle(), social, social)
        d.setInput(link())
        d.accept()
        social.approvals.value = listOf(outgoing("c7", "654321", RequestState.PENDING))
        advanceUntilIdle()
        d.decline()
        advanceUntilIdle()
        assertTrue("declineOutgoing" in social.calls)
        assertEquals(AcceptStep.ENDED, d.uiState.value.step)
        assertEquals(RequestEnd.DECLINED, d.uiState.value.end)
        assertTrue(social.peerDeclines.value.isEmpty())
        assertTrue("dismissPeerDecline" !in social.calls)

        // An outgoing request that failed (expiry, refused hs.init) ends too.
        social.acceptResult = AcceptedConnection("c8", "Inviter")
        val f = AcceptViewModel(SavedStateHandle(), social, social)
        f.setInput(link())
        f.accept()
        social.approvals.value = listOf(outgoing("c8", null, RequestState.WAITING))
        advanceUntilIdle()
        social.approvals.value = emptyList()
        social.requestEnds.value = mapOf("c8" to RequestEnd.FAILED)
        advanceUntilIdle()
        assertEquals(AcceptStep.ENDED, f.uiState.value.step)
        assertEquals(RequestEnd.FAILED, f.uiState.value.end)
    }

    /** 0.10.5: the inviter declined; the accept screen says so with the inviter's name, once. */
    @Test
    fun acceptSaysTheInviterDeclinedOnce() = runTest {
        social.acceptResult = AcceptedConnection("c9", "Morgan Lee")
        val vm = AcceptViewModel(SavedStateHandle(), social, social)
        vm.setInput(link())
        vm.accept()
        social.approvals.value = listOf(outgoing("c9", "654321", RequestState.PENDING))
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals(AcceptStep.APPROVED, vm.uiState.value.step)
        social.peerDeclined("c9", "Morgan Lee", outgoing = true)
        advanceUntilIdle()
        assertEquals(AcceptStep.ENDED, vm.uiState.value.step)
        assertEquals(RequestEnd.PEER_DECLINED, vm.uiState.value.end)
        assertEquals("Morgan Lee", vm.uiState.value.name)
        // Shown here: not again in Connections or Approvals.
        assertEquals(1, social.calls.count { it == "dismissPeerDecline" })
        assertTrue(social.peerDeclines.value.isEmpty())
    }

    /** 0.10.5: the accepter declined; the invite screen says so with the requester's name, once. */
    @Test
    fun inviteSaysTheAccepterDeclinedOnce() = runTest {
        val vm = InviteViewModel(social, social)
        advanceUntilIdle()
        vm.create()
        advanceUntilIdle()
        val id = vm.uiState.value.invite!!.inviteId
        social.approvals.value = listOf(Approval.ConnectionRequest("p2", id, "042817", false, "Alex", null, Instant.now(), null))
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals(InviteStep.CONNECTING, vm.uiState.value.step)
        // Another request's decline is not this one's.
        social.peerDeclined("p-other", "Sam", outgoing = false)
        advanceUntilIdle()
        assertEquals(InviteStep.CONNECTING, vm.uiState.value.step)
        social.peerDeclined("p2", "Alex", outgoing = false)
        advanceUntilIdle()
        assertEquals(InviteStep.DECLINED, vm.uiState.value.step)
        assertEquals("Alex", vm.uiState.value.declinedName)
        assertEquals(listOf("p-other"), social.peerDeclines.value.map { it.requestId })
    }

    /** 0.10.5: Connections lists the other members' declines until the member dismisses them. */
    @Test
    fun connectionsShowAPeerDeclineUntilDismissed() = runTest {
        val vm = ConnectionsViewModel(social, social)
        advanceUntilIdle()
        social.peerDeclined("c3", "Morgan Lee", outgoing = true)
        advanceUntilIdle()
        val d = vm.uiState.value.peerDeclines.single()
        assertEquals("Morgan Lee", d.name)
        assertTrue(d.outgoing)
        vm.dismissPeerDecline("c3")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.peerDeclines.isEmpty())
    }

    /** The copy of §15 item 18 (0.10.5), word for word, for each side. */
    @Test
    fun peerDeclineCopy() {
        assertEquals(R.string.connections_peer_declined_outgoing, peerDeclineRes(outgoing = true))
        assertEquals(R.string.connections_peer_declined_incoming, peerDeclineRes(outgoing = false))
        val xml = java.io.File("src/main/res/values/strings.xml").readText()
        fun string(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(xml)!!.groupValues[1]
        assertEquals("%1\$s declined your connection request", string("connections_peer_declined_outgoing"))
        assertEquals("%1\$s declined the connection", string("connections_peer_declined_incoming"))
    }

    @Test
    fun scannerChecksTheCodeBeforeAnythingIsSent() {
        val vm = ScanViewModel()
        vm.onScanned("https://example.org")
        assertEquals(FailureKind.INVITE_INVALID, vm.uiState.value.problem)
        vm.onScanned(link(InviteKind.AGENT))
        assertEquals(FailureKind.INVITE_NOT_CONNECTION, vm.uiState.value.problem)
        vm.onScanned(link(exp = Instant.now().minusSeconds(1).epochSecond))
        assertEquals(FailureKind.INVITE_EXPIRED, vm.uiState.value.problem)
        val ok = link()
        vm.onScanned(ok)
        assertEquals(ok, vm.uiState.value.link)
        assertNull(vm.uiState.value.problem)
        assertTrue(social.calls.isEmpty())
    }

    @Test
    fun detailEditsFavouritesAuthenticatesAndRemoves() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Sam")))
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social)
        advanceUntilIdle()
        vm.toggleFavorite()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.connection!!.favorite)
        vm.edit(true)
        vm.setAlias("Sammy")
        vm.saveNames()
        advanceUntilIdle()
        assertEquals("Sammy", vm.uiState.value.connection?.alias)
        assertEquals(DetailNotice.SAVED, vm.uiState.value.notice)
        vm.requestAuthentication()
        advanceUntilIdle()
        assertEquals("requested", vm.uiState.value.auth?.lastResult)
        vm.ask(DetailConfirm.REMOVE)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        assertTrue(social.connections.value.isEmpty())
    }
}
