package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A6 close-out: pane title (A3, A30), Next moves focus (A5), dialog bodies scroll (A14, A41). */
@RunWith(RobolectricTestRunner::class)
class A6CloseoutSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun formScaffoldAnnouncesItsTitleAsAPaneTitle() {
        rule.setContent {
            FormScaffold(
                title = "Compare the code",
                primaryLabel = "Continue",
                onPrimary = {},
                modifier = Modifier.testTag("scaffold"),
            ) {}
        }
        val node = rule.onNodeWithTag("scaffold").fetchSemanticsNode()
        assertEquals("Compare the code", node.config.getOrNull(SemanticsProperties.PaneTitle))
    }

    @Test
    fun nextOnASecretFieldMovesFocusToTheNextField() {
        rule.setContent {
            Column {
                SecretField("", {}, "Password", imeAction = ImeAction.Next, modifier = Modifier.testTag("first"))
                SecretField("", {}, "Confirm", modifier = Modifier.testTag("second"))
            }
        }
        rule.onNodeWithTag("first").performImeAction()
        rule.onNodeWithTag("second").assertIsFocused()
    }

    @Test
    fun nextOnAFieldWhoseCallerHandlesItRunsThatAndMovesFocusNoFurther() {
        var handled = 0
        rule.setContent {
            Column {
                // A password that is not the last field: its caller moves focus itself (the item editor), once.
                SecretField(
                    "", {}, "Password",
                    imeAction = ImeAction.Next, onImeAction = { handled++ }, modifier = Modifier.testTag("first"),
                )
                SecretField("", {}, "Confirm", modifier = Modifier.testTag("second"))
                SecretField("", {}, "Third", modifier = Modifier.testTag("third"))
            }
        }
        rule.onNodeWithTag("first").performImeAction()
        assertEquals(1, handled)
        rule.onNodeWithTag("second").assertIsNotFocused()
    }

    @Test
    fun theConfirmDialogBodyScrolls() {
        rule.setContent { ConfirmDialog("Title", "A long body", "OK", onConfirm = {}, onDismiss = {}) }
        rule.onNode(hasScrollAction()).assertExists()
    }

    @Test
    fun theAlertDialogWrapperBodyScrolls() {
        rule.setContent {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { androidx.compose.material3.Text("A long body") })
        }
        rule.onNode(hasScrollAction()).assertExists()
    }
}
