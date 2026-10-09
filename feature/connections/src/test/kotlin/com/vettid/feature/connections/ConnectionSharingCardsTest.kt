// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.items.GrantAsk
import com.vettid.core.data.items.GrantAskEntry
import com.vettid.core.data.items.GrantDirection
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The connection detail's two sharing cards (owner request 2026-10-08): "You share with <First>" (outgoing, gold) and
 * "<First> shares with you" (incoming, neutral), each a heading with a count, its own empty state and its action;
 * "this connection" before the names arrived.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionSharingCardsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val ada = ConnectionInfo("c1", "Countess", ConnectionState.ACTIVE, firstName = "Ada", lastName = "Lovelace")
    private val rule1 = ShareRule("r1", 1, "c1", tags = listOf("medical"), mode = ShareMode.AUTO, uses = 5, included = listOf("i1"))
    private val outGrant = GrantView("g1", "c1", GrantDirection.GIVEN, "i1", "Allergies", "medical", ruleId = "r1")
    private val outGrant2 = GrantView("g2", "c1", GrantDirection.GIVEN, "i2", "Passport", "identity_document")
    private val inGrant = GrantView("g3", "c1", GrantDirection.RECEIVED, "x1", "Home address", "contact")

    private fun detail(c: ConnectionInfo, sharing: DetailSharing, actions: DetailActions = DetailActions()) = rule.setContent {
        ConnectionDetailScreen(ConnectionDetailUiState(c.id, c, null, loading = false, sharing = sharing), actions)
    }

    /** The name as sentences hold it: bidi-isolated (§10.8). */
    private val ada1 = "\u2068Ada\u2069"

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun bothCardsAreHeadedByDirectionWithTheFirstNameAndACount() {
        detail(ada, DetailSharing(listOf(rule1), listOf(outGrant, outGrant2), listOf(inGrant), loaded = true))
        rule.onNodeWithTag("sharing_out_title").assertTextEquals("You share with $ada1").assert(isHeading)
        rule.onNodeWithTag("sharing_in_title").assertTextEquals("$ada1 shares with you").assert(isHeading)
        rule.onNodeWithTag("sharing_out_count").assertContentDescriptionEquals("2 items shared")
        rule.onNodeWithTag("sharing_in_count").assertContentDescriptionEquals("1 item shared with you")
        rule.onNodeWithTag("sharing_out_edge").assertExists()
        rule.onNodeWithTag("sharing_in_edge").assertExists()
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("medical", substring = true)
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("automatically", substring = true)
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("up to 5 fetches of each item", substring = true)
        rule.onNodeWithTag("sharing_given_g1").assertTextContains("Allergies", substring = true)
        // What comes in is read-only and labelled as theirs.
        rule.onNodeWithTag("sharing_received_g3").assertTextContains("Home address", substring = true)
        rule.onNodeWithTag("sharing_received_g3").assertTextContains("$ada1’s", substring = true)
        rule.onNodeWithTag("sharing_out_empty").assertDoesNotExist()
        rule.onNodeWithTag("sharing_in_empty").assertDoesNotExist()
    }

    @Test
    fun eachDirectionHasItsOwnEmptyState() {
        detail(ada, DetailSharing(loaded = true))
        rule.onNodeWithTag("sharing_out_empty").assertTextEquals("You’re not sharing anything with $ada1 yet.")
        rule.onNodeWithTag("sharing_in_empty").assertTextEquals("$ada1 isn’t sharing anything with you yet.")
        rule.onNodeWithTag("sharing_out_count").assertContentDescriptionEquals("0 items shared")
        rule.onNodeWithTag("sharing_out_manage").assertDoesNotExist()
        rule.onNodeWithTag("sharing_in_open").assertDoesNotExist()
    }

    @Test
    fun beforeTheNamesTheCardsSayThisConnection() {
        detail(ConnectionInfo("c2", "Riley", ConnectionState.ACTIVE, keyFingerprint = null), DetailSharing(loaded = true))
        rule.onNodeWithTag("sharing_out_title").assertTextEquals("You share with this connection")
        rule.onNodeWithTag("sharing_in_title").assertTextEquals("This connection shares with you")
        rule.onNodeWithTag("sharing_in_empty").assertTextEquals("This connection isn’t sharing anything with you yet.")
        assertEquals(0, rule.onAllNodesWithText("Name not shared yet shares", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theActionsOpenTheRuleEditorAndTheAsk() {
        val asked = mutableListOf<String>()
        detail(
            ada,
            DetailSharing(listOf(rule1), listOf(outGrant), listOf(inGrant), loaded = true),
            DetailActions(
                onShareItems = { asked += "share" },
                onOpenRule = { asked += "rule:$it" },
                onAskForSomething = { asked += "ask" },
                onSharing = { asked += "manage" },
                onSharedWithYou = { asked += "open" },
            ),
        )
        rule.onNodeWithTag("sharing_out_share").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sharing_rule_r1").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sharing_out_manage").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sharing_in_ask").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sharing_in_open").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("share", "rule:r1", "manage", "ask", "open"), asked)
    }

    @Test
    fun theDetailReadsOnlyThisConnectionsSharingInForce() = runTest {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Ada"), FakeSocial.connection("c9", "Bo"))) }
        val sharing = FakeSharing().apply {
            rulesStored += rule1
            rulesStored += ShareRule("r9", 1, "c9", tags = listOf("x"))
            given += listOf(outGrant, outGrant.copy(grantId = "g8", state = "revoked"), outGrant.copy(grantId = "g9", connectionId = "c9"))
            received += listOf(inGrant, inGrant.copy(grantId = "g7", state = "expired"))
            requested += listOf(
                GrantAsk("q1", "c1", listOf(GrantAskEntry("category", "insurance")), "pending"),
                GrantAsk("q2", "c1", listOf(GrantAskEntry("category", "medical")), "denied"),
            )
        }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, sharing)
        advanceUntilIdle()
        val s = vm.uiState.value.sharing
        assertTrue(s.loaded)
        assertFalse(s.failed)
        assertEquals(listOf("r1"), s.rules.map { it.ruleId })
        assertEquals(listOf("g1"), s.given.map { it.grantId })
        assertEquals(listOf("g3"), s.received.map { it.grantId })
        assertEquals(listOf("q1"), s.asked.map { it.requestId })
        assertEquals(1, s.outgoingCount)
        assertEquals(1, s.incomingCount)
        // Back from the rule editor: read again.
        sharing.received.clear()
        vm.loadSharing()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sharing.incomingEmpty)
    }

    // --- owner feedback 2026-10-09: a rule per tag, each with its own settings ---

    private val address = ShareRule("r1", 1, "c1", tags = listOf("address"), mode = ShareMode.AUTO, included = listOf("i1"))
    private val license = ShareRule("r2", 1, "c1", tags = listOf("drivers-license"), uses = 5, expiresAt = java.time.Instant.parse("2026-12-31T12:00:00Z"), included = listOf("i2"), pending = listOf("i3"))
    private val travel = ShareRule("r3", 1, "c1", tags = listOf("drivers-license", "travel"), match = TagMatch.ALL, mode = ShareMode.AUTO, uses = 1, included = listOf("i2", "i4"))

    @Test
    fun everyRuleIsARowWithItsOwnSettings() {
        detail(ada, DetailSharing(listOf(address, license, travel), loaded = true))
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("address", substring = true)
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("Shared automatically", substring = true)
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("no fetch limit · no end date", substring = true)
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("1 item shared now", substring = true)
        rule.onNodeWithTag("sharing_rule_r2").assertTextContains("Asks you each time", substring = true)
        rule.onNodeWithTag("sharing_rule_r2").assertTextContains("up to 5 fetches of each item · until Dec 31, 2026", substring = true)
        rule.onNodeWithTag("sharing_rule_r2").assertTextContains("1 item shared now · 1 waiting for you", substring = true)
        rule.onNodeWithTag("sharing_rule_r3").assertTextContains("Items with all of these tags", substring = true)
        rule.onNodeWithTag("sharing_rule_r3").assertTextContains("up to 1 fetch of each item", substring = true)
        rule.onNodeWithTag("sharing_rule_r3").assertTextContains("2 items shared now", substring = true)
        // Overlaps (0.23.0: `ask` wins, rules named by their tags): the same tag and item in r2 and r3; none for r1.
        rule.onNodeWithTag("sharing_rule_also_r2", useUnmergedTree = true).assertTextEquals("Overlaps your “drivers-license + travel” rule")
        rule.onNodeWithTag("sharing_rule_also_r3", useUnmergedTree = true).assertTextEquals("Asks you first for the items your “drivers-license” rule also covers")
        // #95's TagChip: each tag in its own colour.
        rule.onNodeWithTag("tag_chip_address", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("tag_chip_drivers-license", useUnmergedTree = true).assertCountEquals(2) // r2 and r3
        rule.onNodeWithTag("sharing_rule_also_r1", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("sharing_out_share").assertTextEquals("Add a rule").assertIsEnabled()
        rule.onNodeWithTag("sharing_out_limit").assertDoesNotExist()
    }

    @Test
    fun eachRuleOpensItsEditorOrIsDeletedAfterAConfirmation() {
        val asked = mutableListOf<String>()
        var state by mutableStateOf(ConnectionDetailUiState("c1", ada, null, loading = false, sharing = DetailSharing(listOf(address, license), loaded = true)))
        rule.setContent {
            ConnectionDetailScreen(
                state,
                DetailActions(
                    onOpenRule = { asked += "open:$it" },
                    onAskDeleteRule = { r -> asked += "ask:${r?.ruleId}"; state = state.copy(deleteRule = r) },
                    onDeleteRule = { asked += "delete" },
                ),
            )
        }
        rule.onNodeWithTag("sharing_rule_r2").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sharing_rule_delete_r2").assertContentDescriptionEquals("Delete this rule")
        rule.onNodeWithTag("sharing_rule_delete_r2").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Delete this sharing rule?").assertExists()
        rule.onNodeWithText("$ada1 can no longer fetch the items this rule shared. Items another rule shares stay shared, and nothing becomes shared because this rule is gone.").assertExists()
        rule.onNodeWithTag("confirm_button").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("open:r2", "ask:r2", "delete"), asked)
    }

    @Test
    fun aRuleIsDeletedOnlyOnceConfirmedAndTheCardIsReadAgain() = runTest {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Ada"))) }
        val sharing = FakeSharing().apply { rulesStored += listOf(address, license) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, sharing)
        advanceUntilIdle()
        vm.askDeleteRule(license)
        advanceUntilIdle()
        assertEquals("r2", vm.uiState.value.deleteRule?.ruleId)
        vm.askDeleteRule(null)
        vm.deleteRule()
        advanceUntilIdle()
        assertTrue("deleteRule" !in sharing.calls)
        vm.askDeleteRule(license)
        vm.deleteRule()
        advanceUntilIdle()
        assertTrue("deleteRule" in sharing.calls)
        assertEquals(null, vm.uiState.value.deleteRule)
        assertEquals(listOf("r1"), vm.uiState.value.sharing.rules.map { it.ruleId })
        // A refusal shows on the detail.
        sharing.fail["deleteRule"] = VaultFailure(FailureKind.NETWORK)
        vm.askDeleteRule(address)
        vm.deleteRule()
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
    }

    @Test
    fun atSixtyFourRulesTheCardNamesTheLimitAndAddsNoRule() {
        detail(ada, DetailSharing((1..64).map { ShareRule("f$it", 1, "c1", tags = listOf("t$it")) }, loaded = true))
        rule.onNodeWithTag("sharing_out_limit").assertTextEquals("This connection has the most share rules it can have (64).")
        rule.onNodeWithTag("sharing_out_share").assertIsNotEnabled()
    }

    @Test
    fun aSharingFailureLeavesTheRestOfTheDetail() = runTest {
        val social = FakeSocial().apply { seed(listOf(FakeSocial.connection("c1", "Ada"))) }
        val sharing = FakeSharing().apply { fail["rules"] = VaultFailure(FailureKind.NETWORK) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, sharing)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sharing.failed)
        assertEquals(null, vm.uiState.value.error)
        assertEquals("c1", vm.uiState.value.connection?.id)
    }

    // --- VAULT-MESSAGING 0.23.0 ---

    @Test
    fun aRulesRateLimitsAndAReceivedGrantsAreShown() {
        val limited = ShareRule("r7", 1, "c1", tags = listOf("medical"), perHour = 5, perDay = 20)
        detail(ada, DetailSharing(listOf(limited), received = listOf(inGrant.copy(perHour = 1, perDay = 3)), loaded = true))
        rule.onNodeWithTag("sharing_rule_r7").assertTextContains("up to 5 an hour · up to 20 a day · no end date", substring = true)
        rule.onNodeWithTag("sharing_received_g3").assertTextContains("up to 1 time an hour · up to 3 times a day", substring = true)
    }

    /** §10.4.1: the asks card follows the connection's state, with the action that ends each; none from an older vault. */
    @Test
    fun theAsksCardShowsMutedPausedAndTheActionsThatEndThem() {
        val asked = mutableListOf<String>()
        var c by mutableStateOf(ada)
        rule.setContent {
            ConnectionDetailScreen(
                ConnectionDetailUiState(c.id, c, null, loading = false, sharing = DetailSharing(loaded = true)),
                DetailActions(onMuteAsks = { asked += "mute:$it" }, onResumeAsks = { asked += "resume" }),
            )
        }
        rule.onNodeWithTag("asks_card").assertDoesNotExist() // a vault before 0.23.0
        c = ada.copy(asks = com.vettid.core.data.social.AskState())
        rule.onNodeWithTag("asks_normal", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("asks_resume").assertDoesNotExist()
        rule.onNodeWithTag("asks_mute").performSemanticsAction(SemanticsActions.OnClick)
        c = ada.copy(asks = com.vettid.core.data.social.AskState(muted = true, paused = true, cooldowns = 2))
        rule.onNodeWithTag("asks_paused", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("asks_muted", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("$ada1’s requests are paused after you declined several", substring = true).assertExists()
        rule.onNodeWithTag("asks_resume").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("asks_unmute").performSemanticsAction(SemanticsActions.OnClick)
        c = ada.copy(asks = com.vettid.core.data.social.AskState(cooldowns = 2))
        rule.onNodeWithTag("asks_cooldowns", useUnmergedTree = true).assertTextEquals("2 requests you declined are refused for 7 days.")
        rule.onNodeWithTag("asks_allow_again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("mute:true", "resume", "mute:false", "resume"), asked)
    }

    @Test
    fun theAsksStateMachineOffersWhatEndsEachState() {
        assertEquals(null, AsksView.of(null))
        assertEquals(listOf(AsksAction.MUTE), AsksView.of(com.vettid.core.data.social.AskState())!!.actions)
        assertEquals(listOf(AsksAction.UNMUTE), AsksView.of(com.vettid.core.data.social.AskState(muted = true))!!.actions)
        assertEquals(listOf(AsksAction.RESUME, AsksAction.MUTE), AsksView.of(com.vettid.core.data.social.AskState(paused = true, cooldowns = 3))!!.actions)
        assertEquals(listOf(AsksAction.RESUME, AsksAction.UNMUTE), AsksView.of(com.vettid.core.data.social.AskState(muted = true, paused = true))!!.actions)
        assertEquals(listOf(AsksAction.ALLOW_AGAIN, AsksAction.MUTE), AsksView.of(com.vettid.core.data.social.AskState(cooldowns = 1))!!.actions)
        assertTrue(AsksView.of(com.vettid.core.data.social.AskState(paused = true))!!.prominent)
        assertFalse(AsksView.of(com.vettid.core.data.social.AskState(cooldowns = 1))!!.prominent)
    }

    /** `connection.asks.mute` / `.resume` from the detail: unmuting keeps a pause, resuming keeps a mute. */
    @Test
    fun muteAndResumeGoToTheVaultAndShowAtOnce() = runTest {
        val social = FakeSocial().apply {
            seed(listOf(FakeSocial.connection("c1", "Ada").copy(asks = com.vettid.core.data.social.AskState(paused = true, cooldowns = 3))))
        }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing())
        advanceUntilIdle()
        vm.muteAsks(true)
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.social.AskState(muted = true, paused = true, cooldowns = 3), vm.uiState.value.connection?.asks)
        vm.muteAsks(false)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.connection?.asks?.paused)
        vm.muteAsks(true)
        vm.resumeAsks()
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.social.AskState(muted = true), vm.uiState.value.connection?.asks)
        assertEquals(listOf("asksMute:c1:true", "asksMute:c1:false", "asksMute:c1:true", "asksResume:c1"), social.calls.filter { it.startsWith("asks") })
        social.fail["asksResume:c1"] = VaultFailure(FailureKind.NETWORK)
        vm.resumeAsks()
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
    }

    /** §10.4.1: the member's own ask that was refused reads "Not accepted", never "declined by <First>". */
    @Test
    fun aRefusedAuthenticationReadsNotAccepted() {
        rule.setContent {
            ConnectionDetailScreen(
                ConnectionDetailUiState("c1", ada, com.vettid.core.data.social.AuthenticationState("c1", lastResult = "denied"), loading = false, sharing = DetailSharing(loaded = true)),
                DetailActions(),
            )
        }
        rule.onNodeWithTag("auth_status").assertTextEquals("Not accepted.")
    }
}
