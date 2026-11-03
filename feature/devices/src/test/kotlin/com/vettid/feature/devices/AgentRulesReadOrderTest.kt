package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.ShareRule
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeSharing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The page starts its first rule read inside `init` (as with `Dispatchers.Main.immediate`): a slow older answer never wins. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRulesReadOrderTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun rule(id: String) = ShareRule(id, 1, null, "a1", listOf(id), perHour = 60, perDay = 1000)

    @Test
    fun aSlowFirstAnswerThatArrivesAfterANewerOneIsDiscarded() = runTest {
        val sharing = FakeSharing().apply { rulesStored += rule("r1") }
        val devices = FakeDevices().apply { this.devices.value = listOf(DeviceInfo("a1", "agent", "Claude", null, null, null, null)) }
        val slow = CompletableDeferred<Unit>()
        sharing.agentRulesGates += slow
        // The first read starts in init and waits for its gate.
        val vm = DeviceViewModel(SavedStateHandle(mapOf(DeviceRoute.ARG to "a1")), devices, sharing, com.vettid.core.testing.FakeSocial())
        assertEquals(1, sharing.calls.count { it == "agentRules" })
        sharing.rulesStored += rule("r3")
        vm.refreshRules()
        assertEquals(listOf("r1", "r3"), vm.uiState.value.rules.map { it.ruleId })
        slow.complete(Unit)
        assertEquals(listOf("r1", "r3"), vm.uiState.value.rules.map { it.ruleId })
    }

    @Test
    fun theOnlyReadIsKeptWhenNothingNewerCameAfterIt() = runTest {
        val sharing = FakeSharing().apply { rulesStored += rule("r1") }
        val devices = FakeDevices().apply { this.devices.value = listOf(DeviceInfo("a1", "agent", "Claude", null, null, null, null)) }
        val gate = CompletableDeferred<Unit>()
        sharing.agentRulesGates += gate
        val vm = DeviceViewModel(SavedStateHandle(mapOf(DeviceRoute.ARG to "a1")), devices, sharing, com.vettid.core.testing.FakeSocial())
        gate.complete(Unit)
        assertEquals(listOf("r1"), vm.uiState.value.rules.map { it.ruleId })
    }
}
