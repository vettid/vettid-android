package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Connections titled from the names on the peer's VettID account (VAULT-MESSAGING 0.18.0 §10.8, ANDROID-PLAN
 * 0.1.10): "First Last", the display name secondary, "Name not shared yet" before the names, the fingerprint in the
 * details, and nothing that calls the names verified. Owner decision 2026-10-08: no alias in titles, no safety
 * code, alias, note, edit or block on the detail; the fingerprint stays as the lasting identity check.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionNamesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val fp = "9a1f bb7d 873e eafb 494b ef94 f072 7b25"
    private val ada = ConnectionInfo(
        "c1", "Countess", ConnectionState.ACTIVE, firstName = "Ada", lastName = "Lovelace", keyFingerprint = fp,
    )

    private fun detail(c: ConnectionInfo) = rule.setContent {
        ConnectionDetailScreen(ConnectionDetailUiState(c.id, c, null, loading = false), DetailActions())
    }

    @Test
    fun theDetailIsTitledFirstLastWithTheDisplayNameAndTheFingerprint() {
        detail(ada)
        rule.onNodeWithTag("detail_name").assertTextContains("Ada Lovelace")
        rule.onNodeWithTag("detail_display_name").assertTextContains("Countess")
        rule.onNodeWithTag("detail_fingerprint").assertTextContains(fp)
        rule.onNodeWithText("VettID account name").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("verified", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("real name", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anAliasNeverTitlesTheConnection() {
        val withAlias = ada.copy(alias = "Mum")
        assertEquals("Ada Lovelace", withAlias.displayName)
        detail(withAlias)
        rule.onNodeWithTag("detail_name").assertTextContains("Ada Lovelace")
        assertEquals(0, rule.onAllNodesWithText("Mum", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDetailHasNoSafetyCodeAliasNoteEditOrBlock() {
        detail(ada)
        rule.onNodeWithTag("fingerprint_card").assertExists()
        for (gone in listOf("Safety code", "Alias", "Your notes", "Edit alias and note", "Block")) {
            assertEquals(gone, 0, rule.onAllNodesWithText(gone, substring = true).fetchSemanticsNodes().size)
            assertEquals(gone, 0, rule.onAllNodesWithContentDescription(gone, substring = true).fetchSemanticsNodes().size)
        }
        rule.onNodeWithContentDescription("Remove").assertExists()
        rule.onNodeWithContentDescription("History", substring = true).assertExists()
    }

    @Test
    fun beforeTheFirstProfileTheTitleIsAPlaceholderNeverTheDisplayName() {
        detail(ConnectionInfo("c2", "Riley", ConnectionState.ACTIVE))
        rule.onNodeWithTag("detail_name").assertTextContains("Name not shared yet")
        rule.onNodeWithTag("detail_display_name").assertTextContains("Riley")
        assertEquals(0, rule.onAllNodesWithText("Unnamed", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theListShowsTheTitleAndTheDisplayNameSecondary() {
        rule.setContent {
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = listOf(ada, ConnectionInfo("c2", "", ConnectionState.ACTIVE))),
                ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {}),
                ConnectionsActions(),
            )
        }
        rule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        rule.onNodeWithText("Countess", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Name not shared yet").assertIsDisplayed()
    }

    @Test
    fun aRequestShowsTheRequestersAccountName() {
        rule.setContent {
            ConnectionRequestContent(
                name = "Morgan Lee", displayName = "Mo", sas = "042817", remote = false, busy = false, error = null,
                onApprove = {}, onDecline = {}, onBlock = {}, onBack = {},
            )
        }
        rule.onNodeWithTag("request_name").assertTextContains("Morgan Lee")
        rule.onNodeWithText("Name on their VettID account").assertIsDisplayed()
        rule.onNodeWithText("Display name they chose: \u2068Mo\u2069").assertIsDisplayed()
    }
}
