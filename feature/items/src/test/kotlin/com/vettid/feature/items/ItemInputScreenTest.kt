// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ItemFilter
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.testing.FakeItems
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Owner requests 2026-10-08 on screen: the template's name as a placeholder, formats enforced while typing (a date's
 * mask, a number's characters, paste normalised), the month picker for a card's expiry, the protection choice for an
 * existing item, and the Vault's search behind the top bar's icon.
 */
@RunWith(RobolectricTestRunner::class)
class ItemInputScreenTest {
    @get:Rule
    val rule = createComposeRule()

    /** An editor whose draft follows what is typed, as the ViewModel's does. */
    private fun editor(start: ItemDraft, hint: String? = null, isNew: Boolean = true): () -> ItemDraft {
        var draft by mutableStateOf(start)
        rule.setContent {
            ItemEditScreen(
                ItemEditUiState(itemId = if (isNew) null else "01J", draft = draft, check = ItemChecks.check(draft), nameHint = hint),
                ItemEditActions(
                    onName = { draft = draft.copy(name = it) },
                    onFieldText = { i, v -> draft = draft.copy(fields = draft.fields.mapIndexed { j, f -> if (j == i) f.copy(text = v) else f }) },
                ),
            )
        }
        return { draft }
    }

    private fun field(kind: String, label: String = "Value", monthYear: Boolean = false) =
        ItemDraft(name = "x", fields = listOf(DraftField(label = label, kind = kind, monthYear = monthYear)))

    private fun shown(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun theTemplatesNameIsAPlaceholderNotAValue() {
        val draft = editor(ItemDraft(category = "payment_card", template = "payment_card"), hint = "Payment card")
        assertEquals("", shown("item_edit_name"))
        rule.onNodeWithText("Left empty, it is saved as “Payment card”").assertExists()
        // Focused, the template's name is the placeholder (as the field inputs' hints).
        rule.onNodeWithTag("item_edit_name").performClick()
        rule.onNodeWithTag("item_edit_name").assertTextContains("Payment card")
        assertEquals("", shown("item_edit_name"))
        rule.onNodeWithTag("item_edit_name").performTextInput("Visa")
        assertEquals("Visa", draft().name)
    }

    @Test
    fun aDateIsMaskedAsItIsTyped() {
        val draft = editor(field(FieldKinds.DATE))
        rule.onNodeWithTag("item_edit_field_value_0").performTextInput("20310430")
        assertEquals("2031-04-30", draft().fields[0].text)
    }

    @Test
    fun anImpossibleDateDigitIsRefused() {
        val draft = editor(field(FieldKinds.DATE).let { it.copy(fields = listOf(it.fields[0].copy(text = "2031"))) })
        rule.onNodeWithTag("item_edit_field_value_0").performTextReplacement("20311")
        assertEquals("2031-1", draft().fields[0].text)
        rule.onNodeWithTag("item_edit_field_value_0").performTextReplacement("203113")
        assertEquals("2031-1", draft().fields[0].text)
    }

    @Test
    fun aPastedDateIsNormalised() {
        val draft = editor(field(FieldKinds.DATE))
        rule.onNodeWithTag("item_edit_field_value_0").performTextReplacement("2031/04/30")
        assertEquals("2031-04-30", draft().fields[0].text)
    }

    @Test
    fun aNumberTakesOnlyItsCharacters() {
        val draft = editor(field(FieldKinds.NUMBER))
        rule.onNodeWithTag("item_edit_field_value_0").performTextInput("12a,5")
        assertEquals("12.5", draft().fields[0].text)
    }

    @Test
    fun aPastedSetupKeyIsNormalised() {
        val draft = editor(field(FieldKinds.OTP))
        rule.onNodeWithTag("item_edit_field_value_0").performTextReplacement("jbsw y3dp ehpk 3pxp")
        assertEquals("JBSWY3DPEHPK3PXP", draft().fields[0].text)
    }

    @Test
    fun anIncompleteEmailSaysItsRuleWhileTyping() {
        editor(field(FieldKinds.EMAIL))
        rule.onNodeWithTag("item_edit_field_value_0").performTextInput("sam@")
        rule.onNodeWithText("Enter an email address, such as name@example.com").assertExists()
    }

    @Test
    fun theCalendarButtonOpensTheDatePicker() {
        editor(field(FieldKinds.DATE, "Issued"))
        rule.onNodeWithContentDescription("Choose a date for Issued").performScrollTo().performClick()
        rule.onNodeWithTag("item_date_picker").assertExists()
    }

    @Test
    fun aCardsExpiryPicksAMonthAndYear() {
        val draft = editor(field(FieldKinds.DATE, "Expires", monthYear = true).let { it.copy(fields = listOf(it.fields[0].copy(text = "2031-04"))) })
        rule.onNodeWithContentDescription("Choose the month and year for Expires").performScrollTo().performClick()
        rule.onNodeWithTag("item_month_year").assertTextContains("2031")
        rule.onNodeWithTag("item_month_next_year").performClick()
        rule.onNodeWithTag("item_month_9").performClick()
        rule.onNodeWithTag("item_month_ok").performClick()
        assertEquals("2032-09", draft().fields[0].text)
        // Typed, it stops at the month.
        rule.onNodeWithTag("item_edit_field_value_0").performTextReplacement("20330515")
        assertEquals("2033-05", draft().fields[0].text)
    }

    @Test
    fun anExistingItemsProtectionCanBeChosenHere() {
        var picked: Sensitivity? = null
        val d = ItemDraft(name = "Login", sensitivity = Sensitivity.SECRET)
        rule.setContent {
            ItemEditScreen(
                ItemEditUiState(itemId = "01J", draft = d, check = ItemChecks.check(d), protectionTo = Sensitivity.CRITICAL),
                ItemEditActions(onSensitivity = { picked = it }),
            )
        }
        rule.onNodeWithTag("item_edit_protection_note").performScrollTo().assertTextContains("when you save", substring = true)
        rule.onNodeWithTag("sensitivity_data").performScrollTo().performClick()
        assertEquals(Sensitivity.DATA, picked)
    }

    @Test
    fun aSharedProfileItemCannotLeaveStandard() {
        val d = ItemDraft(name = "Email", tags = listOf("@profile"))
        rule.setContent { ItemEditScreen(ItemEditUiState(itemId = "01J", draft = d, check = ItemChecks.check(d)), ItemEditActions()) }
        rule.onNodeWithTag("sensitivity_secret").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("item_edit_protection_note").assertTextContains("shared profile", substring = true)
    }

    @Test
    fun theVaultsSearchIsBehindTheTopBarsIcon() {
        val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
        val list = listOf(FakeItems.item("01P", "Passport"), FakeItems.item("01L", "Bank login")).map { it.summary }
        var query by mutableStateOf("")
        rule.setContent {
            ItemsScreen(ItemsUiState(list, ItemFilter(query = query), ListLoad.LOADED), chrome, ItemsActions(onQuery = { query = it }))
        }
        // Hidden by default; the filter chips stay.
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithTag("items_filter_data").assertExists()
        rule.onNodeWithContentDescription("Search your vault").performClick()
        rule.onNodeWithTag("top_bar_search_field").assertIsFocused()
        rule.onNodeWithTag("top_bar_search_field").performTextInput("pass")
        assertEquals("pass", query)
        rule.onNodeWithTag("items_row_01P").assertExists()
        rule.onNodeWithTag("items_row_01L").assertDoesNotExist()
        rule.onNodeWithTag("top_bar_search_close").performClick()
        assertEquals("", query)
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search your vault").assertExists()
    }

    @Test
    fun aQueryKeepsTheSearchShown() {
        val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
        val list = listOf(FakeItems.item("01P", "Passport")).map { it.summary }
        rule.setContent { ItemsScreen(ItemsUiState(list, ItemFilter(query = "zzz"), ListLoad.LOADED), chrome, ItemsActions()) }
        rule.onNodeWithTag("top_bar_search_field").assertExists()
        rule.onNodeWithTag("items_no_match").assertExists()
    }
}
