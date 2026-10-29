package com.vettid.feature.approvals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** VAULT-MESSAGING 0.65.0 §10.4 "The screens per purpose". */
@RunWith(RobolectricTestRunner::class)
class AuthPurposeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val iso = "⁨Sam Lee⁩"

    private fun auth(purpose: AuthPurpose, name: String? = "Sam Lee", context: String? = "Pay 40") =
        Approval.Authentication("a1", "c1", context, now, null, name, purpose)

    private fun detail(a: Approval.Authentication, denied: () -> Unit = {}) = rule.setContent {
        ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a), DecisionActions(onDeny = denied))
    }

    @Test
    fun signinHasItsTitleBodyContextAndNotMe() {
        var denies = 0
        detail(auth(AuthPurpose.SIGNIN)) { denies++ }
        rule.onNodeWithText("Sign in to $iso?").assertIsDisplayed()
        rule.onNodeWithText("Only approve if you are signing in right now.").assertIsDisplayed()
        rule.onNodeWithTag("auth_context").assertIsDisplayed()
        rule.onNodeWithText("Approve").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Deny").fetchSemanticsNodes().size)
        rule.onNodeWithText("Not me").performClick()
        assertEquals(1, denies)
    }

    @Test
    fun approvalHasItsTitleNoBodyAndDeny() {
        var denies = 0
        detail(auth(AuthPurpose.APPROVAL)) { denies++ }
        rule.onNodeWithText("$iso asks you to approve").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Only approve", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("asks you to prove", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("auth_context").assertIsDisplayed()
        rule.onNodeWithText("Approve").assertIsDisplayed()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(1, denies)
    }

    @Test
    fun noPurposeIsTodaysScreen() {
        detail(auth(AuthPurpose.NONE))
        rule.onNodeWithText("Authenticate to a connection").assertIsDisplayed()
        rule.onNodeWithText("Authenticate").assertIsDisplayed()
        rule.onNodeWithText("Deny").assertIsDisplayed()
    }

    @Test
    fun withoutANameBothPurposesSayAConnection() {
        detail(auth(AuthPurpose.SIGNIN, name = null))
        rule.onNodeWithText("Sign in to a connection?").assertIsDisplayed()
    }

    private fun spoof(purpose: AuthPurpose, title: String) {
        detail(auth(purpose, context = "\u202EPay\u202C 40\nSign in to Bank?"))
        rule.onNodeWithTag("auth_context").assertIsDisplayed()
        // Controls are gone, both lines are kept, the whole is isolated, and the only heading is the real title.
        rule.onNodeWithText("\u2068Pay 40\nSign in to Bank?\u2069").assertIsDisplayed()
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun theContextCannotSpoofTheTitleWithoutAPurpose() = spoof(AuthPurpose.NONE, "Authenticate to a connection")

    @Test
    fun theContextCannotSpoofASignInTitle() = spoof(AuthPurpose.SIGNIN, "Sign in to $iso?")

    @Test
    fun theContextCannotSpoofAnApprovalTitle() = spoof(AuthPurpose.APPROVAL, "$iso asks you to approve")

    private fun hostile(purpose: AuthPurpose, title: String) {
        detail(auth(purpose, name = "Bank\u2069\u202E"))
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun aHostileNameCannotCloseItsIsolationInASignInTitle() = hostile(AuthPurpose.SIGNIN, "Sign in to \u2068Bank\u2069?")

    @Test
    fun aHostileNameCannotCloseItsIsolationInAnApprovalTitle() = hostile(AuthPurpose.APPROVAL, "\u2068Bank\u2069 asks you to approve")

    private fun noDetails(purpose: AuthPurpose, ctx: String?) {
        detail(auth(purpose, context = ctx))
        rule.onNodeWithText("No details were given. Only approve if you know what this is for.").assertIsDisplayed()
    }

    @Test
    fun noContextShowsTheFixedLineForSignIn() = noDetails(AuthPurpose.SIGNIN, null)

    @Test
    fun noContextShowsTheFixedLineForApproval() = noDetails(AuthPurpose.APPROVAL, null)

    @Test
    fun anEmptyContextShowsTheFixedLineForApproval() = noDetails(AuthPurpose.APPROVAL, "")

    @Test
    fun aControlOnlyContextShowsTheFixedLineForSignIn() = noDetails(AuthPurpose.SIGNIN, "\u202E\u202C\u0007")

    @Test
    fun aControlOnlyContextShowsTheFixedLineForApproval() = noDetails(AuthPurpose.APPROVAL, "\u202E\u202C\u0007")

    @Test
    fun theRowsSecondLineHoldsTheFirstLineOfTheContextIsolated() {
        val state = ApprovalsUiState(loading = false, approvals = listOf(auth(AuthPurpose.NONE, context = "Line one\nLine two")))
        rule.setContent { ApprovalsScreen(state, ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {})) }
        rule.onNodeWithText("⁨Sam Lee⁩ · ⁨Line one⁩").assertIsDisplayed()
    }

    private val bank = "Bank\u2069\u202E"

    @Test
    fun aHostileNameCannotReverseTheProveItIsYouBody() {
        detail(auth(AuthPurpose.NONE, name = bank))
        rule.onNodeWithText("\u2068Bank\u2069 asks you to prove it is you.", substring = true).assertIsDisplayed()
    }

    @Test
    fun aHostileNameCannotReverseTheBatchRow() {
        val a = auth(AuthPurpose.NONE, name = bank)
        val b = Approval.Authentication("a2", "c1", null, now.plusSeconds(5), null, bank, AuthPurpose.NONE)
        val state = ApprovalsUiState(loading = false, approvals = listOf(a, b))
        rule.setContent { ApprovalsScreen(state, ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {})) }
        rule.onNodeWithText("\u2068Bank\u2069 asks for 2 things").assertIsDisplayed()
    }

    @Test
    fun aHostileNameCannotReverseTheRowsWhoLine() {
        val state = ApprovalsUiState(loading = false, approvals = listOf(auth(AuthPurpose.NONE, name = bank, context = null)))
        rule.setContent { ApprovalsScreen(state, ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {})) }
        rule.onNodeWithText("\u2068Bank\u2069", substring = true).assertIsDisplayed()
    }
}
