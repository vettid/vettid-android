package com.vettid.feature.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Received names in the connect flow and the detail's profile card (VAULT-MESSAGING §10.8 "Showing received text"):
 * cleaned and isolated in a sentence, and a name that shows nothing (U+2800, U+3164) falls back like an empty one.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectFlowNamesTest {
    @get:Rule
    val rule = createComposeRule()

    private val blanks = listOf("\u2800", "\u3164", "\u2800 \u3164")
    private val sam = "\u2068Sam Lee\u2069"
    private val namesNote = "The name they gave VettID for their account. VettID does not check it against any ID."

    @Test
    fun theConnectedSentenceCleansAndIsolatesTheName() {
        // An override after a closing isolate would reorder the sentence if the name went in raw.
        rule.setContent { Connected(name = "Bank\u2069\u202E", onMessage = {}, onDone = {}) }
        rule.onNodeWithText("You and \u2068Bank\u2069 can now message each other through your vaults.").assertIsDisplayed()
    }

    @Test
    fun aConnectedNameThatShowsNothingReadsAsUnnamed() {
        var blank by mutableStateOf(blanks.first())
        rule.setContent { Connected(name = blank, onMessage = {}, onDone = {}) }
        for (b in blanks) {
            blank = b
            rule.waitForIdle()
            rule.onNodeWithText("You can now message each other through your vaults.").assertIsDisplayed()
        }
    }

    @Test
    fun anInvitationNameThatShowsNothingFallsBackToTheConnectionsName() {
        var blank by mutableStateOf(blanks.first())
        rule.setContent {
            AcceptScreen(AcceptUiState(step = AcceptStep.EXISTS, name = blank, connectionName = "Sam Lee"), AcceptActions())
        }
        for (b in blanks) {
            blank = b
            rule.waitForIdle()
            rule.onNodeWithText("connected to $sam,", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun aRequestNameThatShowsNothingIsNotSharedYetWithoutTheNamesNote() {
        var blank by mutableStateOf(blanks.first())
        rule.setContent {
            ConnectionRequestContent(
                name = blank, sas = "042817", remote = false, busy = false, error = null,
                onApprove = {}, onDecline = {}, onBlock = {}, onBack = {},
            )
        }
        for (b in blanks) {
            blank = b
            rule.waitForIdle()
            rule.onNodeWithTag("request_name").assertTextEquals("Name not shared yet")
            assertEquals(b, 0, rule.onAllNodesWithText(namesNote).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aDeclineNameThatShowsNothingFallsBackToTheRequestsName() {
        val request = Approval.ConnectionRequest("p1", "i1", "042817", false, "Sam Lee", null, Instant.EPOCH, null)
        var blank by mutableStateOf(blanks.first())
        rule.setContent {
            InviteScreen(InviteUiState(step = InviteStep.DECLINED, request = request, declinedName = blank), InviteActions())
        }
        for (b in blanks) {
            blank = b
            rule.waitForIdle()
            rule.onNodeWithText("$sam declined the connection").assertIsDisplayed()
        }
    }

    @Test
    fun aProfileWhoseNamesShowNothingIsNoProfile() {
        var blank by mutableStateOf(blanks.first())
        rule.setContent {
            val c = ConnectionInfo("c1", blank, ConnectionState.ACTIVE, firstName = blank, lastName = blank)
            ConnectionDetailScreen(ConnectionDetailUiState(c.id, c, null, loading = false), DetailActions())
        }
        for (b in blanks) {
            blank = b
            rule.waitForIdle()
            rule.onNodeWithText("Their vault has not sent its profile yet.").assertIsDisplayed()
            assertEquals(b, 0, rule.onAllNodesWithText("VettID account name").fetchSemanticsNodes().size)
            assertEquals(b, 0, rule.onAllNodesWithText("They chose this themselves", substring = true).fetchSemanticsNodes().size)
        }
    }
}
