// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.GrantDirection
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleMatch
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultLimit
import org.junit.Assert.assertEquals
import java.time.Instant
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.ui.theme.TagColors
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
        rule.onNodeWithText("Ask me each time").assertExists()
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

    // --- owner feedback 2026-10-09: rules per tag, each with its own settings ---

    private val address = ShareRule("r1", 1, "c1", tags = listOf("address"), mode = ShareMode.AUTO, included = listOf("i1"))
    private val license = ShareRule("r2", 1, "c1", tags = listOf("drivers-license"), uses = 5, expiresAt = Instant.parse("2026-12-31T12:00:00Z"), included = listOf("i2"), pending = listOf("i3"))
    private val travel = ShareRule("r3", 1, "c1", tags = listOf("drivers-license", "travel"), match = TagMatch.ALL, mode = ShareMode.AUTO, uses = 1, included = listOf("i2"))

    @Test
    fun theManageScreenListsEveryRuleWithItsOwnSettings() {
        rule.setContent {
            ConnectionSharingScreen(ConnectionSharingUiState("c1", "Dana Lee", rules = listOf(address, license, travel), loading = false), ConnectionSharingActions())
        }
        rule.onNodeWithTag("rule_r1").assertTextContains("Shared automatically", substring = true)
        rule.onNodeWithTag("rule_r1").assertTextContains("no fetch limit · no end date", substring = true)
        rule.onNodeWithTag("rule_r2").assertTextContains("Asks you each time", substring = true)
        rule.onNodeWithTag("rule_r2").assertTextContains("up to 5 fetches of each item · until", substring = true)
        rule.onNodeWithTag("rule_r3").assertTextContains("All of these tags", substring = true)
        // 0.23.0: rules named by their tags, and an `auto` rule asks first where an `ask` rule overlaps it.
        rule.onNodeWithTag("rule_also_r2", useUnmergedTree = true).assertTextEquals("Overlaps your “drivers-license + travel” rule")
        rule.onNodeWithTag("rule_also_r3", useUnmergedTree = true).assertTextEquals("Asks you first for the items your “drivers-license” rule also covers")
        // Tags in their own colours (#95's TagChip), not plain labels.
        rule.onNodeWithTag("tag_chip_address", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("tag_chip_travel", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rule_also_r1", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("sharing_new_rule").assertIsEnabled()
        rule.onNodeWithTag("rule_limit").assertDoesNotExist()
    }

    @Test
    fun atSixtyFourRulesTheLimitIsNamedAndNoRuleCanBeAdded() {
        rule.setContent {
            ConnectionSharingScreen(ConnectionSharingUiState("c1", "Dana Lee", rules = (1..64).map { ShareRule("f$it", 1, "c1", tags = listOf("t$it")) }, loading = false), ConnectionSharingActions())
        }
        rule.onNodeWithText("This connection has the most share rules it can have (64).").assertExists()
        rule.onNodeWithTag("sharing_new_rule").assertIsNotEnabled()
    }

    @Test
    fun theEditorOffersEveryEndAndShowsTheOneChosen() {
        val at = Instant.parse("2026-12-31T22:59:00Z")
        rule.setContent {
            RuleEditScreen(RuleEditUiState(RuleDraft("c1", listOf("address"), expiresAt = at), "Dana Lee", tags = listOf("address"), expiry = RuleExpiry.CUSTOM), RuleEditActions())
        }
        listOf("never" to "Never", "day" to "1 day", "week" to "1 week", "month" to "1 month", "three_months" to "3 months", "year" to "1 year", "custom" to "Custom…").forEach { (k, label) ->
            rule.onNodeWithTag("rule_end_$k").assertTextEquals(label)
        }
        rule.onNodeWithTag("rule_end_custom").assertIsSelected()
        rule.onNodeWithTag("rule_end_keep").assertDoesNotExist()
        rule.onNodeWithTag("rule_ends_at").assertTextContains("Ends ", substring = true)
        rule.onNodeWithTag("rule_ends_at").assertTextContains("(your time)", substring = true)
        rule.onNodeWithText("Fetches of each item (optional)").assertExists()
        // 0.23.0 §10.12: connection rules take per-hour and per-day limits too.
        rule.onNodeWithText("Fetches per hour (optional)").assertExists()
        rule.onNodeWithText("Fetches per day (optional)").assertExists()
    }

    @Test
    fun customOpensTheDatePickerThenTheTimePicker() {
        var state by mutableStateOf(RuleEditUiState(RuleDraft("c1", listOf("address")), "Dana Lee", tags = listOf("address")))
        val asked = mutableListOf<String>()
        rule.setContent {
            RuleEditScreen(
                state,
                RuleEditActions(
                    onExpiry = { asked += "expiry:$it" }, onEndDate = { asked += "date"; state = state.copy(endPicker = EndPicker.Time(it)) },
                    onEndTime = { h, m -> asked += "time:$h:$m"; state = state.copy(endPicker = null) },
                ),
            )
        }
        rule.onNodeWithTag("rule_end_custom").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("expiry:CUSTOM"), asked)
        state = state.copy(endPicker = EndPicker.Date)
        rule.onNodeWithTag("rule_end_date").assertExists()
        rule.onNodeWithTag("rule_end_date_ok").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("rule_end_time").assertExists()
        rule.onNodeWithTag("rule_end_time_ok").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("date", asked[1])
        assertEquals("time:23:59", asked[2]) // the end of the picked day by default
        rule.onNodeWithTag("rule_end_time").assertDoesNotExist()
    }

    @Test
    fun aRefusedCustomEndSaysWhy() {
        rule.setContent { RuleEditScreen(RuleEditUiState(RuleDraft("c1", listOf("address")), "Dana Lee", tags = listOf("address"), endInvalid = true), RuleEditActions()) }
        rule.onNodeWithTag("rule_end_bad").assertTextEquals("Pick a time in the future, at most 10 years ahead.")
    }

    @Test
    fun theEditorShowsTheOtherRulesCoveringTheSameTagOrItem() {
        val preview = RulePreview(listOf(RuleMatch("i2", "Driver’s license", "identity_document", Sensitivity.DATA, state = "included"), RuleMatch("i4", "Library card", "membership", Sensitivity.DATA)), 2)
        rule.setContent {
            RuleEditScreen(
                RuleEditUiState(RuleDraft.of(license), "Dana Lee", tags = listOf("drivers-license", "travel"), expiry = RuleExpiry.KEEP, usesText = "5", preview = preview, rules = listOf(address, license, travel)),
                RuleEditActions(),
            )
        }
        rule.onNodeWithTag("rule_overlaps").assertExists()
        rule.onNodeWithTag("rule_overlap_r3").assertTextContains("your “drivers-license + travel” rule", substring = true)
        rule.onNodeWithTag("rule_overlap_r3").assertTextContains("Shared automatically · also names drivers-license · 1 of these items is in it", substring = true)
        rule.onNodeWithTag("rule_overlap_r1").assertDoesNotExist()
        rule.onNodeWithTag("rule_overlap_r2").assertDoesNotExist() // not its own overlap
        // 0.23.0: the 0.22 wording ("each rule works on its own") is gone: asking wins.
        rule.onNodeWithText("Each rule works on its own", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("rule_overlap_note", useUnmergedTree = true).assertTextContains("asking wins", substring = true)
        rule.onNodeWithTag("rule_match_i2").assertTextContains("Already shared", substring = true)
        rule.onNodeWithTag("rule_match_i4").assertTextEquals("Library card")
        rule.onNodeWithTag("rule_end_keep").assertIsSelected()
    }

    @Test
    fun aNewRuleAtTheLimitCannotBeSavedAndAVaultLimitIsNamed() {
        rule.setContent {
            RuleEditScreen(
                RuleEditUiState(
                    RuleDraft("c1", listOf("address")), "Dana Lee", tags = listOf("address"), rules = (1..64).map { ShareRule("f$it", 1, "c1", tags = listOf("t$it")) },
                    error = FailureKind.LIMIT, limit = VaultLimit("share_rules", 512),
                ),
                RuleEditActions(),
            )
        }
        rule.onNodeWithText("This connection has the most share rules it can have (64).").assertExists()
        rule.onNodeWithText("Your vault has the most share rules it can have (512). Delete one first.").assertExists()
        rule.onNodeWithText("Save rule").assertIsNotEnabled()
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

    // --- tag colours (owner decision 2026-10-09) ---

    @Test
    fun everyTagHasAColourButtonAndThePickerMarksTheCurrentColour() {
        val asked = mutableListOf<String>()
        val picked = mutableListOf<Int>()
        val travel = TagView("travel", 1, color = TagColors.stored[3])
        var state by mutableStateOf(TagsUiState(TagRegistry(1, listOf(TagView("@profile", 2), travel)), loading = false))
        rule.setContent {
            TagsScreen(
                state,
                TagsActions(onColour = { asked += it.tag; state = state.copy(dialog = TagDialog.Colour(it)) }, onPickColour = { picked += it }),
            )
        }
        rule.onNodeWithContentDescription("Edit the colour of Shared profile").assertExists()
        rule.onNodeWithContentDescription("Edit the colour of travel").performClick()
        assertEquals(listOf("travel"), asked)
        rule.onNodeWithText("Colour of travel").assertIsDisplayed()
        // Ten swatches, the stored one checked.
        TagColors.names.forEach { rule.onNodeWithTag("tag_colour_$it").assertExists() }
        rule.onNodeWithTag("tag_colour_teal").assertIsSelected()
        rule.onNodeWithContentDescription("Teal, selected").assertExists()
        rule.onNodeWithTag("tag_colour_blue").assertIsNotSelected()
        rule.onNodeWithTag("tag_colour_blue").performClick()
        assertEquals(listOf(4), picked)
    }

    @Test
    fun aTagWithoutAStoredColourShowsItsHashColourChecked() {
        val t = TagView("medical", 1)
        rule.setContent { TagsScreen(TagsUiState(TagRegistry(1, listOf(t)), loading = false, dialog = TagDialog.Colour(t)), TagsActions()) }
        rule.onNodeWithTag("tag_colour_${TagColors.names[TagColors.index("medical")]}").assertIsSelected()
    }

    @Test
    fun theSharedProfileColourIsExplained() {
        rule.setContent {
            TagsScreen(TagsUiState(TagRegistry(1, listOf(TagView("@profile", 2))), loading = false, dialog = TagDialog.ProfileColour), TagsActions())
        }
        rule.onNodeWithText("Your shared profile always uses your colour").assertIsDisplayed()
        rule.onNodeWithTag("tag_colour_teal").assertDoesNotExist()
    }

    @Test
    fun theTagFormOffersEditColour() {
        val asked = mutableListOf<String>()
        val t = TagView("travel", 1)
        rule.setContent {
            TagsScreen(TagsUiState(TagRegistry(1, listOf(t)), loading = false, dialog = TagDialog.Edit(t, "travel", "")), TagsActions(onColour = { asked += it.tag }))
        }
        rule.onNodeWithTag("tag_form_colour").performClick()
        assertEquals(listOf("travel"), asked)
    }

    // --- one tag per rule (owner decision 2026-10-09) and VAULT-MESSAGING 0.23.0 ---

    @Test
    fun theTagIsChosenOnceWithAGoldOutlineAndACheck() {
        val chosen = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var state by mutableStateOf(RuleEditUiState(RuleDraft("c1"), "Dana Lee", tags = listOf("address", "medical", "travel"), rules = listOf(address)))
        rule.setContent {
            RuleEditScreen(
                state,
                RuleEditActions(onTag = { chosen += it; state = state.copy(draft = state.draft.copy(tags = listOf(it))) }, onOpenRule = { opened += it }),
            )
        }
        rule.onNodeWithText("Select the tag to share").assertExists()
        rule.onNodeWithText("Items with this tag will be shared with", substring = true).assertExists()
        rule.onNodeWithTag("rule_save").assertIsNotEnabled() // until a tag is chosen
        rule.onNodeWithTag("rule_match_any").assertDoesNotExist() // no any/all
        rule.onNodeWithTag("rule_tag_check_medical", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("rule_tag_medical").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("medical"), chosen)
        rule.onNodeWithTag("rule_tag_medical").assertIsSelected()
        rule.onNodeWithTag("rule_tag_check_medical", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rule_tag_check_travel", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("rule_save").assertIsEnabled()
        // "address" has its rule: not a choice, it opens that rule.
        rule.onNodeWithTag("rule_tag_address").assertDoesNotExist()
        rule.onNodeWithTag("rule_tag_taken_address").assertTextContains("Already shared — edit its rule", substring = true)
        rule.onNodeWithTag("rule_tag_taken_address").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("r1"), opened)
    }

    @Test
    fun aRuleOfSeveralTagsIsShownReadOnly() {
        rule.setContent {
            RuleEditScreen(RuleEditUiState(RuleDraft.of(travel), "Dana Lee", tags = listOf("drivers-license", "travel"), rules = listOf(travel), readOnly = true), RuleEditActions())
        }
        rule.onNodeWithTag("rule_read_only").assertExists()
        rule.onNodeWithTag("rule_save").assertDoesNotExist()
        rule.onNodeWithTag("rule_delete").assertIsEnabled()
        rule.onNodeWithTag("rule_tags").assertDoesNotExist()
        rule.onNodeWithTag("tag_chip_travel", useUnmergedTree = true).assertExists()
    }

    /** §10.12 (0.23.0): the dry run's `outcome` and the rule that asks, named by its tags. */
    @Test
    fun theDryRunExplainsWhyAnAutoRuleAsksFirst() {
        val medical = ShareRule("r5", 1, "c1", tags = listOf("medical"))
        val preview = RulePreview(
            listOf(
                RuleMatch("i1", "Allergies", "medical", Sensitivity.DATA, outcome = RuleMatch.OUTCOME_ASK, askRuleId = "r5"),
                RuleMatch("i4", "Bank", "bank_account", Sensitivity.DATA, outcome = RuleMatch.OUTCOME_INCLUDE),
            ),
            2,
        )
        rule.setContent {
            RuleEditScreen(
                RuleEditUiState(RuleDraft("c1", listOf("money"), mode = ShareMode.AUTO), "Dana Lee", tags = listOf("money"), preview = preview, rules = listOf(medical)),
                RuleEditActions(),
            )
        }
        rule.onNodeWithTag("rule_preview_total").assertTextEquals("2 items match: 1 shared at once, 1 asks you first.")
        rule.onNodeWithTag("rule_match_note_i1", useUnmergedTree = true).assertTextEquals("Asks you first because your “medical” rule covers it")
        rule.onNodeWithTag("rule_match_note_i4", useUnmergedTree = true).assertTextEquals("Shared at once")
    }

    @Test
    fun leavingWithChangesAsksToDiscard() {
        val asked = mutableListOf<String>()
        rule.setContent {
            RuleEditScreen(
                RuleEditUiState(RuleDraft("c1", listOf("address")), "Dana Lee", tags = listOf("address"), dirty = true, confirmDiscard = true),
                RuleEditActions(onDiscard = { asked += "discard" }),
            )
        }
        rule.onNodeWithText("Discard changes to this rule?").assertExists()
        rule.onNodeWithText("Discard").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("discard"), asked)
    }

    /** Limits on both sides (0.23.0): the rule's summary, a received grant's, and "Try again in …" when refused. */
    @Test
    fun rateLimitsAreShownAndARefusalSaysWhenToTryAgain() {
        val limited = ShareRule("r7", 1, "c1", tags = listOf("medical"), perHour = 5, perDay = 20)
        rule.setContent { ConnectionSharingScreen(ConnectionSharingUiState("c1", "Dana Lee", rules = listOf(limited), loading = false), ConnectionSharingActions()) }
        rule.onNodeWithTag("rule_r7").assertTextContains("up to 5 an hour · up to 20 a day · no end date", substring = true)
    }

    @Test
    fun aReceivedGrantShowsItsLimitsAndARateLimitedFetchItsRetry() {
        val g = GrantView("g6", "c1", GrantDirection.RECEIVED, "x2", "Card", "insurance", perHour = 5, perDay = 1)
        rule.setContent {
            SharedWithYouScreen(
                SharedWithYouUiState(
                    "c1", "Dana Lee", received = listOf(g), loading = false,
                    refused = mapOf("g6" to "rate_limited"), retryAt = mapOf("g6" to Instant.now().plusSeconds(11 * 60 + 30)),
                ),
                SharedWithYouActions(),
            )
        }
        rule.onNodeWithTag("received_label_g6").assertTextContains("up to 5 times an hour · up to 1 time a day", substring = true)
        rule.onNodeWithTag("received_refused_g6").assertTextContains("Try again in 12 minutes.", substring = true)
    }

    /** #95: a shared `phone` value is shown formatted for the viewer's region, as the member's own are. */
    @Test
    fun aSharedPhoneNumberIsFormattedForTheRegion() {
        val g = GrantView("g5", "c1", GrantDirection.RECEIVED, "x1", "Practice", "contact")
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalPhoneRegion provides "US") {
                SharedWithYouScreen(
                    SharedWithYouUiState(
                        "c1", "Dana Lee", received = listOf(g), loading = false,
                        opened = mapOf("g5" to SharedContent("x1", "Practice", "contact", listOf(ItemFieldView("f1", "Phone", "phone", FieldValue.Text("+1 650-253-0000"))), null, null)),
                    ),
                    SharedWithYouActions(),
                )
            }
        }
        rule.onNodeWithText("(650) 253-0000").assertExists()
    }
}
