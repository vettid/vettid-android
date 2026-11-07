package com.vettid.feature.credential

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #323: Done on the reset's last field (confirm password) hides the keyboard and sends nothing: the form needs its checkbox
 * and button.
 */
@RunWith(RobolectricTestRunner::class)
class NewCredentialDoneKeyTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun doneOnTheConfirmPasswordHidesTheKeyboardAndSendsNothing() {
        val sent = mutableListOf<String>()
        val actions = NewCredentialActions(submit = { sent += "submit" }, confirm = { sent += "confirm" })
        // Everything filled and acknowledged: a submit would be possible, so one that came from Done would show.
        val state = NewCredentialUiState(
            pin = "123456", current = "old", password = "new password", confirm = "new password", acknowledged = true,
        )
        rule.setContent { NewCredentialContent(state, actions) }
        rule.onNodeWithTag("new_credential_confirm").requestFocus().assertIsFocused()
        rule.onNodeWithTag("new_credential_confirm").performImeAction()
        rule.onNodeWithTag("new_credential_confirm").assertIsNotFocused()
        assertEquals(emptyList<String>(), sent)
    }
}
