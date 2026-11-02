// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleMatch
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.data.votes.WindowNotClosed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The agent rule editor as the member sees it (ANDROID-PLAN 0.1.116 §4 AA4, decisions 4 and 17). */
@RunWith(RobolectricTestRunner::class)
class AgentRuleScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val warning = "On this vault, the agent may receive the values of the items it uses, and its app may keep them in its history."
    private val draft = RuleDraft("", listOf("api"), agentId = "a1", perHour = 60, perDay = 1_000, statusTtl = 900)

    private fun state(
        itemWays: Boolean = false,
        d: RuleDraft = draft,
        extra: (RuleEditUiState) -> RuleEditUiState = { it },
    ) = extra(
        RuleEditUiState(d, "Claude", tags = listOf("api", "travel"), perHourText = "60", perDayText = "1000", expiry = RuleExpiry.MONTH, itemWays = itemWays),
    )

    private fun iso(n: String) = com.vettid.core.data.account.AccountNames.isolate(n)

    private fun show(s: RuleEditUiState, actions: RuleEditActions = RuleEditActions()) = rule.setContent { RuleEditScreen(s, actions) }

    @Test
    fun theTitleNamesTheAgentAndSaveRuleIsInTheTopBar() {
        show(state())
        rule.onNodeWithText("Share with ${iso("Claude")}").assertExists()
        rule.onNodeWithTag("rule_save").assertExists()
        rule.onNodeWithText("Critical items are never shared with agents, whatever their tags.").assertExists()
    }

    @Test
    fun withoutItemWaysAutoIsNotOfferedAndTheWarningIsOnTheEditor() {
        show(state(itemWays = false))
        rule.onNodeWithTag("rule_mode_ask").assertIsSelected()
        assertEquals(0, rule.onAllNodesWithText("Include tagged items automatically").fetchSemanticsNodes().size)
        rule.onNodeWithTag("rule_mode_auto").assertDoesNotExist()
        rule.onNodeWithTag("agent_rule_ways_warning").performScrollTo().assertTextContains(warning)
        // The use-can-reveal text is always there.
        rule.onNodeWithTag("agent_rule_reveal").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aStoredAutoRuleWithoutItemWaysShowsAutomaticDisabledAskSelectedAndTheNote() {
        show(state(itemWays = false, d = draft.copy(storedMode = ShareMode.AUTO, ruleId = "r1")) { it.copy(rules = emptyList()) })
        rule.onNodeWithTag("rule_mode_ask").assertIsSelected()
        rule.onNodeWithTag("rule_mode_auto").assertIsNotEnabled()
        rule.onNodeWithTag("agent_rule_auto_demoted").performScrollTo()
            .assertTextContains("This vault can't keep the agent from receiving item values, so this rule now asks you first.")
    }

    @Test
    fun withItemWaysAutoIsOfferedAndThereIsNoWarning() {
        val auto = draft.copy(mode = ShareMode.AUTO)
        show(state(itemWays = true, d = auto))
        rule.onNodeWithText("Include tagged items automatically").assertExists()
        rule.onNodeWithTag("rule_mode_auto").assertIsSelected()
        rule.onNodeWithTag("agent_rule_ways_warning").assertDoesNotExist()
        rule.onNodeWithTag("rule_mode_auto").assertIsEnabled()
        rule.onNodeWithTag("agent_rule_reveal").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLimitsAreAlwaysThereWithTheDefaultsAndTheNoteReadsThem() {
        show(state())
        rule.onNodeWithText("Uses without asking").performScrollTo()
        rule.onNodeWithTag("agent_rule_per_note").assertTextContains("Up to 60 uses an hour and 1000 a day. Past that, each use asks you.")
        rule.onNodeWithTag("rule_per_hour").assertExists()
        rule.onNodeWithTag("rule_per_day").assertExists()
        rule.onNodeWithTag("rule_uses").assertExists()
    }

    @Test
    fun anEmptyOrOutOfRangeLimitBlocksSave() {
        show(state { it.copy(perHourText = "") })
        rule.onNodeWithTag("rule_save").assertIsNotEnabled()
        rule.onNodeWithText("Between 1 and 3,600").assertExists()
    }

    @Test
    fun saveIsEnabledOnceTheTagIsChosenAndTheLimitsRead() {
        show(state())
        rule.onNodeWithTag("rule_save").assertIsEnabled()
    }

    @Test
    fun saveAsksTheEditorToSave() {
        val calls = mutableListOf<String>()
        show(state(), RuleEditActions(onSave = { calls += "save" }))
        rule.onNodeWithTag("rule_save").performClick()
        assertEquals(listOf("save"), calls)
    }

    @Test
    fun moreOptionsShowsTheStatusLifetimeWithFifteenMinutesSelected() {
        show(state { it.copy(moreOptions = true) })
        rule.onNodeWithText("Status lifetime").performScrollTo().assertExists()
        rule.onNodeWithTag("rule_ttl_900").assertIsSelected()
        rule.onNodeWithTag("rule_ttl_60").assertExists()
        rule.onNodeWithTag("rule_ttl_3600").assertExists()
    }

    @Test
    fun theStatusLifetimeIsHiddenUntilMoreOptions() {
        show(state())
        rule.onNodeWithTag("rule_status_ttl").assertDoesNotExist()
    }

    @Test
    fun theDryRunSaysHowManyAreAskedAndHowManyIncluded() {
        val p = RulePreview(
            listOf(
                RuleMatch("i1", "Key A", "secret", Sensitivity.SECRET, outcome = RuleMatch.OUTCOME_ASK),
                RuleMatch("i2", "Key B", "secret", Sensitivity.SECRET, outcome = RuleMatch.OUTCOME_ASK),
                RuleMatch("i3", "Key C", "secret", Sensitivity.SECRET, outcome = RuleMatch.OUTCOME_INCLUDE),
            ),
            3,
        )
        show(state { it.copy(preview = p) })
        rule.onNodeWithTag("rule_preview_total").performScrollTo().assertTextContains("3 items: 2 will be asked about, 1 included now")
    }

    @Test
    fun aDryRunWithoutOutcomesGuessesNothingFromTheMode() {
        val p = RulePreview(listOf(RuleMatch("i1", "Key A", "secret", Sensitivity.SECRET), RuleMatch("i2", "Key B", "secret", Sensitivity.SECRET)), 2)
        show(state { it.copy(preview = p) })
        rule.onNodeWithTag("rule_preview_total").performScrollTo().assertTextContains("2 items: 0 will be asked about, 0 included now")
    }

    @Test
    fun theUsesNoteAppearsOnlyOnceThereIsANumber() {
        show(state { it.copy(usesText = "5") })
        rule.onNodeWithText("Stop after 5 uses of each item.").assertExists()
    }

    @Test
    fun noUsesNoteWhileTheFieldIsEmpty() {
        show(state())
        assertEquals(0, rule.onAllNodesWithText("Stop after", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anItemAskedBecauseAnotherRuleCoversItSaysWhichRuleAndTheAgent() {
        val asker = ShareRule("r5", 1, null, "a1", listOf("medical"), perHour = 60, perDay = 1_000)
        val p = RulePreview(listOf(RuleMatch("i1", "Key A", "secret", Sensitivity.SECRET, outcome = RuleMatch.OUTCOME_ASK, askRuleId = "r5")), 1)
        show(state(true, draft.copy(mode = ShareMode.AUTO)) { it.copy(preview = p, rules = listOf(asker)) })
        rule.onNodeWithTag("rule_match_note_i1", useUnmergedTree = true).assertTextContains("Asks you first because your “medical” rule for ${iso("Claude")} covers it")
    }

    @Test
    fun thePasswordSheetAsksForTheCredentialPasswordAndConfirmNeedsOne() {
        val calls = mutableListOf<String>()
        var s = state { it.copy(prompt = RulePrompt.PASSWORD) }
        show(s, RuleEditActions(onPassword = { calls += "pw:$it" }, onSubmitPassword = { calls += "go" }))
        rule.onNodeWithText("Confirm with your credential password").assertIsDisplayed()
        rule.onNodeWithText("Giving ${iso("Claude")} a permission is signed with your credential, so only you can do it. Enter your credential password.").assertIsDisplayed()
        rule.onNodeWithTag("agent_rule_password_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("agent_rule_password").performTextInput("x")
        assertEquals(listOf("pw:x"), calls)
    }

    @Test
    fun withAPasswordConfirmIsEnabledAndSends() {
        val calls = mutableListOf<String>()
        show(state { it.copy(prompt = RulePrompt.PASSWORD, password = "pw-fake-1") }, RuleEditActions(onSubmitPassword = { calls += "go" }))
        rule.onNodeWithTag("agent_rule_password_confirm").assertIsEnabled().performClick()
        assertEquals(listOf("go"), calls)
    }

    @Test
    fun aWrongPasswordIsToldInThePrompt() {
        show(state { it.copy(prompt = RulePrompt.PASSWORD, passwordError = FailureKind.BAD_PASSWORD) })
        rule.onNodeWithText("That credential password is not correct.", substring = true).assertExists()
    }

    @Test
    fun anAlarmIsToldInThePlansWordsWithALinkToTheAlarm() {
        val calls = mutableListOf<String>()
        show(state { it.copy(prompt = RulePrompt.PASSWORD, passwordError = FailureKind.CREDENTIAL_FROZEN) }, RuleEditActions(onOpenAlarm = { calls += "alarm" }))
        rule.onNodeWithText("Permissions can't be changed while a credential alarm is open").assertExists()
        rule.onNodeWithTag("agent_rule_alarm").performClick()
        assertEquals(listOf("alarm"), calls)
    }

    private fun limitShows(limit: String, text: String) {
        show(state { it.copy(error = FailureKind.LIMIT, limit = VaultLimit(limit, 1)) })
        rule.onNodeWithText(text, substring = true).assertExists()
    }

    @Test
    fun theGrantLimitIsThePlansText() =
        limitShows("agent_grants", "An agent can have at most 32 permissions, its share rules included. Remove one first.")

    @Test
    fun theSubjectLimitIsThePlansText() = limitShows("share_rules_subject", "An agent can have at most 64 rules.")

    @Test
    fun theVaultLimitIsThePlansText() = limitShows("share_rules", "Your vault holds at most 512 share rules.")

    @Test
    fun notFoundSaysTheAgentIsNoLongerPaired() {
        show(state { it.copy(error = FailureKind.NOT_FOUND) })
        rule.onNodeWithText("This agent is no longer paired.", substring = true).assertExists()
    }

    @Test
    fun theOwnerCheckHasItsLink() {
        val calls = mutableListOf<String>()
        show(state { it.copy(error = FailureKind.OWNER_CHECK_REQUIRED) }, RuleEditActions(onOwnerCheck = { calls += "check" }))
        rule.onNodeWithTag("agent_rule_owner_check").performClick()
        assertEquals(listOf("check"), calls)
    }

    @Test
    fun aSecondCredentialLockedAndAConflictAreToldInWords() {
        show(state { it.copy(lockedAgain = true, conflict = true) })
        rule.onNodeWithText("Your credential locked again before the rule was saved. Try again.").assertExists()
        rule.onNodeWithText("Another device changed this rule. Here is the current one.").assertExists()
    }

    @Test
    fun aWindowThatStayedOpenIsToldWithCloseItNow() {
        val calls = mutableListOf<String>()
        show(state { it.copy(windowNotClosed = WindowNotClosed(300)) }, RuleEditActions(onCloseWindow = { calls += "close" }))
        rule.onNodeWithText("not been used for 5 minutes", substring = true).assertExists()
        rule.onNodeWithTag("agent_rule_window_close").performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun stopSharingConfirmsWithThePlansWordsAndTheStatusBound() {
        val r = ShareRule("r1", 1, null, "a1", listOf("medical"), perHour = 60, perDay = 1_000, statusTtl = 900)
        show(state(d = RuleDraft.of(r)) { it.copy(rules = listOf(r), confirmDelete = true) })
        rule.onNodeWithText("Stop sharing your “medical” rule's items with ${iso("Claude")}?").assertExists()
        rule.onNodeWithText("It can no longer use them from now on. Services it showed this rule to may accept it for up to 16 minutes.").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun aConnectionRuleIsUnchanged() {
        rule.setContent { RuleEditScreen(RuleEditUiState(RuleDraft("c1"), "Dana Lee", tags = listOf("medical")), RuleEditActions()) }
        rule.onNodeWithText("Ask me each time").assertExists()
        rule.onNodeWithText("Share automatically").assertExists()
        rule.onNodeWithTag("agent_rule_ways_warning").assertDoesNotExist()
        rule.onNodeWithText("Not used", substring = true).assertDoesNotExist()
    }
}
