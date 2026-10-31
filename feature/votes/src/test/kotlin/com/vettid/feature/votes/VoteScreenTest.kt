// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.votes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.CheckState
import com.vettid.core.data.votes.SiteState
import com.vettid.core.data.votes.VoteBallot
import com.vettid.core.data.votes.VoteChoice
import com.vettid.core.data.votes.VoteCheck
import com.vettid.core.data.votes.VoteCounts
import com.vettid.core.data.votes.VoteDetail
import com.vettid.core.data.votes.VoteHint
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.data.votes.VoteWire
import com.vettid.core.ui.components.ShellChrome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private const val SERVICE = "Example Cooperative"

/** The Votes screens (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes"), rendered as the member sees them. */
@RunWith(RobolectricTestRunner::class)
// A tall screen: the screens scroll, and what is below the fold is not "displayed".
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class VoteScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome("Sam", onMenuClick = {}, onAvatarClick = {})
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private fun summary(
        state: VoteState = VoteState.OPEN,
        ballot: VoteBallot? = null,
        check: VoteCheck? = null,
        conflictEvidence: Boolean = false,
        canCast: Boolean = ballot == null && state == VoteState.OPEN,
        cannotCast: String? = null,
        reason: String? = null,
        site: SiteState? = null,
        id: String = "P1",
        title: String = "Adopt the budget",
    ) = VoteSummary(
        proposalId = id, connectionId = "c1", title = title, state = state, reason = reason,
        opensAt = now.minusSeconds(86_400), closesAt = now.plusSeconds(86_400 * 4),
        ballot = ballot, check = check, conflictEvidence = conflictEvidence, canCast = canCast, cannotCast = cannotCast, site = site,
    )

    private val choices = listOf(VoteChoice("no", "No"), VoteChoice("abstain", "Yes, really"), VoteChoice("yes", "Yes"))

    private fun detail(s: VoteSummary, text: String = "The board proposes the budget.", choiceId: String? = null, tally: Map<String, Long>? = null, counts: VoteCounts? = null) =
        VoteDetail(
            summary = s, text = text, choices = choices, quorumText = "At least 40 ballots", minBallots = 40, binding = true, recordTag = null,
            choiceId = choiceId, extensions = 0, tally = tally, counts = counts, serviceBallotChoiceId = null,
        )

    private fun state(
        s: VoteSummary,
        d: VoteDetail? = detail(s),
        selected: String? = null,
        cast: CastStep = CastStep.NONE,
        hint: VoteHint = VoteHint(),
        proofExported: Boolean = false,
    ) = VoteUiState(
        connectionId = "c1", proposalId = s.proposalId, summary = s, detail = d, serviceName = SERVICE, loaded = true,
        selected = selected, cast = cast, hint = hint, proofExported = proofExported,
    )

    private fun show(state: VoteUiState, actions: VoteActions = VoteActions()) = rule.setContent { VoteScreen(state, actions, now = now) }

    private fun texts(substring: String) = shownAll(substring, substring = true).fetchSemanticsNodes().size

    /** Names in sentences are bidi-isolated (§10.8): the words are compared without the isolates. */
    private fun clean(s: String) = s.replace("\u2068", "").replace("\u2069", "")

    private fun matcher(text: String, substring: Boolean) = SemanticsMatcher("shows \"$text\"") { node ->
        val shownTexts = (node.config.getOrElse(SemanticsProperties.Text) { emptyList() }).map { clean(it.text) }
        shownTexts.any { if (substring) it.contains(clean(text)) else it == clean(text) }
    }

    private fun shown(text: String, substring: Boolean = false, useUnmergedTree: Boolean = false) =
        rule.onNode(matcher(text, substring), useUnmergedTree)

    private fun shownAll(text: String, substring: Boolean = false) = rule.onAllNodes(matcher(text, substring), useUnmergedTree = true)

    @Test
    fun theServicesWordsAreShownAsPlainTextWithNoMarkupLinkOrControlCharacter() {
        val raw = Json.parseToJsonElement(
            """{"proposal_id":"P1","connection_id":"c1","title":"Budget‮","state":"open","can_cast":true,"text":"Vote <b>now</b> at http://example.test/x‮evil\nLine two\u0007","choices":[{"id":"yes","label":"Yes"}]}""",
        ) as JsonObject
        val d = VoteWire.detail(raw)!!
        show(state(d.summary, d))
        rule.onNodeWithTag("vote_text").assertIsDisplayed()
        assertTrue(d.text.none { it == '‮' || it == '\u0007' })
        // Markup and the link are text, as sent; nothing renders them.
        shown("Vote <b>now</b> at http://example.test/xevil\nLine two", substring = true).assertIsDisplayed()
    }

    @Test
    fun theChoicesAreInTheOrderSentWithTheirLabelsAndAbstainAsSent() {
        show(state(summary()))
        val labels = listOf("No", "Yes, really", "Yes")
        labels.forEach { shown(it).assertIsDisplayed() }
        val order = listOf("vote_choice_no", "vote_choice_abstain", "vote_choice_yes").map {
            rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(order.sorted(), order)
        shown("Choose one").assertIsDisplayed()
        shown("Written by Example Cooperative", substring = true).assertIsDisplayed()
    }

    @Test
    fun castIsDisabledUntilAChoiceIsSelected() {
        show(state(summary()))
        rule.onNodeWithTag("vote_cast").assertIsNotEnabled()
    }

    @Test
    fun withAChoiceSelectedCastIsEnabledAndAsksForTheConfirmation() {
        var asked = 0
        show(state(summary(), selected = "yes"), VoteActions(onCast = { asked++ }))
        rule.onNodeWithTag("vote_cast").assertIsEnabled().performClick()
        assertEquals(1, asked)
    }

    private fun reason(code: String, text: String) {
        val s = summary(canCast = false, cannotCast = code)
        show(state(s, selected = "yes"))
        rule.onNodeWithTag("vote_cast").assertIsNotEnabled()
        assertTrue("$code: $text", texts(text) >= 1)
    }

    @Test
    fun notOpenIsWrittenWhereTheButtonIs() = reason("not_open", "This vote opens")

    @Test
    fun closingIsWrittenWhereTheButtonIs() =
        reason("closing", "This vote closes in less than 2 minutes. It is too late to cast your vote from this phone.")

    @Test
    fun aSiteMismatchIsWrittenWhereTheButtonIs() = reason(
        "site_mismatch",
        "The vote you received differs from the one published on the public site, so you can't vote in it. You can save the proof.",
    )

    @Test
    fun aLostConnectionIsWrittenWhereTheButtonIs() = reason("connection_unavailable", "You are no longer connected to $SERVICE, so you can't vote.")

    @Test
    fun notSignedInIsWrittenWhereTheButtonIs() = reason(
        "not_signed_in",
        "Before you can vote, sign in to $SERVICE once with VettID. This lets $SERVICE check that your vote is yours.",
    )

    @Test
    fun aCheckingVoteSaysVettIdWillTryAgainForTheClockAndOffersNoCast() {
        val s = summary(VoteState.CHECKING, reason = "clock", canCast = false, cannotCast = "checking")
        show(state(s, null))
        shown("VettID can't finish checking this vote yet because of a time check on VettID's side. It will try again.").assertIsDisplayed()
        assertEquals(0, shownAll("Cast your vote").fetchSemanticsNodes().size)
    }

    @Test
    fun aRecordedBallotShowsTheServiceAndTheMembersOwnChoiceNeverResultsBeforeTheCheck() {
        val s = summary(ballot = VoteBallot(BallotState.RECORDED))
        show(state(s, detail(s, choiceId = "yes")))
        shown("$SERVICE recorded your vote. It can't be changed.").assertIsDisplayed()
        rule.onNodeWithTag("vote_your_choice").assertIsDisplayed()
        assertEquals(0, texts("The published results include your vote."))
        assertEquals(0, shownAll("Cast your vote").fetchSemanticsNodes().size)
    }

    @Test
    fun aWaitingBallotNeverSaysFailedAndOffersToCheckWithTheService() {
        val s = summary(ballot = VoteBallot(BallotState.WAITING, reason = "not_open"))
        show(state(s))
        shown("Waiting for $SERVICE to confirm.", substring = true).assertIsDisplayed()
        assertEquals(0, texts("failed"))
        shown("Check with the service").assertIsDisplayed()
    }

    @Test
    fun aVerifiedCheckSaysTheResultsIncludeTheVoteAndShowsTheTally() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.VERIFIED), canCast = false)
        show(state(s, detail(s, choiceId = "yes", tally = mapOf("yes" to 52L, "no" to 30L, "abstain" to 2L), counts = VoteCounts(120, 84, 0, 3))))
        shown("The published results include your vote.").assertIsDisplayed()
        shown("Results").assertIsDisplayed()
        shown("Yes: 52").assertIsDisplayed()
        shown("84 of 120 eligible members voted.").assertIsDisplayed()
        shown("Enough votes were cast: 84, and the service asked for at least 40.").assertIsDisplayed()
        shown("3 messages sent to $SERVICE during the vote were not handled; any vote among them was not counted.").assertIsDisplayed()
    }

    @Test
    fun noNumberIsShownBeforeTheVerifiedResults() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.PENDING), canCast = false)
        show(state(s, detail(s, choiceId = "yes")))
        assertEquals(0, shownAll("Results").fetchSemanticsNodes().size)
        shown("VettID will check the published results against your vote.").assertIsDisplayed()
    }

    @Test
    fun aConflictFoundAfterAVerifiedCheckIsTheApprovedTextUnderTheResultsLineWithTheProofAndNoErrorState() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.VERIFIED), conflictEvidence = true, canCast = false)
        show(state(s, detail(s, choiceId = "yes")))
        shown("The published results include your vote.").assertIsDisplayed()
        // The owner-approved text, word for word, with the service's name; the asterisks are never shown.
        rule.onNodeWithTag("vote_note_IN_RESULTS_CONFLICT").assertIsDisplayed()
        shown(
            "Your vote is in the published results. $SERVICE also once showed a different vote under your code. Save the proof and show it to the organisation.",
        ).assertIsDisplayed()
        assertEquals(0, texts("**"))
        shown("Save the proof").assertIsDisplayed()
        // Not the failed title, not the conflict body.
        assertEquals(0, texts("The published results don't match your vote"))
        assertEquals(0, texts("holds a different vote under your code than the one you cast"))
    }

    @Test
    fun withoutConflictEvidenceOnlyTheResultsLineShows() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.VERIFIED), canCast = false)
        show(state(s, detail(s, choiceId = "yes")))
        assertEquals(0, texts("also once showed a different vote"))
        assertEquals(0, shownAll("Save the proof").fetchSemanticsNodes().size)
    }

    @Test
    fun aPendingCheckWithReasonInputIsAProblemWithTheResultsNeverTheFailedTitle() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.PENDING, "input"), canCast = false)
        show(state(s, detail(s, choiceId = "yes")))
        assertEquals(0, texts("The published results don't match your vote"))
        shown("VettID could not check the published results of this vote yet.", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedCheckShowsTheTitleTheBodyAndTheProof() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.FAILED), canCast = false)
        show(state(s, detail(s, choiceId = "yes")))
        shown("The published results don't match your vote").assertIsDisplayed()
        shown("You can save the proof and show it to the organisation.").assertIsDisplayed()
        shown("Save the proof").assertIsDisplayed()
    }

    @Test
    fun gaveUpSaysWhichOfTheTwoThingsWithCheckAgain() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.GAVE_UP), canCast = false)
        rule.setContent { VoteScreen(state(s, detail(s), hint = VoteHint(gaveUp = com.vettid.core.data.votes.GaveUpWhy.SITE)), VoteActions(), now = now) }
        shown("VettID could not reach the votes site for 7 days, so your vote has not been checked.").assertIsDisplayed()
        shown("Check again").assertIsDisplayed()
    }

    @Test
    fun theSaveProofWarningIsAConfirmationBeforeTheVaultIsAsked() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.CONFLICT), canCast = false)
        rule.setContent { VoteScreen(state(s, detail(s, choiceId = "yes")).copy(proofWarning = true), VoteActions(), now = now) }
        shown("The file shows how you voted. Keep it private, and share it only with the organisation.").assertIsDisplayed()
    }

    @Test
    fun releaseIsOfferedOnlyAfterTheProofWasSaved() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.CONFLICT), canCast = false)
        rule.setContent { VoteScreen(state(s, detail(s, choiceId = "yes")), VoteActions(), now = now) }
        assertEquals(0, shownAll("Release").fetchSemanticsNodes().size)
    }

    @Test
    fun releaseShowsWhenTheProofWasSavedAndTheRecordIsClosed() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.CONFLICT), canCast = false)
        rule.setContent { VoteScreen(state(s, detail(s, choiceId = "yes"), proofExported = true), VoteActions(), now = now) }
        rule.onNodeWithTag("vote_release").assertIsDisplayed()
    }

    @Test
    fun theConfirmSheetShowsTheFixedSentenceOnceWhateverTheProposalSays() {
        val sentence = "$SERVICE will see how you vote and keep that record. The published results show only an anonymous code for you."
        val s = summary()
        // A proposal that contains the sentence, and a different one.
        val d = detail(s, text = "$sentence Also: your vote is anonymous and secret.")
        rule.setContent { VoteScreen(state(s, d, selected = "yes", cast = CastStep.CONFIRM), VoteActions(), now = now) }
        rule.onNodeWithTag("vote_confirm_sheet").assertIsDisplayed()
        rule.onNodeWithTag("vote_disclosure").assertIsDisplayed()
        shown("Vote “Yes”?").assertIsDisplayed()
        shown("Your vote can't be changed after you cast it.").assertIsDisplayed()
        rule.onNodeWithTag("vote_confirm_yes").assertIsEnabled()
        // The app's own sentence is in its own element, exactly once; the proposal's copy is the proposal's.
        assertEquals(1, shownAll(sentence).fetchSemanticsNodes().size)
    }

    @Test
    fun thePasswordPromptIsTheUnlockWindowPrompt() {
        val s = summary()
        rule.setContent { VoteScreen(state(s, selected = "yes", cast = CastStep.PASSWORD), VoteActions(), now = now) }
        shown("Confirm with your credential password").assertIsDisplayed()
        shown("Your vote is signed with your credential. Enter your credential password.").assertIsDisplayed()
        rule.onNodeWithTag("vote_password_confirm").assertIsNotEnabled()
    }

    @Test
    fun theRestoreDialogNamesTheServiceTheChoiceAndTheTitle() {
        val s = summary().copy(confirmPending = true, canCast = false, cannotCast = "checking")
        val d = detail(s).copy(serviceBallotChoiceId = "yes")
        rule.setContent { VoteScreen(state(s, d), VoteActions(), now = now) }
        shown("Is this your vote?").assertIsDisplayed()
        shown("$SERVICE says it recorded this vote for you: “Yes” on “Adopt the budget”.").assertIsDisplayed()
        shown("Yes, it's mine").assertIsDisplayed()
        shown("No, it isn't mine").assertIsDisplayed()
    }

    @Test
    fun aVoteNotThereAnyMoreSaysSoNotAnEmptyScreen() {
        rule.setContent {
            VoteScreen(VoteUiState(connectionId = "c1", proposalId = "P1", loaded = true), VoteActions(), now = now)
        }
        rule.onNodeWithTag("vote_gone").assertIsDisplayed()
    }

    @Test
    fun aRejectedVoteIsSetAsideWithItsReasonAndTheProof() {
        val s = summary(VoteState.REJECTED, reason = "anchor", canCast = false)
        show(state(s, null))
        shown("VettID set this vote aside").assertIsDisplayed()
        shown("The public record of this vote did not check out.").assertIsDisplayed()
        shown("Save the proof").assertIsDisplayed()
    }

    @Test
    fun alreadyVotedIsNeverSilentAndNamesTheChoiceWhenKnown() {
        val s = summary(ballot = VoteBallot(BallotState.RECORDED))
        show(state(s, detail(s, choiceId = "yes")).copy(alreadyVoted = true))
        shown("You had already voted: Yes. Your vote can't be changed.").assertIsDisplayed()
    }

    @Test
    fun alreadyVotedWithoutAKnownChoiceFallsBack() {
        val s = summary(ballot = VoteBallot(BallotState.SENDING))
        show(state(s, detail(s)).copy(alreadyVoted = true))
        shown("You had already voted. Your vote can't be changed.").assertIsDisplayed()
    }

    @Test
    fun theUnlockWindowNoticeNamesTheTimeAndOffersToCloseIt() {
        var closed = 0
        show(state(summary()).copy(windowNotClosed = com.vettid.core.data.votes.WindowNotClosed(300)), VoteActions(onCloseWindow = { closed++ }))
        shown("The unlock window may still be open").assertIsDisplayed()
        shown("It closes by itself once it has not been used for 5 minutes.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("vote_window_close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun theUnlockTimeIsInWholeMinutesRoundedUp() {
        show(state(summary()).copy(windowNotClosed = com.vettid.core.data.votes.WindowNotClosed(61)))
        shown("It closes by itself once it has not been used for 2 minutes.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theUnlockTimeUpToAMinuteIsOneMinute() {
        show(state(summary()).copy(windowNotClosed = com.vettid.core.data.votes.WindowNotClosed(30)))
        shown("It closes by itself once it has not been used for 1 minute.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theUnlockWindowNoticeWithoutAKnownTimeIsPlain() {
        show(state(summary()).copy(windowNotClosed = com.vettid.core.data.votes.WindowNotClosed(null)))
        shown("It closes by itself.", substring = true).assertIsDisplayed()
    }

    @Test
    fun noAnswerSaysTheVoteMayHaveBeenSent() {
        val p = VoteProblem(com.vettid.core.data.vault.FailureKind.NO_RESPONSE, null, null)
        show(state(summary()).copy(castProblem = p))
        shown("VettID got no answer").assertIsDisplayed()
        shown("If your first vote did arrive, it is the one that counts: a vote can't be changed.", substring = true).assertIsDisplayed()
        assertEquals(0, texts("Your vote was not sent"))
    }

    @Test
    fun resultsTooLargeAreAProblemWithNoTryAgain() {
        val s = summary(VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.PENDING), canCast = false)
        show(state(s, detail(s), hint = VoteHint(resultsProblem = true, resultsTooLarge = true)))
        shown("VettID can't check the published results of this vote: they are larger than your vault accepts. They may not be valid.").assertIsDisplayed()
        assertEquals(0, texts("It will try again"))
    }

    // ---- The list ----

    private fun list(vararg v: VoteSummary) =
        VotesUiState(votes = v.toList(), services = mapOf("c1" to SERVICE), load = ListLoad.LOADED)

    @Test
    fun theListHasOpenAndClosedSectionsWithAChipInWordsOnEveryRow() {
        val open = summary(id = "A", title = "Adopt the budget")
        val voted = summary(id = "B", title = "Elect the board", ballot = VoteBallot(BallotState.RECORDED))
        val closed = summary(VoteState.CLOSED, id = "C", title = "Change the bylaws", ballot = VoteBallot(BallotState.RECORDED), check = VoteCheck(CheckState.VERIFIED))
        val missed = summary(VoteState.CLOSED, id = "D", title = "Choose the venue")
        rule.setContent { VotesScreen(list(open, voted, closed, missed), chrome) }
        shown("Open").assertIsDisplayed()
        shown("Closed").assertIsDisplayed()
        shown("Not voted yet").assertIsDisplayed()
        shown("You voted").assertIsDisplayed()
        shown("Results are out").assertIsDisplayed()
        shown("You didn't vote").assertIsDisplayed()
        shown("Adopt the budget", substring = true).assertIsDisplayed()
        assertEquals(4, shownAll("$SERVICE · Service", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anyBallotOrCheckProblemIsNeedsYourAttention() {
        val v = summary(VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT), id = "E")
        rule.setContent { VotesScreen(list(v), chrome) }
        shown("Needs your attention").assertIsDisplayed()
    }

    @Test
    fun theListNeverShowsAnEmptyScreenOfNothing() {
        rule.setContent { VotesScreen(list(), chrome) }
        shown("No votes").assertIsDisplayed()
    }

    @Test
    fun connectAgainToVoteIsACardAtTheTopOfTheListWithoutAnyVote() {
        var opened: String? = null
        rule.setContent {
            VotesScreen(
                list().copy(reconnect = listOf(com.vettid.core.data.votes.ReconnectEntry("c9", "svc", "Old Cooperative"))),
                chrome,
                onOpenConnection = { opened = it },
            )
        }
        shown("Connect again to vote").assertIsDisplayed()
        shown(
            "Old Cooperative is registered with VettID as a service. This connection was made before it said it was a service, so it can't take part in votes. Remove it and connect again from a new invitation from Old Cooperative.",
            substring = true,
        ).assertIsDisplayed()
        shown("Open the connection").performClick()
        assertEquals("c9", opened)
    }

    @Test
    fun aDroppedNoticeIsShownThenAcknowledgedOnce() {
        val shown = mutableListOf<List<String>>()
        rule.setContent {
            VotesScreen(
                list(summary()).copy(dropped = listOf(com.vettid.core.data.votes.DroppedNotice("c1", 3))),
                chrome,
                onNoticesShown = { shown += it },
            )
        }
        shown("$SERVICE has more votes under way than your vault can keep, so 3 were not kept.").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(listOf(listOf("c1")), shown)
    }

    @Test
    fun theProblemsNoticeNamesTheServiceAndTheCount() {
        rule.setContent {
            VotesScreen(list(summary()).copy(problems = listOf(com.vettid.core.data.votes.ProblemsNotice("c1", 16))), chrome)
        }
        shown(
            "$SERVICE has 16 unresolved vote problems. Save the proof of each and release them to receive new votes from $SERVICE.",
        ).assertIsDisplayed()
    }

    @Test
    fun aRowIsReadWholeWithItsServiceAndStatus() {
        val v = summary(id = "A", ballot = VoteBallot(BallotState.RECORDED))
        rule.setContent { VotesScreen(list(v), chrome) }
        val node = rule.onNodeWithTag("vote_c1/A").fetchSemanticsNode()
        val description = (node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }).joinToString()
        val said = clean(description)
        assertTrue(said, said.startsWith("Adopt the budget, from the service Example Cooperative, You voted"))
    }
}
