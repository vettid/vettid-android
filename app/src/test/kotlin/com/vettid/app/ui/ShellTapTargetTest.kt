package com.vettid.app.ui

import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.wallet.NoWalletAccess
import com.vettid.core.notify.NotificationOpenInbox
import com.vettid.core.notify.NotifyVisibility
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeFeed
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.testing.FakeVotes
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * (GC #7254, as vettid-ios 7103f64) A tapped `device.update_needed` notification goes where the Notifications screen opens it:
 * the named device's page while the device list in memory has it, else the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellTapTargetTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val devices = FakeDevices()

    private fun vm(): ShellViewModel {
        val social = FakeSocial()
        return ShellViewModel(
            social, social, InviteLinkInbox(), FakeVault(), FakeFeed(), InMemoryPreferencesRepository(), NotificationOpenInbox(),
            NotifyVisibility(), FakeVotes(), NoWalletAccess, FakePresence(), devices = devices,
        )
    }

    private val item = FeedItem("u1", 1, "device.update_needed", "2026-11-10T12:00:00Z", "active", "high", deviceId = "d1")

    @Test
    fun aTappedUpdateNoticeOpensTheListedDevicesPageElseTheList() = runTest {
        val vm = vm()
        assertEquals("the list not read", FeedTarget.DeviceList, vm.tapTarget(item))
        devices.devices.value = listOf(DeviceInfo("d1", "desktop", "Laptop", null, null, null, null))
        assertEquals(FeedTarget.Device("d1"), vm.tapTarget(item))
        devices.devices.value = listOf(DeviceInfo("d2", "desktop", "Other", null, null, null, null))
        assertEquals("the device gone", FeedTarget.DeviceList, vm.tapTarget(item))
        // An item naming this phone: the list, not a page that would close at once.
        devices.devices.value = listOf(DeviceInfo("d1", "app", "Pixel", null, null, null, null, thisPhone = true))
        assertEquals(FeedTarget.DeviceList, vm.tapTarget(item))
    }

    /** (Review Low 1) A tap finds the list unread: it is read first, briefly; a read that fails or takes too long opens the list. */
    @Test
    fun aTapWithTheListUnreadReadsItFirst() = runTest {
        val vm = vm()
        devices.load.value = ListLoad.NOT_LOADED
        devices.onRefresh = { devices.devices.value = listOf(DeviceInfo("d1", "desktop", "Laptop", null, null, null, null)) }
        assertEquals(FeedTarget.Device("d1"), vm.tapTarget(item))
        assertEquals(1, devices.calls.count { it == "refresh" })
        // Read already: no second read.
        vm.tapTarget(item)
        assertEquals(1, devices.calls.count { it == "refresh" })
        // A read that fails.
        devices.devices.value = emptyList()
        devices.load.value = ListLoad.NOT_LOADED
        devices.fail["refresh"] = VaultFailure(FailureKind.NETWORK)
        assertEquals(FeedTarget.DeviceList, vm.tapTarget(item))
        // A read that does not answer in time.
        devices.load.value = ListLoad.NOT_LOADED
        devices.refreshGate = CompletableDeferred()
        val t = async { vm.tapTarget(item) }
        advanceTimeBy(3_001)
        assertEquals(FeedTarget.DeviceList, t.await())
        // Another kind reads nothing.
        devices.load.value = ListLoad.NOT_LOADED
        val calls = devices.calls.count { it == "refresh" }
        vm.tapTarget(item.copy(kind = "device.paired"))
        assertEquals(calls, devices.calls.count { it == "refresh" })
    }
}
