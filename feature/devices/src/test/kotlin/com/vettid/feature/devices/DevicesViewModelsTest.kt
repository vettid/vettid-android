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
import kotlinx.coroutines.test.advanceUntilIdle
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
    private val devices = FakeDevices().apply { this.devices.value = listOf(phone, agent, desktop) }

    private fun page(id: String) = DeviceViewModel(SavedStateHandle(mapOf(DeviceRoute.ARG to id)), devices)

    private fun check(state: OwnerCheckState) = OwnerCheckView(state, null, 86_400, 0, hold = true, holdOffUntil = null)

    @Test
    fun theListIsReadWhenItOpensAndShowsWhatTheRepositoryHolds() = runTest {
        val vm = DevicesViewModel(devices, FakeVault())
        advanceUntilIdle()
        assertTrue("refresh" in devices.calls)
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
        devices.removed.value = "Claude"
        advanceUntilIdle()
        assertEquals("Claude", vm.uiState.value.removedName)
        vm.removedShown()
        advanceUntilIdle()
        assertNull(vm.uiState.value.removedName)
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
        assertEquals("Claude", devices.removed.value)
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
    fun neverAsksForTheCredentialPasswordOrTheUnlockWindow() {
        // The page's ViewModel holds only the devices repository: it has no way to sign anything.
        val ctor = DeviceViewModel::class.java.constructors.single()
        assertEquals(listOf("SavedStateHandle", "DevicesRepository"), ctor.parameterTypes.map { it.simpleName })
    }
}
