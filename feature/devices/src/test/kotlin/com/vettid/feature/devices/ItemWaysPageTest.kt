// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.FolderProblem
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWaysRules
import com.vettid.core.data.devices.RunSettings
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.ui.components.ShownStringBox
import com.vettid.core.ui.text.ShownString
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What AA7 draws: the agent page's items, the item's page in each of its states, the confirmations. */
@RunWith(RobolectricTestRunner::class)
class ItemWaysPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val sha = "1a2b3c4d5e6f7a8b" + "0".repeat(48)
    private val all = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","run","proxy","reveal"],"run":{"programs":[{"program":"/usr/bin/git","sha256":"$sha","path":"/usr/bin/git","env":["GH_HOST"]},{"program":"C:/tools/build.cmd","env":[]}],"each":false},"proxy":{"hosts":["api.example.com"],"header":"Authorization","prefix":"Bearer "}}"""

    private val withFolders = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","run"],"run":{"programs":[{"program":"git","env":[]}],"each":true,"folders":[{"path":"/home/me/src","write":false},{"path":"/home/me/out","write":true}]}}"""

    private fun page(json: String, f: (ItemWaysUiState) -> ItemWaysUiState = { it }): ItemWaysUiState {
        val r = ItemWaysRules.one(json.toByteArray())!!
        return f(ItemWaysUiState(agentName = "Claude", itemName = "GitHub token", read = r, draft = r.ways))
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
        rule.onNodeWithText("Fingerprint recorded: 1a2b 3c4d 5e6f 7a8b, at ${ShownString.inline("/usr/bin/git", "(empty)")}").assertExists()
        // Ask me before each run is off: the #636 advice on git, the batch-file line on build.cmd.
        rule.onNodeWithTag("ways_program_0").assertTextContains("${ShownString.inline("git", "(empty)")} can run other commands given in its arguments", substring = true)
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
    fun forgetPinConfirm() = confirmShows(WaysConfirm.ForgetPin(0), "Forget this fingerprint?", "The next run of ${ShownString.inline("git", "(empty)")} asks you again")

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

    /** AA7 "Saving" (0.1.138): the vault's message inside the sentence, or the fallback; announced, on the page. */
    @Test
    fun aRefusedSaveShowsTheVaultsReasonOrTheFallback() {
        var state by mutableStateOf(page(all) { it.copy(refused = "run.programs[0].env[1]: VETTID_IN is reserved") })
        rule.setContent { ItemWaysScreen(state, ItemWaysActions()) }
        rule.onNodeWithTag("ways_refused")
            .assertTextEquals("These settings could not be saved. Your vault answered: run.programs[0].env[1]: VETTID_IN is reserved.")
        state = state.copy(refused = "")
        rule.onNodeWithTag("ways_refused").assertTextEquals(
            "These settings could not be saved: your vault refused one of them. Check the programs, the variables, the folders, the hosts, the header name and the prefix.",
        )
        state = state.copy(refused = null)
        rule.onNodeWithTag("ways_refused").assertDoesNotExist()
    }

    /** An answer with `run.folders`: "Stop runs" says the folders go too. */
    @Test
    fun stopRunsNamesTheFolders() {
        show(page(withFolders) { it.copy(confirm = WaysConfirm.RunOff) })
        rule.onNodeWithText("The programs you listed, their variables, their recorded fingerprints and the folders runs may use are removed.").assertExists()
    }

    /** A re-read that failed while settings are shown says so, with Try again. */
    @Test
    fun aFailedReloadIsShown() {
        var retried = false
        show(page(all) { it.copy(loadError = VaultFailure(FailureKind.NETWORK)) }, ItemWaysActions(onRetry = { retried = true }))
        rule.onNodeWithTag("ways_reload_failed").assertExists()
        rule.onNodeWithText("These settings could not be loaded.").assertExists()
        rule.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, retried)
    }

    /** The #636 advice shows only while Ask me before each run is off; the switch is off-limits with no program. */
    @Test
    fun theAdviceAndTheAskMeSwitch() {
        val git = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","run"],"run":{"programs":[{"program":"git","env":[]}],"each":true}}"""
        var state by mutableStateOf(page(git))
        rule.setContent { ItemWaysScreen(state, ItemWaysActions()) }
        rule.onNodeWithTag("ways_runs_commands", useUnmergedTree = true).assertDoesNotExist()
        state = state.copy(draft = state.draft!!.copy(run = state.draft!!.run!!.copy(each = false)))
        rule.onNodeWithTag("ways_runs_commands", useUnmergedTree = true).assertExists()
        state = state.copy(draft = state.draft!!.copy(run = RunSettings(emptyList(), each = true)))
        rule.onNodeWithTag("ways_each_switch").performScrollTo().assertIsNotEnabled()
    }

    /** A read-only page that cannot send a turn-off offers no switch. */
    @Test
    fun aReadOnlyPageThatCannotTurnOffOffersNoSwitch() {
        val dup = """{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","reveal"],"x":1,"x":2}"""
        show(page(dup) { it.copy(draft = null) })
        rule.onNodeWithTag("ways_reveal_switch").assertIsNotEnabled()
    }

    @Test
    fun theFieldsTurnOffCapitalsAndAutocorrect() {
        assertEquals(androidx.compose.ui.text.input.KeyboardCapitalization.None, PLAIN.capitalization)
        assertEquals(false, PLAIN.autoCorrectEnabled)
    }

    /** Another agent's item: no turn-off can be sent for it, so no switch is offered. */
    @Test
    fun anotherAgentsItemOffersNoSwitch() {
        val other = """{"items":[{"agent_id":"a2","item_id":"i1","version":3,"ways":["use","reveal"]}]}"""
        val r = ItemWaysRules.list(other.toByteArray(), agentId = "a1")!!.single()
        show(ItemWaysUiState(agentName = "Claude", itemName = "GitHub token", read = r))
        rule.onNodeWithTag("ways_reveal_switch").assertIsNotEnabled()
    }

    /** AA9: the folders under the programs, each Read only or Can change files, with Remove and "Add a folder". */
    @Test
    fun theFoldersCard() {
        var removed = -1
        var opened = -1
        show(page(withFolders), ItemWaysActions(onRemoveFolder = { removed = it }, onOpenFolder = { opened = it }))
        rule.onNodeWithTag("ways_folders_card").performScrollTo()
        rule.onNodeWithText("Folders for runs with this item").assertExists()
        rule.onNodeWithText("The agent can run these programs only in the folders listed here", substring = true).assertExists()
        rule.onNodeWithTag("ways_folder_0").assertTextContains("Read only")
        rule.onNodeWithTag("ways_folder_1").assertTextContains("Can change files")
        rule.onNodeWithTag("ways_folder_remove_1").performScrollTo().performClick()
        assertEquals(1, removed)
        rule.onNodeWithTag("ways_folder_0").performScrollTo().performClick()
        assertEquals(0, opened)
        rule.onNodeWithText("Folder path on your computer").assertExists()
        rule.onNodeWithText("A full path, exactly as your computer shows it", substring = true).assertExists()
    }

    /** No folders section without the vault's `run.folders`. */
    @Test
    fun noFoldersCardWithoutRunFolders() {
        show(page(all))
        rule.onNodeWithTag("ways_folders_card").assertDoesNotExist()
    }

    /** Each refusal's text under the field. */
    @Test
    fun aRefusedFolderSaysWhy() {
        val texts = mapOf(
            FolderProblem.NOT_FULL_PATH to "Give a full path.",
            FolderProblem.WHOLE_DRIVE to "Pick a folder, not a whole drive.",
            FolderProblem.DUPLICATE to "This folder is already in the list.",
            FolderProblem.NESTED to "This folder is inside another folder in the list, or has one inside it. List only one of them.",
            FolderProblem.TOO_MANY to "You can add at most 16 folders.",
            FolderProblem.DOTS_ONLY to "A part of this path is only dots and spaces. Give the folder's real name.",
        )
        var state by mutableStateOf(page(withFolders))
        rule.setContent { ItemWaysScreen(state, ItemWaysActions()) }
        for ((p, text) in texts) {
            state = page(withFolders) { it.copy(folderInput = "/x", folderProblem = p) }
            rule.onNodeWithTag("ways_folder_input").performScrollTo()
            rule.onNodeWithText(text).assertExists()
        }
    }

    /** At 16 folders the field gives way to the limit's line. */
    @Test
    fun sixteenFoldersAddNoMore() {
        val sixteen = (1..16).joinToString(",") { """{"path":"/f$it","write":false}""" }
        show(page("""{"agent_id":"a1","item_id":"i1","version":3,"ways":["use","run"],"run":{"programs":[],"each":true,"folders":[$sixteen]}}"""))
        rule.onNodeWithTag("ways_folder_15").assertExists()
        rule.onNodeWithTag("ways_folder_input").assertDoesNotExist()
        rule.onNodeWithText("You can add at most 16 folders.").assertExists()
    }

    /** A folder's page: the switch (off for a read-only folder), its line, what it covers, Remove. */
    @Test
    fun aFoldersPage() {
        var write: Pair<Int, Boolean>? = null
        show(page(withFolders) { it.copy(openFolder = 0) }, ItemWaysActions(onFolderWrite = { i, w -> write = i to w }))
        rule.onNodeWithTag("ways_folder_page").assertExists()
        rule.onNodeWithTag("ways_folders_card").assertDoesNotExist()
        rule.onNodeWithText("Also covers every folder inside it.").assertExists()
        rule.onNodeWithText("A program run here could change or delete files in this folder", substring = true).assertExists()
        rule.onNodeWithTag("ways_folder_write_switch").assertIsOff().performClick()
        assertEquals(0 to true, write)
        rule.onNodeWithTag("ways_folder_page_remove").assertExists()
    }

    /** Back on a folder's page closes the page, not the screen. */
    @Test
    fun backOnAFoldersPageClosesIt() {
        var closed = 0
        var left = 0
        show(page(withFolders) { it.copy(openFolder = 1) }, ItemWaysActions(onCloseFolder = { closed++ }, onBack = { left++ }))
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        assertEquals(1, closed)
        assertEquals(0, left)
    }
}
