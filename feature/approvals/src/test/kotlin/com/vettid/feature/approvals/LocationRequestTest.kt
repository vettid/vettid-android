// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.SendIssue
import com.vettid.core.data.location.ShareTiming
import com.vettid.core.data.social.Approval
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeDevices
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeLocation
import com.vettid.core.testing.FakePairing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A connection asks where the member is (ANDROID-PLAN §4 "Location", L1b): "{First} asks where you are" with the note, **Share
 * once**, **Share for a while…** and **Not now**.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class LocationRequestTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.now()
    private val social = FakeSocial()
    private val location = FakeLocation()

    private fun ask(service: Boolean = false, note: String? = "Are you near the station?", exp: Instant = now.plusSeconds(86_400)) =
        Approval.LocationRequest("r1", "c1", note, now, exp, connectionName = "Sam Doe", connectionService = service, firstName = "Sam")

    private fun vm(a: Approval.LocationRequest): ApprovalDetailViewModel {
        social.approvals.value = listOf(a)
        return ApprovalDetailViewModel(
            SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to a.key)), social, FakeItems(), FakeDevices(), FakeVault(), social, FakePairing(), location,
        )
    }

    // --- the three actions ---

    @Test
    fun shareOnceStartsAOnceShareAnsweringTheRequestAndClosesIt() = runTest {
        val m = vm(ask())
        runCurrent()
        m.shareOnce()
        runCurrent()
        val started = location.started.single()
        assertEquals("c1", started.connectionId)
        assertEquals("r1", started.requestId)
        assertEquals(ShareTiming.Once, started.timing)
        assertTrue(m.uiState.value.done)
        assertTrue(social.calls.contains("dismissLocationRequest:r1")) // it leaves the list at once; the vault's closing event follows
    }

    @Test
    fun whenNothingCanBeSentTheRequestStaysWithTheReason() = runTest {
        val m = vm(ask())
        runCurrent()
        location.fail = VaultFailure(FailureKind.OTHER, SendIssue.NO_PERMISSION.code)
        m.shareOnce()
        runCurrent()
        val s = m.uiState.value
        assertEquals(SendIssue.NO_PERMISSION, s.locationIssue)
        assertNull(s.error)
        assertFalse(s.done)
        assertTrue(location.started.isEmpty())
        assertEquals(listOf("location:r1"), social.approvals.value.map { it.key })
    }

    @Test
    fun anotherFailureIsShownAndKeepsTheRequest() = runTest {
        val m = vm(ask())
        runCurrent()
        location.fail = VaultFailure(FailureKind.NETWORK)
        m.shareOnce()
        runCurrent()
        assertEquals(FailureKind.NETWORK, m.uiState.value.error)
        assertFalse(m.uiState.value.done)
    }

    @Test
    fun notNowSendsNothingAndClosesTheScreen() = runTest {
        val m = vm(ask())
        runCurrent()
        m.notNow()
        runCurrent()
        assertTrue(location.started.isEmpty())
        assertEquals(listOf("dismissLocationRequest:r1"), social.calls.filter { it.startsWith("dismiss") })
        assertTrue(m.uiState.value.done)
        assertTrue(social.approvals.value.isEmpty())
    }

    @Test
    fun aServiceCannotBeAnsweredByASharingAndAnExpiredRequestStartsNothing() = runTest {
        val svc = vm(ask(service = true))
        runCurrent()
        svc.shareOnce()
        runCurrent()
        assertTrue(location.started.isEmpty())
        val old = vm(ask(exp = now.minusSeconds(1)))
        runCurrent()
        old.shareOnce()
        runCurrent()
        assertTrue(location.started.isEmpty())
    }

    @Test
    fun anOpenRequestHoldsTheDailyCheckLikeAnyApproval() = runTest {
        val m = vm(ask())
        runCurrent()
        assertTrue(m.uiState.value.openForDecision)
    }

    // --- the screen ---

    @Test
    fun theScreenSaysWhoAsksWithTheNoteAndOffersTheThreeActions() {
        val actions = mutableListOf<String>()
        val s = ApprovalDetailUiState("location:r1", approval = ask())
        rule.setContent {
            ApprovalDetailScreen(s, DecisionActions(onShareOnce = { actions += "once" }, onShareWhile = { actions += "while" }, onNotNow = { actions += "not" }))
        }
        rule.onNodeWithText("${AccountNames.isolate("Sam")} asks where you are").assertIsDisplayed()
        rule.onNodeWithText(AccountNames.isolate("Are you near the station?")).assertIsDisplayed()
        rule.onNodeWithText("Share once").assertIsEnabled()
        rule.onNodeWithText("Share for a while…").performClick()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(listOf("while", "not"), actions)
    }

    @Test
    fun aServiceRequestOffersOnlyNotNow() {
        val s = ApprovalDetailUiState("location:r1", approval = ask(service = true))
        rule.setContent { ApprovalDetailScreen(s, DecisionActions()) }
        rule.onNodeWithText("Not now").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Share once").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Share for a while…").fetchSemanticsNodes().size)
    }

    @Test
    fun theReasonNothingWasSentIsShown() {
        val s = ApprovalDetailUiState("location:r1", approval = ask(), locationIssue = SendIssue.LOCATION_OFF)
        rule.setContent { ApprovalDetailScreen(s, DecisionActions()) }
        rule.onNodeWithTag("location_request_issue").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapSendsOneShare() = runTest {
        val m = vm(ask())
        runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        location.startGate = gate
        m.shareOnce()
        m.shareOnce() // the second tap while the first start is out
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, location.started.size)
    }

    @Test
    fun thePermissionPromptHoldsTheScreenAndItsResultStartsTheShareOnce() = runTest {
        val m = vm(ask())
        runCurrent()
        m.permissionAsked()
        runCurrent()
        assertTrue(m.uiState.value.busy)
        m.shareOnce() // a tap while the prompt is up does nothing
        runCurrent()
        assertTrue(location.started.isEmpty())
        m.permissionAnswered(true)
        runCurrent()
        assertEquals(1, location.started.size)
    }

    @Test
    fun whileSharingWithTheAskerShareIsRefusedAndOnlyNotNowIsOffered() = runTest {
        val m = vm(ask())
        runCurrent()
        val share = location.start(com.vettid.core.data.location.ShareRequest("c1"))
        location.started.clear()
        runCurrent()
        val s = m.uiState.value
        assertEquals(share.expiresAt, s.sharingUntil)
        m.shareOnce()
        runCurrent()
        assertTrue("refused in the view model, not only by the button", location.started.isEmpty())
        rule.setContent { ApprovalDetailScreen(s, DecisionActions()) }
        rule.onNodeWithTag("location_request_already_sharing").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Share once").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Share for a while…").fetchSemanticsNodes().size)
        rule.onNodeWithText("Not now").assertIsDisplayed()
        // the share ends: the actions are back
        location.stop(share.shareId)
        runCurrent()
        assertNull(m.uiState.value.sharingUntil)
        m.shareOnce()
        runCurrent()
        assertEquals(1, location.started.size)
    }

    @Test
    fun aSecondTapInTheSameFrameDoesNotAskThePermissionAgain() = runTest {
        val m = vm(ask())
        runCurrent()
        assertTrue(m.permissionAsked())
        assertFalse("the screen launches the prompt only when this is true", m.permissionAsked())
    }

    @Test
    fun comingBackFromSettingsWithThePermissionClearsTheStaleReason() = runTest {
        val m = vm(ask())
        runCurrent()
        m.permissionAsked()
        m.permissionAnswered(false)
        runCurrent()
        assertEquals(SendIssue.NO_PERMISSION, m.uiState.value.locationIssue)
        m.permissionNowGranted()
        runCurrent()
        assertNull(m.uiState.value.locationIssue)
    }

    @Test
    fun aRefusedPermissionKeepsTheRequestWithTheReasonAndOpenSettings() = runTest {
        val m = vm(ask())
        runCurrent()
        m.permissionAsked()
        m.permissionAnswered(false)
        runCurrent()
        val s = m.uiState.value
        assertFalse(s.busy)
        assertEquals(SendIssue.NO_PERMISSION, s.locationIssue)
        assertTrue(location.started.isEmpty())
        rule.setContent { ApprovalDetailScreen(s, DecisionActions()) }
        rule.onNodeWithTag("location_request_issue").assertIsDisplayed()
        rule.onNodeWithTag("location_request_settings").assertIsDisplayed()
    }

    @Test
    fun thePermissionTextIsShownBeforeThePromptWhileNotGranted() {
        val s = ApprovalDetailUiState("location:r1", approval = ask())
        rule.setContent { ApprovalDetailScreen(s, DecisionActions()) }
        rule.onNodeWithText("To share your location, VettID needs to know where this phone is.", substring = true).assertIsDisplayed()
    }
}
