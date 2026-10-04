// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.social.AcceptedConnection
import com.vettid.core.data.social.Approval
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
        val vm = ConnectionsViewModel(social)
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

    @Test
    fun acceptWaitsForTheApprovalAndReportsBadLinks() = runTest {
        val vm = AcceptViewModel(SavedStateHandle(), social)
        vm.setInput("not a link")
        vm.accept()
        advanceUntilIdle()
        assertEquals(FailureKind.INVITE_INVALID, vm.uiState.value.error)
        assertEquals(AcceptStep.INPUT, vm.uiState.value.step)

        social.acceptResult = AcceptedConnection("c5", null)
        vm.setInput(link())
        vm.accept()
        advanceUntilIdle()
        assertEquals(AcceptStep.WAITING, vm.uiState.value.step)
        assertNull(vm.uiState.value.sas)
        social.seed(listOf(FakeSocial.connection("c5", "Inviter", state = ConnectionState.ACTIVE)))
        advanceUntilIdle()
        assertEquals(AcceptStep.CONNECTED, vm.uiState.value.step)
        assertEquals("Inviter", vm.uiState.value.connectionName)
    }

    @Test
    fun aScannedLinkIsAcceptedAtOnce() = runTest {
        social.acceptResult = AcceptedConnection("c6", "123456")
        val vm = AcceptViewModel(SavedStateHandle(mapOf(AcceptRoute.ARG to link())), social)
        advanceUntilIdle()
        assertEquals(AcceptStep.WAITING, vm.uiState.value.step)
        assertEquals("123456", vm.uiState.value.sas)
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
