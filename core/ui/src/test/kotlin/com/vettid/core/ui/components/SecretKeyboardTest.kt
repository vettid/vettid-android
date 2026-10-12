package com.vettid.core.ui.components

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the keyboard is told about a secret field (A6 audit S2; the app-lock keystroke leak of 2026-10-09 is the
 * precedent for pinning this): the editor info the focused field hands to the input method, read the way the
 * platform reads it. A visible secret is the password class of text, without autocorrect, flagged "do not learn",
 * and still shown as typed.
 */
@RunWith(RobolectricTestRunner::class)
class SecretKeyboardTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    /** Focuses the field tagged [tag] and returns what it tells the keyboard. */
    private fun editorInfo(tag: String): EditorInfo {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnUiThread { checkNotNull(view.onCreateInputConnection(info)) { "no field is taking input" } }
        return info
    }

    private fun EditorInfo.variation() = inputType and InputType.TYPE_MASK_VARIATION

    private fun EditorInfo.has(flag: Int) = inputType and flag != 0

    private fun EditorInfo.noLearning() = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    @Test
    fun aVisibleSecretIsPasswordClassWithoutAutocorrectOrLearningAndStaysReadable() {
        rule.setContent {
            view = LocalView.current
            Column {
                NoKeyboardLearning {
                    OutlinedTextField(
                        "abandon ability able",
                        {},
                        keyboardOptions = visibleSecretKeyboard(KeyboardCapitalization.Characters, ImeAction.Done),
                        modifier = Modifier.testTag("secret"),
                    )
                }
                OutlinedTextField("hello", {}, modifier = Modifier.testTag("plain"))
            }
        }
        val secret = editorInfo("secret")
        assertEquals(InputType.TYPE_CLASS_TEXT, secret.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, secret.variation())
        assertFalse("autocorrect", secret.has(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
        assertTrue("capitals as asked", secret.has(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
        assertTrue("the keyboard is told not to learn", secret.noLearning())
        assertEquals(EditorInfo.IME_ACTION_DONE, secret.imeOptions and EditorInfo.IME_MASK_ACTION)
        // Visible: the text is on screen as typed, and the field is not announced as a password.
        val node = rule.onNodeWithTag("secret").fetchSemanticsNode().config
        assertEquals("abandon ability able", node.getOrNull(SemanticsProperties.EditableText)?.text)
        assertNull(node.getOrNull(SemanticsProperties.Password))

        // An ordinary field next to it is what a keyboard learns from: the test tells the two apart.
        val plain = editorInfo("plain")
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, plain.variation())
        assertTrue(plain.has(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
        assertFalse(plain.noLearning())
    }

    /** Switched off (an item that is not secret), a field is left as its own options say. */
    @Test
    fun switchedOffTheFieldIsLeftAsItsOptionsSay() {
        rule.setContent {
            view = LocalView.current
            NoKeyboardLearning(enabled = false) {
                OutlinedTextField("hello", {}, modifier = Modifier.testTag("plain"))
            }
        }
        val plain = editorInfo("plain")
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, plain.variation())
        assertFalse(plain.noLearning())
    }

    @Test
    fun aMultiLineVisibleSecretKeepsItsLinesAndTheSameProtection() {
        rule.setContent {
            view = LocalView.current
            NoKeyboardLearning {
                OutlinedTextField(
                    "one\ntwo",
                    {},
                    minLines = 3,
                    keyboardOptions = visibleSecretKeyboard(),
                    modifier = Modifier.testTag("phrase"),
                )
            }
        }
        val info = editorInfo("phrase")
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.variation())
        assertTrue("Enter is a new line", info.has(InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(info.has(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
        assertTrue(info.noLearning())
    }

    /** The PIN and password fields' keyboards, as they were: the classes a keyboard never learns from. */
    @Test
    fun thePinAndPasswordFieldsAreNumberPasswordAndPassword() {
        rule.setContent {
            view = LocalView.current
            Column {
                SecretField("1234", {}, "Vault PIN", Modifier.testTag("pin"), isPin = true)
                SecretField("secret", {}, "Credential password", Modifier.testTag("password"))
            }
        }
        val pin = editorInfo("pin")
        assertEquals(InputType.TYPE_CLASS_NUMBER, pin.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, pin.variation())
        val password = editorInfo("password")
        assertEquals(InputType.TYPE_CLASS_TEXT, password.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, password.variation())
        assertFalse(password.has(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
    }
}
