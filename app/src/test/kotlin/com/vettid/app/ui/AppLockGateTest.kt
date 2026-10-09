package com.vettid.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app lock replaces the app while locked (owner report 2026-10-09: the phone PIN typed into the unlock prompt
 * appeared in the text field of the screen under the lock).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockGateTest {
    @get:Rule
    val rule = createComposeRule()

    private var locked by mutableStateOf(false)
    private var prompts by mutableIntStateOf(0)

    private fun show() = rule.setContent {
        AppLockGate(
            locked = locked,
            onAutoPrompt = { prompts++ },
            lockScreen = { Text("VettID is locked", Modifier.testTag("lock_screen")) },
        ) {
            var draft by rememberSaveable { mutableStateOf("") }
            Column {
                TextField(draft, { draft = it }, Modifier.testTag("draft"))
            }
        }
    }

    @Test
    fun whileLockedNoFieldHoldsFocusAndKeysDoNotReachIt() {
        show()
        rule.onNodeWithTag("draft").performClick()
        rule.onNodeWithTag("draft").performTextInput("hello")
        rule.onNodeWithTag("draft").assertIsFocused()

        locked = true
        rule.waitForIdle()
        // Nothing of the app is composed under the lock: no field, so no focus and no keyboard connection.
        rule.onNodeWithTag("draft").assertDoesNotExist()
        rule.onAllNodes(isFocused() and hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("lock_screen").assertExists()
        rule.onNodeWithTag("app_lock_layer").performKeyInput {
            pressKey(Key.One)
            pressKey(Key.Two)
            pressKey(Key.Three)
            pressKey(Key.Four)
        }

        locked = false
        rule.waitForIdle()
        // The draft comes back as it was, without the keys typed under the lock.
        rule.onNodeWithTag("draft").assertTextEquals("hello")
    }

    @Test
    fun theLockAsksForOnePromptAndRecompositionDoesNotAskAgain() {
        show()
        locked = true
        rule.waitForIdle()
        assertEquals(1, prompts)
        // Recomposition of the lock screen (state read again) asks nothing more.
        rule.onNodeWithTag("lock_screen").assertExists()
        rule.waitForIdle()
        assertEquals(1, prompts)
    }
}
