package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
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
 * #323: Done on a secret field with no handler of its own hides the soft keyboard, keeps the field and submits nothing; a
 * handler still runs alone.
 */
@RunWith(RobolectricTestRunner::class)
class SecretFieldDoneTest {
    @get:Rule
    val rule = createComposeRule()

    /** Counts the soft keyboard's hides, in place of the real one. */
    private class CountingKeyboard : SoftwareKeyboardController {
        var hides = 0
        override fun show() = Unit
        override fun hide() {
            hides++
        }
    }

    @Test
    fun doneWithoutAHandlerHidesTheKeyboardAndKeepsTheField() {
        val keyboard = CountingKeyboard()
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                Column {
                    // As on a form, a Back button comes first: focus must not move to it (a second hardware Enter would leave).
                    Button(onClick = {}) { Text("Back") }
                    SecretField("secret", {}, "Password", modifier = Modifier.testTag("last"))
                }
            }
        }
        rule.onNodeWithTag("last").requestFocus().assertIsFocused()
        rule.onNodeWithTag("last").performImeAction()
        rule.runOnIdle { assertEquals(1, keyboard.hides) }
        rule.onNodeWithTag("last").assertIsFocused()
    }

    @Test
    fun doneWithItsOwnHandlerRunsItOnceAndKeepsTheField() {
        var handled = 0
        val keyboard = CountingKeyboard()
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                Column {
                    SecretField("1234", {}, "PIN", isPin = true, onImeAction = { handled++ }, modifier = Modifier.testTag("pin"))
                }
            }
        }
        rule.onNodeWithTag("pin").requestFocus()
        rule.onNodeWithTag("pin").performImeAction()
        assertEquals(1, handled)
        // The handler decides alone: the field does not also hide the keyboard.
        assertEquals(0, keyboard.hides)
        // The handler decides what happens next: the field does not also drop focus on its own.
        rule.onNodeWithTag("pin").assertIsFocused()
    }
}
