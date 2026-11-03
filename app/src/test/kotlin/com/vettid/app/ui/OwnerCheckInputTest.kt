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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.components.AlertDialog
import com.vettid.core.ui.components.InputGuard
import com.vettid.core.ui.components.LocalCheckCovered
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

    private enum class Screen { READ_ONLY, EDITOR, DRAWER, OPEN_REQUEST, CREDENTIAL_PROMPT, SECRET_DIALOG, NONE }

    private var gate by mutableStateOf(OwnerCheckGateState())
    private var screen by mutableStateOf(Screen.READ_ONLY)
    private var pin by mutableStateOf("")
    private var callUp by mutableStateOf(false)

    private var cover: OwnerCheckCover? = null

    private fun due(overdue: Boolean = false, deadline: Instant? = null) {
        gate = OwnerCheckGateState(gated = true, overdue = overdue, deadline = deadline)
        // With the clock held by hand nothing else tells the composition that state changed.
        Snapshot.sendApplyNotifications()
    }

    /** The check is passed: the vault says so (`gated` false) and the shell lets go of the covering after its debounce. */
    private fun passCheck() {
        gate = OwnerCheckGateState()
        rule.mainClock.advanceTimeBy(OWNER_CHECK_UNLATCH_MS + 100)
        rule.waitForIdle()
    }

    /** The shell in small: the screens under the guard, and the check over them as [rememberOwnerCheckMode] says. */
    private fun show() = rule.setContent { Shell() }

    @Composable
    private fun Shell() {
        val guard = remember { InputGuard() }
        val covering = rememberOwnerCheckMode(gate, onList = screen == Screen.DRAWER, guard = guard, asked = null)
        cover = covering
        val mode = covering.mode
        Box {
            CompositionLocalProvider(LocalInputGuard provides guard, LocalCheckCovered provides (mode != null)) {
                when (screen) {
                    Screen.READ_ONLY, Screen.DRAWER -> Text("content", Modifier.testTag("content"))
                    Screen.EDITOR -> Editor()
                    // An approval open for a decision: nothing typed.
                    Screen.OPEN_REQUEST -> {
                        HoldsInput(true)
                        Text("request", Modifier.testTag("content"))
                    }
                    // A credential prompt: a PIN field with nothing typed.
                    Screen.CREDENTIAL_PROMPT -> SecretField(pin, { pin = it }, "PIN", isPin = true)
                    // The same prompt in a dialog, which the shell hides when the check covers it.
                    Screen.SECRET_DIALOG -> AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        text = { SecretField(pin, { pin = it }, "PIN", isPin = true, modifier = Modifier.testTag("dialog_pin")) },
                    )
                    Screen.NONE -> Unit
                }
            }
            if (mode == OwnerCheckMode.GATED) Text("check", Modifier.testTag("check"))
            // The call screen is drawn after the shell, above everything in it (CallOverlay).
            if (callUp) Text("call", Modifier.testTag("call"))
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
        passCheck()
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
        passCheck()
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
    fun `a call does not defer the check, whose cover stays under the call screen`() {
        screen = Screen.DRAWER
        callUp = true
        show()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
        rule.onNodeWithTag("call").assertExists()
    }

    @Test
    fun `a typed password is gone when the check covers, and a draft message is kept`() {
        screen = Screen.CREDENTIAL_PROMPT
        pin = "1234"
        show()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
        assertEquals("", pin)
        passCheck()
        checkNotShown()
        // A draft is not a secret.
        screen = Screen.EDITOR
        rule.waitForIdle()
        rule.onNodeWithTag("editor").performTextInput("draft")
        rule.waitForIdle()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
        assertEquals("draft", rule.onNodeWithTag("editor").fetchSemanticsNode().config.editableText())
    }

    @Test
    fun `a password typed in a dialog is wiped before the dialog is hidden`() {
        screen = Screen.SECRET_DIALOG
        pin = "1234"
        show()
        rule.waitForIdle()
        due(overdue = true)
        rule.waitForIdle()
        checkShown()
        assertEquals("", pin)
        rule.onNodeWithTag("dialog_pin").assertDoesNotExist()
    }

    @Test
    fun `a short dip of gated does not let the check go, a pass does`() {
        show()
        due()
        rule.waitForIdle()
        checkShown()
        // By hand, frame by frame: with the clock running by itself the whole wait would pass in waitForIdle.
        rule.mainClock.autoAdvance = false
        gate = OwnerCheckGateState()
        rule.mainClock.advanceTimeBy(500)
        checkShown()
        due()
        rule.mainClock.advanceTimeBy(OWNER_CHECK_UNLATCH_MS + 100)
        checkShown()
        rule.mainClock.autoAdvance = true
        passCheck()
        checkNotShown()
    }

    @Test
    fun `a pass keeps the check off for ten seconds while gated stays true, and not for a new deadline`() {
        show()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        due(deadline = Instant.parse("2026-11-03T08:00:00Z"))
        rule.mainClock.advanceTimeBy(500)
        checkShown()
        rule.runOnUiThread {
            cover!!.release()
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(500)
        checkNotShown()
        // The vault still says gated: the check comes back after OWNER_CHECK_PASSED_MS.
        rule.mainClock.advanceTimeBy(OWNER_CHECK_PASSED_MS + 200)
        checkShown()
        // A new deadline (the next check came due) is covered at once, not after the ten seconds.
        rule.runOnUiThread {
            cover!!.release()
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(500)
        checkNotShown()
        due(deadline = Instant.parse("2026-11-04T08:00:00Z"))
        rule.mainClock.advanceTimeBy(500)
        checkShown()
    }

    @Test
    fun `the keys of the phone pass the check's layer, the others do not`() {
        listOf(
            Key.Back, Key.VolumeUp, Key.VolumeDown, Key.VolumeMute, Key.HeadsetHook, Key.MediaPlayPause, Key.MediaPlay,
            Key.MediaPause, Key.MediaNext, Key.MediaPrevious, Key.Call, Key.EndCall,
        ).forEach { assertEquals("$it", false, ownerCheckLayerEats(it)) }
        listOf(Key.A, Key.Five, Key.Enter, Key.Backspace, Key.Tab, Key.DirectionDown).forEach {
            assertEquals("$it", true, ownerCheckLayerEats(it))
        }
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

    @Test
    fun `a new composition with a draft does not cover it before the screen reports its input`() {
        screen = Screen.EDITOR
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        restore.setContent { Shell() }
        rule.onNodeWithTag("editor").performTextInput("draft")
        rule.waitForIdle()
        due()
        rule.waitForIdle()
        checkNotShown()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        checkNotShown()
        assertEquals("draft", rule.onNodeWithTag("editor").fetchSemanticsNode().config.editableText())
    }

    @Test
    fun `the gate's clock wakes at the next boundary, so overdue turns true at deadline plus 300 seconds`() {
        val deadline = Instant.parse("2026-11-03T08:00:00Z")
        val v = view(deadline)
        fun wake(now: String, scale: Long = 1) = nextWakeMillis(v, Instant.parse(now), scale, 15_000)
        // Far from any mark: the tick.
        assertEquals(15_000L, wake("2026-11-03T03:00:00Z"))
        // Just before the deadline, the deadline; just after it, the end of the deferral, capped at the tick.
        assertEquals(1_001L, wake("2026-11-03T07:59:59Z"))
        assertEquals(15_000L, wake("2026-11-03T08:00:01Z"))
        // Five seconds before the end of the deferral the clock sleeps exactly until it.
        val before = Instant.parse("2026-11-03T08:04:55Z")
        val sleep = nextWakeMillis(v, before, 1, 15_000)
        assertEquals(5_001L, sleep)
        assertEquals(false, OwnerCheckGateState.of(v, emptyList(), before).overdue)
        assertEquals(true, OwnerCheckGateState.of(v, emptyList(), before.plusMillis(sleep)).overdue)
        // After the end of the deferral, and with no deadline, the tick.
        assertEquals(15_000L, wake("2026-11-03T08:06:00Z"))
        assertEquals(15_000L, nextWakeMillis(null, before, 1, 15_000))
        // On a staging vault of scale 60 the deferral ends after 5 seconds.
        assertEquals(4_001L, wake("2026-11-03T08:00:01Z", 60))
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
        fun mode(gated: Boolean, onList: Boolean, busy: Boolean, overdue: Boolean, asked: OwnerCheckMode? = null) =
            ownerCheckMode(gated, onList, busy, overdue, asked)
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = true, busy = true, overdue = false))
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = false, busy = false, overdue = false))
        assertEquals(OwnerCheckMode.GATED, mode(gated = true, onList = false, busy = true, overdue = true))
        assertEquals(null, mode(gated = true, onList = false, busy = true, overdue = false))
        assertEquals(null, mode(gated = false, onList = true, busy = true, overdue = true))
        assertEquals(
            OwnerCheckMode.VOLUNTARY,
            mode(gated = false, onList = false, busy = false, overdue = false, asked = OwnerCheckMode.VOLUNTARY),
        )
    }
}

private fun SemanticsConfiguration.editableText(): String = this[SemanticsProperties.EditableText].text
