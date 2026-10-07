// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "DestructuringDeclarationWithTooManyEntries")

package com.vettid.feature.items

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.items.DraftProblem
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeItems
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Vault screens (ANDROID-PLAN 0.1.11): "Vault" wording, hidden values, the critical password step and the checks. */
@RunWith(RobolectricTestRunner::class)
class ItemsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val passport = FakeItems.item("01P", "Passport", tags = listOf("travel"), category = "identity_document")
    private val login = FakeItems.item("01L", "Bank login", Sensitivity.SECRET, category = "login", fields = listOf("Password" to "hunter2"))
        .let { it.copy(fields = it.fields.map { f -> f.copy(kind = "password") }) }

    private fun noValue(text: String) =
        assertEquals(0, rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size)

    @Test
    fun anEmptyVaultSaysSoAndOffersAddToVault() {
        var added = false
        rule.setContent {
            ItemsScreen(ItemsUiState(load = ListLoad.LOADED), chrome, ItemsActions(onAdd = { added = true }))
        }
        rule.onNodeWithText("Your vault is empty").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add to vault").performClick()
        assertTrue(added)
    }

    @Test
    fun theListShowsNamesCategoriesAndProtection() {
        rule.setContent {
            ItemsScreen(ItemsUiState(listOf(passport.summary, login.summary), load = ListLoad.LOADED), chrome, ItemsActions())
        }
        rule.onNodeWithText("Vault").assertIsDisplayed()
        rule.onNodeWithText("Passport").assertIsDisplayed()
        rule.onNodeWithText("Identity document").assertIsDisplayed()
        rule.onNodeWithText("Login · Secret").assertIsDisplayed()
        noValue("hunter2")
    }

    @Test
    fun aSecretItemsValuesStayHiddenUntilRevealed() {
        var revealed = false
        rule.setContent {
            ItemDetailScreen(ItemDetailUiState("01L", login.hidden(), loading = false), ItemDetailActions(onReveal = { revealed = true }))
        }
        rule.onNodeWithTag("item_gate").assertIsDisplayed()
        rule.onNodeWithTag("item_value_f1").assertTextContains("Hidden")
        noValue("hunter2")
        rule.onNodeWithTag("item_reveal").performClick()
        assertTrue(revealed)
    }

    @Test
    fun aRevealedPasswordIsStillMaskedUntilShown() {
        rule.setContent {
            ItemDetailScreen(ItemDetailUiState("01L", login, loading = false), ItemDetailActions())
        }
        rule.onNodeWithTag("item_value_f1").assertTextContains("••••••••")
        noValue("hunter2")
        rule.onNodeWithContentDescription("Show Password").assertIsDisplayed()
    }

    @Test
    fun aShownPasswordIsVisible() {
        rule.setContent {
            ItemDetailScreen(ItemDetailUiState("01L", login, loading = false, shown = setOf("f1")), ItemDetailActions())
        }
        rule.onNodeWithTag("item_value_f1").assertTextContains("hunter2")
    }

    @Test
    fun aCriticalItemAsksForTheCredentialPassword() {
        val phrase: ItemDetail = FakeItems.item("01C", "Recovery phrase", Sensitivity.CRITICAL).hidden()
        rule.setContent {
            ItemDetailScreen(
                ItemDetailUiState("01C", phrase, loading = false, prompt = PasswordPrompt(PasswordPurpose.OPEN, error = FailureKind.BAD_PASSWORD)),
                ItemDetailActions(),
            )
        }
        rule.onNodeWithText("Open a critical item").assertIsDisplayed()
        rule.onNodeWithText("Recovery phrase", substring = true).assertIsDisplayed()
        rule.onNodeWithText("That password is not right").assertIsDisplayed()
    }

    @Test
    fun theEditScreenShowsProblemsAfterSaving() {
        val d = ItemDraft(name = "", fields = listOf(com.vettid.core.data.items.DraftField(label = "Expires", kind = "date", text = "soon")))
        rule.setContent {
            ItemEditScreen(ItemEditUiState(draft = d, check = ItemChecks.check(d), showErrors = true), ItemEditActions())
        }
        rule.onNodeWithText("Give the item a name").assertIsDisplayed()
        rule.onNodeWithText("Enter a date as YYYY-MM-DD or YYYY-MM").assertExists()
        rule.onNodeWithText("Add to vault").assertIsDisplayed()
        assertTrue(DraftProblem.NAME_EMPTY in ItemChecks.check(d).problems)
    }

    @Test
    fun theProfileTagIsExplained() {
        val d = ItemDraft(name = "Contact details", tags = listOf("@profile"))
        rule.setContent {
            ItemEditScreen(ItemEditUiState(draft = d, check = ItemChecks.check(d)), ItemEditActions())
        }
        rule.onNodeWithTag("item_edit_profile_note").assertExists()
        rule.onNodeWithText("Shared profile").assertExists()
    }

    @Test
    fun theTemplatesStartWithABlankItem() {
        rule.setContent { TemplatePickerScreen(onBack = {}, onPick = {}) }
        rule.onNodeWithText("Blank item").assertIsDisplayed()
        rule.onNodeWithText("Passport").assertIsDisplayed()
    }
}
