package com.vettid.feature.credential

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ANDROID-PLAN 0.1.88 "Replacing the vault's keys, once": the prompt on the password change's success screen. */
@RunWith(RobolectricTestRunner::class)
class ReplaceKeysPromptTest {
    @get:Rule
    val rule = createComposeRule()

    private val log = mutableListOf<String>()

    private fun show(state: ChangePasswordUiState) = rule.setContent {
        ChangePasswordContent(
            state, {}, {}, {}, {}, { log += "back" },
            onReplaceKeys = { log += "replace" }, onNotNow = { log += "not_now" },
        )
    }

    @Test
    fun theSuccessScreenShowsThePromptInTheApprovedWords() {
        show(ChangePasswordUiState(done = true, askReplaceKeys = true))
        rule.onNodeWithText("Also replace your vault's keys?").assertExists()
        rule.onNodeWithText(
            "So an older copy of your vault, opened with your old PIN or password, can't be used to reach your connections. " +
                "Your connections are told about your new keys.",
        ).assertExists()
        rule.onNodeWithText("Replace keys").assertExists()
        rule.onNodeWithText("Not now").assertExists()
    }

    @Test
    fun noPromptUnlessAsked() {
        show(ChangePasswordUiState(done = true, askReplaceKeys = false))
        rule.onNodeWithTag("replace_keys_prompt").assertDoesNotExist()
    }

    @Test
    fun notNowOnlyPutsItAway() {
        show(ChangePasswordUiState(done = true, askReplaceKeys = true))
        rule.onNodeWithText("Not now").performClick()
        assertEquals(listOf("not_now"), log)
    }

    @Test
    fun replaceKeysOpensTheRotation() {
        show(ChangePasswordUiState(done = true, askReplaceKeys = true))
        rule.onNodeWithText("Replace keys").performClick()
        assertEquals(listOf("replace"), log)
    }
}
