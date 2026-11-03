package com.vettid.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.components.InputGuard
import com.vettid.core.ui.components.LocalInputGuard
import com.vettid.feature.onboarding.OwnerCheckMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The daily owner check and input in progress (VAULT-MESSAGING §3.6.5, owner decision 2026-11-03): due, it comes at once
 * everywhere except over a screen holding unsent input, where it waits until the member leaves or [OWNER_CHECK_DEFERRAL_MS]
 * pass; then it is drawn over the screen, which keeps what was typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnerCheckInputTest {
    @get:Rule
    val rule = createComposeRule()

    private enum class Screen { READ_ONLY, EDITOR, DRAWER, NONE }

    private var gated by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.READ_ONLY)

    /** The shell in small: the screens under the guard, and the check over them as [rememberOwnerCheckMode] says. */
    private fun show() = rule.setContent {
        val guard = remember { InputGuard() }
        val mode = rememberOwnerCheckMode(
            gated = gated,
            onList = screen == Screen.DRAWER,
            guard = guard,
            asked = null,
            clock = { rule.mainClock.currentTime },
        )
        Box {
            CompositionLocalProvider(LocalInputGuard provides guard) {
                when (screen) {
                    Screen.READ_ONLY, Screen.DRAWER -> Text("content", Modifier.testTag("content"))
                    Screen.EDITOR -> Editor()
                    Screen.NONE -> Unit
                }
            }
            if (mode == OwnerCheckMode.GATED) Text("check", Modifier.testTag("check"))
        }
    }

    @androidx.compose.runtime.Composable
    private fun Editor() {
        var text by rememberSaveable { mutableStateOf("") }
        HoldsInput(text.isNotEmpty())
        BasicTextField(text, { text = it }, Modifier.testTag("editor"))
    }

    private fun checkShown() = rule.onNodeWithTag("check").assertExists()

    private fun checkNotShown() = rule.onNodeWithTag("check").assertDoesNotExist()

    @Test
    fun `due on a read-only pushed screen the check comes at once`() {
        show()
        gated = true
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on a drawer screen the check comes at once`() {
        screen = Screen.DRAWER
        show()
        gated = true
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on an editor with no input yet the check comes at once`() {
        screen = Screen.EDITOR
        show()
        gated = true
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on an editor with input the check waits, and comes when the member leaves`() {
        screen = Screen.EDITOR
        show()
        rule.onNodeWithTag("editor").performTextInput("draft")
        gated = true
        rule.waitForIdle()
        checkNotShown()
        screen = Screen.NONE
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on an editor with input the check covers it after five minutes and the input is kept`() {
        screen = Screen.EDITOR
        show()
        rule.onNodeWithTag("editor").performTextInput("draft")
        gated = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(OWNER_CHECK_DEFERRAL_MS - 1_000)
        checkNotShown()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        checkShown()
        // The check passes: the same screen is there, with its input.
        gated = false
        rule.waitForIdle()
        checkNotShown()
        rule.onNodeWithTag("editor").assertExists()
        assertEquals("draft", rule.onNodeWithTag("editor").fetchSemanticsNode().config.editableText())
    }

    @Test
    fun `the rule in one place`() {
        fun mode(gated: Boolean, onList: Boolean, input: Boolean, overdue: Boolean, asked: OwnerCheckMode? = null) =
            ownerCheckMode(gated, onList, input, overdue, asked)
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = true, input = true, overdue = false))
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = false, input = false, overdue = false))
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = false, input = true, overdue = true))
        assertEquals(null, mode(gated = true, onList = false, input = true, overdue = false))
        assertEquals(null, mode(gated = false, onList = true, input = true, overdue = true))
        assertEquals(
            OwnerCheckMode.VOLUNTARY,
            mode(gated = false, onList = false, input = false, overdue = false, asked = OwnerCheckMode.VOLUNTARY),
        )
    }
}

private fun androidx.compose.ui.semantics.SemanticsConfiguration.editableText(): String =
    this[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
