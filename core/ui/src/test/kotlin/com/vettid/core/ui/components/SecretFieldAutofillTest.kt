package com.vettid.core.ui.components

import android.view.View
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Secret inputs are excluded from autofill (owner decision 2026-10-06): password managers neither fill nor offer
 * to save them. The sign-in email address still autofills.
 */
@RunWith(RobolectricTestRunner::class)
class SecretFieldAutofillTest {
    @get:Rule
    val rule = createComposeRule()

    private fun dataType(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDataType)

    @Test
    fun secretFieldsAreNotAutofillableAndTheirWindowIsExcludedWhileShown() {
        var showPin by mutableStateOf(true)
        var showPassword by mutableStateOf(true)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            if (showPin) SecretField("1234", {}, "Vault PIN", Modifier.testTag("pin"), isPin = true)
            if (showPassword) SecretField("secret", {}, "Credential password", Modifier.testTag("password"))
        }
        rule.waitForIdle()
        assertEquals(ContentDataType.None, dataType("pin"))
        assertEquals(ContentDataType.None, dataType("password"))
        // The platform's own check (it reads the parents), as the autofill structure uses it.
        assertFalse(view.isImportantForAutofill)

        showPin = false
        rule.waitForIdle()
        assertFalse("one secret field is still shown", view.isImportantForAutofill)

        showPassword = false
        rule.waitForIdle()
        assertTrue("the last secret field left", view.isImportantForAutofill)
    }

    @Test
    fun aPlainSecretInputCanBeExcludedToo() {
        rule.setContent {
            OutlinedTextField("ABCD-EFGH", {}, label = { Text("Recovery code") }, modifier = Modifier.excludeFromAutofill().testTag("code"))
        }
        assertEquals(ContentDataType.None, dataType("code"))
    }

    @Test
    fun theEmailFieldStillAutofills() {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            OutlinedTextField(
                "member@example.org",
                {},
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.testTag("email"),
            )
        }
        assertEquals(ContentDataType.Text, dataType("email"))
        assertTrue(view.isImportantForAutofill)
    }
}
