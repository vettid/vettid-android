package com.vettid.feature.connections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ANDROID-PLAN 0.1.57 (2): an unmapped vault code under a connect form is named by `error_vault_code`. */
@RunWith(RobolectricTestRunner::class)
class ConnectionsVaultCodeTextTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anUnmappedCodeIsNamed() {
        rule.setContent { FailureText(FailureKind.OTHER, shownCode = "some_new_code") }
        rule.onNodeWithText(
            "Something went wrong (code: some_new_code). Try again. If it keeps happening, contact VettID and give this code.",
        ).assertExists()
    }

    @Test
    fun aMappedKindKeepsItsText() {
        rule.setContent { FailureText(FailureKind.OTHER, shownCode = null) }
        rule.onNodeWithText("Something went wrong. Try again.").assertExists()
    }
}
