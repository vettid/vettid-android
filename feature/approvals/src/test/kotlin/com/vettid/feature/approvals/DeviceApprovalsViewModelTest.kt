// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import com.vettid.core.data.devices.PendingPairing
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.DeviceDecision
import com.vettid.core.data.social.HeldRequest
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakePairing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * ANDROID-PLAN 0.1.116 §4 AA5: Allow and Deny on an access request, Approve and Deny on a held approval, deny-only when
 * the details are gone, and how each answer reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceApprovalsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.now()
    private val social = FakeSocial()
    private val devices = FakeDevices()
    private val pairing = FakePairing()
    private val items = FakeItems().apply { add(FakeItems.item("i1", "Bank PIN", Sensitivity.SECRET, fields = listOf("PIN" to "1234"))) }

    private fun agent(session: Instant? = null) = DeviceInfo("A", "agent", "Build bot", null, null, null, session)

    private fun session(seconds: Int? = 5_400, fromFeed: Boolean = false, role: String? = "agent") =
        Approval.DeviceRequest(
            "device.session.pending", "s1", if (fromFeed) null else "Build bot", role, null, now, now.plusSeconds(600),
            deviceId = "A", seconds = seconds.takeIf { !fromFeed }, fromFeed = fromFeed,
        )

    private fun heldAsk(h: HeldRequest?, fromFeed: Boolean = false) =
        Approval.DeviceRequest(
            "approval.pending", "h1", if (fromFeed) null else "Build bot", "agent", "agent.request", now, now.plusSeconds(300),
            deviceId = "A", held = h, fromFeed = fromFeed,
        )

    private fun vm(a: Approval.DeviceRequest, list: List<DeviceInfo> = listOf(agent())): ApprovalDetailViewModel {
        social.approvals.value = listOf(a)
        devices.devices.value = list
        return ApprovalDetailViewModel(SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to a.key)), social, items, devices, FakeVault(), social, pairing)
    }

    // --- an access request ---

    @Test
    fun allowSendsTheRequestedLengthByDefaultAndTheChosenOneWhenChosen() = runTest {
        val a = vm(session())
        runCurrent()
        assertTrue(a.uiState.value.canApprove)
        assertEquals(5_400, a.uiState.value.allowSeconds)
        a.approve()
        runCurrent()
        assertEquals("device:device.session.pending:s1" to 5_400, social.lastDeviceApproval)

        social.calls.clear()
        val b = vm(session())
        runCurrent()
        b.chooseSeconds(900)
        runCurrent()
        assertEquals(900, b.uiState.value.allowSeconds)
        b.approve()
        runCurrent()
        assertEquals("device:device.session.pending:s1" to 900, social.lastDeviceApproval)
    }

    @Test
    fun allowShowsTheEndOfTheAccessAndDoesNotCloseUntilTheMemberDoes() = runTest {
        val until = now.plusSeconds(900)
        social.deviceDecision = DeviceDecision(expiresAt = until)
        val m = vm(session())
        runCurrent()
        m.approve()
        runCurrent()
        assertEquals(DeviceOutcome.Allowed(until), m.uiState.value.outcome)
        assertFalse(m.uiState.value.done)
        assertFalse("the screen is not 'gone' under its outcome", m.uiState.value.gone)
        m.finish()
        runCurrent()
        assertTrue(m.uiState.value.done)
    }

    @Test
    fun denySendsDenyOnly() = runTest {
        val m = vm(session())
        runCurrent()
        m.deny()
        runCurrent()
        assertTrue("declineDeviceRequest" in social.calls)
        assertFalse("approveDeviceRequest" in social.calls)
        assertTrue(m.uiState.value.done)
    }

    @Test
    fun aRequestKnownOnlyFromItsFeedItemIsAllowedForAListedDeviceWithOneHour() = runTest {
        val m = vm(session(fromFeed = true))
        runCurrent()
        val s = m.uiState.value
        assertEquals("Build bot", (s.approval as Approval.DeviceRequest).deviceName)
        assertTrue(s.deviceListed)
        assertTrue(s.canApprove)
        assertEquals(3_600, s.allowSeconds)
        m.approve()
        runCurrent()
        assertEquals("device:device.session.pending:s1" to 3_600, social.lastDeviceApproval)
    }

    @Test
    fun aFeedOnlyRequestOfADeviceNotInTheListOffersDenyOnly() = runTest {
        val m = vm(session(fromFeed = true), list = emptyList())
        runCurrent()
        assertFalse(m.uiState.value.deviceListed)
        assertFalse(m.uiState.value.canApprove)
        m.approve()
        runCurrent()
        assertFalse("approveDeviceRequest" in social.calls)
        m.deny()
        runCurrent()
        assertTrue("declineDeviceRequest" in social.calls)
    }

    @Test
    fun aFeedOnlyRequestWhenTheListCannotBeReadOffersDenyOnly() = runTest {
        devices.fail["refresh"] = VaultFailure(FailureKind.NETWORK)
        val m = vm(session(fromFeed = true))
        runCurrent()
        assertFalse(m.uiState.value.canApprove)
    }

    @Test
    fun anErrorAnswerIsNoLongerWaiting() = runTest {
        for (kind in listOf(FailureKind.NOT_FOUND, FailureKind.OTHER)) {
            social.fail["approveDeviceRequest"] = VaultFailure(kind, "whatever")
            val m = vm(session())
            runCurrent()
            m.approve()
            runCurrent()
            assertEquals(kind.name, DeviceOutcome.NoLongerWaiting, m.uiState.value.outcome)
            assertNull(m.uiState.value.error)
        }
        // The same for a deny.
        social.fail["declineDeviceRequest"] = VaultFailure(FailureKind.NOT_FOUND)
        val d = vm(session())
        runCurrent()
        d.deny()
        runCurrent()
        assertEquals(DeviceOutcome.NoLongerWaiting, d.uiState.value.outcome)
    }

    @Test
    fun theOwnerCheckIsNotNoLongerWaiting() = runTest {
        social.fail["approveDeviceRequest"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val m = vm(session())
        runCurrent()
        m.approve()
        runCurrent()
        assertNull(m.uiState.value.outcome)
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.uiState.value.error)
    }

    @Test
    fun aRenewalCanBeAllowedToo() = runTest {
        val m = vm(session(), list = listOf(agent(session = now.plusSeconds(3_000))))
        runCurrent()
        assertNotNull(m.uiState.value.renewalUntil)
        assertTrue(m.uiState.value.canApprove)
    }

    // --- a held approval ---

    private val use = HeldRequest.ItemUse("i1", "f1", "challenge".toByteArray())

    @Test
    fun anItemUseIsApprovedWithApprovalDecideAndTheResultIsKept() = runTest {
        social.deviceDecision = DeviceDecision(result = "forbidden")
        val m = vm(heldAsk(use))
        runCurrent()
        assertTrue(m.uiState.value.canApprove)
        m.approve()
        runCurrent()
        assertEquals("device:approval.pending:h1" to null, social.lastDeviceApproval)
        assertEquals(DeviceOutcome.Approved("forbidden"), m.uiState.value.outcome)
        assertFalse(m.uiState.value.done)
    }

    @Test
    fun denyOfAHeldApprovalSaysDenied() = runTest {
        val m = vm(heldAsk(use))
        runCurrent()
        m.deny()
        runCurrent()
        assertTrue("declineDeviceRequest" in social.calls)
        assertEquals(DeviceOutcome.Denied, m.uiState.value.outcome)
    }

    @Test
    fun everythingTheAppCannotShowIsDenyOnly() = runTest {
        val unapprovable = listOf(
            heldAsk(HeldRequest.Later("i1")),
            heldAsk(HeldRequest.DesktopStepUp),
            heldAsk(HeldRequest.Unshown),
            heldAsk(null),
            // No details (after a restart): deny-only.
            heldAsk(null, fromFeed = true),
            // An item or field the phone no longer has.
            heldAsk(HeldRequest.ItemUse("gone", "f1", byteArrayOf(1))),
            heldAsk(HeldRequest.ItemUse("i1", "nope", byteArrayOf(1))),
            heldAsk(HeldRequest.ItemGet("gone", null)),
        )
        for (a in unapprovable) {
            social.calls.clear()
            val m = vm(a)
            runCurrent()
            assertFalse(a.toString(), m.uiState.value.canApprove)
            m.approve()
            runCurrent()
            assertFalse("never approved unseen", "approveDeviceRequest" in social.calls)
            m.deny()
            runCurrent()
            assertTrue("declineDeviceRequest" in social.calls)
        }
    }

    @Test
    fun theItemCacheNotLoadedYetIsDenyOnlyUntilItIs() = runTest {
        val empty = FakeItems()
        social.approvals.value = listOf(heldAsk(use))
        val m = ApprovalDetailViewModel(
            SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to heldAsk(use).key)), social, empty, devices, FakeVault(), social, pairing,
        )
        runCurrent()
        assertFalse(m.uiState.value.canApprove)
        empty.add(FakeItems.item("i1", "Bank PIN", Sensitivity.SECRET, fields = listOf("PIN" to "1234")))
        runCurrent()
        assertTrue(m.uiState.value.canApprove)
    }

    @Test
    fun theCatalogAndAnItemGetAreApprovable() = runTest {
        assertTrue(vm(heldAsk(HeldRequest.Catalog)).also { runCurrent() }.uiState.value.canApprove)
        assertTrue(vm(heldAsk(HeldRequest.ItemGet("i1", listOf("f1")))).also { runCurrent() }.uiState.value.canApprove)
        assertTrue(vm(heldAsk(HeldRequest.ItemGet("i1", null))).also { runCurrent() }.uiState.value.canApprove)
    }

    // --- a pairing known from its feed item ---

    @Test
    fun aPairingOffersNothingButTheComparisonWhenThePhoneHoldsIt() = runTest {
        val p = Approval.DeviceRequest("device.pair.pending", "p1", null, null, null, now, now.plusSeconds(600), fromFeed = true)
        val m = vm(p)
        runCurrent()
        assertFalse(m.uiState.value.canApprove)
        assertFalse(m.uiState.value.pairingHeld)
        m.approve()
        runCurrent()
        assertFalse("approveDeviceRequest" in social.calls)

        pairing.state.value = PairingState(PairingStage.Compare(PendingPairing("pp", "New bot", "123456", now.plusSeconds(600))))
        runCurrent()
        assertTrue(m.uiState.value.pairingHeld)
        assertEquals("New bot", m.uiState.value.pairingName)
        // Still nothing is approved from here: the comparison is its own screen.
        assertFalse(m.uiState.value.canApprove)
    }
}
