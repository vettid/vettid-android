package com.vettid.feature.items

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ANDROID-PLAN 0.1.57 (2) wherever a vault error response is the failure: an unmapped code on an item is named by
 * `error_vault_code`; an item's `bad_request` keeps its own text, and a failure without a vault code its usual one.
 */
@RunWith(RobolectricTestRunner::class)
class ItemsVaultCodeTextTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anUnmappedCodeIsNamed() {
        rule.setContent { Text(ItemsText.failure(FailureKind.OTHER, shownCode = "internal")) }
        rule.onNodeWithText(
            "Something went wrong (code: internal). Try again. If it keeps happening, contact VettID and give this code.",
        ).assertExists()
    }

    @Test
    fun anItemsBadRequestKeepsItsText() {
        var expected = ""
        rule.setContent {
            expected = androidx.compose.ui.res.stringResource(R.string.items_error_refused)
            Text(ItemsText.failure(FailureKind.OTHER, shownCode = "bad_request"))
        }
        rule.onNodeWithText(expected).assertExists()
    }
}
