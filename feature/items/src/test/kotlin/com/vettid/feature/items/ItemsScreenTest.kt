// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "DestructuringDeclarationWithTooManyEntries")

package com.vettid.feature.items

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
        rule.onNodeWithTag("item_edit_profile_choice").assertIsOn()
        rule.onNodeWithText("Shared profile").assertExists()
        rule.onNodeWithText("Everyone you're connected with sees items with this tag.").assertExists()
        // The reserved tag is the built-in choice, not one of the member's tag chips.
        rule.onNodeWithTag("item_edit_tags").assertDoesNotExist()
    }

    @Test
    fun theSharedProfileChoiceIsForStandardItems() {
        var chosen: Boolean? = null
        editor(ItemDraft(name = "Email"), actions = ItemEditActions(onInProfile = { chosen = it }))
        rule.onNodeWithTag("item_edit_profile_choice").performScrollTo().assertIsEnabled().assertIsOff().performClick()
        assertEquals(true, chosen)
    }

    @Test
    fun aSecretItemCannotJoinTheSharedProfileAndSaysWhy() {
        editor(ItemDraft(name = "Bank login", sensitivity = Sensitivity.SECRET))
        rule.onNodeWithTag("item_edit_profile_choice").assertIsNotEnabled()
        rule.onNodeWithText("Only standard items can be in your shared profile").assertExists()
        rule.onNodeWithText("Everyone you're connected with sees items with this tag.").assertDoesNotExist()
    }

    @Test
    fun aBlankItemInvitesTheFirstField() {
        editor(ItemDraft())
        rule.onNodeWithTag("item_edit_no_fields").assertExists()
        rule.onNodeWithTag("item_edit_field_value_0").assertDoesNotExist()
        rule.onNodeWithTag("item_edit_add_field").assertExists()
    }

    @Test
    fun theTemplatesOfferOneItemPerContactPoint() {
        rule.setContent { TemplatePickerScreen(onBack = {}, onPick = {}) }
        listOf("Email address", "Phone number", "Postal address", "Website").forEach {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("Contact details").assertDoesNotExist()
    }

    @Test
    fun theTemplatesStartWithABlankItem() {
        rule.setContent { TemplatePickerScreen(onBack = {}, onPick = {}) }
        rule.onNodeWithText("Blank item").assertIsDisplayed()
        rule.onNodeWithText("Passport").assertIsDisplayed()
    }

    @Test
    fun anEditKeepsHiddenValuesHiddenAndSaysSo() {
        // VAULT-MESSAGING 0.21.0 §10.7 Kept values: the edit form never holds the stored values.
        val hidden = login.copy(notes = "Branch: Kreuzberg").hidden().copy(size = 60_000)
        val d = ItemDraft.of(hidden)
        rule.setContent { ItemEditScreen(ItemEditUiState(itemId = "01L", draft = d, check = ItemChecks.check(d)), ItemEditActions()) }
        noValue("hunter2")
        noValue("Kreuzberg")
        rule.onNodeWithTag("item_edit_notes_remove").assertExists()
        rule.onAllNodesWithText("Kept as it is", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // 60,000 of 65,536 bytes: the room left, from the vault's size.
        rule.onNodeWithTag("item_edit_size").assertTextContains("Room left: 5 of 64 KB")
    }

    @Test
    fun aNamedLimitIsSaidInTheMembersWords() {
        val d = ItemDraft.of(passport)
        rule.setContent {
            ItemEditScreen(
                ItemEditUiState(
                    itemId = "01P", draft = d, check = ItemChecks.check(d), error = FailureKind.LIMIT,
                    limit = com.vettid.core.data.vault.VaultLimit("share_pending", 4_096),
                ),
                ItemEditActions(),
            )
        }
        rule.onNodeWithText("Too many items are waiting for your sharing decision (at most 4,096). Decide some first.").assertExists()
    }

    @Test
    fun theSharingNoticeSaysWhatIsGainedAndWithdrawn() {
        val d = ItemDraft.of(passport)
        rule.setContent {
            ItemEditScreen(
                ItemEditUiState(
                    itemId = "01P", draft = d, check = ItemChecks.check(d),
                    shareImpact = listOf(ShareImpact("Dana Lee", com.vettid.core.data.items.ShareMode.AUTO), ShareImpact("Jo Park", com.vettid.core.data.items.ShareMode.ASK, withdrawn = true)),
                ),
                ItemEditActions(),
            )
        }
        rule.onNodeWithTag("item_edit_share_impact").assertTextContains("automatically", substring = true)
        rule.onNodeWithTag("item_edit_share_impact").assertTextContains("stops sharing", substring = true)
    }

    private val card = ItemDraft(
        name = "Visa",
        category = "payment_card",
        fields = listOf(
            com.vettid.core.data.items.DraftField(label = "Cardholder", kind = "text"),
            com.vettid.core.data.items.DraftField(label = "Number", kind = "number"),
            com.vettid.core.data.items.DraftField(label = "Expires", kind = "date"),
        ),
    )

    private fun editor(d: ItemDraft = card, state: (ItemEditUiState) -> ItemEditUiState = { it }, actions: ItemEditActions = ItemEditActions()) =
        rule.setContent { ItemEditScreen(state(ItemEditUiState(draft = d, check = ItemChecks.check(d))), actions) }

    @Test
    fun eachFieldIsOneValueInputCaptionedByItsLabel() {
        // Owner feedback 2026-10-08: no Label text box and no kind row; the kind is a hint under the value.
        editor()
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Cardholder")
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Text")
        rule.onNodeWithTag("item_edit_field_value_1").assertTextContains("Number")
        rule.onNodeWithTag("item_edit_field_value_2").assertTextContains("Date")
        rule.onNodeWithTag("item_edit_field_label_0").assertDoesNotExist()
        assertEquals(0, rule.onAllNodesWithText("Label").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Value").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Options for Cardholder").assertExists()
    }

    @Test
    fun aProblemReplacesTheKindHint() {
        val d = card.copy(fields = listOf(com.vettid.core.data.items.DraftField(label = "Expires", kind = "date", text = "soon")))
        editor(d, state = { it.copy(showErrors = true) })
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Enter a date as YYYY-MM-DD or YYYY-MM")
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Expires")
    }

    @Test
    fun theFieldMenuRenamesRetypesMovesAndRemoves() {
        val calls = mutableListOf<String>()
        editor(
            actions = ItemEditActions(
                onAskRenameField = { calls += "rename $it" },
                onAskFieldKind = { calls += "kind $it" },
                onMoveField = { i, d -> calls += "move $i $d" },
                onRemoveField = { calls += "remove $it" },
            ),
        )
        fun pick(item: String) {
            rule.onNodeWithTag("item_edit_field_menu_1").performScrollTo().performClick()
            rule.onNodeWithTag(item).performClick()
            rule.waitForIdle()
        }
        pick("item_edit_field_rename")
        pick("item_edit_field_kind")
        pick("item_edit_field_up")
        pick("item_edit_field_down")
        pick("item_edit_field_remove")
        assertEquals(listOf("rename 1", "kind 1", "move 1 -1", "move 1 1", "remove 1"), calls)
    }

    @Test
    fun theFirstFieldCannotMoveUp() {
        editor()
        rule.onNodeWithTag("item_edit_field_menu_0").performScrollTo().performClick()
        rule.onNodeWithTag("item_edit_field_up").assertIsNotEnabled()
        rule.onNodeWithTag("item_edit_field_down").assertIsEnabled()
    }

    @Test
    fun aSavedFieldKeepsItsTypeAndSaysWhy() {
        val d = ItemDraft.of(passport)
        editor(d, state = { it.copy(itemId = "01P") })
        rule.onNodeWithTag("item_edit_field_menu_0").performScrollTo().performClick()
        rule.onNodeWithTag("item_edit_field_kind").assertIsNotEnabled()
        rule.onNodeWithText("A saved field keeps its type").assertExists()
        rule.onNodeWithTag("item_edit_field_rename").assertIsEnabled()
    }

    @Test
    fun addAFieldOpensItsDialog() {
        var asked = false
        editor(actions = ItemEditActions(onAskAddField = { asked = true }))
        rule.onNodeWithTag("item_edit_add_field").performScrollTo().performClick()
        assertTrue(asked)
    }

    @Test
    fun theAddFieldDialogAsksWhatTheFieldIsCalled() {
        var confirmed = 0
        var kind: String? = null
        editor(
            state = { it.copy(dialog = EditDialog.AddField("Security code", "number")) },
            actions = ItemEditActions(onConfirmDialog = { confirmed++ }, onDialogKind = { kind = it }),
        )
        rule.onNodeWithText("What is this field called?").assertExists()
        rule.onNodeWithTag("item_field_dialog_label").assertTextContains("Security code")
        rule.onNodeWithTag("item_field_dialog_kind").performClick()
        rule.onNodeWithTag("item_field_kind_password").performClick()
        assertEquals("password", kind)
        rule.onNodeWithTag("item_field_dialog_confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun theAddFieldDialogChecksTheLabel() {
        editor(state = { it.copy(dialog = EditDialog.AddField("x".repeat(65))) })
        rule.onNodeWithText("The label is too long (at most 64 bytes)").assertExists()
        rule.onNodeWithTag("item_field_dialog_confirm").assertIsNotEnabled()
    }

    @Test
    fun anEmptyLabelCannotBeAdded() {
        editor(state = { it.copy(dialog = EditDialog.AddField()) })
        rule.onNodeWithTag("item_field_dialog_confirm").assertIsNotEnabled()
        rule.onNodeWithText("Give the field a label").assertDoesNotExist()
    }

    @Test
    fun theRenameDialogHasNoTypePicker() {
        editor(state = { it.copy(dialog = EditDialog.RenameField(0, "Cardholder")) })
        rule.onNodeWithText("Rename the field").assertExists()
        rule.onNodeWithTag("item_field_dialog_label").assertTextContains("Cardholder")
        rule.onNodeWithTag("item_field_dialog_kind").assertDoesNotExist()
    }

    @Test
    fun keptValuesStayKeptInTheValueFirstLayout() {
        val d = ItemDraft.of(login.hidden())
        editor(d, state = { it.copy(itemId = "01L") })
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Password")
        rule.onNodeWithTag("item_edit_field_value_0").assertTextContains("Password · Kept as it is. Type to replace it.")
        noValue("hunter2")
    }

    @Test
    fun theTagsAreExplained() {
        editor()
        rule.onNodeWithTag("item_edit_tags_note").assertTextContains("decide what you share", substring = true)
        rule.onNodeWithText("Lowercase letters, digits, spaces, hyphens (-) and underscores (_), up to 32 characters.").assertExists()
    }

    @Test
    fun theCategoryPickerOffersTheMembersOwnAndANewOne() {
        var asked = false
        var picked: String? = null
        editor(
            state = { it.copy(customCategories = listOf("gym", "loyalty_cards")) },
            actions = ItemEditActions(onAskNewCategory = { asked = true }, onCategory = { picked = it }),
        )
        rule.onNodeWithTag("item_edit_category").performClick()
        rule.onNodeWithText("Loyalty cards").assertExists()
        rule.onNodeWithTag("item_edit_category_gym").performScrollTo().performClick()
        assertEquals("gym", picked)
        rule.onNodeWithTag("item_edit_category").performClick()
        rule.onNodeWithTag("item_edit_category_new").performScrollTo().performClick()
        assertTrue(asked)
    }

    @Test
    fun aNewCategoryShowsItsIdentifierOrWhyNot() {
        editor(state = { it.copy(dialog = EditDialog.NewCategory("Loyalty cards")) })
        rule.onNodeWithText("Saved as loyalty_cards").assertExists()
        rule.onNodeWithTag("item_category_dialog_confirm").assertIsEnabled()
    }

    @Test
    fun aNewCategoryMustStartWithALetter() {
        editor(state = { it.copy(dialog = EditDialog.NewCategory("2fa codes")) })
        rule.onNodeWithText("Start with a letter").assertExists()
        rule.onNodeWithTag("item_category_dialog_confirm").assertIsNotEnabled()
    }

    @Test
    fun aCustomCategoryIsShownHumanized() {
        editor(card.copy(category = "loyalty_cards"))
        rule.onNodeWithText("Loyalty cards").assertExists()
    }
}
