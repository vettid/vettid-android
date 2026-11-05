// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.items.FieldLabel
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.HeldRequest
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The texts of ANDROID-PLAN 0.1.116 §4 "Approvals for devices and agents (AA5)", word for word (drafts), and their layout. */
@RunWith(RobolectricTestRunner::class)
class DeviceAsksScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.now()
    private val bot = "⁨Build bot⁩"
    private val items = listOf(
        ItemSummary("i1", 1, "Bank PIN", "other", Sensitivity.SECRET, fieldLabels = listOf(FieldLabel("f1", "PIN", "password"), FieldLabel("f2", "Hint", "text"))),
    )

    private fun session(seconds: Int? = 3_600, role: String? = "agent", fromFeed: Boolean = false, name: String? = "Build bot") =
        Approval.DeviceRequest(
            "device.session.pending", "s1", name, role, null, now, now.plusSeconds(8 * 60 + 30),
            deviceId = "A", seconds = seconds, fromFeed = fromFeed,
        )

    private fun held(h: HeldRequest?, fromFeed: Boolean = false, role: String = "agent") =
        Approval.DeviceRequest("approval.pending", "h1", "Build bot", role, "agent.request", now, now.plusSeconds(4 * 60 + 30), deviceId = "A", held = h, fromFeed = fromFeed)

    private fun state(a: Approval.DeviceRequest, listed: Boolean = true, seconds: Int? = null, outcome: DeviceOutcome? = null, pairingHeld: Boolean = false) =
        ApprovalDetailUiState(a.key, a, items = items, deviceListed = listed, sessionSeconds = seconds, outcome = outcome, pairingHeld = pairingHeld)

    private fun show(s: ApprovalDetailUiState, actions: DecisionActions = DecisionActions()) = rule.setContent { ApprovalDetailScreen(s, actions) }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    // --- an access request ---

    @Test
    fun anAccessRequestOffersTheFiveLengthsTheRequestedOnePreselectedAndAllowAndDeny() {
        show(state(session(seconds = 3_600)))
        rule.onNodeWithText("$bot asks for access").assertExists()
        rule.onNodeWithText("Allow $bot to act for you for:").assertExists()
        for (t in listOf("15 minutes", "1 hour", "4 hours", "8 hours", "24 hours")) rule.onNodeWithText(t).assertExists()
        rule.onNodeWithTag("session_3600").assertIsSelected()
        rule.onNodeWithText("It can use only the permissions you gave it.").assertExists()
        rule.onNodeWithText("Waiting for 8 more minutes").assertExists()
        rule.onNodeWithText("Allow").assertExists()
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun aLengthThatIsNotOneOfThemIsAddedAndPreselected() {
        show(state(session(seconds = 5_400)))
        rule.onNodeWithText("90 minutes").assertExists()
        rule.onNodeWithTag("session_5400").assertIsSelected()
    }

    @Test
    fun aDesktopHasNoAgentNoteAndAChoiceIsTapped() {
        var chosen = 0
        show(state(session(role = "desktop", name = "Laptop")), DecisionActions(onChooseSeconds = { chosen = it }))
        assertEquals(false, has("It can use only the permissions you gave it."))
        rule.onNodeWithTag("session_900").performScrollTo().performClick()
        assertEquals(900, chosen)
    }

    @Test
    fun aRequestKnownFromItsFeedItemSaysTheLengthIsUnknownAndPreselectsOneHour() {
        show(state(session(seconds = null, fromFeed = true)))
        rule.onNodeWithText("$bot asked for access").assertExists()
        rule.onNodeWithText("The length it asked for isn't known on this phone any more").assertExists()
        rule.onNodeWithTag("session_3600").assertIsSelected()
        rule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun aFeedOnlyRequestOfAnUnlistedDeviceOffersDenyOnly() {
        show(state(session(seconds = null, fromFeed = true), listed = false))
        assertEquals(false, has("Allow"))
        rule.onNodeWithText("Deny").assertExists()
        rule.onNodeWithText("$bot isn't in your list of devices, so you can only deny this request.").assertExists()
        assertEquals(0, rule.onAllNodesWithText("1 hour").fetchSemanticsNodes().size)
    }

    @Test
    fun theOutcomesOfAnAccessRequest() {
        val until = now.plusSeconds(3_600)
        show(state(session(), outcome = DeviceOutcome.Allowed(until)))
        // The same-day rule itself is pinned in DeviceAsksUntilTextTest; here, that the screen uses it.
        rule.onNodeWithText("Access allowed until ${untilText(until)}", substring = true).assertExists()
    }

    @Test
    fun noLongerWaiting() {
        show(state(session(), outcome = DeviceOutcome.NoLongerWaiting))
        rule.onNodeWithText("This request is no longer waiting.").assertExists()
    }

    // --- a held approval ---

    @Test
    fun anItemUseShowsTheItemFieldAndDataAsText() {
        show(state(held(HeldRequest.ItemUse("i1", "f1", "ping 12:00".toByteArray()))))
        rule.onNodeWithText("$bot wants to use ⁨Bank PIN⁩ without seeing it").assertExists()
        rule.onNodeWithText(
            "Field: ⁨PIN⁩. Your vault uses the value for the agent and gives it only the result. For a short or guessable value, such as a PIN or a common password, the agent could work the value out from that result.",
        ).assertExists()
        rule.onNodeWithText("Data from the agent").assertExists()
        // The box is one node (A6 audit A36): its parts are in the unmerged tree.
        rule.onNodeWithTag("use_data_text", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ping 12:00").assertExists()
        rule.onNodeWithText("Waiting for 4 more minutes").assertExists()
        rule.onNodeWithText("Approve").assertExists()
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun aFailureOfTheDecisionIsAnnounced() {
        val failed = state(held(HeldRequest.ItemUse("i1", "f1", "ping".toByteArray())))
            .copy(error = com.vettid.core.data.vault.FailureKind.NETWORK)
        show(failed)
        val live = rule.onNodeWithTag("failure").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, live)
    }

    @Test
    fun theAgentsDataBoxIsOneNodeWithTheLengthAndTheText() {
        show(state(held(HeldRequest.ItemUse("i1", "f1", "ping 12:00".toByteArray()))))
        val box = rule.onNodeWithTag("use_data_box").fetchSemanticsNode()
        val texts = box.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        // The label, the byte count and the agent's text are all in the one node.
        assertTrue(texts.joinToString("|"), texts.any { it == "Data from the agent" })
        assertTrue(texts.joinToString("|"), texts.any { it.contains("10") && it.contains("byte") })
        assertTrue(texts.joinToString("|"), texts.any { it == "ping 12:00" })
    }

    @Test
    fun dataThatIsNotPrintableIsShownAsHex() {
        show(state(held(HeldRequest.ItemUse("i1", "f1", byteArrayOf(0, 1, 0xFE.toByte())))))
        rule.onNodeWithTag("use_data_hex", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("00 01 fe").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("use_data_text", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aHostileDataTextIsShownAsHexNotAsReorderedText() {
        show(state(held(HeldRequest.ItemUse("i1", "f1", "pay ‮evil".toByteArray()))))
        rule.onNodeWithTag("use_data_hex", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anItemGetNamesFieldsAndAlwaysSaysTheAppMayKeepTheValue() {
        show(state(held(HeldRequest.ItemGet("i1", listOf("f1", "f2")))))
        rule.onNodeWithText("$bot wants to see ⁨Bank PIN⁩").assertExists()
        rule.onNodeWithText("Fields: ⁨PIN⁩, ⁨Hint⁩").assertExists()
        rule.onNodeWithText("The agent's app may keep the value in its history.").assertExists()
        rule.onNodeWithText("Approve").assertExists()
    }

    @Test
    fun anItemGetWithoutFieldsIsAllFieldsAndNotes() {
        show(state(held(HeldRequest.ItemGet("i1", null))))
        rule.onNodeWithText("All fields and notes").assertExists()
        rule.onNodeWithText("The agent's app may keep the value in its history.").assertExists()
    }

    @Test
    fun aHeldRequestShowsTheKindOfDeviceWhateverItCallsItself() {
        show(state(held(HeldRequest.Catalog).copy(deviceName = "Work desktop")))
        rule.onNodeWithTag("device_kind").assertExists()
        rule.onNodeWithText("Agent").assertExists()
        rule.onNodeWithText("\u2068Work desktop\u2069 wants to list the items it may use", substring = true).assertExists()
    }

    @Test
    fun theCatalog() {
        show(state(held(HeldRequest.Catalog)))
        rule.onNodeWithText("$bot wants to list the items it may use").assertExists()
        rule.onNodeWithText("It sees their names and field labels, never their values.").assertExists()
        rule.onNodeWithText("Approve").assertExists()
    }

    @Test
    fun runAndProxyCanOnlyBeDenied() {
        show(state(held(HeldRequest.Later("i1"))))
        rule.onNodeWithText("$bot wants to use ⁨Bank PIN⁩ in a way this version of VettID can't show").assertExists()
        rule.onNodeWithText("Update VettID to see the details. Until then you can only deny it.").assertExists()
        assertEquals(false, has("Approve"))
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun aDesktopsStepUpAndAnyOtherTypeCanOnlyBeDenied() {
        show(state(held(HeldRequest.DesktopStepUp, role = "desktop").copy(deviceName = "Laptop")))
        rule.onNodeWithText("⁨Laptop⁩ asks to approve a request").assertExists()
        rule.onNodeWithText("Desktop requests can't be shown in this version of VettID, so you can only deny them.").assertExists()
        assertEquals(false, has("Approve"))
    }

    @Test
    fun anyOtherTypeOrOp() {
        show(state(held(HeldRequest.Unshown)))
        rule.onNodeWithText("$bot asks to approve a request").assertExists()
        rule.onNodeWithText("This version of VettID can't show this request, so you can only deny it.").assertExists()
        assertEquals(false, has("Approve"))
    }

    @Test
    fun anItemTheAppCannotResolveIsAnItemYouNoLongerHaveAndDenyOnly() {
        show(state(held(HeldRequest.ItemUse("gone", "f1", byteArrayOf(1)))))
        rule.onNodeWithText("$bot wants to use an item you no longer have without seeing it").assertExists()
        assertEquals(false, has("Approve"))
        rule.onNodeWithText("Deny").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Data from the agent").fetchSemanticsNodes().size)
    }

    @Test
    fun anApprovalKnownOnlyFromItsFeedItemCanOnlyBeDenied() {
        show(state(held(null, fromFeed = true)))
        rule.onNodeWithText("$bot asked for something this phone can no longer show. Deny it, or let it expire.").assertExists()
        assertEquals(false, has("Approve"))
        rule.onNodeWithText("Deny").assertExists()
    }

    @Test
    fun eachResultOfApproveHasItsText() {
        val cases = mapOf(
            "ok" to "Approved",
            "not_found" to "This request is no longer waiting.",
            null to "Your vault didn't say whether it was done.",
            "forbidden" to "Approved, but $bot no longer has a permission that covers it, or is paused, so nothing was done.",
            "session_required" to "Approved, but $bot's access ended first, so nothing was done.",
            "denied" to "Approved, but $bot's access ended first, so nothing was done.",
            "way_not_allowed" to "Approved, but this item no longer allows that, so nothing was done.",
            "something_new" to "Approved, but your vault answered: \u2068something_new\u2069.",
        )
        val s = androidx.compose.runtime.mutableStateOf(state(held(HeldRequest.Catalog), outcome = DeviceOutcome.Approved("ok")))
        rule.setContent { ApprovalDetailScreen(s.value, DecisionActions()) }
        cases.forEach { (code, text) ->
            s.value = state(held(HeldRequest.Catalog), outcome = DeviceOutcome.Approved(code))
            rule.waitForIdle()
            rule.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun deniedSaysDenied() {
        show(state(held(HeldRequest.Catalog), outcome = DeviceOutcome.Denied))
        rule.onNodeWithText("Denied").assertExists()
    }

    @Test
    fun anAnswerWithoutATimeSaysAccessAllowedAndNothingMadeUp() {
        show(state(session(), outcome = DeviceOutcome.Allowed(null)))
        rule.onNodeWithText("Access allowed").assertExists()
        assertEquals(false, has("until", substring = true))
    }

    @Test
    fun theDataIsInABoxWithItsByteLength() {
        show(state(held(HeldRequest.ItemUse("i1", "f1", "ping 12:00".toByteArray()))))
        rule.onNodeWithTag("use_data_box").assertExists()
        rule.onNodeWithText("10 bytes").assertExists()
    }

    @Test
    fun oneByteIsOneByte() {
        show(state(held(HeldRequest.ItemUse("i1", "f1", byteArrayOf(65)))))
        rule.onNodeWithText("1 byte").assertExists()
    }

    @Test
    fun whileTheItemCacheLoadsTheTitleSaysLoading() {
        val s = state(held(HeldRequest.ItemUse("i1", "f1", byteArrayOf(1)))).copy(items = emptyList(), itemsLoaded = false)
        show(s)
        rule.onNodeWithText("$bot wants to use Loading… without seeing it").assertExists()
        assertEquals(false, has("an item you no longer have", substring = true))
        assertEquals(false, has("Approve"))
        rule.onNodeWithText("Deny").assertExists()
    }

    // --- a pairing known from its feed item ---

    private fun pairing() = Approval.DeviceRequest("device.pair.pending", "p1", null, null, null, now, now.plusSeconds(600), fromFeed = true)

    @Test
    fun aPairingWithoutItsComparisonOffersOnlyReject() {
        show(state(pairing()))
        rule.onNodeWithText("A device asks to pair").assertExists()
        rule.onNodeWithText(
            "This pairing can't be checked on this phone any more, so it can't be approved here. Reject it, or let it expire: it ends by itself within 10 minutes and nothing is paired. If you want to pair, start again.",
        ).assertExists()
        assertEquals(false, has("Deny"))
        assertEquals(false, has("Approve"))
        assertEquals(false, has("Allow"))
        assertEquals(false, has("Compare the code"))
        assertEquals(true, has("Reject"))
    }

    @Test
    fun rejectOfAPairingTheyCannotCheckIsClicked() {
        var denied = false
        show(state(pairing()), DecisionActions(onDeny = { denied = true }))
        rule.onNodeWithText("Reject").performClick()
        assertEquals(true, denied)
    }

    @Test
    fun aHeldPairingOpensItsComparison() {
        var opened = false
        show(state(pairing(), pairingHeld = true).copy(pairingName = "New bot"), DecisionActions(onOpenPairing = { opened = true }))
        rule.onNodeWithText("⁨New bot⁩ asks to pair").assertExists()
        rule.onNodeWithText("Compare the code").performClick()
        assertEquals(true, opened)
        assertEquals(false, has("Reject"))
    }

    // --- the list's rows ---

    private fun list(vararg a: Approval) =
        rule.setContent { ApprovalsScreen(ApprovalsUiState(loading = false, approvals = a.toList(), items = items), ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {})) }

    @Test
    fun theRowsOfAnAccessRequestAndAHeldApproval() {
        list(session(seconds = 3_600), held(HeldRequest.ItemUse("i1", "f1", byteArrayOf(1))))
        rule.onNodeWithText("$bot asks for access").assertIsDisplayed()
        rule.onNodeWithText("Agent · for 1 hour · Waiting for 8 more minutes").assertIsDisplayed()
        rule.onNodeWithText("$bot wants to use ⁨Bank PIN⁩ without seeing it").assertIsDisplayed()
        rule.onNodeWithText("Agent · Waiting for 4 more minutes").assertIsDisplayed()
    }

    @Test
    fun theRowOfAFeedOnlyRequestIsInThePastTense() {
        list(session(seconds = null, fromFeed = true), held(null, fromFeed = true))
        rule.onNodeWithText("$bot asked for access").assertIsDisplayed()
        rule.onNodeWithText("$bot asks to approve a request").assertIsDisplayed()
    }

    @Test
    fun aHostileDeviceNameCannotReverseARow() {
        list(session(name = "Bot⁩‮"))
        rule.onNodeWithText("⁨Bot⁩ asks for access").assertIsDisplayed()
    }
}
