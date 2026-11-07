package com.vettid.feature.credential

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsFocused
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
 * #323: Done on the reset's last field (confirm password) hides the soft keyboard, keeps the field and sends nothing: the
 * form needs its checkbox and button.
 */
@RunWith(RobolectricTestRunner::class)
class NewCredentialDoneKeyTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun doneOnTheConfirmPasswordHidesTheKeyboardKeepsTheFieldAndSendsNothing() {
        val sent = mutableListOf<String>()
        val actions = NewCredentialActions(submit = { sent += "submit" }, confirm = { sent += "confirm" })
        // Everything filled and acknowledged: a submit would be possible, so one that came from Done would show.
        val state = NewCredentialUiState(
            pin = "123456", current = "old", password = "new password", confirm = "new password", acknowledged = true,
        )
        var hides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() {
                hides++
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { NewCredentialContent(state, actions) }
        }
        rule.onNodeWithTag("new_credential_confirm").requestFocus().assertIsFocused()
        rule.onNodeWithTag("new_credential_confirm").performImeAction()
        rule.runOnIdle { assertEquals(1, hides) }
        // Focus stays on the field: with a hardware keyboard, a second Enter must not land on Back and leave the form.
        rule.onNodeWithTag("new_credential_confirm").assertIsFocused()
        assertEquals(emptyList<String>(), sent)
    }
}
