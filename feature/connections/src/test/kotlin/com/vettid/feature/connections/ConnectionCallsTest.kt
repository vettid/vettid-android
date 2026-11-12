// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.data.calls.ConnectionCalls
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeCalls
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** (C2, VAULT-MESSAGING §10.10 "Permission and limits") "Calls with <First>" on a connection's page. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class ConnectionCallsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial()
    private val calls = FakeCalls()

    private fun vm(id: String = "c1") = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to id)), social, FakeSharing(), calls = calls)

    private fun read(vararg differing: Pair<String, ConnectionCalls>) {
        calls.permissions.value = CallPermissions(supported = true, known = true, differing = differing.toMap())
    }

    @Test
    fun theSwitchIsShownOnlyForAConnectedPersonOnceTheVaultSaid() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Ada Lovelace")))
        val vm = vm()
        advanceUntilIdle()
        // Not read yet (or a vault before C2): no switch.
        assertNull(vm.uiState.value.calls)
        read()
        advanceUntilIdle()
        assertEquals(DetailCalls(allowed = true, limited = false), vm.uiState.value.calls)
        assertTrue(vm.uiState.value.canCall)
        // A build or a vault without calls: no switch.
        calls.availability.value = CallAvailability.NOT_IN_VAULT
        advanceUntilIdle()
        assertNull(vm.uiState.value.calls)
    }

    @Test
    fun neverForAService() = runTest {
        social.seed(listOf(ConnectionInfo("c1", "Example Cooperative", ConnectionState.ACTIVE, firstName = "Example", lastName = "Cooperative", service = true)))
        read()
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.calls)
    }

    @Test
    fun offTakesTheCallAwayAndLimitedIsSaid() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Ada Lovelace")))
        read("c1" to ConnectionCalls(allowed = true, limited = true))
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.calls!!.limited)
        vm.setCallsAllowed(false)
        advanceUntilIdle()
        assertEquals(listOf("c1" to false), calls.allowedSet)
        assertFalse(vm.uiState.value.calls!!.allowed)
        assertFalse("no Call while calls are off", vm.uiState.value.canCall)
        vm.call()
        assertTrue(calls.called.isEmpty())
        assertFalse(vm.uiState.value.callsChanging)
    }

    @Test
    fun aRefusalIsSaidAndChangesNothing() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Ada Lovelace")))
        read()
        calls.setFailure = VaultFailure(FailureKind.NETWORK)
        val vm = vm()
        advanceUntilIdle()
        vm.setCallsAllowed(false)
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        assertTrue(vm.uiState.value.calls!!.allowed)
        assertFalse(vm.uiState.value.callsChanging)
    }

    @Test
    fun theCardSaysOffAndLimited() {
        rule.setContent { CallsCard(allowed = false, limited = false, first = "Ada", enabled = true, onChange = {}) }
        rule.onNodeWithText("Calls with Ada").assertIsDisplayed()
        rule.onNodeWithTag("detail_calls_note").assertIsDisplayed()
        rule.onNodeWithText("Off both ways. Ada can't call you and you can't call Ada. Ada isn't told: their calls just go unanswered.").assertIsDisplayed()
    }

    @Test
    fun theLimitedNote() {
        rule.setContent { CallsCard(allowed = true, limited = true, first = "Ada", enabled = true, onChange = {}) }
        rule.onNodeWithText("Ada called several times without an answer, so their calls won't ring for a while.").assertIsDisplayed()
    }
}
