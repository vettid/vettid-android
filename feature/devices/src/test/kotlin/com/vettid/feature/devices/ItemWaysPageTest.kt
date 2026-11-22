// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWaysRules
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** What AA7 draws: the agent page's items, the item's page in each of its states, the confirmations. */
@RunWith(RobolectricTestRunner::class)
class ItemWaysPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val sha = "1a2b3c4d5e6f7a8b" + "0".repeat(48)
    private val all = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","run","proxy","reveal"],"run":{"programs":[{"program":"/usr/bin/git","sha256":"$sha","path":"/usr/bin/git","env":["GH_HOST"]},{"program":"C:/tools/build.cmd","env":[]}],"each":false},"proxy":{"hosts":["api.example.com"],"header":"Authorization","prefix":"Bearer "}}"""

    private fun page(json: String, f: (ItemWaysUiState) -> ItemWaysUiState = { it }): ItemWaysUiState {
        val r = ItemWaysRules.one(json.toByteArray())!!
        return f(ItemWaysUiState(agentName = "Claude", itemName = "GitHub token", loading = false, read = r, draft = r.ways))
    }

    private fun show(state: ItemWaysUiState, actions: ItemWaysActions = ItemWaysActions()) =
        rule.setContent { ItemWaysScreen(state, actions) }

    @Test
    fun theAgentsPageListsItsItemsWithTheirWays() {
        val agent = DeviceInfo("a1", "agent", "Claude", null, Instant.EPOCH, null, null)
        var opened: String? = null
        val rows = listOf(
            WayItemRow("i1", "GitHub token", true, listOf(ItemWay.USE, ItemWay.RUN, ItemWay.REVEAL)),
            WayItemRow("i2", null, true, listOf(ItemWay.USE)),
            WayItemRow("i3", null, false, listOf(ItemWay.USE)),
        )
        rule.setContent {
            DevicePageScreen(
                DeviceUiState(device = agent, loaded = true, rulesLoaded = true, itemWays = true, wayItems = rows, wayItemsLoaded = true),
                DevicePageActions(onOpenWayItem = { opened = it }),
            )
        }
        rule.onNodeWithText("Items it may use").performScrollTo()
        rule.onNodeWithTag("way_item_i1").assertTextContains("GitHub token", substring = true)
            .assertTextContains("Use without seeing it · Run programs · May be shown", substring = true)
        rule.onNodeWithTag("way_item_i2").assertTextContains("An item you no longer have", substring = true)
        rule.onNodeWithTag("way_item_i3").assertTextContains("Loading…", substring = true)
        rule.onNodeWithTag("way_item_i1").performSemanticsAction(SemanticsActions.OnClick) // the pill overlays the bottom
        assertEquals("i1", opened)
    }

    @Test
    fun noItemsCardWithoutItemWays() {
        val agent = DeviceInfo("a1", "agent", "Claude", null, Instant.EPOCH, null, null)
        rule.setContent { DevicePageScreen(DeviceUiState(device = agent, loaded = true, itemWays = false), DevicePageActions()) }
        rule.onNodeWithTag("way_items_card").assertDoesNotExist()
    }

    @Test
    fun aPageWithEveryWayOn() {
        show(page(all))
        rule.onNodeWithTag("ways_title").assertTextContains("How Claude may use GitHub token")
        rule.onNodeWithTag("ways_use_switch").assertIsOn().assertIsNotEnabled()
        rule.onNodeWithTag("ways_run_switch").assertIsOn()
        rule.onNodeWithTag("ways_proxy_switch").assertIsOn()
        rule.onNodeWithTag("ways_reveal_switch").assertIsOn()
        rule.onNodeWithTag("ways_program_0").performScrollTo().assertTextContains("GH_HOST", substring = true)
        // The fingerprint line is its own tap target (all 64 digits).
        rule.onNodeWithText("Fingerprint recorded: 1a2b 3c4d 5e6f 7a8b, at /usr/bin/git").assertExists()
        // Ask me before each run is off: the #636 advice on git, the batch-file line on build.cmd.
        rule.onNodeWithTag("ways_program_0").assertTextContains("git can run other commands given in its arguments", substring = true)
        rule.onNodeWithTag("ways_program_1").performScrollTo().assertTextContains("This is a Windows batch file.", substring = true)
            .assertTextContains("No fingerprint yet.", substring = true)
        rule.onNodeWithTag("ways_each_off_help").performScrollTo()
        rule.onNodeWithTag("ways_sent_as").performScrollTo().assertTextContains("Sent as: Authorization: Bearer␣••••••••")
        rule.onNodeWithText("The key is sent only to these hosts, over a verified connection. The agent is not given the key.").assertExists()
        // Nothing changed: Save is off.
        rule.onNodeWithTag("ways_save").assertIsNotEnabled()
    }

    @Test
    fun aPageWithNoSettings() {
        show(page("""{"agent_id":"a1","item_id":"i1","ways":["use"]}"""))
        rule.onNodeWithTag("ways_run_switch").assertIsOff()
        rule.onNodeWithTag("ways_run_card").assertDoesNotExist()
        rule.onNodeWithTag("ways_proxy_card").assertDoesNotExist()
    }

    @Test
    fun aReadOnlyPage() {
        val json = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","reveal","teleport"]}"""
        show(page(json))
        rule.onNodeWithTag("ways_read_only").assertTextContains("This item has settings this version of VettID can't show. Update VettID to change them.")
        rule.onNodeWithTag("ways_save").assertDoesNotExist()
        rule.onNodeWithTag("ways_reveal_switch").assertIsOn()
        // A way that is off cannot be turned on.
        rule.onNodeWithTag("ways_run_switch").assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun revealOnConfirm() = confirmShows(
        WaysConfirm.RevealOn,
        "Let Claude see GitHub token?",
        "You are asked each time before it sees the value.",
        "It may also reach the AI service that the agent's app uses.",
    )

    @Test
    fun runOffConfirm() = confirmShows(WaysConfirm.RunOff, "Stop runs with GitHub token?", "The programs you listed, their variables and their recorded fingerprints are removed.")

    @Test
    fun proxyOffConfirm() = confirmShows(WaysConfirm.ProxyOff, "Stop sending GitHub token to an API?", "The hosts and the header you set are removed.")

    @Test
    fun eachOffConfirm() = confirmShows(WaysConfirm.EachOff, "Stop asking before each run?", "Claude chooses the arguments each time")

    @Test
    fun removeLastConfirm() = confirmShows(WaysConfirm.RemoveLastProgram(0), "Remove the last program?", "With no programs listed, Claude may ask to run any program")

    @Test
    fun forgetPinConfirm() = confirmShows(WaysConfirm.ForgetPin(0), "Forget this fingerprint?", "The next run of git asks you again")

    private fun confirmShows(c: WaysConfirm, vararg texts: String) {
        show(page(all) { it.copy(confirm = c) })
        texts.forEach { t -> rule.onNodeWithText(t, substring = true).assertExists() }
    }

    @Test
    fun thePasswordPromptForAWidening() {
        show(page(all) { it.copy(prompt = WaysPrompt()) })
        rule.onNodeWithText(
            "Letting Claude do more with GitHub token needs your credential password, so only you can do it. Enter your credential password.",
        ).assertExists()
        rule.onNodeWithTag("ways_password_confirm").assertIsNotEnabled()
    }

    @Test
    fun theAlarmOnThePrompt() {
        show(page(all) { it.copy(prompt = WaysPrompt(error = com.vettid.core.data.vault.FailureKind.CREDENTIAL_FROZEN)) })
        rule.onNodeWithText("Settings can't be widened while a credential alarm is open").assertExists()
        rule.onNodeWithTag("ways_alarm").assertExists()
    }

    @Test
    fun aProgramsPage() {
        show(page(all) { it.copy(openProgram = 0, editInput = "/usr/bin/git") })
        rule.onNodeWithTag("ways_program_page").assertExists()
        rule.onNodeWithText("Variables it may receive").assertExists()
        rule.onNodeWithTag("ways_variable_GH_HOST").assertExists()
        rule.onNodeWithText("Changing the program forgets its fingerprint.").assertExists()
        rule.onNodeWithTag("ways_pin_forget").assertExists()
    }

    @Test
    fun theFullFingerprint() {
        show(page(all) { it.copy(fullPin = sha) })
        rule.onNodeWithTag("ways_pin_full").assertTextContains("1a2b 3c4d 5e6f 7a8b 0000", substring = true)
    }

    @Test
    fun changesElsewhereAndNotIncluded() {
        show(page(all) { it.copy(changedElsewhere = true, notIncluded = true) })
        rule.onNodeWithText("Another device changed these settings.").assertExists()
        rule.onNodeWithText("Show the current ones").assertExists()
        rule.onNodeWithText("Claude can no longer use this item: none of its rules includes it.").assertExists()
    }

    @Test
    fun anEscapedCharacterIsShownAndSpoken() {
        val rlo = String(Character.toChars(0x202E))
        rule.setContent { ShownStringBox("g" + rlo + "it") }
        rule.onNodeWithTag("shown_string").assertTextContains("U+202E", substring = true)
        rule.onNodeWithTag("shown_string", useUnmergedTree = true).assertContentDescriptionContains("escaped character U+202E", substring = true)
    }
}
