package com.vettid.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** (follow-up to #302) While the settlement window runs the screen says the member can leave the app. */
@RunWith(RobolectricTestRunner::class)
class TransferInSettlingNoteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var settling = true
    private var left = 0

    private val actions = object : TransferInActions {
        override fun scan() = Unit
        override fun paste() = Unit
        // The ViewModel's rule: consumed while settling, otherwise the screen is left.
        override fun back() = settling
        override fun scanned(text: String) = Unit
        override fun setInput(v: String) = Unit
        override fun submitInput() = Unit
        override fun cancel() = Unit
        override fun again() = Unit
        override fun finish() = Unit
    }

    private fun show(settling: Boolean) {
        this.settling = settling
        rule.setContent {
        TransferInContent(
            TransferInUiState(step = TransferInStep.COMPARE, sas = "042817", secondsLeft = if (settling) 0 else 300, settling = settling),
            actions,
            onLeave = { left++ },
        )
        }
    }

    @Test
    fun theNoteShowsUnderTheCheckingTextWhileSettling() {
        show(settling = true)
        // One merged live-region node holds both lines, so TalkBack reads them together, once.
        rule.onNodeWithText("Checking whether your old phone approved in time…", substring = true).assertExists()
        rule.onNodeWithText("The check finishes in the background or the next time you open VettID.", substring = true).assertExists()
        rule.onNodeWithTag("checking_old_phone").assertTextContains("You can leave the app.", substring = true)
    }

    @Test
    fun theNoteIsNotShownBeforeTheWindow() {
        show(settling = false)
        rule.onNodeWithText("You can leave the app.", substring = true).assertDoesNotExist()
    }

    /** The bug: system Back during the window left the screen, and the phase routed straight back to it. */
    @Test
    fun systemBackWhileSettlingDoesNotLeave() {
        show(settling = true)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals("the screen was not left", 0, left)
    }

    @Test
    fun systemBackBeforeTheWindowLeavesAsBefore() {
        show(settling = false)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, left)
        assertFalse(settling)
    }
}
