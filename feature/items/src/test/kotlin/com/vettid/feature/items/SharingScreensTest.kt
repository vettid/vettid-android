// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.GrantDirection
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sharing screens: ask by default, what a connection can see, and shared-with-you labelled as theirs. */
@RunWith(RobolectricTestRunner::class)
class SharingScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aNewRuleAsksForEachNewItem() {
        rule.setContent { RuleEditScreen(RuleEditUiState(RuleDraft("c1"), "Dana Lee", tags = listOf("medical")), RuleEditActions()) }
        rule.onNodeWithTag("rule_mode_ask").assertIsSelected()
        rule.onNodeWithText("Ask me for each new item").assertExists()
    }

    @Test
    fun aConnectionsSharingShowsItsRules() {
        rule.setContent {
            ConnectionSharingScreen(
                ConnectionSharingUiState("c1", "Dana Lee", rules = listOf(ShareRule("r1", 1, "c1", tags = listOf("medical"), included = listOf("i1"))), loading = false),
                ConnectionSharingActions(),
            )
        }
        rule.onNodeWithText("can see", substring = true).assertExists()
        rule.onNodeWithTag("rule_r1").assertIsDisplayed()
    }

    @Test
    fun sharedItemsAreLabelledAsTheirs() {
        val g = GrantView("g5", "c1", GrantDirection.RECEIVED, "x1", "Insurance card", "insurance")
        rule.setContent {
            SharedWithYouScreen(
                SharedWithYouUiState(
                    "c1", "Dana Lee", received = listOf(g), loading = false,
                    opened = mapOf("g5" to SharedContent("x1", "Insurance card", "insurance", listOf(ItemFieldView("f1", "Policy", "text", FieldValue.Text("P-42"))), null, null)),
                ),
                SharedWithYouActions(),
            )
        }
        rule.onNodeWithTag("received_label_g5").assertIsDisplayed()
        rule.onNodeWithTag("received_label_g5").assertTextContains("shared by", substring = true)
        rule.onNodeWithText("P-42").assertIsDisplayed()
    }

    @Test
    fun theProfileTagIsNotEditable() {
        rule.setContent {
            TagsScreen(TagsUiState(TagRegistry(1, listOf(TagView("@profile", 2), TagView("medical", 3, listOf("r1")))), loading = false), TagsActions())
        }
        rule.onNodeWithText("Shared profile").assertIsDisplayed()
        rule.onNodeWithText("3 items · used by 1 sharing rule", substring = true).assertIsDisplayed()
    }
}
