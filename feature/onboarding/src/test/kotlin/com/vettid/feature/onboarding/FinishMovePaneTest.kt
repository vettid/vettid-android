package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeVault
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "Finish the move" refused for want of a usable user_guid: the unlock screen's recover-or-erase words, not "not yet". */
@RunWith(RobolectricTestRunner::class)
class FinishMovePaneTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aPhoneWithoutAUserGuidIsToldItCannotOpenTheVault() {
        val vm = FinishMoveViewModel(FakeVault(AppPhase.Unlocked))
        val state = FinishMoveUiState(failure = FailureKind.NOT_SUPPORTED, noUserGuid = true)
        rule.setContent { FinishMovePane(state, vm, onDone = {}) }
        rule.onNodeWithTag("finish_move_no_user_guid").assertExists()
        rule.onNodeWithText("This phone cannot open your vault").assertExists()
        rule.onNodeWithText("does not support this yet", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("finish_move_later").assertExists()
        // No PIN to type and no "Finish the move" to try again; "Later" stays.
        rule.onNodeWithTag("finish_move_pin").assertIsNotEnabled()
        rule.onNodeWithTag("finish_move_go").assertIsNotEnabled()
        rule.onNodeWithTag("finish_move_later").assertIsEnabled()
    }

    /** Another refusal keeps the PIN and the button. */
    @Test
    fun anotherRefusalCanBeTriedAgain() {
        val vm = FinishMoveViewModel(FakeVault(AppPhase.Unlocked))
        rule.setContent { FinishMovePane(FinishMoveUiState(pin = "975310", failure = FailureKind.NETWORK), vm, onDone = {}) }
        rule.onNodeWithTag("finish_move_pin").assertIsEnabled()
        rule.onNodeWithTag("finish_move_go").assertIsEnabled()
    }

    @Test
    fun anotherRefusalKeepsItsOwnText() {
        val vm = FinishMoveViewModel(FakeVault(AppPhase.Unlocked))
        rule.setContent { FinishMovePane(FinishMoveUiState(failure = FailureKind.NOT_SUPPORTED), vm, onDone = {}) }
        rule.onNodeWithTag("finish_move_no_user_guid").assertDoesNotExist()
        rule.onNodeWithText("does not support this yet", substring = true).assertExists()
    }
}
