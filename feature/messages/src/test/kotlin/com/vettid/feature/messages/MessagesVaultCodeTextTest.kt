package com.vettid.feature.messages

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ANDROID-PLAN 0.1.57 (2): an unmapped vault code on the conversations list is named by `error_vault_code`. */
@RunWith(RobolectricTestRunner::class)
class MessagesVaultCodeTextTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anUnmappedCodeIsNamed() {
        rule.setContent { ErrorState(FailureKind.OTHER, onRetry = {}, shownCode = "relay_error") }
        rule.onNodeWithText(
            "Something went wrong (code: relay_error). Try again. If it keeps happening, contact VettID and give this code.",
        ).assertExists()
    }

    @Test
    fun withoutAVaultCodeTheTextIsTheUsualOne() {
        rule.setContent { ErrorState(FailureKind.OTHER, onRetry = {}) }
        rule.onNodeWithText("Something went wrong. Try again.").assertExists()
    }
}
