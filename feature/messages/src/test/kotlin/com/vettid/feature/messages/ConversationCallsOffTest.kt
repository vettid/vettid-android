// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.messages

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.data.calls.ConnectionCalls
import com.vettid.core.testing.FakeCalls
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** (C2, VAULT-MESSAGING §10.10 "Permission and limits") The conversation's call button while calls are off. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCallsOffTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c2", "Alex")), emptyMap()) }

    private fun conversation(calls: FakeCalls) =
        ConversationViewModel(SavedStateHandle(mapOf(ConversationRoute.ARG to "c2")), social, social, com.vettid.core.testing.FakeAttachments(), calls)

    /** (C2) Calls with this connection turned off: the call button says where to turn them on, and calls nobody. */
    @Test
    fun callsOffSayWhereToTurnThemOn() = runTest {
        val calls = FakeCalls()
        calls.permissions.value = CallPermissions(
            supported = true, known = true, differing = mapOf("c2" to ConnectionCalls(allowed = false)),
        )
        val vm = conversation(calls)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callShown && vm.uiState.value.callsOff)
        vm.call()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.callsOffNotice)
        assertTrue(calls.called.isEmpty())
        // Turned on again (here or on another device): the notice goes, and the button calls.
        calls.permissions.value = CallPermissions(supported = true, known = true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.callsOffNotice)
        vm.call()
        assertEquals(listOf("c2"), calls.called)
    }
}
