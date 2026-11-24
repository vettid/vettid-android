package com.vettid.feature.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.ui.components.ApprovalsIndicator
import com.vettid.core.ui.components.LocalApprovalsIndicator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The daily check is a gate: never the approvals indicator in its top bar, whatever provides one (ANDROID-PLAN §4 "The
 * approvals indicator" 2). Its top bar's Cancel shows only beside Lock vault, and no empty action row otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerCheckIndicatorTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = object : OwnerCheckActions {
        override fun setPin(v: String) = Unit
        override fun setPassword(v: String) = Unit
        override fun setHoldOff(choice: HoldOffChoice) = Unit
        override fun submit() = Unit
        override fun lockVault() = Unit
    }

    private fun show(state: OwnerCheckUiState) = rule.setContent {
        CompositionLocalProvider(LocalApprovalsIndicator provides ApprovalsIndicator(count = 2) {}) {
            OwnerCheckContent(state, actions, onCancel = {})
        }
    }

    @Test
    fun aCheckTheMemberAskedForHasCancelBesideLockAndNoIndicator() {
        show(OwnerCheckUiState(mode = OwnerCheckMode.VOLUNTARY, watch = LockWatch.CHECKING))
        rule.onNodeWithTag("owner_check_cancel").assertExists()
        rule.onAllNodesWithTag("top_bar_approvals").assertCountEquals(0)
    }

    @Test
    fun aCheckWithoutTheLockLineHasNoCancelInTheBar() {
        show(OwnerCheckUiState(mode = OwnerCheckMode.VOLUNTARY))
        rule.onAllNodesWithTag("owner_check_cancel").assertCountEquals(0)
        rule.onAllNodesWithTag("top_bar_approvals").assertCountEquals(0)
    }
}
