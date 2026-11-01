// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.wallet.WalletsManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Spending limits screen as the member sees it (ANDROID-PLAN §4 "Spending limits", "Texts (drafts)"). */
@RunWith(RobolectricTestRunner::class)
class SpendingLimitsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun state(
        perTx: Long? = 500_000,
        perPeriod: Long? = 2_000_000,
        spent: Long? = 750_000,
        step: LimitsStep = LimitsStep.OVERVIEW,
        raise: Boolean = false,
        form: CapsEditForm = CapsEditForm(),
        error: FailureKind? = null,
        code: String? = null,
        supported: Boolean = true,
    ) = SpendingLimitsUiState(
        loaded = true, name = "Savings", supported = supported, perTx = perTx, perPeriod = perPeriod, spent = spent, step = step,
        raiseForm = raise, form = form, error = error, errorCode = code,
    )

    private fun show(s: SpendingLimitsUiState, a: SpendingLimitsActions = SpendingLimitsActions()) =
        rule.setContent { SpendingLimitsScreen(s, a) }

    @Test
    fun theLimitsAreInSatsWithASeparatorAndTheUsedLineIsThePlans() {
        show(state())
        rule.onNodeWithText("Spending limits").assertIsDisplayed()
        rule.onNodeWithText("Most per payment").assertIsDisplayed()
        rule.onNodeWithText("500,000 sats").assertIsDisplayed()
        rule.onNodeWithText("Most in any 24 hours").assertIsDisplayed()
        rule.onNodeWithText("2,000,000 sats").assertIsDisplayed()
        rule.onNodeWithTag("limits_used").assertIsDisplayed()
        rule.onNodeWithText("Used in the last 24 hours: 750,000 sats of 2,000,000 sats").assertIsDisplayed()
        rule.onNodeWithText("Lower a limit").assertIsDisplayed()
        rule.onNodeWithText("Raise or remove a limit").assertIsDisplayed()
    }

    @Test
    fun noLimitsSaysNoLimitHasNoUsedLineAndNothingToRaise() {
        show(state(perTx = null, perPeriod = null, spent = 0))
        rule.onNodeWithTag("limits_now_tx").assertIsDisplayed()
        rule.onAllNodesWithTextCount("No limit", 2)
        rule.onNodeWithTag("limits_used").assertDoesNotExist()
        rule.onNodeWithText("Raise or remove a limit").assertDoesNotExist()
        rule.onNodeWithText("Lower a limit").assertIsDisplayed()
    }

    @Test
    fun aVaultWithoutLimitsOffersNoButtons() {
        show(state(supported = false))
        rule.onNodeWithTag("wallet_limits_unsupported").assertIsDisplayed()
        rule.onNodeWithText("Lower a limit").assertDoesNotExist()
    }

    @Test
    fun theButtonsOpenTheForms() {
        var lowered = 0
        var raised = 0
        show(state(), SpendingLimitsActions(onLower = { lowered++ }, onRaise = { raised++ }))
        rule.onNodeWithText("Lower a limit").performClick()
        rule.onNodeWithText("Raise or remove a limit").performClick()
        assertEquals(1, lowered)
        assertEquals(1, raised)
    }

    @Test
    fun theLowerFormExplainsAHigherNumberInlineAndStaysOff() {
        show(state(step = LimitsStep.LOWER, form = CapsEditForm(perTx = "600000")))
        rule.onNodeWithText("That is not lower than it is now. To raise a limit, use Raise or remove a limit.").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun theLowerFormTakesALowerNumber() {
        var next = 0
        show(state(step = LimitsStep.LOWER, form = CapsEditForm(perTx = "400000")), SpendingLimitsActions(onNext = { next++ }))
        rule.onNodeWithText("Next").assertIsEnabled().performClick()
        assertEquals(1, next)
    }

    @Test
    fun theLowerQuestionIsThePlansWithNoPasswordField() {
        show(state(step = LimitsStep.CONFIRM_SET, form = CapsEditForm(perTx = "400000")))
        rule.onNodeWithText("Lower the limit to 400,000 sats per payment?").assertIsDisplayed()
        rule.onNodeWithTag("wallet_limits_password").assertDoesNotExist()
        rule.onNodeWithTag("wallet_limits_pin").assertDoesNotExist()
    }

    @Test
    fun theRaiseQuestionAsksForThePinAndPassword() {
        show(state(step = LimitsStep.CONFIRM_RAISE, raise = true, form = CapsEditForm(perTx = "600000")))
        rule.onNodeWithText("Raise the limit from 500,000 sats per payment to 600,000 sats per payment?").assertIsDisplayed()
        rule.onNodeWithText("Enter your PIN and password.").assertIsDisplayed()
        rule.onNodeWithTag("wallet_limits_pin").assertExists()
        rule.onNodeWithTag("wallet_limits_password").assertExists()
        // Nothing to send until both are typed.
        rule.onNodeWithText("Raise").assertIsNotEnabled()
    }

    @Test
    fun removingAsksRemoveTheLimit() {
        show(state(step = LimitsStep.CONFIRM_RAISE, raise = true, form = CapsEditForm(removePeriod = true)))
        rule.onNodeWithText("Remove the limit?").assertIsDisplayed()
        rule.onNodeWithText("Remove").assertIsNotEnabled()
    }

    @Test
    fun removingBothLimitsAsksInThePlural() {
        show(state(step = LimitsStep.CONFIRM_RAISE, raise = true, form = CapsEditForm(removeTx = true, removePeriod = true)))
        rule.onNodeWithText("Remove the limits?").assertIsDisplayed()
    }

    @Test
    fun anUnreadableSettingsRefusalPointsToTheReset() {
        show(state(error = FailureKind.OTHER, code = WalletsManager.SETTINGS_UNREADABLE))
        rule.onNodeWithText("This phone can't read its wallet settings, so nothing was raised. Open the wallet and choose Reset wallet settings on this phone.").assertExists()
    }

    @Test
    fun aDuplicateWalletRefusalHasItsOwnText() {
        show(state(error = FailureKind.OTHER, code = WalletsManager.DUPLICATE_WALLET))
        rule.onNodeWithText("Your vault holds another copy of this wallet. Delete one copy before setting a limit.").assertExists()
        rule.onNodeWithText("Nothing was changed. Your limits are as they were.").assertExists()
    }

    @Test
    fun aWrongPinIsOnThePinFieldAndTheLimitsAreSaidToBeUnchanged() {
        show(state(step = LimitsStep.CONFIRM_RAISE, raise = true, form = CapsEditForm(perTx = "600000"), error = FailureKind.BAD_PIN))
        rule.onNodeWithText("That PIN is not correct.").assertExists()
        rule.onNodeWithText("Nothing was changed. Your limits are as they were.").assertExists()
    }

    @Test
    fun backoffAndConflictAreShownPlainly() {
        show(state(error = FailureKind.BACKOFF))
        rule.onNodeWithText("Too many wrong attempts. Wait before trying again.").assertExists()
        rule.onNodeWithText("Nothing was changed. Your limits are as they were.").assertExists()
    }

    @Test
    fun aConflictSaysSoAndAPendingLimitHasItsOwnText() {
        show(state(error = FailureKind.CONFLICT))
        rule.onNodeWithText("This changed on another device. Look again and retry.").assertExists()
    }

    @Test
    fun theStateOfAPendingLimitIsNamed() {
        show(state(error = FailureKind.OTHER, code = WalletsManager.CAPS_PENDING))
        rule.onNodeWithText("A spending limit you chose has not been set yet, so nothing was raised. Try again once it is set.").assertExists()
    }

    @Test
    fun theRaiseFormOffersRemoveOnlyForALimitThatIsThere() {
        show(state(perTx = null, perPeriod = 2_000_000, step = LimitsStep.RAISE, raise = true))
        rule.onNodeWithTag("limit_remove_period").performScrollTo().assertExists()
        rule.onNodeWithTag("limit_remove_tx").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, count: Int) {
    assertEquals(count, onAllNodesWithText(text).fetchSemanticsNodes().size)
}
