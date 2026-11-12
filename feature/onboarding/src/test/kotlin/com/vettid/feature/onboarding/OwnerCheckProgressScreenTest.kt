package com.vettid.feature.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.124 §4 "The owner check's progress", on screen: "Checking with your vault…" in place of the entries with no
 * Check or Cancel, Lock vault enabled on a gated check, "Check passed" with no actions; both lines are announced (live regions)
 * and the tick's meaning is its text. Back does not leave while a check is out or its tick shows.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerCheckProgressScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val view = OwnerCheckView(OwnerCheckState.HELD, Instant.now().minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null)

    private var locks = 0

    private val actions = object : OwnerCheckActions {
        override fun setPin(v: String) = Unit
        override fun setPassword(v: String) = Unit
        override fun setHoldOff(choice: HoldOffChoice) = Unit
        override fun submit() = Unit
        override fun lockVault() {
            locks++
        }
    }

    private val checking = "Checking with your vault…"
    private val passed = "Check passed"
    private val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val lockLabel = ctx.getString(R.string.ownercheck_lock)
    private val cancelLabel = ctx.getString(R.string.ownercheck_cancel)
    private val submitLabel = ctx.getString(R.string.ownercheck_submit)
    private val holdOffLabel = ctx.getString(R.string.ownercheck_submit_hold_off)
    private fun pane(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text)

    private fun show(s: OwnerCheckUiState) = rule.setContent { OwnerCheckContent(s, actions, onCancel = {}) }

    @Test
    fun aGatedCheckWithTheVaultShowsCheckingAndKeepsLockVaultEnabled() {
        show(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view, busy = true))
        rule.onNodeWithTag("owner_check_checking").assert(pane(checking)) // announced as it appears
        rule.onNodeWithText(checking).assertExists()
        rule.onNodeWithTag("owner_check_pin").assertDoesNotExist()
        rule.onNodeWithTag("owner_check_password").assertDoesNotExist()
        rule.onNodeWithText(submitLabel).assertDoesNotExist()
        rule.onNodeWithText(lockLabel).assertIsEnabled().performClick()
        assertEquals(1, locks)
    }

    @Test
    fun aVoluntaryCheckWithTheVaultHasNoButtons() = noButtonsWhileChecking(OwnerCheckMode.VOLUNTARY)

    @Test
    fun theHoldOffWithTheVaultHasNoButtons() = noButtonsWhileChecking(OwnerCheckMode.HOLD_OFF)

    private fun noButtonsWhileChecking(mode: OwnerCheckMode) {
        show(OwnerCheckUiState(mode = mode, view = view, busy = true))
        rule.onNodeWithText(checking).assertExists()
        rule.onNodeWithText(cancelLabel).assertDoesNotExist()
        rule.onNodeWithText(lockLabel).assertDoesNotExist()
        rule.onNodeWithText(submitLabel).assertDoesNotExist()
        rule.onNodeWithText(holdOffLabel).assertDoesNotExist()
        rule.onNodeWithTag("hold_off_warning").assertDoesNotExist()
    }

    @Test
    fun withNoCheckOutTheFormIsAsBefore() {
        show(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view))
        rule.onNodeWithTag("owner_check_checking").assertDoesNotExist()
        rule.onNodeWithTag("owner_check_pin").assertExists()
        rule.onNodeWithText(lockLabel).assertExists()
    }

    @Test
    fun aPassShowsTheTickAndItsTextAnnouncedWithNoActions() {
        show(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view, passedTick = true))
        rule.onNodeWithTag("owner_check_passed").assertExists()
        rule.onNodeWithText(passed).assertExists()
        rule.onNode(pane(passed)).assertExists() // announced as it appears
        rule.onNodeWithText(lockLabel).assertDoesNotExist()
        rule.onNodeWithText(submitLabel).assertDoesNotExist()
        rule.onNodeWithTag("owner_check_pin").assertDoesNotExist()
    }

    @Test
    fun aPassThatNeedsPresenceRecordedShowsNoTick() {
        show(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view, passedUnrecorded = true))
        rule.onNodeWithTag("owner_check_passed_unrecorded").assertExists()
        rule.onNodeWithText(passed).assertDoesNotExist()
    }

    @Test
    fun backDoesNotLeaveWhileACheckIsOutOrItsTickShows() {
        val idle = OwnerCheckUiState(mode = OwnerCheckMode.VOLUNTARY, view = view)
        assertTrue(backLeaves(OwnerCheckMode.VOLUNTARY, idle))
        assertTrue(backLeaves(OwnerCheckMode.HOLD_OFF, idle))
        assertFalse(backLeaves(OwnerCheckMode.VOLUNTARY, idle.copy(busy = true)))
        assertFalse(backLeaves(OwnerCheckMode.HOLD_OFF, idle.copy(busy = true)))
        assertFalse(backLeaves(OwnerCheckMode.VOLUNTARY, idle.copy(passedTick = true)))
        assertFalse(backLeaves(OwnerCheckMode.GATED, idle))
    }

    @Test
    fun theTextsAreTheApprovedOnes() {
        assertEquals(checking, ctx.getString(R.string.ownercheck_checking))
        assertEquals(passed, ctx.getString(R.string.ownercheck_passed))
    }
}
