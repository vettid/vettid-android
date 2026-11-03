package com.vettid.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.components.InputGuard
import com.vettid.core.ui.components.LocalInputGuard
import com.vettid.core.ui.components.SecretField
import com.vettid.feature.onboarding.OwnerCheckMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The daily owner check and input in progress (VAULT-MESSAGING §3.6.5, owner decision 2026-11-03): due, it comes at once
 * everywhere except over a screen holding unsent input or an operation in progress, where it waits until the member leaves or
 * five minutes after the deadline; a call defers it without a limit. Then it is drawn over the screen, which keeps what was typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnerCheckInputTest {
    @get:Rule
    val rule = createComposeRule()

    private enum class Screen { READ_ONLY, EDITOR, DRAWER, OPEN_REQUEST, CREDENTIAL_PROMPT, NONE }

    private var gate by mutableStateOf(OwnerCheckGateState())
    private var screen by mutableStateOf(Screen.READ_ONLY)

    private fun due(overdue: Boolean = false, inCall: Boolean = false) {
        gate = OwnerCheckGateState(gated = true, overdue = overdue, inCall = inCall)
    }

    /** The shell in small: the screens under the guard, and the check over them as [rememberOwnerCheckMode] says. */
    private fun show() = rule.setContent { Shell() }

    @Composable
    private fun Shell() {
        val guard = remember { InputGuard() }
        val mode = rememberOwnerCheckMode(gate, onList = screen == Screen.DRAWER, guard = guard, asked = null)
        Box {
            CompositionLocalProvider(LocalInputGuard provides guard) {
                when (screen) {
                    Screen.READ_ONLY, Screen.DRAWER -> Text("content", Modifier.testTag("content"))
                    Screen.EDITOR -> Editor()
                    // An approval open for a decision: nothing typed.
                    Screen.OPEN_REQUEST -> {
                        HoldsInput(true)
                        Text("request", Modifier.testTag("content"))
                    }
                    // A credential prompt: a PIN field with nothing typed.
                    Screen.CREDENTIAL_PROMPT -> SecretField("", {}, "PIN", isPin = true)
                    Screen.NONE -> Unit
                }
            }
            if (mode == OwnerCheckMode.GATED) Text("check", Modifier.testTag("check"))
        }
    }

    @Composable
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
        due()
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on a drawer screen the check comes at once`() {
        screen = Screen.DRAWER
        show()
        due()
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on an editor with no input yet the check comes at once`() {
        screen = Screen.EDITOR
        show()
        due()
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `due on an editor with input the check waits, and comes when the member leaves`() {
        screen = Screen.EDITOR
        show()
        rule.onNodeWithTag("editor").performTextInput("draft")
        rule.waitForIdle()
        due()
        rule.waitForIdle()
        checkNotShown()
        screen = Screen.NONE
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `an editor with input is covered after the deadline plus five minutes and the input is kept`() {
        screen = Screen.EDITOR
        show()
        rule.onNodeWithTag("editor").performTextInput("draft")
        rule.waitForIdle()
        due()
        rule.waitForIdle()
        checkNotShown()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
        // The check passes: the same screen is there, with its input.
        gate = OwnerCheckGateState()
        rule.waitForIdle()
        checkNotShown()
        assertEquals("draft", rule.onNodeWithTag("editor").fetchSemanticsNode().config.editableText())
    }

    @Test
    fun `input that appears under the check does not take it away`() {
        screen = Screen.EDITOR
        show()
        due()
        rule.waitForIdle()
        checkShown()
        rule.onNodeWithTag("editor").performTextInput("typed under the check")
        rule.waitForIdle()
        checkShown()
        gate = OwnerCheckGateState()
        rule.waitForIdle()
        checkNotShown()
    }

    @Test
    fun `an approval open with nothing typed waits, then is covered at the deadline`() {
        screen = Screen.OPEN_REQUEST
        show()
        due()
        rule.waitForIdle()
        checkNotShown()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `a credential prompt with nothing typed waits, then is covered at the deadline`() {
        screen = Screen.CREDENTIAL_PROMPT
        show()
        due()
        rule.waitForIdle()
        checkNotShown()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `a call defers the check without a limit, on any screen, until it ends`() {
        screen = Screen.DRAWER
        show()
        due(overdue = true, inCall = true)
        rule.waitForIdle()
        checkNotShown()
        due(overdue = true, inCall = false)
        rule.waitForIdle()
        checkShown()
    }

    @Test
    fun `the covering survives a new composition`() {
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        restore.setContent { Shell() }
        due()
        rule.waitForIdle()
        checkShown()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        checkShown()
    }

    private fun view(deadline: Instant?, state: OwnerCheckState = OwnerCheckState.OK) =
        OwnerCheckView(state, deadline, 86_400, 0, true, null)

    @Test
    fun `the five minutes count from the vault's deadline, not from when the shell saw it`() {
        val deadline = Instant.parse("2026-11-03T08:00:00Z")
        fun at(now: String, scale: Long = 1) = OwnerCheckGateState.of(view(deadline), emptyList(), Instant.parse(now), timeScale = scale)
        assertEquals(false, at("2026-11-03T07:59:00Z").gated)
        assertEquals(true, at("2026-11-03T08:00:01Z").gated)
        assertEquals(false, at("2026-11-03T08:00:01Z").overdue)
        assertEquals(false, at("2026-11-03T08:04:59Z").overdue)
        // Back after hours: a new process, an unlock, the foreground: overdue at once.
        assertEquals(true, at("2026-11-03T08:05:00Z").overdue)
        assertEquals(true, at("2026-11-03T14:00:00Z").overdue)
        // On a staging vault of time scale 60 the five minutes are five seconds.
        assertEquals(false, at("2026-11-03T08:00:04Z", 60).overdue)
        assertEquals(true, at("2026-11-03T08:00:05Z", 60).overdue)
    }

    @Test
    fun `a check that is due without a deadline has nothing to wait from`() {
        val s = OwnerCheckGateState.of(view(null, OwnerCheckState.HELD), emptyList(), Instant.parse("2026-11-03T08:00:00Z"))
        assertEquals(true, s.gated)
        assertEquals(true, s.overdue)
    }

    @Test
    fun `the rule in one place`() {
        fun mode(gated: Boolean, onList: Boolean, busy: Boolean, overdue: Boolean, inCall: Boolean = false, asked: OwnerCheckMode? = null) =
            ownerCheckMode(gated, onList, busy, overdue, inCall, asked)
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = true, busy = true, overdue = false))
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = false, busy = false, overdue = false))
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = false, busy = true, overdue = true))
        assertEquals(null, mode(gated = true, onList = false, busy = true, overdue = false))
        assertEquals(null, mode(gated = true, onList = true, busy = false, overdue = true, inCall = true))
        assertEquals(null, mode(gated = false, onList = true, busy = true, overdue = true))
        assertEquals(
            OwnerCheckMode.VOLUNTARY,
            mode(gated = false, onList = false, busy = false, overdue = false, asked = OwnerCheckMode.VOLUNTARY),
        )
    }
}

private fun SemanticsConfiguration.editableText(): String = this[SemanticsProperties.EditableText].text
