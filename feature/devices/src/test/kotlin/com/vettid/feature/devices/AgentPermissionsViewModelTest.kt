// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.AgentChange
import com.vettid.core.data.devices.AgentGrant
import com.vettid.core.data.devices.AgentPermissions
import com.vettid.core.data.devices.AgentResumption
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.GrantApproval
import com.vettid.core.data.devices.PermissionRemoval
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** An agent's page: its other permissions, Remove, the paused banner and Resume (ANDROID-PLAN 0.1.116 §4 AA3). */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPermissionsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val agent = DeviceInfo("a1", "agent", "Claude", null, now.minusSeconds(3600), null, now.plusSeconds(1800))
    private val desktop = DeviceInfo("d1", "desktop", "Laptop", null, null, null, null)
    private val g1 = AgentGrant("g1", "connection.list", 1, GrantApproval.ASK, 60, 1_000, null, emptyList(), 900)
    private val g2 = AgentGrant("g2", "message.send", 1, GrantApproval.AUTO, 5, 50, now.plusSeconds(86_400), listOf("c1", "gone"), 3_600)
    private val devices = FakeDevices().apply {
        this.devices.value = listOf(agent, desktop)
        grantsHeld["a1"] = AgentPermissions(listOf(g1, g2), suspended = false)
    }
    private val sharing = FakeSharing()
    private val social = FakeSocial().apply { seed(listOf(ConnectionInfo("c1", "sam", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee"))) }

    private fun page(id: String = "a1") = DeviceViewModel(SavedStateHandle(mapOf(DeviceRoute.ARG to id)), devices, sharing, social)

    @Test
    fun openingAnAgentReadsItsGrantsAndNamesTheConnections() = runTest {
        val vm = page()
        advanceUntilIdle()
        assertEquals(1, devices.calls.count { it == "refreshPermissions:a1" })
        assertEquals(listOf("g1", "g2"), vm.uiState.value.permissions.map { it.grantId })
        assertTrue(vm.uiState.value.permissionsLoaded)
        assertEquals("Sam Lee", vm.uiState.value.connectionNames["c1"])
        assertFalse("gone" in vm.uiState.value.connectionNames)
    }

    @Test
    fun aDesktopReadsNoGrants() = runTest {
        val vm = page("d1")
        advanceUntilIdle()
        vm.refreshPermissions()
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("refreshPermissions") })
        assertTrue(vm.uiState.value.permissions.isEmpty())
    }

    @Test
    fun anAgentWithOnlyShareRulesHasNoOtherPermissions() = runTest {
        devices.grantsHeld["a1"] = AgentPermissions(emptyList(), false)
        val vm = page()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.permissionsLoaded)
        assertTrue(vm.uiState.value.permissions.isEmpty())
    }

    @Test
    fun aFailedFirstReadIsShownAndANewerGoodOneClearsIt() = runTest {
        devices.fail["refreshPermissions:a1"] = VaultFailure(FailureKind.NETWORK)
        val vm = page()
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.permissionsError)
        assertFalse(vm.uiState.value.permissionsLoaded)
        vm.refreshPermissions()
        advanceUntilIdle()
        assertNull(vm.uiState.value.permissionsError)
        assertTrue(vm.uiState.value.permissionsLoaded)
    }

    // --- the re-read triggers ---

    @Test
    fun aGrantEventOfThisAgentReadsTheGrantsAgainAndAnotherAgentsDoesNot() = runTest {
        val vm = page()
        advanceUntilIdle()
        devices.changes.tryEmit(AgentChange.Grants("a1"))
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refreshPermissions:a1" })
        devices.changes.tryEmit(AgentChange.Grants("someone-else"))
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refreshPermissions:a1" })
        assertEquals(2, vm.uiState.value.permissions.size)
    }

    @Test
    fun aRuleEventReadsTheGrantsAndTheRulesAgain() = runTest {
        page()
        advanceUntilIdle()
        val rules = sharing.calls.count { it == "agentRules" }
        devices.changes.tryEmit(AgentChange.Rule("r1"))
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refreshPermissions:a1" })
        assertEquals(rules + 1, sharing.calls.count { it == "agentRules" })
    }

    @Test
    fun aDesktopPageIgnoresEvents() = runTest {
        page("d1")
        advanceUntilIdle()
        devices.changes.tryEmit(AgentChange.Rule("r1"))
        devices.changes.tryEmit(AgentChange.Grants("d1"))
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("refreshPermissions") })
        assertTrue(sharing.calls.none { it == "agentRules" })
    }

    // --- Remove ---

    @Test
    fun removeAsksFirstAndSendsNothingUntilConfirmed() = runTest {
        val vm = page()
        advanceUntilIdle()
        vm.askRemovePermission("g2")
        advanceUntilIdle()
        assertEquals(DeviceConfirm.REMOVE_PERMISSION, vm.uiState.value.confirm)
        assertEquals("g2", vm.uiState.value.confirmGrant?.grantId)
        assertTrue(devices.calls.none { it.startsWith("removePermission") })
        vm.dismissConfirm()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("removePermission") })
        assertNull(vm.uiState.value.confirmGrant)
    }

    @Test
    fun confirmingRemovesThatGrantOnceSaysSoAndTheRowGoes() = runTest {
        val vm = page()
        advanceUntilIdle()
        vm.askRemovePermission("g2")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("removePermission:g2"), devices.calls.filter { it.startsWith("removePermission") })
        assertEquals(PermissionNotice.REMOVED, vm.uiState.value.notice)
        assertEquals(listOf("g1"), vm.uiState.value.permissions.map { it.grantId })
        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.failure)
        vm.noticeShown()
        advanceUntilIdle()
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun aGrantThatWasAlreadyRemovedIsToldSo() = runTest {
        devices.removal = PermissionRemoval.ALREADY_GONE
        val vm = page()
        advanceUntilIdle()
        vm.askRemovePermission("g1")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(PermissionNotice.ALREADY_REMOVED, vm.uiState.value.notice)
    }

    @Test
    fun anyOtherFailureShowsItsErrorAndKeepsTheRow() = runTest {
        val vm = page()
        advanceUntilIdle()
        devices.fail["removePermission:g1"] = VaultFailure(FailureKind.OTHER, "internal")
        vm.askRemovePermission("g1")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(DeviceFailure.REMOVE_PERMISSION, vm.uiState.value.failure)
        assertEquals(FailureKind.OTHER, vm.uiState.value.error)
        assertEquals(listOf("g1", "g2"), vm.uiState.value.permissions.map { it.grantId })
        assertNull(vm.uiState.value.notice)
        assertFalse("a refused removal does not read the device list", devices.calls.count { it == "refresh" } > 1)
        vm.dismissFailure()
        advanceUntilIdle()
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun aGrantThatIsNotListedCannotBeAskedAbout() = runTest {
        val vm = page()
        advanceUntilIdle()
        vm.askRemovePermission("nope")
        assertNull(vm.uiState.value.confirm)
    }

    // --- the paused banner and Resume ---

    @Test
    fun anAgentInSuspendedIsPausedOnItsPage() = runTest {
        devices.grantsHeld["a1"] = AgentPermissions(listOf(g1), suspended = true)
        val vm = page()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.device?.paused == true)
    }

    @Test
    fun resumeSendsOnceWithNoConfirmationAndSaysResumed() = runTest {
        devices.grantsHeld["a1"] = AgentPermissions(listOf(g1), suspended = true)
        val vm = page()
        advanceUntilIdle()
        vm.resume()
        advanceUntilIdle()
        assertEquals(listOf("resume:a1"), devices.calls.filter { it.startsWith("resume") })
        assertEquals(PermissionNotice.RESUMED, vm.uiState.value.notice)
        assertFalse("the banner goes with the pause", vm.uiState.value.device?.paused == true)
    }

    @Test
    fun resumeNotFoundSaysItIsNoLongerPaused() = runTest {
        devices.grantsHeld["a1"] = AgentPermissions(listOf(g1), suspended = true)
        devices.resumption = AgentResumption.NOT_PAUSED
        val vm = page()
        advanceUntilIdle()
        vm.resume()
        advanceUntilIdle()
        assertEquals(PermissionNotice.NOT_PAUSED, vm.uiState.value.notice)
    }

    @Test
    fun aFailedResumeKeepsTheBannerAndSaysSo() = runTest {
        devices.grantsHeld["a1"] = AgentPermissions(listOf(g1), suspended = true)
        val vm = page()
        advanceUntilIdle()
        devices.fail["resume:a1"] = VaultFailure(FailureKind.NETWORK)
        vm.resume()
        advanceUntilIdle()
        assertEquals(DeviceFailure.RESUME, vm.uiState.value.failure)
        assertTrue(vm.uiState.value.device?.paused == true)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun resumeIsNotSentForAnAgentThatIsNotPausedOrForADesktop() = runTest {
        val vm = page()
        advanceUntilIdle()
        vm.resume()
        page("d1").resume()
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("resume") })
    }

    // --- privacy ---

    @Test
    fun nothingTheStateHoldsIsPrinted() = runTest {
        val vm = page()
        advanceUntilIdle()
        vm.askRemovePermission("g2")
        advanceUntilIdle()
        val printed = vm.uiState.value.toString() + vm.uiState.value.permissions + vm.uiState.value.confirmGrant
        assertEquals("DeviceUiState" + "[AgentGrant, AgentGrant]" + "AgentGrant", printed)
        for (secret in listOf("Sam", "Lee", "Claude", "c1", "g2", "gone")) assertFalse(secret, printed.contains(secret))
    }
}
