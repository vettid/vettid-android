package com.vettid.feature.approvals

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.GrantEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * A grant entry is named only by the member's vault (VAULT-MESSAGING 0.21.0 §10.12 "What an entry would grant"):
 * never from the item list on this phone.
 */
@RunWith(RobolectricTestRunner::class)
class GrantEntryNameTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val local = listOf(ItemSummary("i1", 1, "Passport", "identity_document", Sensitivity.DATA))

    private fun show(entry: GrantEntry) = rule.setContent {
        val a = Approval.GrantRequest("g1", "c1", listOf(entry), 1, null, null, now, null, "Sam Lee")
        ApprovalDetailScreen(ApprovalDetailUiState(key = a.key, approval = a, items = local), DecisionActions())
    }

    @Test
    fun anEntryWithoutItsNameIsNotNamedFromThePhonesItems() {
        // As the parser leaves an entry the vault sent without being available.
        show(GrantEntry("item", "i1", "Your passport", available = false))
        rule.onNodeWithText("Passport").assertDoesNotExist()
        rule.onNodeWithText("(not in your vault)", substring = true).assertExists()
        rule.onNodeWithTag("grant_entry_0").assertIsNotEnabled()
    }

    @Test
    fun anUnreadableEntryIsInTheVaultAndNeverGrantable() {
        // Sent as available without a name that shows: the parser marks it unreadable and unavailable.
        show(GrantEntry("item", "i1", "Your passport", available = false, unreadable = true))
        rule.onNodeWithText("An item in your vault").assertExists()
        rule.onNodeWithText("(not in your vault)", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Passport").assertDoesNotExist()
        rule.onNodeWithTag("grant_entry_0").assertIsNotEnabled()
    }

    @Test
    fun anUnreadableEntryWithANameShowsIt() {
        // Unreadable for its labels: its name shows, and it is still not grantable.
        show(GrantEntry("item", "i1", null, available = false, name = "Travel passport", category = "other", unreadable = true))
        rule.onNodeWithText("\u2068Travel passport\u2069").assertExists()
        rule.onNodeWithTag("grant_entry_0").assertIsNotEnabled()
    }

    @Test
    fun anAvailableEntryWhoseNameShowsNothingIsNeverGrantable() {
        // A second line of defence behind the parser: the screen grants an item only by a name that shows.
        show(GrantEntry("item", "i1", "Your passport", available = true, name = "\u200b"))
        rule.onNodeWithTag("grant_entry_0").assertIsNotEnabled()
    }

    @Test
    fun anEntryIsNamedByTheVault() {
        show(GrantEntry("item", "i1", "Your passport", available = true, name = "Travel passport"))
        rule.onNodeWithText("\u2068Travel passport\u2069").assertExists()
        rule.onNodeWithTag("grant_entry_0").assertIsEnabled()
    }
}
