// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.devices.PairingCode
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import com.vettid.core.data.devices.PendingPairing
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The pairing screens (ANDROID-PLAN 0.1.53 AA2), rendered as the member sees them. */
@RunWith(RobolectricTestRunner::class)
class PairScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val exp = now.plusSeconds(581)
    private val link = InviteQr(InviteKind.AGENT, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 }, exp.epochSecond).link()
    private val code = PairingCode("p1", link, exp)
    private val pending = PendingPairing("p1", "Claude on laptop", "123456", now.plusSeconds(600))
    private val calls = mutableListOf<String>()

    private fun actions() = PairActions(
        onCopied = { calls += "copied" },
        onCancel = { calls += "cancel" },
        onNewCode = { calls += "new" },
        onCodesMatch = { calls += "match" },
        onReject = { calls += "reject" },
        onPair = { calls += "pair" },
        onChoose = { calls += "choose:${it.name}" },
        onDone = { calls += "done" },
        onChooseWhatItMayUse = { calls += "choose-use:$it" },
        onOwnerCheck = { calls += "check" },
        onBack = { calls += "back" },
    )

    private fun show(state: PairUiState) = rule.setContent { PairScreen(state, actions(), now = { now }) }

    private fun at(stage: PairingStage, held: Boolean = false, copied: Boolean = false, sheet: Boolean = false, busy: Boolean = false, error: FailureKind? = null) =
        PairUiState(PairingState(stage, busy = busy, error = error), held = held, copied = copied, sheet = sheet)

    @Test
    fun theCodeScreenShowsTheQrTheCodeTheCountdownAndTheButtons() {
        show(at(PairingStage.Code(code)))
        rule.onNodeWithText("Pair an agent").assertIsDisplayed()
        rule.onNodeWithText("Copy this code to the computer where the agent runs. It works once and expires in 10 minutes.").assertIsDisplayed()
        rule.onNodeWithTag("pair_qr").assertExists()
        rule.onNodeWithTag("pair_link").assertTextContains(link)
        rule.onNodeWithTag("pair_countdown").assertTextContains("Expires in 9:41")
        rule.onNodeWithTag("pair_share").assertExists()
        rule.onNodeWithTag("pair_copy").assertTextContains("Copy", substring = true)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun anExpiredCodeSaysSoAndOffersANewOne() {
        show(at(PairingStage.CodeExpired))
        rule.onNodeWithText("This code expired.").assertIsDisplayed()
        rule.onNodeWithText("New code").performClick()
        assertEquals(listOf("new"), calls)
    }

    @Test
    fun aCodeThatCouldNotBeMadeSaysSoPlainly() {
        show(at(PairingStage.Idle, error = FailureKind.NETWORK))
        rule.onNodeWithText("A pairing code could not be made. Try again.").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("new"), calls)
    }

    @Test
    fun aDueDailyCheckOffersTheCheck() {
        show(at(PairingStage.Idle, error = FailureKind.OWNER_CHECK_REQUIRED))
        rule.onNodeWithText("Check now").performClick()
        assertEquals(listOf("check"), calls)
    }

    @Test
    fun theComparisonShowsTheDigitsInTwoGroupsTheNameAndBothAnswers() {
        show(at(PairingStage.Compare(pending)))
        rule.onNodeWithText("Compare the code").assertIsDisplayed()
        rule.onNodeWithTag("pair_sas").assertTextContains("Does the agent show 123 456? Approve only if the codes match.")
        rule.onNodeWithTag("pair_name").assertTextContains("Claude on laptop", substring = true)
        rule.onNodeWithText("The code proves this phone talks to the agent you just gave the code to. The name is only what the agent says about itself.")
            .assertIsDisplayed()
        rule.onNodeWithText("The codes match").performClick()
        rule.onNodeWithText("They don't match").performClick()
        assertEquals(listOf("match", "reject"), calls)
    }

    @Test
    fun theDigitsAreReadOneByOne() {
        show(at(PairingStage.Compare(pending)))
        val description = rule.onNodeWithTag("pair_sas").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals("Does the agent show 1 2 3 4 5 6? Approve only if the codes match.", description)
    }

    @Test
    fun aNameThatCouldPassForThePhoneOrVettIdIsShownWithItsRole() {
        show(at(PairingStage.Compare(PendingPairing("p1", "VettID", "123456", now.plusSeconds(600)))))
        rule.onNodeWithTag("pair_name").assertTextContains("VettID (Agent)", substring = true)
    }

    @Test
    fun aPairingThatCannotBeComparedOffersOnlyReject() {
        show(at(PairingStage.Compare(PendingPairing("p1", "Claude", null, now.plusSeconds(600)))))
        rule.onNodeWithTag("pair_cannot_compare").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("The codes match").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("They don't match").fetchSemanticsNodes().size)
        rule.onNodeWithText("Reject").performClick()
        assertEquals(listOf("reject"), calls)
    }

    @Test
    fun theSheetOffersTheAccessLengthsWithOneHourPreselected() {
        show(at(PairingStage.Compare(pending), sheet = true))
        rule.onNodeWithText("Allow access now?").assertIsDisplayed()
        rule.onNodeWithText("Agents act only while you allow access, and only within the permissions you give them.").assertIsDisplayed()
        for (c in listOf("No access yet", "15 minutes", "1 hour", "4 hours", "8 hours", "24 hours")) rule.onNodeWithText(c).assertIsDisplayed()
        rule.onNodeWithTag("pair_session_HOUR_1").assertIsSelected()
        rule.onNodeWithTag("pair_session_NONE").assertIsNotSelected()
        rule.onNodeWithTag("pair_session_HOURS_4").performClick()
        rule.onNodeWithTag("pair_confirm").performScrollTo().performClick()
        assertEquals(listOf("choose:HOURS_4", "pair"), calls)
    }

    @Test
    fun aDueDailyCheckOnTheSheetOffersTheCheck() {
        show(at(PairingStage.Compare(pending), sheet = true, error = FailureKind.OWNER_CHECK_REQUIRED))
        rule.onNodeWithText("Check now").performScrollTo().performClick()
        assertEquals(listOf("check"), calls)
    }

    @Test
    fun rejectedSaysTheAgentIsToldOrThatTheVaultCouldNotBe() {
        show(at(PairingStage.Rejected(told = true)))
        rule.onNodeWithText("Pairing rejected").assertIsDisplayed()
        rule.onNodeWithText("The agent is told it was rejected.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test
    fun aRejectionTheVaultDidNotTakeSaysNothingIsPaired() {
        show(at(PairingStage.Rejected(told = false)))
        rule.onNodeWithText("Nothing is paired.", substring = true).assertIsDisplayed()
    }

    @Test
    fun pairedNamesTheAgentAndOffersTheNextStep() {
        show(at(PairingStage.Paired("Claude on laptop", "a1")))
        rule.onNodeWithText("is paired", substring = true).assertIsDisplayed()
        rule.onNodeWithText("It can't use any of your items yet.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Choose what it may use").performClick()
        rule.onNodeWithText("Later").performClick()
        assertEquals(listOf("choose-use:a1", "done"), calls)
    }

    @Test
    fun anEndedPairingSaysNothingWasPaired() {
        show(at(PairingStage.Ended))
        rule.onNodeWithText("This pairing has ended. Start again from Devices & agents.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("If your vault did get the approval", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun noAnswerFromTheVaultAddsThatTheAgentMayBeInTheList() {
        rule.setContent {
            PairScreen(
                PairUiState(PairingState(PairingStage.Ended, error = FailureKind.NO_RESPONSE, unknownOutcome = true)),
                actions(),
                now = { now },
            )
        }
        rule.onNodeWithText("If your vault did get the approval", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFeedItemWithNothingHeldSaysItCannotBeChecked() {
        show(at(PairingStage.Idle, held = true))
        rule.onNodeWithText("A device asks to pair").assertIsDisplayed()
        rule.onNodeWithText("This pairing can't be checked on this phone any more.", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("The codes match").fetchSemanticsNodes().size)
    }
}
