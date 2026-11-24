package com.vettid.core.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The account button names the member once the account is read; before that (just after an unlock) it has no name, only
 * "Account" for screen readers (owner decision a, GC #17652), never a placeholder name.
 */
@RunWith(RobolectricTestRunner::class)
class AccountButtonLabelTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theButtonNamesTheMember() {
        rule.setContent { TopLevelScaffold("Vault", ShellChrome("Sam Rivera", {}, {})) { Text("list") } }
        rule.onNodeWithContentDescription("Account: Sam Rivera").assertExists()
    }

    @Test
    fun noNameYetIsJustAccount() {
        rule.setContent { TopLevelScaffold("Vault", ShellChrome("", {}, {})) { Text("list") } }
        rule.onNodeWithContentDescription("Account").assertExists()
        rule.onNodeWithContentDescription("Account: ", substring = true).assertDoesNotExist()
    }
}
