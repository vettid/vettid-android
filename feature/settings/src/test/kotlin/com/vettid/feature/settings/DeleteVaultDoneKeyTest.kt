package com.vettid.feature.settings

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import com.vettid.core.data.policy.DeletePhrase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #323: Done on the deletion's password hides the keyboard and sends nothing: the form needs its checkbox and button. */
@RunWith(RobolectricTestRunner::class)
class DeleteVaultDoneKeyTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun doneOnThePasswordHidesTheKeyboardAndSendsNothing() {
        val sent = mutableListOf<String>()
        val actions = DeleteVaultActions(submit = { sent += "submit" }, confirm = { sent += "confirm" })
        // Ready to send: a submit that came from Done would show.
        val state = DeleteVaultUiState(phrase = DeletePhrase.PHRASE, pin = "123456", password = "secret", acknowledged = true)
        rule.setContent { DeleteVaultContent(state, actions) }
        rule.onNodeWithTag("delete_password").requestFocus().assertIsFocused()
        rule.onNodeWithTag("delete_password").performImeAction()
        rule.onNodeWithTag("delete_password").assertIsNotFocused()
        assertEquals(emptyList<String>(), sent)
    }
}
