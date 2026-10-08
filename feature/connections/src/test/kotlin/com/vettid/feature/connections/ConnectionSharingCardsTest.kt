// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        rule.onNodeWithTag("sharing_rule_r1").assertTextContains("5 uses", substring = true)
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
}
