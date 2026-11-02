package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.AgentRuleRoute
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Nothing secret in logs or in state that survives (ANDROID-PLAN 0.1.116 §4 AA4, "The password is held only while the request
 * runs"): the credential password is not in saved state, not in the state's text, and no source of the agent rule path logs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRulePrivacyTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun thePasswordIsNeverWrittenToSavedStateAndIsGoneOnceTheSaveEnds() = runTest {
        val sharing = FakeSharing().apply { registry = TagRegistry(1, listOf(TagView("api"))) }
        val devices = FakeDevices().apply { this.devices.value = listOf(DeviceInfo("a1", "agent", "Claude", null, null, null, null)) }
        val saved = SavedStateHandle(mapOf(AgentRuleRoute.ARG_AGENT to "a1"))
        val keys = saved.keys().toSet()
        val vm = RuleEditViewModel(saved, sharing, FakeSocial(), devices)
        advanceUntilIdle()
        vm.selectTag("api")
        advanceUntilIdle()
        vm.save()
        vm.setPassword("pw-fake-secret")
        assertEquals(keys, saved.keys().toSet())
        assertFalse("${vm.uiState.value}".contains("pw-fake-secret"))
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.password)
        assertEquals(keys, saved.keys().toSet())
    }

    @Test
    fun noSourceOfTheAgentRulePathLogsOrKeepsAnythingInSavedState() {
        val files = listOf(
            "src/main/kotlin/com/vettid/feature/items/AgentRuleEditor.kt",
            "src/main/kotlin/com/vettid/feature/items/SharingViewModels.kt",
            "src/main/kotlin/com/vettid/feature/items/RuleEditScreen.kt",
            "../devices/src/main/kotlin/com/vettid/feature/devices/DevicesViewModels.kt",
            "../devices/src/main/kotlin/com/vettid/feature/devices/DevicePageScreen.kt",
            "../../core/data/src/main/kotlin/com/vettid/core/data/items/SharingManager.kt",
        )
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "System.out", "System.err", "java.util.logging",
            "Logger", "stackTraceToString", "rememberSaveable", "saved.set", "saved[\"password", "getStateFlow",
        )
        for (path in files) {
            val f = File(path)
            assertTrue("$path exists", f.exists())
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (b in banned) assertFalse("$path uses $b", code.contains(b))
        }
    }
}
