package com.vettid.feature.items

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A6 audit S2: the values and notes of a secret or critical item (a recovery phrase, recovery codes, a secure note, a
 * login's address) and a one-time-code seed in any item are typed on a keyboard that does not suggest, correct or
 * learn them: the password class of text with the characters visible, autocorrect off, flagged "do not learn". The
 * values of an ordinary item keep the ordinary keyboard. What the focused field tells the input method is read the
 * way the platform reads it (the app-lock keystroke leak of 2026-10-09 is the precedent for pinning this).
 */
@RunWith(RobolectricTestRunner::class)
class SecretItemKeyboardTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    private val kinds = listOf(
        FieldKinds.TEXT, FieldKinds.MULTILINE, FieldKinds.OTP, FieldKinds.EMAIL, FieldKinds.URL, FieldKinds.NUMBER, FieldKinds.PHONE,
    )

    private fun show(sensitivity: Sensitivity) {
        val draft = ItemDraft(
            name = "x",
            sensitivity = sensitivity,
            fields = kinds.map { DraftField(label = it, kind = it) } + DraftField(label = "Home", kind = FieldKinds.ADDRESS),
        )
        rule.setContent {
            view = LocalView.current
            ItemEditScreen(ItemEditUiState(draft = draft, check = ItemChecks.check(draft)), ItemEditActions())
        }
    }

    private fun focused(): EditorInfo {
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnUiThread { checkNotNull(view.onCreateInputConnection(info)) { "no field is taking input" } }
        return info
    }

    private fun value(kind: String): EditorInfo {
        rule.onNodeWithTag("item_edit_field_value_${kinds.indexOf(kind)}").performScrollTo().performClick()
        return focused()
    }

    private fun notes(): EditorInfo {
        rule.onNodeWithTag("item_edit_notes").performScrollTo().performClick()
        return focused()
    }

    private fun street(): EditorInfo {
        rule.onNodeWithText("Street").performScrollTo().performClick()
        return focused()
    }

    private fun EditorInfo.passwordClass() = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
        inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun EditorInfo.autocorrect() = inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0

    private fun EditorInfo.noLearning() = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    private fun assertNeverLearned(what: String, info: EditorInfo) {
        assertTrue("$what: password class", info.passwordClass())
        assertFalse("$what: autocorrect", info.autocorrect())
        assertTrue("$what: do not learn", info.noLearning())
    }

    private fun secretOrCritical(sensitivity: Sensitivity) {
        show(sensitivity)
        listOf(FieldKinds.TEXT, FieldKinds.MULTILINE, FieldKinds.OTP, FieldKinds.EMAIL, FieldKinds.URL).forEach {
            assertNeverLearned(it, value(it))
        }
        // A recovery phrase's lines stay lines.
        assertTrue(value(FieldKinds.MULTILINE).inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertNeverLearned("address", street())
        assertNeverLearned("notes", notes())
        // Digits keep their digit keyboards, which have no suggestions to learn from.
        assertEquals(InputType.TYPE_CLASS_NUMBER, value(FieldKinds.NUMBER).inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_CLASS_PHONE, value(FieldKinds.PHONE).inputType and InputType.TYPE_MASK_CLASS)
    }

    @Test
    fun aCriticalItemsValuesAndNotesAreNeverLearned() = secretOrCritical(Sensitivity.CRITICAL)

    @Test
    fun aSecretItemsValuesAndNotesAreNeverLearned() = secretOrCritical(Sensitivity.SECRET)

    @Test
    fun anOrdinaryItemKeepsTheOrdinaryKeyboardExceptForAOneTimeCodeSeed() {
        show(Sensitivity.DATA)
        assertNeverLearned("otp", value(FieldKinds.OTP))
        listOf(FieldKinds.TEXT, FieldKinds.MULTILINE).forEach {
            val info = value(it)
            assertFalse("$it: an ordinary text keyboard", info.passwordClass())
            assertTrue("$it: autocorrect", info.autocorrect())
            assertFalse(info.noLearning())
        }
        assertFalse(notes().passwordClass())
        assertFalse(street().noLearning())
    }

    /** The recovery phrase template is critical and its words are a multi-line field: the case the audit names. */
    @Test
    fun theRecoveryPhraseTemplateIsCriticalWithAMultiLineValue() {
        val t = ItemTemplates.all.single { it.id == "recovery_phrase" }
        assertEquals(Sensitivity.CRITICAL, t.sensitivity)
        assertTrue(t.fields.any { it.kind == FieldKinds.MULTILINE })
    }
}
