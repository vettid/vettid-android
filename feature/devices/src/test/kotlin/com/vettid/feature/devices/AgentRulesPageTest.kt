package com.vettid.feature.devices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** What the agent's page lists under "What it may use" (ANDROID-PLAN 0.1.116 §4 AA4, decision 17). */
@RunWith(RobolectricTestRunner::class)
class AgentRulesPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val agent = DeviceInfo("a1", "agent", "Claude", null, now.minusSeconds(86_400), null, null)
    private val ask = ShareRule(
        "r1", 1, null, "a1", listOf("api"), perHour = 60, perDay = 1_000, expiresAt = now.plusSeconds(86_400 * 20L),
        included = listOf("i1", "i2"), pending = listOf("i3"),
    )
    private val auto = ShareRule("r2", 1, null, "a1", listOf("keys", "ops"), mode = ShareMode.AUTO, perHour = 5, perDay = 50)
    private val warning = "On this vault, the agent may receive the values of the items it uses, and its app may keep them in its history."

    private fun show(state: DeviceUiState, actions: DevicePageActions = DevicePageActions()) =
        rule.setContent { DevicePageScreen(state, actions, now = now) }

    private fun state(vararg r: ShareRule, itemWays: Boolean = false, loaded: Boolean = true) =
        DeviceUiState(device = agent, loaded = true, rules = r.toList(), rulesLoaded = loaded, itemWays = itemWays)

    @Test
    fun aRuleRowShowsItsNameModeLimitEndAndCounts() {
        show(state(ask))
        rule.onNodeWithText("What it may use").assertExists()
        val row = rule.onNodeWithTag("rule_r1")
        row.performScrollTo()
        row.assertTextContains("api", substring = true)
        row.assertTextContains("Asks you about each item", substring = true)
        row.assertTextContains("Up to 60 uses an hour", substring = true)
        row.assertTextContains("Ends", substring = true)
        row.assertTextContains("2 items", substring = true)
        row.assertTextContains("1 waiting for you", substring = true)
    }

    @Test
    fun anAutoRuleIsNamedByItsTagsAndSaysItIncludesAutomatically() {
        show(state(auto, itemWays = true))
        val row = rule.onNodeWithTag("rule_r2")
        row.performScrollTo()
        row.assertTextContains("keys or ops", substring = true)
        row.assertTextContains("Includes tagged items automatically", substring = true)
        row.assertTextContains("No end date", substring = true)
    }

    @Test
    fun theWarningIsOnEveryRowWithoutItemWaysAndOnNoneWith() {
        show(state(ask, auto, itemWays = false))
        rule.onNodeWithTag("rule_ways_warning_r1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rule_ways_warning_r2", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rule_r1").assertTextContains(warning, substring = true)
    }

    @Test
    fun noWarningOnAVaultThatListsItemWays() {
        show(state(ask, auto, itemWays = true))
        rule.onNodeWithTag("rule_r1").assertExists()
        assertEquals(0, rule.onAllNodesWithTagCount("rule_ways_warning_r1"))
        assertEquals(0, rule.onAllNodesWithTagCount("rule_ways_warning_r2"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun noRulesSaysItCantUseAnythingYetAndAddOpensTheEditor() {
        val calls = mutableListOf<String>()
        show(state(), DevicePageActions(onNewRule = { calls += "new" }))
        rule.onNodeWithTag("rules_none").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("It can't use any of your items yet.").assertExists()
        rule.onNodeWithTag("rule_add").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("new"), calls)
    }

    @Test
    fun aRowOpensItsEditor() {
        val calls = mutableListOf<String>()
        show(state(ask), DevicePageActions(onOpenRule = { calls += it }))
        rule.onNodeWithTag("rule_r1").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("r1"), calls)
    }

    @Test
    fun atSixtyFourRulesThereIsNoAddAndTheLimitIsSaid() {
        val many = (1..64).map { ask.copy(ruleId = "r$it", tags = listOf("t$it")) }
        show(DeviceUiState(device = agent, loaded = true, rules = many, rulesLoaded = true))
        rule.onNodeWithTag("rules_limit").performScrollTo().assertTextContains("An agent can have at most 64 rules.")
        rule.onNodeWithTag("rule_add").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aRuleListThatCouldNotBeReadSaysSoWithTryAgain() {
        val calls = mutableListOf<String>()
        show(
            DeviceUiState(device = agent, loaded = true, rulesError = com.vettid.core.data.vault.FailureKind.NO_RESPONSE),
            DevicePageActions(onRetryRules = { calls += "retry" }),
        )
        rule.onNodeWithTag("rules_failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun aDesktopHasNoRules() {
        show(DeviceUiState(device = DeviceInfo("d1", "desktop", "Laptop", null, null, null, null), loaded = true, rulesLoaded = true))
        assertEquals(0, rule.onAllNodesWithTagCount("rules_card"))
    }

    @Test
    fun savedIsToldOnceAsASnackbar() {
        var shown = 0
        show(DeviceUiState(device = agent, loaded = true, ruleSaved = true), DevicePageActions(onRuleSavedShown = { shown++ }))
        rule.waitForIdle()
        rule.onNodeWithText("Rule saved").assertExists()
        assertEquals(1, shown)
    }
}
