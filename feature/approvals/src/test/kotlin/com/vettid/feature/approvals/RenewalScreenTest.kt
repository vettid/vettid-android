package com.vettid.feature.approvals

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.social.Approval
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.format.Times
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** VAULT-MESSAGING 0.78.0 §6.8 step 3: the texts as the owner approved them. */
@RunWith(RobolectricTestRunner::class)
class RenewalScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val until = Instant.parse("2026-10-29T15:30:00Z")
    private val iso = "⁨Laptop⁩"

    private fun request(name: String? = "Laptop", role: String = "desktop") =
        Approval.DeviceRequest("device.session.pending", "r1", name, role, null, now, null, deviceId = "dev1")

    private fun show(state: ApprovalDetailUiState, actions: DecisionActions = DecisionActions()) = rule.setContent {
        ApprovalDetailScreen(state, actions)
    }

    private fun state(a: Approval.DeviceRequest, until: Instant? = this.until, confirm: Boolean = false, failure: EndFailure? = null) =
        ApprovalDetailUiState(a.key, a, renewalUntil = until, confirmEnd = confirm, endFailure = failure)

    @Test
    fun aRenewalShowsTheNoticeAndTwoChoices() {
        show(state(request()))
        rule.onNodeWithTag("renewal_notice").assertExists()
        rule.onNodeWithText("Denying refuses only the extra time. $iso keeps its current access until ${Times.full(until)}.")
            .assertExists()
        assertEquals(0, rule.onAllNodesWithText("Approve").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("This version of the app", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Deny").assertExists()
        rule.onNodeWithText("Deny and end access now").assertExists()
    }

    @Test
    fun theNoticeNamesAKindlessDeviceByItsKind() {
        show(state(request(name = null, role = "agent")))
        rule.onNodeWithText("Denying refuses only the extra time. An agent keeps its current access until ${Times.full(until)}.")
            .assertExists()
    }

    @Test
    fun aFirstRequestHasNoNoticeAndOnlyDeny() {
        show(state(request(), until = null))
        assertEquals(0, rule.onAllNodesWithTag("renewal_notice").size)
        assertEquals(0, rule.onAllNodesWithText("Approve").fetchSemanticsNodes().size)
        rule.onNodeWithText("This version of the app does not pair desktops or agents, so it can only decline.").assertExists()
        rule.onNodeWithText("Deny").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Deny and end access now").fetchSemanticsNodes().size)
    }

    @Test
    fun theThirdChoiceAsksForTheConfirmationFirst() {
        var asked = 0
        show(state(request()), DecisionActions(onAskEndAccess = { asked++ }))
        rule.onNodeWithText("Deny and end access now").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun theConfirmationIsTheDevicesPagesAndEndsOnConfirm() {
        var ended = 0
        show(state(request(), confirm = true), DecisionActions(onEndAccess = { ended++ }))
        rule.onNodeWithText("End access for $iso?").assertExists()
        rule.onNodeWithText(
            "It stops at once, and any of its requests still waiting for you are declined. It can ask for access again; you decide then.",
        ).assertExists()
        rule.onNodeWithText("End access").performClick()
        assertEquals(1, ended)
    }

    @Test
    fun aFailedEndSaysSoAndOffersItAgain() {
        var again = 0
        show(state(request(), failure = EndFailure(FailureKind.NETWORK, null)), DecisionActions(onAskEndAccess = { again++ }))
        rule.onNodeWithText("Access not ended").assertExists()
        rule.onNodeWithText("Access for $iso could not be ended. Try again.").assertExists()
        rule.onNodeWithTag("end_retry").performScrollTo().performClick()
        assertEquals(1, again)
        // The request stays open.
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun aRefusedDailyCheckShowsTheVaultsWordsWithTryAgain() {
        show(state(request(), failure = EndFailure(FailureKind.OWNER_CHECK_REQUIRED, null)))
        rule.onNodeWithText("Access not ended").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Access for $iso could not be ended. Try again.").fetchSemanticsNodes().size)
        rule.onNodeWithTag("end_retry").assertExists()
    }

    @Test
    fun anOpenConfirmationIsStillDrawnOnTheGoneScreen() {
        var ended = 0
        show(state(request(), until = null, confirm = true).copy(gone = true), DecisionActions(onEndAccess = { ended++ }))
        rule.onNodeWithText("End access for $iso?").assertExists()
        rule.onNodeWithText("End access").performClick()
        assertEquals(1, ended)
    }

    @Test
    fun aFailedEndOnTheGoneScreenIsSaidWithTryAgain() {
        var again = 0
        show(
            state(request(), failure = EndFailure(FailureKind.NETWORK, null)).copy(gone = true),
            DecisionActions(onAskEndAccess = { again++ }),
        )
        rule.onNodeWithText("Access not ended").assertExists()
        rule.onNodeWithText("Access for $iso could not be ended. Try again.").assertExists()
        rule.onNodeWithTag("end_retry").performScrollTo().performClick()
        assertEquals(1, again)
    }

    @Test
    fun aFailedEndOnTheGoneScreenWithoutALiveSessionOffersNoRetry() {
        show(state(request(), until = null, failure = EndFailure(FailureKind.NETWORK, null)).copy(gone = true))
        rule.onNodeWithText("Access not ended").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("end_retry").size)
    }

    @Test
    fun heldDrawsNoConfirmation() {
        show(state(request(), until = null, confirm = true).copy(gone = true, held = true))
        assertEquals(0, rule.onAllNodesWithText("End access for $iso?").fetchSemanticsNodes().size)
    }

    @Test
    fun denyIsItsOwnCall() {
        var denied = 0
        show(state(request()), DecisionActions(onDeny = { denied++ }))
        rule.onNodeWithText("Deny").performClick()
        assertEquals(1, denied)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String) =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes()
