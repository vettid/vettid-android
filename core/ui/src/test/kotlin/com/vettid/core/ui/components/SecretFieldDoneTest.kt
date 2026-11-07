package com.vettid.core.ui.components

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #323: Done on a secret field with no handler of its own hides the keyboard and submits nothing; a handler still runs. */
@RunWith(RobolectricTestRunner::class)
class SecretFieldDoneTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    @Test
    fun doneWithoutAHandlerClearsFocusSoNoFieldTakesInput() {
        rule.setContent {
            view = LocalView.current
            Column {
                // As on a form, the field is not the first thing that can take focus (the test runs out of touch mode,
                // where a cleared screen's focus goes back to its first focusable).
                Button(onClick = {}) { Text("Back") }
                SecretField("secret", {}, "Password", modifier = Modifier.testTag("last"))
            }
        }
        rule.onNodeWithTag("last").requestFocus().assertIsFocused()
        rule.onNodeWithTag("last").performImeAction()
        rule.onNodeWithTag("last").assertIsNotFocused()
        // The keyboard has nothing to type into: it is hidden.
        rule.runOnUiThread { assertNull(view.onCreateInputConnection(EditorInfo())) }
    }

    @Test
    fun doneWithItsOwnHandlerRunsItOnceAndKeepsTheField() {
        var handled = 0
        rule.setContent {
            Column {
                SecretField("1234", {}, "PIN", isPin = true, onImeAction = { handled++ }, modifier = Modifier.testTag("pin"))
            }
        }
        rule.onNodeWithTag("pin").requestFocus()
        rule.onNodeWithTag("pin").performImeAction()
        assertEquals(1, handled)
        // The handler decides what happens next: the field does not also drop focus on its own.
        rule.onNodeWithTag("pin").assertIsFocused()
    }
}
