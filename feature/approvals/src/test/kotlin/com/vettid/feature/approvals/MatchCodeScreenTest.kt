package com.vettid.feature.approvals

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.AuthPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** VAULT-MESSAGING 0.79.0 §10.4, ANDROID-PLAN 0.1.100 "Sign-in match code" (the note heading stays over every note). */
@RunWith(RobolectricTestRunner::class)
class MatchCodeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun auth(code: String?, purpose: AuthPurpose = AuthPurpose.SIGNIN, context: String? = "Bank sign-in") =
        Approval.Authentication("a1", "c1", context, now, null, "Sam Lee", purpose, code)

    private fun show(a: Approval.Authentication, fontScale: Float = 1f) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a), DecisionActions())
        }
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    @Test
    fun aCodeShowsLargeWithItsLabelAndInstruction() {
        show(auth("42"))
        // The label and the digits are one element for a screen reader; the tree below it is read unmerged.
        rule.onNodeWithText("Match code", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("match_code_digits", useUnmergedTree = true).assertTextEquals("42")
        rule.onNodeWithText("Check that the sign-in screen shows the same number: 42").assertIsDisplayed()
        rule.onNodeWithText("Approve").assertIsDisplayed()
        rule.onNodeWithText("Not me").assertIsDisplayed()
    }

    @Test
    fun theOrderIsTitleCodeNoteThenOnlyApproveAndThereIsNoFromLine() {
        show(auth("42"))
        rule.onNodeWithText("Sign in to ⁨Sam Lee⁩?").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("From").fetchSemanticsNodes().size)
        val title = rule.onNodeWithText("Sign in to ⁨Sam Lee⁩?").getUnclippedBoundsInRoot().top
        val code = top("match_code")
        val note = top("auth_context")
        val only = top("auth_signin_body")
        // "Expires" (when the ask has a deadline) comes after "Only approve", as on iOS.
        assertTrue("title above code", title < code)
        assertTrue("code above note", code < note)
        assertTrue("note above 'Only approve'", note < only)
        rule.onNodeWithText("Only approve if you are signing in right now.").assertExists()
    }

    @Test
    fun theTheirNoteHeadingStaysOverTheNoteWithAndWithoutACode() {
        show(auth("42"))
        rule.onNodeWithText("Their note").assertExists()
    }

    @Test
    fun withoutACodeItIsTodaysScreenWithNoBlockAndNoGap() {
        show(auth(null))
        assertEquals(0, rule.onAllNodesWithText("Match code").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("match_code").size)
        rule.onNodeWithText("From").assertIsDisplayed()
        rule.onNodeWithText("Their note").assertIsDisplayed()
        rule.onNodeWithText("Only approve if you are signing in right now.").assertIsDisplayed()
        // "Only approve" sits under the title as today: above the note.
        val only = rule.onNodeWithText("Only approve if you are signing in right now.").getUnclippedBoundsInRoot().top
        assertTrue(only < top("auth_context"))
    }

    @Test
    fun aMalformedCodeShowsTodaysScreen() {
        // The parser drops these too; the screen checks again for a challenge built any other way.
        listOf("4", "1234", "4a", " 42", "", "４２").forEach { assertNull(it, shownMatchCode(auth(it))) }
        show(auth("4a"))
        assertEquals(0, rule.onAllNodesWithTag("match_code").size)
        rule.onNodeWithText("From").assertIsDisplayed()
    }

    @Test
    fun aCodeOnAnApprovalIsNotShown() {
        show(auth("42", purpose = AuthPurpose.APPROVAL))
        assertEquals(0, rule.onAllNodesWithTag("match_code").size)
        assertEquals(0, rule.onAllNodesWithText("Match code").fetchSemanticsNodes().size)
    }

    @Test
    fun aNoteThatSaysMatchCodeWithoutTheFieldShowsNoBlock() {
        show(auth(null, context = "match code 42"))
        assertEquals(0, rule.onAllNodesWithTag("match_code").size)
        rule.onNodeWithText("match code 42", substring = true).assertIsDisplayed()
    }

    @Test
    fun aLeadingZeroIsKept() {
        show(auth("07"))
        rule.onNodeWithTag("match_code_digits", useUnmergedTree = true).assertTextEquals("07")
        rule.onNodeWithText("Check that the sign-in screen shows the same number: 07").assertIsDisplayed()
    }

    @Test
    fun theBlockIsOneElementThatReadsTheDigitsOneByOne() {
        show(auth("42"))
        rule.onNodeWithContentDescription("Match code 4 2").assertExists()
    }

    @Test
    fun aThreeDigitCodeReadsTheSameWay() {
        rule.setContent { MatchCodeBlock("907") }
        rule.onNodeWithContentDescription("Match code 9 0 7").assertIsDisplayed()
        rule.onNodeWithText("Check that the sign-in screen shows the same number: 907").assertIsDisplayed()
    }

    @Test
    fun nothingLooksVerified() {
        show(auth("42"))
        listOf("verified", "Verified", "safe", "secure", "success").forEach {
            assertEquals(it, 0, rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size)
        }
        // No icon, check mark or shield in the block: it has no image semantics.
        assertEquals(0, rule.onAllNodesWithTag("match_code_icon").size)
    }

    @Test
    fun itFitsAtTwiceTheFontSize() {
        show(auth("907"), fontScale = 2f)
        rule.onNodeWithTag("match_code_digits", useUnmergedTree = true).assertTextEquals("907")
        rule.onNodeWithText("Check that the sign-in screen shows the same number: 907").assertExists()
    }

    @Test
    fun expiresComesAfterOnlyApprove() {
        show(auth("42").copy(exp = now.plusSeconds(600)))
        val only = rule.onNodeWithText("Only approve if you are signing in right now.").getUnclippedBoundsInRoot().top
        val exp = rule.onNodeWithText("Expires", substring = true).getUnclippedBoundsInRoot().top
        assertTrue(only < exp)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes()
}
