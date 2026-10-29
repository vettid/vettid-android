package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.social.Approval
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** VAULT-MESSAGING 0.78.0 §6.8 step 3: renewal detection and the "Deny and end access now" flow. */
@OptIn(ExperimentalCoroutinesApi::class)
class RenewalTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.now()
    private val live = now.plusSeconds(3_600)

    private fun device(id: String = "dev1", expires: Instant? = live, self: Boolean = false) =
        DeviceInfo(id, "desktop", "Laptop", null, null, null, expires, thisPhone = self)

    private fun request(deviceId: String? = "dev1", type: String = "device.session.pending") =
        Approval.DeviceRequest(type, "r1", "Laptop", "desktop", null, now, now.plusSeconds(600), deviceId = deviceId)

    // --- detection ---

    @Test
    fun aLiveSessionIsARenewal() {
        assertEquals(live, Renewal.expiresAt(request(), listOf(device()), true, now))
    }

    @Test
    fun anExpiredSessionIsAFirstRequest() {
        assertNull(Renewal.expiresAt(request(), listOf(device(expires = now.minusSeconds(1))), true, now))
        assertNull(Renewal.expiresAt(request(), listOf(device(expires = now)), true, now))
    }

    @Test
    fun noSessionIsAFirstRequest() {
        assertNull(Renewal.expiresAt(request(), listOf(device(expires = null)), true, now))
        assertNull(Renewal.expiresAt(request(), listOf(device(id = "other")), true, now))
        assertNull(Renewal.expiresAt(request(deviceId = null), listOf(device()), true, now))
        assertNull(Renewal.expiresAt(request(type = "approval.pending"), listOf(device()), true, now))
    }

    @Test
    fun aListThatCouldNotBeReadIsAFirstRequest() {
        assertNull(Renewal.expiresAt(request(), listOf(device()), false, now))
    }

    // --- the screen's model ---

    private val social = FakeSocial()
    private val devices = FakeDevices()
    private val vault = FakeVault()

    private fun vm(r: Approval.DeviceRequest = request(), list: List<DeviceInfo> = listOf(device())): ApprovalDetailViewModel {
        social.approvals.value = listOf(r)
        devices.devices.value = list
        return ApprovalDetailViewModel(
            SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to r.key)),
            social,
            com.vettid.core.testing.FakeItems(),
            devices,
            vault,
        )
    }

    @Test
    fun aRenewalOffersTheEndChoiceAndAFirstRequestDoesNot() = runTest {
        val renewal = vm()
        runCurrent()
        assertEquals(live, renewal.uiState.value.renewalUntil)
        assertFalse(renewal.uiState.value.canApprove)
        assertTrue("refresh" in devices.calls)

        val first = vm(list = listOf(device(expires = null)))
        runCurrent()
        assertNull(first.uiState.value.renewalUntil)
        assertFalse(first.uiState.value.canApprove)
    }

    @Test
    fun aFailedListReadShowsAFirstRequestAndBlocksNothing() = runTest {
        devices.fail["refresh"] = VaultFailure(FailureKind.NETWORK)
        val m = vm()
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
        assertNotNull(m.uiState.value.approval)
        assertNull(m.uiState.value.error)
        assertFalse(m.uiState.value.gone)
        m.deny()
        runCurrent()
        assertTrue("declineDeviceRequest" in social.calls)
    }

    @Test
    fun denyAndEndSendsOnlyTheEndAfterTheConfirmation() = runTest {
        val m = vm()
        runCurrent()
        m.askEndAccess()
        runCurrent()
        assertTrue(m.uiState.value.confirmEnd)
        assertFalse(devices.calls.any { it.startsWith("endAccess") })
        m.endAccess()
        runCurrent()
        assertEquals(listOf("endAccess:dev1"), devices.calls.filter { it.startsWith("endAccess") })
        // The deny is not sent as well.
        assertFalse("declineDeviceRequest" in social.calls)
        assertTrue(m.uiState.value.done)
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() = runTest {
        val m = vm()
        runCurrent()
        m.askEndAccess()
        runCurrent()
        m.dismissEndAccess()
        runCurrent()
        m.endAccess()
        runCurrent()
        assertFalse(devices.calls.any { it.startsWith("endAccess") })
    }

    @Test
    fun aFirstRequestHasNoEndAccess() = runTest {
        val m = vm(list = listOf(device(expires = null)))
        runCurrent()
        m.askEndAccess()
        runCurrent()
        assertFalse(m.uiState.value.confirmEnd)
    }

    @Test
    fun aFailedEndKeepsTheRequestAndCanBeTriedAgain() = runTest {
        devices.fail["endAccess:dev1"] = VaultFailure(FailureKind.NETWORK)
        val m = vm()
        runCurrent()
        m.askEndAccess()
        runCurrent()
        m.endAccess()
        runCurrent()
        val s = m.uiState.value
        assertEquals(FailureKind.NETWORK, s.endFailure?.kind)
        assertFalse(s.done)
        assertFalse(s.gone)
        assertFalse(s.busy)
        assertNotNull(s.approval)
        assertEquals(live, s.renewalUntil)
        assertFalse("declineDeviceRequest" in social.calls)
        // Try again: the confirmation again, then the end.
        m.askEndAccess()
        runCurrent()
        assertNull(m.uiState.value.endFailure)
        assertTrue(m.uiState.value.confirmEnd)
        m.endAccess()
        runCurrent()
        assertTrue(m.uiState.value.done)
        assertEquals(2, devices.calls.count { it == "endAccess:dev1" })
    }

    @Test
    fun theNoticeGoesWhenTheSessionPassesWhileShown() = runTest {
        val soon = Instant.now().plusMillis(5_000)
        val m = vm(list = listOf(device(expires = soon)))
        runCurrent()
        assertEquals(soon, m.uiState.value.renewalUntil)
        advanceTimeBy(6_000)
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
        assertFalse(m.uiState.value.gone)
    }

    @Test
    fun aConfirmationAlreadyOpenStaysAndEndsAccessAfterTheSessionPassed() = runTest {
        val soon = Instant.now().plusMillis(5_000)
        val m = vm(list = listOf(device(expires = soon)))
        runCurrent()
        m.askEndAccess()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
        assertTrue(m.uiState.value.confirmEnd)
        m.endAccess()
        runCurrent()
        assertTrue("endAccess:dev1" in devices.calls)
    }

    @Test
    fun aLaterSessionEventMakesItARenewal() = runTest {
        val m = vm(list = listOf(device(expires = null)))
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
        // `sync.event{kind: "device.session"}` re-reads the list (the repository): the new session shows.
        devices.devices.value = listOf(device(expires = live))
        runCurrent()
        assertEquals(live, m.uiState.value.renewalUntil)
    }

    @Test
    fun theRequestLeavesWhenDecidedElsewhereOrTheSessionEnds() = runTest {
        val m = vm()
        runCurrent()
        social.approvals.value = emptyList()
        runCurrent()
        assertTrue(m.uiState.value.gone)
    }

    @Test
    fun heldTheRequestLeavesAndAnOpenConfirmationEndsNothing() = runTest {
        val m = vm()
        runCurrent()
        m.askEndAccess()
        runCurrent()
        vault.ownerCheck.value = OwnerCheckView(OwnerCheckState.HELD, null, 86_400, 0, hold = true, holdOffUntil = null)
        runCurrent()
        assertTrue(m.uiState.value.gone)
        m.endAccess()
        runCurrent()
        assertFalse(devices.calls.any { it.startsWith("endAccess") })
        // The hold closes the confirmation.
        assertFalse(m.uiState.value.confirmEnd)
    }

    @Test
    fun aStepUpRequestStillOnlyDeclines() = runTest {
        val r = request(type = "approval.pending")
        val m = vm(r)
        runCurrent()
        assertFalse(m.uiState.value.canApprove)
        assertNull(m.uiState.value.renewalUntil)
        assertFalse("refresh" in devices.calls)
    }

    @Test
    fun aConfirmationOpenWhenTheRequestReachesItsExpiryStaysAndEndsAccess() = runTest {
        val soon = Instant.now().plusMillis(5_000)
        val m = vm(r = request().copy(exp = soon))
        runCurrent()
        m.askEndAccess()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(m.uiState.value.gone)
        assertFalse(m.uiState.value.held)
        assertTrue(m.uiState.value.confirmEnd)
        m.endAccess()
        runCurrent()
        assertTrue("endAccess:dev1" in devices.calls)
    }

    @Test
    fun aConfirmationOpenWhenAnotherDeviceDecidesStays() = runTest {
        val m = vm()
        runCurrent()
        m.askEndAccess()
        runCurrent()
        social.approvals.value = emptyList()
        runCurrent()
        assertTrue(m.uiState.value.gone)
        assertTrue(m.uiState.value.confirmEnd)
        m.endAccess()
        runCurrent()
        assertTrue("endAccess:dev1" in devices.calls)
    }

    @Test
    fun heldIsReportedSoTheConfirmationIsNotDrawn() = runTest {
        val m = vm()
        runCurrent()
        vault.ownerCheck.value = OwnerCheckView(OwnerCheckState.HELD, null, 86_400, 0, hold = true, holdOffUntil = null)
        runCurrent()
        assertTrue(m.uiState.value.held)
    }

    @Test
    fun aFailureLeavesWhenTheSessionEndsAndTheRetryIsNeverDead() = runTest {
        devices.fail["endAccess:dev1"] = VaultFailure(FailureKind.NETWORK)
        val m = vm()
        runCurrent()
        m.askEndAccess()
        m.endAccess()
        runCurrent()
        assertNotNull(m.uiState.value.endFailure)
        // A sync event re-reads the list: the session ended meanwhile; it is a first request, with no failure to retry.
        devices.devices.value = listOf(device(expires = null))
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
        assertNull(m.uiState.value.endFailure)
        assertFalse(m.uiState.value.gone)
    }

    @Test
    fun aSyncDrivenSessionEndFlipsBackToAFirstRequest() = runTest {
        val m = vm()
        runCurrent()
        assertEquals(live, m.uiState.value.renewalUntil)
        devices.devices.value = listOf(device(expires = null))
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
    }

    @Test
    fun aLaterSuccessfulReadAfterAFailedOneCanMakeItARenewal() = runTest {
        devices.fail["refresh"] = VaultFailure(FailureKind.NETWORK)
        val m = vm()
        runCurrent()
        assertNull(m.uiState.value.renewalUntil)
        // The repository's quiet re-read (after `sync.event` kind `device.session`) succeeds.
        devices.load.value = com.vettid.core.data.items.ListLoad.LOADED
        runCurrent()
        assertEquals(live, m.uiState.value.renewalUntil)
    }

    @Test
    fun aRefusedDailyCheckIsSaidInTheVaultsWords() = runTest {
        devices.fail["endAccess:dev1"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val m = vm()
        runCurrent()
        m.askEndAccess()
        m.endAccess()
        runCurrent()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.uiState.value.endFailure?.kind)
        assertFalse(m.uiState.value.done)
        assertNotNull(m.uiState.value.approval)
    }

    @Test
    fun aFailedEndFromTheGoneScreenIsSaidAndCanBeRetried() = runTest {
        val soon = Instant.now().plusMillis(5_000)
        val m = vm(r = request().copy(exp = soon))
        runCurrent()
        m.askEndAccess()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(m.uiState.value.gone)
        devices.fail["endAccess:dev1"] = VaultFailure(FailureKind.NETWORK)
        m.endAccess()
        runCurrent()
        var s = m.uiState.value
        assertTrue(s.gone)
        assertFalse(s.ending)
        assertEquals(FailureKind.NETWORK, s.endFailure?.kind)
        // The session is still live: Try again reopens the confirmation on the gone screen.
        assertNotNull(s.renewalUntil)
        m.askEndAccess()
        runCurrent()
        assertTrue(m.uiState.value.confirmEnd)
        m.endAccess()
        runCurrent()
        s = m.uiState.value
        assertTrue(s.done)
        assertEquals(2, devices.calls.count { it == "endAccess:dev1" })
    }

    @Test
    fun theGoneScreenStaysWhileAccessIsBeingEnded() = runTest {
        val m = vm()
        runCurrent()
        m.askEndAccess()
        runCurrent()
        social.approvals.value = emptyList()
        runCurrent()
        m.endAccess()
        // Sent but not yet run: the screen has not flickered back to the request.
        assertTrue(m.uiState.value.busy || m.uiState.value.gone)
        runCurrent()
        assertTrue(m.uiState.value.done)
    }

    @Test
    fun aFailureRecordedAfterTheSessionPassedIsStillShown() = runTest {
        val m = vm(list = listOf(device(expires = null)))
        runCurrent()
        // An open confirmation cannot be asked for a first request, so the end is set up through a live session first.
        devices.devices.value = listOf(device(expires = live))
        runCurrent()
        m.askEndAccess()
        runCurrent()
        devices.devices.value = listOf(device(expires = null))
        runCurrent()
        devices.fail["endAccess:dev1"] = VaultFailure(FailureKind.NETWORK)
        m.endAccess()
        runCurrent()
        assertNotNull(m.uiState.value.endFailure)
        assertNull(m.uiState.value.renewalUntil)
    }
}
