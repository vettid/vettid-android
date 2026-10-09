// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.ItemFilter
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.testing.FakeItems
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Owner requests 2026-10-09: one "✕ Clear" in the filter row; "Save item" in the editor's top bar, the keyboard's
 * Next and Done never saving, and a question before unsaved changes are dropped; tags in their colours; phone numbers
 * formatted as typed and shown for the viewer's region.
 */
@RunWith(RobolectricTestRunner::class)
class ItemsUiRequestsTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val passport = FakeItems.item("01P", "Passport", tags = listOf("travel", "@profile"), category = "identity_document")

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun oneClearChipOnlyWhileAFilterIsOn() {
        var filter by mutableFilter(ItemFilter())
        var cleared = false
        rule.setContent {
            ItemsScreen(ItemsUiState(listOf(passport.summary), filter, ListLoad.LOADED), chrome, ItemsActions(onClearFilters = { cleared = true }))
        }
        assertEquals(0, count("clear_filters"))
        assertEquals(0, rule.onAllNodesWithContentDescription("Clear filters").fetchSemanticsNodes().size)

        filter = ItemFilter(sensitivity = Sensitivity.CRITICAL)
        rule.waitForIdle()
        rule.onNodeWithText("No items match these filters").assertIsDisplayed()
        // Exactly one clear action, the chip in the filter row: none under the empty state.
        assertEquals(1, count("clear_filters"))
        assertEquals(1, rule.onAllNodesWithContentDescription("Clear filters").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Clear filters").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Clear filters").assertTextContains("Clear").assertIsDisplayed().performClick()
        assertTrue(cleared)

        // A filter that still matches shows the same single chip.
        filter = ItemFilter(tag = "travel")
        rule.waitForIdle()
        rule.onNodeWithText("Passport").assertIsDisplayed()
        assertEquals(1, count("clear_filters"))
    }

    @Test
    fun rowsShowTheirTagsAsColouredChips() {
        rule.setContent { ItemsScreen(ItemsUiState(listOf(passport.summary), load = ListLoad.LOADED), chrome, ItemsActions()) }
        rule.onNode(hasText("travel") and hasAnyAncestor(hasTestTag("tag_chip_travel")), useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasText("Shared profile") and hasAnyAncestor(hasTestTag("tag_chip_@profile")), useUnmergedTree = true).assertIsDisplayed()
    }

    private val draft = ItemDraft(
        "Card", "payment_card", "payment_card", Sensitivity.DATA, listOf("money"),
        listOf(DraftField(label = "Cardholder", kind = "text"), DraftField(label = "Number", kind = "number")),
    )

    private fun editor(state: ItemEditUiState = ItemEditUiState(draft = draft, check = ItemChecks.check(draft)), actions: ItemEditActions) =
        rule.setContent { ItemEditScreen(state, actions) }

    @Test
    fun saveItemIsInTheTopBarAndThereIsNoBottomSaveBar() {
        var saves = 0
        editor(actions = ItemEditActions(onSave = { saves++ }))
        assertEquals(0, count("primary_button"))
        assertEquals(0, rule.onAllNodesWithText("Save").fetchSemanticsNodes().size)
        rule.onNodeWithTag("item_edit_save").assertTextContains("Save item").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun saveItemShowsProgressWhileSaving() {
        var saves = 0
        editor(ItemEditUiState(draft = draft, check = ItemChecks.check(draft), busy = true), ItemEditActions(onSave = { saves++ }))
        rule.onNodeWithTag("top_bar_action_busy", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("item_edit_save").performClick()
        assertEquals(0, saves)
    }

    @Test
    fun nextMovesOnAndDoneOnTheLastFieldClosesTheKeyboardWithoutSaving() {
        var saves = 0
        editor(actions = ItemEditActions(onSave = { saves++ }))
        rule.onNodeWithTag("item_edit_name").performClick()
        rule.onNodeWithTag("item_edit_name").performImeAction()
        rule.onNodeWithTag("item_edit_field_value_0").assertIsFocused()
        rule.onNodeWithTag("item_edit_field_value_0").performImeAction()
        rule.onNodeWithTag("item_edit_field_value_1").assertIsFocused()
        // The last field: Done.
        rule.onNodeWithTag("item_edit_field_value_1").performImeAction()
        rule.onNodeWithTag("item_edit_field_value_1").assertIsNotFocused()
        assertEquals(0, saves)
    }

    @Test
    fun withoutFieldsTheNameIsTheLastInput() {
        val d = draft.copy(fields = emptyList())
        var saves = 0
        editor(ItemEditUiState(draft = d, check = ItemChecks.check(d)), ItemEditActions(onSave = { saves++ }))
        rule.onNodeWithTag("item_edit_name").performClick()
        rule.onNodeWithTag("item_edit_name").performImeAction()
        rule.onNodeWithTag("item_edit_name").assertIsNotFocused()
        assertEquals(0, saves)
    }

    @Test
    fun backWithChangesAsksAndWithoutLeaves() {
        var asked = 0
        var left = 0
        editor(ItemEditUiState(draft = draft, check = ItemChecks.check(draft), dirty = true), ItemEditActions(onAskDiscard = { asked++ }, onBack = { left++ }))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1 to 0, asked to left)
    }

    @Test
    fun backWithoutChangesLeaves() {
        var asked = 0
        var left = 0
        editor(actions = ItemEditActions(onAskDiscard = { asked++ }, onBack = { left++ }))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(0 to 1, asked to left)
        assertEquals(0, count("item_edit_discard"))
    }

    @Test
    fun theDiscardQuestion() {
        var discarded = false
        var kept = false
        editor(
            ItemEditUiState(draft = draft, check = ItemChecks.check(draft), dirty = true, confirmDiscard = true),
            ItemEditActions(onDiscard = { discarded = true }, onKeepEditing = { kept = true }),
        )
        rule.onNodeWithText("Discard changes to this item?").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").performClick()
        assertTrue(kept)
        assertFalse(discarded)
        rule.onNodeWithText("Discard").performClick()
        assertTrue(discarded)
    }

    @Test
    fun aPhoneIsFormattedAsTypedAndAPasteIsTakenIn() {
        val d = draft.copy(fields = listOf(DraftField(label = "Mobile", kind = "phone")))
        var typed = ""
        rule.setContent {
            CompositionLocalProvider(LocalPhoneRegion provides "US") {
                ItemEditScreen(
                    ItemEditUiState(draft = d.copy(fields = listOf(d.fields[0].copy(text = typed))), check = ItemChecks.check(d)),
                    ItemEditActions(onFieldText = { _, v -> typed = v }),
                )
            }
        }
        rule.onNodeWithTag("item_edit_field_value_0").performTextReplacement("+1 (650) 253-0000")
        assertEquals("+16502530000", typed)
    }

    @Test
    fun aPhoneShowsItsFormatAndAnUnknownNumberAHint() {
        val d = draft.copy(fields = listOf(DraftField(label = "Mobile", kind = "phone", text = "6502530000"), DraftField(label = "Old", kind = "phone", text = "55512")))
        rule.setContent {
            CompositionLocalProvider(LocalPhoneRegion provides "US") {
                ItemEditScreen(ItemEditUiState(draft = d, check = ItemChecks.check(d)), ItemEditActions())
            }
        }
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("(650) 253-0000")
        rule.onNodeWithTag("item_edit_field_value_1").assertTextContains("Check this number")
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Phone")
    }

    @Test
    fun aStoredPhoneIsShownForTheViewersRegion() {
        val item = passport.copy(
            fields = listOf(
                ItemFieldView("f1", "Mobile", "phone", FieldValue.Text("+1 650-253-0000")),
                ItemFieldView("f2", "Office", "phone", FieldValue.Text("+44 20 7946 0958")),
            ),
        )
        rule.setContent {
            CompositionLocalProvider(LocalPhoneRegion provides "US") {
                ItemDetailScreen(ItemDetailUiState(item.itemId, item, loading = false), ItemDetailActions())
            }
        }
        rule.onNodeWithTag("item_value_f1").assertTextContains("(650) 253-0000")
        rule.onNodeWithTag("item_value_f2").assertTextContains("+44 20 7946 0958")
    }

    private fun mutableFilter(f: ItemFilter) = androidx.compose.runtime.mutableStateOf(f)
}
