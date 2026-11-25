package com.vettid.feature.connections

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.ui.components.ApprovalsIndicator
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.LocalApprovalsIndicator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The inviter's connection request is a request's screen: no approvals indicator (ANDROID-PLAN §4 "The approvals indicator" 2). */
@RunWith(RobolectricTestRunner::class)
class RequestIndicatorTest {
    @get:Rule
    val rule = createComposeRule()

    private val indicator = ApprovalsIndicator(count = 1) {}

    @Test
    fun anotherFormWithABackArrowShowsIt() {
        rule.setContent {
            CompositionLocalProvider(LocalApprovalsIndicator provides indicator) {
                FormScaffold(title = "Invite", primaryLabel = null, onPrimary = {}, onBack = {}) {}
            }
        }
        rule.onNodeWithTag("top_bar_approvals").assertExists()
    }

    @Test
    fun theRequestDoesNot() {
        rule.setContent {
            CompositionLocalProvider(LocalApprovalsIndicator provides indicator) {
                ConnectionRequestContent(
                    name = "Sam Lee", sas = "042817", remote = false, busy = false, error = null,
                    onApprove = {}, onDecline = {}, onBack = {},
                )
            }
        }
        rule.onAllNodesWithTag("top_bar_approvals").assertCountEquals(0)
    }
}
