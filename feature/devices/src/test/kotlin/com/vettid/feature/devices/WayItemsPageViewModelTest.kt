// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.AgentChange
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWaysList
import com.vettid.core.data.devices.ItemWaysRead
import com.vettid.core.data.devices.ItemWaysRules
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** AA7 on the agent's page: "Items it may use" is read only with `item_ways`, and again on the events that change it. */
@OptIn(ExperimentalCoroutinesApi::class)
class WayItemsPageViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class Fake : ItemWaysList {
        override val items = MutableStateFlow<Map<String, List<ItemWaysRead>>>(emptyMap())
        val reads = mutableListOf<String>()
        var fail: VaultFailure? = null

        override suspend fun list(agentId: String): List<ItemWaysRead> {
            reads += agentId
            fail?.let { throw it }
            val r = listOf(
                ItemWaysRules.one("""{"agent_id":"a1","item_id":"i1","version":2,"ways":["use","reveal"]}""".toByteArray())!!,
                ItemWaysRules.one("""{"agent_id":"a1","item_id":"i2","ways":["use"]}""".toByteArray())!!,
            )
            items.value = items.value + (agentId to r)
            return r
        }
    }

    private val ways = Fake()
    private val sharing = FakeSharing()
    private val devices = FakeDevices().apply {
        devices.value = listOf(DeviceInfo("a1", "agent", "Claude", null, null, null, null))
        load.value = ListLoad.LOADED
    }
    private val items = FakeItems().apply {
        this.items.value = listOf(ItemSummary("i1", 1, "GitHub token", "secret", Sensitivity.SECRET))
        load.value = ListLoad.LOADED
    }

    private fun page() = DeviceViewModel(SavedStateHandle(mapOf(DeviceRoute.ARG to "a1")), devices, sharing, FakeSocial(), ways, items)

    @Test
    fun withoutItemWaysNothingIsRead() = runTest(main.dispatcher) {
        sharing.itemWays = false
        page()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), ways.reads)
    }

    @Test
    fun theRowsCarryNamesAndWays() = runTest(main.dispatcher) {
        sharing.itemWays = true
        val vm = page()
        advanceUntilIdle()
        assertEquals(listOf("a1"), ways.reads)
        val rows = vm.uiState.value.wayItems
        assertEquals(listOf("GitHub token", null), rows.map { it.name })
        assertEquals(listOf(ItemWay.USE, ItemWay.REVEAL), rows[0].ways)
        assertEquals(listOf(ItemWay.USE), rows[1].ways)
        assertEquals(true, rows[1].namesLoaded)
    }

    @Test
    fun readAgainOnTheEventsAndOnReturn() = runTest(main.dispatcher) {
        sharing.itemWays = true
        val vm = page()
        advanceUntilIdle()
        devices.changes.emit(AgentChange.Item("a1", "i1", 3))
        advanceUntilIdle()
        devices.changes.emit(AgentChange.Item("a2", "i1", 3)) // another agent's
        advanceUntilIdle()
        devices.changes.emit(AgentChange.Rule("r1"))
        advanceUntilIdle()
        vm.refreshRules()
        advanceUntilIdle()
        assertEquals(4, ways.reads.size)
    }

    @Test
    fun aFailedReadIsShownAndRetried() = runTest(main.dispatcher) {
        sharing.itemWays = true
        ways.fail = VaultFailure(FailureKind.NETWORK)
        val vm = page()
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.wayItemsError)
        ways.fail = null
        vm.refreshWayItems()
        advanceUntilIdle()
        assertNull(vm.uiState.value.wayItemsError)
    }
}
