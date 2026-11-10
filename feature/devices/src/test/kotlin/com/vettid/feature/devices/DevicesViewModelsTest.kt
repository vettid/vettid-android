// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val phone = DeviceInfo("me", "app", "Pixel", null, null, null, null, thisPhone = true)
    private val agent = DeviceInfo("a1", "agent", "Claude", null, now.minusSeconds(3600), now.minusSeconds(600), now.plusSeconds(1800))
    private val desktop = DeviceInfo("d1", "desktop", "Laptop", null, now.minusSeconds(7200), null, null)
    private val sharing = com.vettid.core.testing.FakeSharing()
    private val devices = FakeDevices().apply { this.devices.value = listOf(phone, agent, desktop) }

    private fun page(id: String) = DeviceViewModel(SavedStateHandle(mapOf(DeviceRoute.ARG to id)), devices, sharing, com.vettid.core.testing.FakeSocial())

    /** What a hold does to the list in memory (`DevicesManager.clear()`): empty, not loaded. */
    private fun holdClears() {
        devices.devices.value = emptyList()
        devices.load.value = ListLoad.NOT_LOADED
    }

    /** The answer of the next read, as `device.list` gives it. */
    private fun readGives() {
        devices.devices.value = listOf(phone, agent, desktop)
        devices.load.value = ListLoad.LOADED
    }

    /**
     * After a hold ends, an open list reads itself again (owner decision a, GC #7047, as vettid-ios #239): it no longer stays
     * on its spinner until the member leaves and comes back.
     */
    @Test
    fun anOpenListReadsAgainWhenTheHoldEnds() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.OK) }
        val vm = DevicesViewModel(devices, vault)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, devices.calls.count { it == "refresh" })
        vault.ownerCheck.value = check(OwnerCheckState.HELD)
        holdClears()
        advanceUntilIdle()
        assertTrue("the spinner while held", vm.uiState.value.loading)
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refresh" })
        readGives()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
    }

    /** A device's page does the same: the hold cleared the list it shows from. */
    @Test
    fun anOpenDevicePageReadsAgainWhenTheHoldEnds() = runTest {
        val vm = page("d1")
        advanceUntilIdle()
        assertEquals(1, devices.calls.count { it == "refresh" })
        holdClears()
        advanceUntilIdle()
        devices.readAgain.emit(Unit) // the manager: the hold ended
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refresh" })
        readGives()
        advanceUntilIdle()
        assertEquals("Laptop", vm.uiState.value.device?.name)
    }

    /** A daily check that passes while the list is shown and was never cleared reads nothing more. */
    @Test
    fun aPassedCheckWithAListShownReadsNothingMore() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.DUE) }
        val vm = DevicesViewModel(devices, vault)
        vm.refresh()
        val p = page("d1")
        advanceUntilIdle()
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        devices.readAgain.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refresh" })
        assertEquals("Laptop", p.uiState.value.device?.name)
        assertFalse(vm.uiState.value.loading)
    }

    private fun check(state: OwnerCheckState) = OwnerCheckView(state, null, 86_400, 0, hold = true, holdOffUntil = null)

    @Test
    fun theListIsReadWhenItOpensAndShowsWhatTheRepositoryHolds() = runTest {
        val vm = DevicesViewModel(devices, FakeVault())
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, devices.calls.count { it == "refresh" })
        assertEquals(3, vm.uiState.value.devices.size)
        assertFalse(vm.uiState.value.onlyThisPhone)
    }

    @Test
    fun thisPhoneAloneIsTheEmptyState() = runTest {
        devices.devices.value = listOf(phone)
        val vm = DevicesViewModel(devices, FakeVault())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.onlyThisPhone)
    }

    @Test
    fun aFailedReadIsShownAndReadsAgainWhenTheDailyCheckPasses() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.HELD) }
        devices.fail["refresh"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        val vm = DevicesViewModel(devices, vault)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals(2, devices.calls.count { it == "refresh" })
    }

    @Test
    fun theRemovedNameReachesTheListOnceUntilItIsShown() = runTest {
        val vm = DevicesViewModel(devices, FakeVault())
        advanceUntilIdle()
        devices.removed.value = agent
        advanceUntilIdle()
        assertEquals("Claude", vm.uiState.value.removedDevice?.name)
        vm.removedShown()
        advanceUntilIdle()
        assertNull(vm.uiState.value.removedDevice)
    }

    @Test
    fun endingAccessAsksFirstThenSendsOnceAndSaysSo() = runTest {
        val vm = page("a1")
        advanceUntilIdle()
        vm.askEndAccess()
        advanceUntilIdle()
        assertEquals(DeviceConfirm.END_ACCESS, vm.uiState.value.confirm)
        assertTrue("nothing is sent before the confirmation", devices.calls.none { it.startsWith("endAccess") })
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("endAccess:a1"), devices.calls.filter { it.startsWith("endAccess") })
        assertTrue(vm.uiState.value.accessEnded)
        assertNull(vm.uiState.value.device?.sessionExpiresAt)
        vm.accessEndedShown()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.accessEnded)
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() = runTest {
        val vm = page("a1")
        advanceUntilIdle()
        vm.askEndAccess()
        vm.dismissConfirm()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("endAccess") || it.startsWith("remove:") })
    }

    @Test
    fun aFailedEndOfAccessSaysItDidNotHappenAndKeepsTheSession() = runTest {
        val vm = page("a1")
        advanceUntilIdle()
        devices.fail["endAccess:a1"] = VaultFailure(FailureKind.NOT_FOUND)
        vm.askEndAccess()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(DeviceFailure.END_ACCESS, vm.uiState.value.failure)
        assertFalse(vm.uiState.value.accessEnded)
        assertEquals(now.plusSeconds(1800), vm.uiState.value.device?.sessionExpiresAt)
        vm.dismissFailure()
        advanceUntilIdle()
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun removingAnAgentNamesTheBoundFirstAndLeavesAfterwards() = runTest {
        devices.bound = 960
        val vm = page("a1")
        advanceUntilIdle()
        vm.askRemove()
        advanceUntilIdle()
        assertEquals(DeviceConfirm.REMOVE, vm.uiState.value.confirm)
        assertEquals(960L, vm.uiState.value.removalBoundSeconds)
        assertTrue("removalBound:a1" in devices.calls)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("remove:a1"), devices.calls.filter { it.startsWith("remove:") })
        assertTrue(vm.uiState.value.removed)
        // Told on the page (owner decision a, GC #7101): the list does not say it again.
        assertNull(devices.removed.value)
        assertTrue("dismissRemoved" in devices.calls)
    }

    /**
     * After Remove (owner decision a, GC #7101, as vettid-ios #239): the page itself says "<name> was removed" for 3 seconds,
     * with the device still shown and no action of it taken, then leaves.
     */
    @Test
    fun theRemovedNoticeStaysThreeSecondsOnThePageWithNoActions() = runTest {
        val vm = page("a1")
        advanceUntilIdle()
        vm.askRemove()
        advanceUntilIdle()
        vm.confirm()
        runCurrent()
        val s = vm.uiState.value
        assertEquals("Claude", s.removedDevice?.name)
        assertEquals("the device stays shown", "a1", s.device?.id)
        assertFalse(s.gone)
        assertFalse(s.removed)
        assertNull("the list does not repeat it", devices.removed.value)
        // No action while the notice is up.
        vm.askEndAccess()
        vm.askRemove()
        runCurrent()
        assertNull(vm.uiState.value.confirm)
        assertEquals(1, devices.calls.count { it == "removalBound:a1" })
        advanceTimeBy(REMOVED_NOTICE_MS - 1)
        assertFalse(vm.uiState.value.removed)
        advanceTimeBy(2)
        assertTrue("then it leaves", vm.uiState.value.removed)
        assertEquals(3_000L, REMOVED_NOTICE_MS)
    }

    /** (review Low 1) The list reads again on the repository's word alone (the collector that cleared it). */
    @Test
    fun theListReadsAgainOnTheRepositorysWordAlone() = runTest {
        val vm = DevicesViewModel(devices, FakeVault().apply { ownerCheck.value = check(OwnerCheckState.OK) })
        vm.refresh()
        advanceUntilIdle()
        holdClears()
        devices.readAgain.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, devices.calls.count { it == "refresh" })
    }

    @Test
    fun removingADesktopAsksNoBound() = runTest {
        val vm = page("d1")
        advanceUntilIdle()
        vm.askRemove()
        advanceUntilIdle()
        assertEquals(DeviceConfirm.REMOVE, vm.uiState.value.confirm)
        assertEquals(0L, vm.uiState.value.removalBoundSeconds)
        assertTrue(devices.calls.none { it.startsWith("removalBound") })
    }

    @Test
    fun aFailedRemovalKeepsTheDeviceAndSaysSo() = runTest {
        val vm = page("d1")
        advanceUntilIdle()
        devices.fail["remove:d1"] = VaultFailure(FailureKind.OTHER, "internal")
        vm.askRemove()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(DeviceFailure.REMOVE, vm.uiState.value.failure)
        assertFalse(vm.uiState.value.removed)
        assertEquals("d1", vm.uiState.value.device?.id)
    }

    @Test
    fun thisPhoneHasNoPageAndNothingIsSentForIt() = runTest {
        val vm = page("me")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        vm.askEndAccess()
        advanceUntilIdle()
        vm.confirm()
        vm.askRemove()
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("endAccess") || it.startsWith("remove") })
    }

    @Test
    fun aDeviceThatIsNoLongerListedIsGoneOnceTheListWasRead() = runTest {
        val vm = page("zzz")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        devices.load.value = ListLoad.NOT_LOADED
        advanceUntilIdle()
        assertFalse("not before the list was read", vm.uiState.value.gone)
    }

    @Test
    fun aListThatCannotBeReadIsShownOnThePageWithRetry() = runTest {
        devices.devices.value = emptyList()
        devices.fail["refresh"] = VaultFailure(FailureKind.NETWORK)
        val vm = page("a1")
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.loadError)
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.loadError)
        assertEquals(2, devices.calls.count { it == "refresh" })
    }

    @Test
    fun anotherAppEntryHasNoActions() = runTest {
        devices.devices.value = devices.devices.value + DeviceInfo("app2", "app", "Old phone", null, null, null, null)
        val vm = page("app2")
        advanceUntilIdle()
        vm.askEndAccess()
        vm.askRemove()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(devices.calls.none { it.startsWith("endAccess") || it.startsWith("remove") })
    }

    @Test
    fun neverAsksForTheCredentialPasswordOrTheUnlockWindow() = runTest {
        // The page's ViewModel holds the devices repository and, since AA4, the sharing repository to read the agent's rules:
        // ending access, removing and reading the rules (and, since AA3, removing a permission or resuming, with the connections' names) sign nothing (no password, no unlock window, no rule saved).
        val ctor = DeviceViewModel::class.java.constructors.single()
        assertEquals(listOf("SavedStateHandle", "DevicesRepository", "SharingRepository", "ConnectionsRepository"), ctor.parameterTypes.map { it.simpleName })
        val vm = page("a1")
        advanceUntilIdle()
        vm.refreshRules()
        vm.askEndAccess()
        vm.confirm()
        advanceUntilIdle()
        vm.askRemove()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(sharing.calls.all { it == "agentRules" })
        assertTrue(sharing.agentSaves.isEmpty())
    }

    @Test
    fun theAgentsRulesAreReadOnOpeningAndAgainOnReturn() = runTest {
        sharing.rulesStored += com.vettid.core.data.items.ShareRule("r1", 1, null, "a1", listOf("api"), perHour = 60, perDay = 1000)
        sharing.rulesStored += com.vettid.core.data.items.ShareRule("r2", 1, null, "other", listOf("x"))
        val vm = page("a1")
        advanceUntilIdle()
        assertEquals(listOf("r1"), vm.uiState.value.rules.map { it.ruleId })
        assertTrue(vm.uiState.value.rulesLoaded)
        sharing.rulesStored += com.vettid.core.data.items.ShareRule("r3", 1, null, "a1", listOf("ops"), perHour = 60, perDay = 1000)
        vm.refreshRules()
        advanceUntilIdle()
        assertEquals(listOf("r1", "r3"), vm.uiState.value.rules.map { it.ruleId })
    }

    @Test
    fun aDesktopsRulesAreNeverRead() = runTest {
        val vm = page("d1")
        advanceUntilIdle()
        vm.refreshRules()
        advanceUntilIdle()
        assertTrue("agentRules" !in sharing.calls)
        assertFalse(vm.uiState.value.rulesLoaded)
    }

    @Test
    fun aRuleListThatFailsKeepsWhatWasShownAndSaysSo() = runTest {
        val vm = page("a1")
        advanceUntilIdle()
        sharing.fail["agentRules"] = VaultFailure(FailureKind.NO_RESPONSE, "no_response")
        vm.refreshRules()
        advanceUntilIdle()
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.rulesError)
        vm.refreshRules()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.rulesError)
    }

    @Test
    fun theSavedNoticeComesFromTheSharingRepositoryAndClearsOnce() = runTest {
        val vm = page("a1")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.ruleSaved)
        sharing.savedFlow.value = "other"
        advanceUntilIdle()
        assertFalse(vm.uiState.value.ruleSaved) // another agent's rule was saved
        sharing.savedFlow.value = "a1"
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ruleSaved)
        vm.ruleSavedShown()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.ruleSaved)
    }
}
