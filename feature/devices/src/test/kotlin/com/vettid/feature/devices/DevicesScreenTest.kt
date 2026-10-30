package com.vettid.feature.devices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.ListLoad
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/** The list and a device's page (ANDROID-PLAN 0.1.53 AA1), rendered as the member sees them. */
@RunWith(RobolectricTestRunner::class)
class DevicesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {})

    // A fixed instant in the middle of a day in every zone: "today" is the 21st wherever the tests run.
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val ik = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val phone = DeviceInfo("me", "app", "Pixel", null, null, null, null, thisPhone = true)
    private val agent = DeviceInfo(
        "a1", "agent", "Claude", ik, now.minusSeconds(86_400), now.minusSeconds(600), now.plusSeconds(1_800), paused = true,
    )
    private val desktop = DeviceInfo("d1", "desktop", "Laptop", ik, now.minusSeconds(172_800), null, null)

    private fun list(state: DevicesUiState) = rule.setContent { DevicesScreen(state, chrome, now = now, onOpen = {}) }

    private fun state(vararg d: DeviceInfo) = DevicesUiState(devices = d.toList(), load = ListLoad.LOADED)

    @Test
    fun theListShowsThisPhoneAgentsAndDesktopsWithAccessAndActivity() {
        list(state(phone, agent, desktop))
        rule.onNodeWithText("This phone").assertIsDisplayed()
        rule.onNodeWithText("Claude").assertIsDisplayed()
        rule.onNodeWithText("Agent").assertIsDisplayed()
        rule.onNodeWithText("Desktop").assertIsDisplayed()
        rule.onNodeWithTag("device_a1").assertTextContains("Last active 10 min ago", substring = true)
        rule.onNodeWithText("No access").assertIsDisplayed()
        rule.onNodeWithText("Not active yet").assertIsDisplayed()
        rule.onNodeWithTag("paused_chip", useUnmergedTree = true).assertExists()
        // A desktop is never "Paused".
        assertEquals(1, rule.onAllNodesWithText("Paused").fetchSemanticsNodes().size)
    }

    @Test
    fun thePairButtonIsThereWithOnlyThisPhoneAndWithAgents() {
        var pairs = 0
        rule.setContent { DevicesScreen(state(phone), chrome, now = now, onPair = { pairs++ }) }
        rule.onNodeWithContentDescription("Pair an agent").performClick()
        assertEquals(1, pairs)
    }

    @Test
    fun theListShowsThePairButtonBesideAgents() {
        list(state(phone, agent))
        rule.onNodeWithTag("pair_agent").assertIsDisplayed()
    }

    @Test
    fun aSessionPastItsEndIsNoAccess() {
        list(state(phone, agent.copy(sessionExpiresAt = now.minusSeconds(1))))
        rule.onNodeWithText("No access").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Access until", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun thisPhoneAloneSaysNoAgentsYet() {
        list(state(phone))
        rule.onNodeWithText("No agents yet").assertIsDisplayed()
        rule.onNodeWithText("This phone").assertIsDisplayed()
    }

    @Test
    fun aDeviceWithoutANameIsNamedByItsRole() {
        list(state(phone, agent.copy(name = "", paused = false)))
        rule.onNodeWithText("An agent").assertIsDisplayed()
    }

    private fun lookalike(name: String) {
        list(state(phone, agent.copy(id = "x1", name = name, paused = false)))
        rule.onNodeWithText("$name (Agent)").assertIsDisplayed()
    }

    @Test
    fun lookalikeWithTwoSpacesKeepsItsRole() = lookalike("This  phone")

    @Test
    fun lookalikeWithNoBreakSpaceKeepsItsRole() = lookalike("This\u00A0phone")

    @Test
    fun lookalikeVettIdWithTrailingZwjKeepsItsRole() = lookalike("VettID\u200D")

    @Test
    fun lookalikeVettIdWithInnerZwjKeepsItsRole() = lookalike("Vett\u200DID")

    @Test
    fun lookalikeWithHangulFillerKeepsItsRole() = lookalike("This phone\u3164")

    @Test
    fun aNameThatCouldPassForThePhoneOrVettIdIsShownWithItsRole() {
        list(state(phone, agent.copy(id = "a8", name = "this PHONE", paused = false), desktop.copy(id = "d8", name = "VettID")))
        rule.onNodeWithText("this PHONE (Agent)").assertIsDisplayed()
        rule.onNodeWithText("VettID (Desktop)").assertIsDisplayed()
    }

    @Test
    fun aFailedActionHasItsOwnTitle() {
        page(DeviceUiState(device = agent, loaded = true, failure = DeviceFailure.END_ACCESS))
        rule.onNodeWithText("Access not ended").assertIsDisplayed()
    }

    @Test
    fun aFailedRemovalTitleIsNotLoadingYourDevices() {
        page(DeviceUiState(device = agent, loaded = true, failure = DeviceFailure.REMOVE))
        rule.onNodeWithText("Not removed").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Could not load", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aPageThatCannotReadTheListOffersRetry() {
        var retried = 0
        page(
            DeviceUiState(loadError = com.vettid.core.data.vault.FailureKind.NETWORK),
            DevicePageActions(onRetry = { retried++ }),
        )
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun anotherAppEntryOffersNeitherEndAccessNorRemove() {
        val old = DeviceInfo("app2", "app", "Old phone", ik, null, null, now.plusSeconds(600))
        page(DeviceUiState(device = old, loaded = true))
        rule.onNodeWithText("Phone").assertExists()
        assertEquals(0, rule.onAllNodesWithText("End access").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithContentDescription("Remove").fetchSemanticsNodes().size)
    }

    @Test
    fun aDeviceWithoutANameHasNoHistoryButton() {
        page(DeviceUiState(device = agent.copy(name = ""), loaded = true))
        assertEquals(0, rule.onAllNodesWithContentDescription("History").fetchSemanticsNodes().size)
    }

    @Test
    fun aRemovedDeviceNamedLikeThePhoneIsNeverToldAsThePhone() {
        list(state(phone, agent).copy(removedDevice = desktop.copy(name = "This phone")))
        rule.onNodeWithText("This phone (Desktop)", substring = true).assertIsDisplayed()
    }

    @Test
    fun theRemovedNameIsToldInASnackbar() {
        list(state(phone, agent).copy(removedDevice = agent.copy(name = "Old agent")))
        // The name sits in bidirectional isolation marks.
        rule.onNodeWithText("was removed", substring = true).assertIsDisplayed()
    }

    private fun page(state: DeviceUiState, actions: DevicePageActions = DevicePageActions()) =
        rule.setContent { DevicePageScreen(state, actions, now = now) }

    @Test
    fun anAgentsPageShowsItsFactsTheAccessAndTheRule() {
        page(DeviceUiState(device = agent, loaded = true))
        rule.onNodeWithTag("device_name").assertTextContains("Claude")
        rule.onNodeWithText("Name given by the agent").assertIsDisplayed()
        rule.onNodeWithTag("device_fingerprint").assertExists()
        rule.onNodeWithTag("access_status").assertTextContains("Access until", substring = true)
        rule.onNodeWithTag("end_access").assertIsDisplayed()
        rule.onNodeWithText("Agents act only while you allow access, and only within the permissions you give them.").assertIsDisplayed()
        rule.onNodeWithContentDescription("History").assertExists()
        rule.onNodeWithContentDescription("Remove").assertExists()
    }

    @Test
    fun withoutAccessThePageSaysHowTheAgentAsksAndOffersNoEnd() {
        page(DeviceUiState(device = agent.copy(sessionExpiresAt = null), loaded = true))
        rule.onNodeWithText("No access. When the agent needs access, it asks you in Approvals.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("End access").fetchSemanticsNodes().size)
    }

    @Test
    fun aDesktopsPageHasNoAgentRuleAndSaysDesktop() {
        page(DeviceUiState(device = desktop, loaded = true))
        rule.onNodeWithText("Name given by the desktop").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Agents act only", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun endAccessAsksFirst() {
        var asked = 0
        page(DeviceUiState(device = agent, loaded = true), DevicePageActions(onEndAccess = { asked++ }))
        rule.onNodeWithTag("end_access").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun theEndAccessConfirmationNamesTheDevice() {
        page(DeviceUiState(device = agent, loaded = true, confirm = DeviceConfirm.END_ACCESS))
        rule.onNodeWithText(
            "It stops at once, and any of its requests still waiting for you are declined. It can ask for access again; you decide then.",
        ).assertIsDisplayed()
    }

    @Test
    fun removingAnAgentSaysHowLongServicesMayStillAcceptIt() {
        page(DeviceUiState(device = agent, loaded = true, confirm = DeviceConfirm.REMOVE, removalBoundSeconds = 960))
        rule.onNodeWithText("Services it showed a permission to may accept it for up to 16 minutes.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("To use this agent again, pair it again.", substring = true).assertIsDisplayed()
    }

    @Test
    fun withNoPermissionLeftTheBoundIsNotMentioned() {
        page(DeviceUiState(device = agent, loaded = true, confirm = DeviceConfirm.REMOVE, removalBoundSeconds = 0))
        assertEquals(0, rule.onAllNodesWithText("Services it showed", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Its permissions and its share rules are deleted.", substring = true).assertIsDisplayed()
    }

    @Test
    fun removingADesktopHasItsOwnText() {
        page(DeviceUiState(device = desktop, loaded = true, confirm = DeviceConfirm.REMOVE))
        rule.onNodeWithText(
            "It loses access to your vault at once and cannot connect again. To use it again, pair it again.",
        ).assertIsDisplayed()
    }

    @Test
    fun aFailedRemovalSaysItWasNotRemoved() {
        page(DeviceUiState(device = agent, loaded = true, failure = DeviceFailure.REMOVE))
        // The name sits in bidirectional isolation marks inside the sentence.
        rule.onNodeWithText("could not be removed. Try again.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theHistoryActionCarriesTheDevicesName() {
        var query: String? = null
        page(DeviceUiState(device = agent, loaded = true), DevicePageActions(onHistory = { query = it }))
        rule.onNodeWithContentDescription("History").performClick()
        assertEquals("Claude", query)
    }

    @Test
    fun theTimeOfTheSessionIsInTheZoneOfThePhoneWithTheDateWhenNotToday() {
        // Not today: the date comes with the time. (The zone is the phone's; here the default.)
        val later = agent.copy(sessionExpiresAt = now.plusSeconds(3 * 86_400))
        page(DeviceUiState(device = later, loaded = true))
        val year = later.sessionExpiresAt!!.atZone(ZoneId.systemDefault()).year.toString()
        rule.onNodeWithTag("access_status").assertTextContains(year, substring = true)
    }
}
