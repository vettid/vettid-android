package com.vettid.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** (follow-up to #302) While the settlement window runs the screen says the member can leave the app. */
@RunWith(RobolectricTestRunner::class)
class TransferInSettlingNoteTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = object : TransferInActions {
        override fun scan() = Unit
        override fun paste() = Unit
        override fun back() = true
        override fun scanned(text: String) = Unit
        override fun setInput(v: String) = Unit
        override fun submitInput() = Unit
        override fun cancel() = Unit
        override fun again() = Unit
        override fun finish() = Unit
    }

    private fun show(settling: Boolean) = rule.setContent {
        TransferInContent(
            TransferInUiState(step = TransferInStep.COMPARE, sas = "042817", secondsLeft = if (settling) 0 else 300, settling = settling),
            actions,
            onLeave = {},
        )
    }

    @Test
    fun theNoteShowsUnderTheCheckingTextWhileSettling() {
        show(settling = true)
        rule.onNodeWithText("Checking whether your old phone approved in time…").assertExists()
        rule.onNodeWithText("You can leave the app. The check goes on without it.").assertExists()
    }

    @Test
    fun theNoteIsNotShownBeforeTheWindow() {
        show(settling = false)
        rule.onNodeWithText("You can leave the app. The check goes on without it.").assertDoesNotExist()
    }
}
