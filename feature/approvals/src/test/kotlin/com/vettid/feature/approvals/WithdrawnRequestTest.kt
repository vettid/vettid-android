package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AskState
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** VAULT-MESSAGING 0.80.0 §10.4 "Withdrawing a challenge", ANDROID-PLAN 0.1.101 "Withdrawn requests". */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WithdrawnRequestTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val auth = Approval.Authentication("a1", "c1", "Sign in", now, null, "Sam Lee", AuthPurpose.SIGNIN, "42")
    private val person = ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")
    private val service = person.copy(service = true)
    private val social = FakeSocial().apply {
        approvals.value = listOf(auth)
        connections.value = listOf(person)
    }

    private fun detail() = ApprovalDetailViewModel(
        SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to auth.key)), social, com.vettid.core.testing.FakeItems(),
        com.vettid.core.testing.FakeDevices(), FakeVault(), social,
    )

    /** What the real manager does with the ask a decision found `not_found`: it leaves the list. */
    private fun vanish() {
        social.approvals.value = emptyList()
    }

    // --- the screen ---

    @Test
    fun aWithdrawalKeepsTheScreenAndOffersTheNoticeAndMute() = runTest {
        val vm = detail()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.withdrawn)
        vanish()
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.withdrawn)
        assertFalse("the screen is not closed or read as decided elsewhere", s.gone)
        assertFalse(s.done)
        assertTrue(s.canMute)
        assertNull(s.error)
    }

    @Test
    fun withoutTheFlagTheAskReadsAsDecidedElsewhere() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vanish()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        assertFalse(vm.uiState.value.withdrawn)
    }

    // --- an approve or deny in flight answered not_found ---

    @Test
    fun notFoundWithTheFlagAlreadySeenShowsTheNotice() = runTest {
        val vm = detail()
        advanceUntilIdle()
        social.fail["approveAuthentication"] = VaultFailure(FailureKind.NOT_FOUND)
        vm.setPassword("pw")
        vanish()
        social.withdrawn.value = setOf("a1")
        vm.approve()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.withdrawn)
        assertNull(s.error)
        assertFalse(s.busy)
        assertFalse(s.gone)
    }

    @Test
    fun notFoundWaitsAboutTwoSecondsWithProgressForTheFlag() = runTest {
        val vm = detail()
        advanceUntilIdle()
        social.fail["denyAuthentication"] = VaultFailure(FailureKind.NOT_FOUND)
        vanish()
        vm.deny()
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue("progress while it waits", vm.uiState.value.busy)
        assertFalse("not the gone screen while it waits", vm.uiState.value.gone)
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.withdrawn)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun notFoundWithoutTheFlagReadsAsNoLongerWaitingAndAnImmediateFlagStillSwitchesLater() = runTest {
        val vm = detail()
        advanceUntilIdle()
        social.fail["denyAuthentication"] = VaultFailure(FailureKind.NOT_FOUND)
        vanish()
        vm.deny()
        runCurrent()
        advanceTimeBy(2_500)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        assertFalse(vm.uiState.value.withdrawn)
        assertEquals(FailureKind.NOT_FOUND, vm.uiState.value.error)
        // The flag arrives after that while the screen is still open: it switches.
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.withdrawn)
        assertFalse(vm.uiState.value.gone)
    }

    // --- mute ---

    @Test
    fun aPersonsConnectionIsMutedAtOnce() = runTest {
        val vm = detail()
        vanish()
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        vm.askMute()
        advanceUntilIdle()
        assertTrue("asksMute:c1:true" in social.calls)
        assertFalse(vm.uiState.value.confirmMute)
        assertFalse("muted: not offered again", vm.uiState.value.canMute)
    }

    @Test
    fun aServiceAsksFirstAndCancelMutesNothing() = runTest {
        social.connections.value = listOf(service)
        val vm = detail()
        vanish()
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        vm.askMute()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.confirmMute)
        assertTrue(social.calls.none { it.startsWith("asksMute") })
        vm.dismissMute()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.confirmMute)
        assertTrue(social.calls.none { it.startsWith("asksMute") })
        vm.askMute()
        vm.mute()
        advanceUntilIdle()
        assertTrue("asksMute:c1:true" in social.calls)
    }

    @Test
    fun anAlreadyMutedConnectionOffersNoMute() = runTest {
        social.connections.value = listOf(person.copy(asks = AskState(muted = true)))
        val vm = detail()
        vanish()
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canMute)
    }

    @Test
    fun aFailedMuteSaysSoAndKeepsTheNotice() = runTest {
        val vm = detail()
        vanish()
        social.withdrawn.value = setOf("a1")
        advanceUntilIdle()
        social.fail["asksMute:c1:true"] = VaultFailure(FailureKind.OTHER)
        vm.askMute()
        advanceUntilIdle()
        assertEquals(FailureKind.OTHER, vm.uiState.value.error)
        assertTrue(vm.uiState.value.withdrawn)
        assertTrue(vm.uiState.value.canMute)
    }

    // --- the words ---

    private fun withdrawnState(canMute: Boolean = true, confirm: Boolean = false) =
        ApprovalDetailUiState(auth.key, auth, withdrawn = true, canMute = canMute, confirmMute = confirm)

    @Test
    fun theNoticeReplacesTheChoicesWithClose() {
        var closed = 0
        var muted = 0
        rule.setContent {
            ApprovalDetailScreen(withdrawnState(), DecisionActions(onBack = { closed++ }, onAskMute = { muted++ }))
        }
        rule.onNodeWithText("⁨Sam Lee⁩ withdrew this request. You don't need to answer it.").assertIsDisplayed()
        rule.onNodeWithText("Mute requests from ⁨Sam Lee⁩").assertExists()
        rule.onNodeWithText("Close").assertIsDisplayed()
        listOf("Approve", "Not me", "Deny", "Expires", "declined", "denied").forEach {
            assertEquals(it, 0, rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size)
        }
        assertEquals("no error line", 0, rule.onAllNodes(hasTestTag("failure")).fetchSemanticsNodes().size)
        rule.onNodeWithText("Mute requests from ⁨Sam Lee⁩").performScrollTo().performClick()
        assertEquals(1, muted)
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun noMuteIsOfferedWhenItCannotBeDone() {
        rule.setContent { ApprovalDetailScreen(withdrawnState(canMute = false), DecisionActions()) }
        assertEquals(0, rule.onAllNodesWithText("Mute requests", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("withdrawn_notice").assertIsDisplayed()
    }

    @Test
    fun theServiceConfirmationUsesTheApprovedWords() {
        var confirmed = 0
        rule.setContent {
            ApprovalDetailScreen(withdrawnState(confirm = true), DecisionActions(onMute = { confirmed++ }))
        }
        rule.onNodeWithText("You won't be asked to sign in or approve anything for ⁨Sam Lee⁩ until you unmute.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Mute").performClick()
        assertEquals(1, confirmed)
    }
}
