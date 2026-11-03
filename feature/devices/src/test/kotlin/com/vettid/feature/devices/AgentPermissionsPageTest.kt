// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.devices.AgentGrant
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.GrantApproval
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** What an agent's page shows for its other permissions, the paused banner and the remove confirmation (ANDROID-PLAN 0.1.116 §4 AA3). */
@RunWith(RobolectricTestRunner::class)
class AgentPermissionsPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val agent = DeviceInfo("a1", "agent", "Claude", null, now.minusSeconds(86_400), null, null)
    private val ask = AgentGrant("g1", "connection.list", 1, GrantApproval.ASK, 60, 1_000, null, emptyList(), 900)
    private val auto = AgentGrant(
        "g2", "message.send", 1, GrantApproval.AUTO, 60, 1_000, Instant.parse("2026-12-03T12:00:00Z"), listOf("c1", "c2", "gone"), 3_600,
    )
    private val unknown = AgentGrant("g3", "wallet.send", 1, GrantApproval.ASK, 60, 1_000, null, emptyList(), 900)
    private val names = mapOf("c1" to "Sam Lee", "c2" to "Ana Ruiz")

    /** A name inside a sentence is bidirectionally isolated (§10.8). */
    private fun iso(s: String) = AccountNames.isolate(s)

    private fun show(state: DeviceUiState, actions: DevicePageActions = DevicePageActions()) =
        rule.setContent { DevicePageScreen(state, actions, now = now) }

    private fun state(vararg g: AgentGrant, agent: DeviceInfo = this.agent) =
        DeviceUiState(device = agent, loaded = true, permissions = g.toList(), permissionsLoaded = true, connectionNames = names, connectionsLoaded = true)

    private fun ComposeContentTestRule.count(tag: String) = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun withoutOtherGrantsTheCardIsNotShownAtAll() {
        show(state())
        assertEquals(0, rule.count("permissions_card"))
        rule.onNodeWithText("Other permissions").assertDoesNotExist()
        rule.onNodeWithText("What it may use").assertExists()
    }

    @Test
    fun anAskRowSaysItAsksEachTime() {
        show(state(ask))
        rule.onNodeWithText("Other permissions").assertExists()
        val row = rule.onNodeWithTag("perm_g1")
        row.performScrollTo()
        row.assertTextContains("See your list of connections", substring = true)
        row.assertTextContains("Asks you each time", substring = true)
    }

    @Test
    fun anAutoRowSaysItsLimitsItsEndAndTheNamesOfItsConnections() {
        show(state(auto))
        val row = rule.onNodeWithTag("perm_g2")
        row.performScrollTo()
        row.assertTextContains("Send messages for you", substring = true)
        row.assertTextContains("Allowed automatically, up to 60 an hour and 1,000 a day without asking", substring = true)
        row.assertTextContains(", until ", substring = true)
        row.assertTextContains("2026", substring = true)
        row.assertTextContains(", only with ${iso("Sam Lee")}, ${iso("Ana Ruiz")}, Removed connection", substring = true)
    }

    @Test
    fun anApprovalThisAppDoesNotKnowSaysNothingAboutHowItIsAllowed() {
        show(state(ask.copy(approval = GrantApproval.UNKNOWN, expiresAt = Instant.parse("2026-12-03T12:00:00Z"))))
        val row = rule.onNodeWithTag("perm_g1")
        row.performScrollTo()
        row.assertTextContains("See your list of connections", substring = true)
        row.assertTextContains("until ", substring = true)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Asks you each time", substring = true)).fetchSemanticsNodes().size)
        assertEquals("the line starts without a comma", 0, rule.onAllNodes(androidx.compose.ui.test.hasText(", until", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnknownApprovalWithNothingElseHasNoSecondLine() {
        show(state(ask.copy(approval = GrantApproval.UNKNOWN)))
        rule.onNodeWithTag("perm_g1").performScrollTo()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Asks you each time", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithText("See your list of connections").assertExists()
    }

    @Test
    fun beforeTheConnectionsAreReadNoConnectionIsCalledRemoved() {
        show(state(auto).copy(connectionNames = emptyMap(), connectionsLoaded = false))
        val row = rule.onNodeWithTag("perm_g2")
        row.performScrollTo()
        row.assertTextContains("Allowed automatically", substring = true)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Removed connection", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("only with ", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun onceTheConnectionsAreReadAnUnlistedIdIsARemovedConnection() {
        show(state(auto).copy(connectionNames = emptyMap(), connectionsLoaded = true))
        rule.onNodeWithTag("perm_g2").performScrollTo().assertTextContains("only with Removed connection, Removed connection, Removed connection", substring = true)
    }

    @Test
    fun aScopeWithoutALabelIsStillListed() {
        show(state(unknown))
        rule.onNodeWithTag("perm_g3").performScrollTo().assertTextContains("Another permission", substring = true)
    }

    @Test
    fun aConnectionWithoutANameYetIsSaidSo() {
        show(state(auto).copy(connectionNames = mapOf("c1" to "", "c2" to "Ana Ruiz", "gone" to "")))
        rule.onNodeWithTag("perm_g2").performScrollTo().assertTextContains("only with Name not shared yet, ${iso("Ana Ruiz")}, Name not shared yet", substring = true)
    }

    @Test
    fun thereIsNoAddActionAndATapOpensNothing() {
        val calls = mutableListOf<String>()
        show(state(ask), DevicePageActions(onOpenRule = { calls += it }, onNewRule = { calls += "new" }, onRemovePermission = { calls += "remove:$it" }))
        assertEquals(0, rule.count("permission_add"))
        rule.onNodeWithText("Add a permission").assertDoesNotExist()
        rule.onNodeWithTag("perm_g1").performScrollTo()
        assertEquals("a row has no click action", false, rule.onNodeWithTag("perm_g1").fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theMenuOffersRemoveAndAsksForThatGrant() {
        val calls = mutableListOf<String>()
        show(state(ask, auto), DevicePageActions(onRemovePermission = { calls += it }))
        rule.onNodeWithTag("perm_more_g2").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("perm_remove_g2").assertIsDisplayed()
        rule.onNodeWithTag("perm_remove_g2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("g2"), calls)
    }

    @Test
    fun theRemoveConfirmationSaysWhatTheAgentLosesAndForHowLongAServiceMayAcceptIt() {
        show(state(auto).copy(confirm = DeviceConfirm.REMOVE_PERMISSION, confirmGrant = auto))
        rule.onNodeWithText("Remove this permission?").assertIsDisplayed()
        rule.onNodeWithText(
            "${iso("Claude")} can no longer send messages for you from now on. Services it showed this permission to may accept it for up to 61 minutes.",
        ).assertExists()
        rule.onNodeWithTag("confirm_button").assertTextContains("Remove")
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun theConfirmationForAScopeWithoutALabelDoesNotReadAsBrokenEnglish() {
        show(state(unknown).copy(confirm = DeviceConfirm.REMOVE_PERMISSION, confirmGrant = unknown))
        rule.onNodeWithText(
            "${iso("Claude")} loses this permission from now on. Services it showed this permission to may accept it for up to 16 minutes.",
        ).assertExists()
    }

    @Test
    fun confirmAndCancelAreWiredToTheirActions() {
        val calls = mutableListOf<String>()
        show(
            state(ask).copy(confirm = DeviceConfirm.REMOVE_PERMISSION, confirmGrant = ask),
            DevicePageActions(onConfirm = { calls += "confirm" }, onDismissConfirm = { calls += "dismiss" }),
        )
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("confirm_button").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("dismiss", "confirm"), calls)
    }

    @Test
    fun aListThatCouldNotBeReadSaysSoWithTryAgainUntilOneWasRead() {
        val calls = mutableListOf<String>()
        show(
            DeviceUiState(device = agent, loaded = true, permissionsError = FailureKind.NETWORK),
            DevicePageActions(onRetryPermissions = { calls += "retry" }),
        )
        rule.onNodeWithTag("permissions_failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Other permissions could not be loaded.").assertExists()
        rule.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun aFailedReadAfterAGoodOneKeepsTheRowsAndSaysNothing() {
        show(state(ask).copy(permissionsError = FailureKind.NETWORK))
        assertEquals(0, rule.count("permissions_failed"))
        rule.onNodeWithTag("perm_g1").assertExists()
    }

    @Test
    fun aDesktopHasNoPermissionsCard() {
        show(DeviceUiState(device = DeviceInfo("d1", "desktop", "Laptop", null, null, null, null), loaded = true, permissions = listOf(ask), permissionsLoaded = true))
        assertEquals(0, rule.count("permissions_card"))
    }

    @Test
    fun aFailedRemovalSaysItIsNotRemovedAndKeepsTheRow() {
        show(state(ask).copy(failure = DeviceFailure.REMOVE_PERMISSION, error = FailureKind.NETWORK))
        rule.onNodeWithText("That permission could not be removed. Try again.").assertExists()
        rule.onNodeWithText("Not removed").assertExists()
        rule.onNodeWithTag("perm_g1").assertExists()
    }

    // --- the paused banner ---

    @Test
    fun aPausedAgentShowsTheBannerWithItsNameAndResume() {
        val calls = mutableListOf<String>()
        show(state(ask, agent = agent.copy(paused = true)), DevicePageActions(onResume = { calls += "resume" }))
        rule.onNodeWithText("Paused by your vault").assertIsDisplayed()
        rule.onNodeWithText(
            "${iso("Claude")} made too many refused requests in an hour, so your vault paused all of its permissions. Resume it only if you know why this happened.",
        ).assertExists()
        rule.onNodeWithTag("resume").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun noBannerForAnAgentThatIsNotPausedOrForADesktop() {
        show(state(ask))
        assertEquals(0, rule.count("paused_banner"))
    }

    @Test
    fun resumeIsOffWhileARequestIsRunning() {
        show(state(ask, agent = agent.copy(paused = true)).copy(busy = true))
        rule.onNodeWithTag("resume").assertIsNotEnabled()
    }

    private fun noticeIsToldOnce(notice: PermissionNotice, text: String) {
        var shown = 0
        show(state(ask).copy(notice = notice), DevicePageActions(onNoticeShown = { shown++ }))
        rule.waitForIdle()
        rule.onNodeWithText(text).assertExists()
        assertEquals(1, shown)
    }

    @Test
    fun resumedIsToldOnceAsASnackbar() = noticeIsToldOnce(PermissionNotice.RESUMED, "Resumed")

    @Test
    fun noLongerPausedIsToldOnceAsASnackbar() = noticeIsToldOnce(PermissionNotice.NOT_PAUSED, "It is no longer paused")

    @Test
    fun removedIsToldOnceAsASnackbar() = noticeIsToldOnce(PermissionNotice.REMOVED, "Permission removed")

    @Test
    fun alreadyRemovedIsToldOnceAsASnackbar() = noticeIsToldOnce(PermissionNotice.ALREADY_REMOVED, "It was already removed.")
}
