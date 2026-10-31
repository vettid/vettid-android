// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.votes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
/** How a vote reads (ANDROID-PLAN 0.1.108 "Votes"): a table over the vault's states, and the "never success" rules. */
class VoteRulesTest {
    private fun vote(
        state: VoteState = VoteState.OPEN,
        ballot: VoteBallot? = null,
        check: VoteCheck? = null,
        conflictEvidence: Boolean = false,
        confirmPending: Boolean = false,
        canCast: Boolean = ballot == null && state == VoteState.OPEN,
        cannotCast: String? = null,
        site: SiteState? = null,
        reason: String? = null,
    ) = VoteSummary(
        proposalId = "P", connectionId = "C", title = "T", state = state, reason = reason, ballot = ballot, check = check,
        conflictEvidence = conflictEvidence, confirmPending = confirmPending, canCast = canCast, cannotCast = cannotCast, site = site,
    )

    private fun ballot(s: BallotState, code: String? = null, reason: String? = null) = VoteBallot(s, code, reason)

    private fun check(s: CheckState, reason: String? = null) = VoteCheck(s, reason)

    @Test
    fun aBallotStateAndACheckStateEachShowTheirText() {
        val table = listOf(
            vote(ballot = ballot(BallotState.SENDING)) to VoteNote.SENDING,
            vote(ballot = ballot(BallotState.WAITING, reason = "not_open")) to VoteNote.WAITING,
            vote(ballot = ballot(BallotState.WAITING, reason = "not_signed_in")) to VoteNote.NOT_SIGNED_IN,
            vote(ballot = ballot(BallotState.WAITING, reason = "closed")) to VoteNote.LATE_PENDING,
            vote(ballot = ballot(BallotState.RECORDED)) to VoteNote.RECORDED,
            vote(ballot = ballot(BallotState.REFUSED, "closed")) to VoteNote.REFUSED_LATE,
            vote(ballot = ballot(BallotState.REFUSED, "late")) to VoteNote.REFUSED_LATE,
            vote(ballot = ballot(BallotState.REFUSED, "not_active")) to VoteNote.REFUSED_NOT_ACTIVE,
            vote(ballot = ballot(BallotState.REFUSED, "not_eligible")) to VoteNote.REFUSED_NOT_ELIGIBLE,
            vote(ballot = ballot(BallotState.REFUSED, "bad_ballot")) to VoteNote.REFUSED_BAD_BALLOT,
            vote(ballot = ballot(BallotState.REFUSED, "connection_ended")) to VoteNote.REFUSED_ENDED,
            vote(ballot = ballot(BallotState.CONFLICT)) to VoteNote.CONFLICT,
            vote(ballot = ballot(BallotState.DISPUTED)) to VoteNote.DISPUTED,
        )
        for ((v, want) in table) assertTrue("$want in ${VoteRules.notes(v)}", want in VoteRules.notes(v))
    }

    @Test
    fun aBallotStateNobodyKnowsIsNeverShownAsSuccess() {
        val notes = VoteRules.notes(vote(ballot = ballot(BallotState.UNKNOWN)))
        assertTrue(VoteNote.WAITING in notes)
        assertFalse(VoteNote.RECORDED in notes || VoteNote.IN_RESULTS in notes)
        val refusedUnknown = VoteRules.notes(vote(ballot = ballot(BallotState.REFUSED, "something_new")))
        assertTrue(VoteNote.REFUSED_BAD_BALLOT in refusedUnknown)
    }

    @Test
    fun aWaitingBallotNeverShowsAsFailed() {
        for (reason in listOf(null, "not_open", "not_signed_in", "closed", "bad_ballot")) {
            val notes = VoteRules.notes(vote(ballot = ballot(BallotState.WAITING, reason = reason)))
            assertFalse(VoteNote.FAILED in notes)
            assertFalse(notes.any { it.severity == VoteNote.Severity.ERROR })
        }
    }

    @Test
    fun theResultsLineAppearsOnlyForAVerifiedCheckOfARecordedBallot() {
        val recorded = ballot(BallotState.RECORDED)
        for (c in CheckState.entries) {
            val notes = VoteRules.notes(vote(VoteState.CLOSED, recorded, check(c)))
            assertEquals("$c", c == CheckState.VERIFIED, VoteNote.IN_RESULTS in notes)
        }
        // A ballot with no receipt is not "included" even if the check is verified (it was refused or empty).
        assertFalse(VoteNote.IN_RESULTS in VoteRules.notes(vote(VoteState.CLOSED, ballot(BallotState.SENDING), check(CheckState.VERIFIED))))
    }

    @Test
    fun conflictEvidenceUnderAVerifiedCheckIsANoteUnderTheResultsLineNotAnError() {
        val v = vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.VERIFIED), conflictEvidence = true)
        val notes = VoteRules.notes(v)
        assertEquals(listOf(VoteNote.CLOSED, VoteNote.RECORDED, VoteNote.IN_RESULTS, VoteNote.IN_RESULTS_CONFLICT), notes)
        assertEquals(VoteNote.Severity.NOTE, VoteNote.IN_RESULTS_CONFLICT.severity)
        assertFalse(VoteNote.FAILED in notes)
        assertTrue(VoteRules.hasProof(v))
        assertEquals(VoteChip.PROBLEM, VoteRules.chip(v))
        // Without the flag, only the first line.
        val plain = VoteRules.notes(v.copy(conflictEvidence = false))
        assertFalse(VoteNote.IN_RESULTS_CONFLICT in plain)
        assertTrue(VoteNote.IN_RESULTS in plain)
    }

    @Test
    fun aPendingCheckWithReasonInputIsAProblemWithTheResultsAndNeverFailed() {
        val v = vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.PENDING, "input"))
        val notes = VoteRules.notes(v)
        assertTrue(VoteNote.CHECK_RESULTS_PROBLEM in notes)
        assertFalse(VoteNote.FAILED in notes)
        assertFalse(VoteRules.hasProof(v))
        // Retried: the chip says it is being checked.
        assertEquals(VoteChip.CHECKING, VoteRules.chip(v))
    }

    @Test
    fun resultsTooLargeAreAProblemWithoutATryAgain() {
        val v = vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.PENDING))
        val notes = VoteRules.notes(v, resultsProblem = true, resultsTooLarge = true)
        assertTrue(VoteNote.CHECK_RESULTS_TOO_LARGE in notes)
        assertFalse(VoteNote.CHECK_RESULTS_PROBLEM in notes)
    }

    @Test
    fun aFailedCheckShowsTheMismatchAndTheProof() {
        val v = vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.FAILED))
        assertTrue(VoteNote.FAILED in VoteRules.notes(v))
        assertTrue(VoteRules.hasProof(v))
        assertEquals(VoteChip.PROBLEM, VoteRules.chip(v))
    }

    @Test
    fun gaveUpSaysWhichOfTwoThingsWhenTheAppKnows() {
        val v = vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.GAVE_UP))
        assertTrue(VoteNote.CHECK_GAVE_UP_SITE in VoteRules.notes(v, GaveUpWhy.SITE))
        assertTrue(VoteNote.CHECK_GAVE_UP_UNPUBLISHED in VoteRules.notes(v, GaveUpWhy.UNPUBLISHED))
        assertTrue(VoteNote.CHECK_GAVE_UP in VoteRules.notes(v, null))
    }

    @Test
    fun theAppsOwnFindInTheResultsIsShownWhileTheVaultHasNotJudged() {
        val v = vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check = null)
        assertTrue(VoteNote.CHECK_RESULTS_PROBLEM in VoteRules.notes(v, resultsProblem = true))
        assertFalse(VoteNote.CHECK_RESULTS_PROBLEM in VoteRules.notes(v, resultsProblem = false))
    }

    @Test
    fun aClosedVoteWithoutABallotSaysSoOnlyAfterAVerifiedCheck() {
        assertTrue(VoteNote.DIDNT_VOTE in VoteRules.notes(vote(VoteState.CLOSED, null, check(CheckState.VERIFIED))))
        assertFalse(VoteNote.DIDNT_VOTE in VoteRules.notes(vote(VoteState.CLOSED, null, check(CheckState.PENDING))))
        assertEquals(VoteChip.MISSED, VoteRules.chip(vote(VoteState.CLOSED, null, null)))
    }

    @Test
    fun theReasonACastIsNotOfferedIsWrittenForEachCode() {
        val table = mapOf(
            "not_open" to VoteNote.NOT_OPEN,
            "closed" to VoteNote.CLOSED,
            "closing" to VoteNote.CLOSING_SOON,
            "site_mismatch" to VoteNote.SITE_MISMATCH,
            "connection_unavailable" to VoteNote.NO_CONNECTION,
            "not_signed_in" to VoteNote.NOT_SIGNED_IN,
            "something_new" to VoteNote.CANNOT_CAST_OTHER,
        )
        for ((code, want) in table) {
            assertEquals(code, want, VoteRules.cannotCastNote(vote(canCast = false, cannotCast = code)))
        }
        assertNull("the ballot says it", VoteRules.cannotCastNote(vote(canCast = false, cannotCast = "already_voted")))
        assertNull(VoteRules.cannotCastNote(vote()))
    }

    @Test
    fun aCheckingVoteSaysWhyForEachReasonAndCannotBeCast() {
        val table = mapOf(
            "service_list" to VoteNote.CHECKING_LIST,
            "trust_root" to VoteNote.CHECKING_TRUST,
            "clock" to VoteNote.CHECKING_CLOCK,
            "site" to VoteNote.CHECKING_SITE,
            "restore" to VoteNote.CHECKING_RESTORE,
            "something_new" to VoteNote.CHECKING_OTHER,
        )
        for ((reason, want) in table) {
            val v = vote(VoteState.CHECKING, reason = reason, canCast = false, cannotCast = "checking")
            assertEquals(reason, listOf(want), VoteRules.notes(v))
            assertFalse(VoteRules.castAllowed(v))
            assertEquals(VoteChip.CHECKING, VoteRules.chip(v))
        }
    }

    @Test
    fun noCastWhileAConfirmationWaitsOrWhenTheVaultSaysNo() {
        assertTrue(VoteRules.castAllowed(vote()))
        assertFalse(VoteRules.castAllowed(vote(confirmPending = true, canCast = true)))
        assertFalse(VoteRules.castAllowed(vote(canCast = false, cannotCast = "closing")))
        assertFalse(VoteRules.castAllowed(vote(ballot = ballot(BallotState.SENDING), canCast = true)))
        assertFalse(VoteRules.castAllowed(vote(VoteState.UPCOMING, canCast = true)))
        assertEquals(VoteNote.CHECKING_RESTORE, VoteRules.cannotCastNote(vote(confirmPending = true, canCast = true)))
    }

    @Test
    fun siteNotesAreWarningsBeforeACastAndTheAfterTextAfterOne() {
        assertTrue(VoteNote.NOT_PUBLISHED in VoteRules.notes(vote(site = SiteState.NOT_LISTED)))
        assertTrue(VoteNote.NOT_LISTED_LATE in VoteRules.notes(vote(site = SiteState.NOT_LISTED), notListedLate = true))
        assertTrue(VoteNote.NOT_LISTED_LATE in VoteRules.notes(vote(VoteState.CLOSED, site = SiteState.NOT_LISTED)))
        assertTrue(VoteNote.SITE_UNREACHABLE in VoteRules.notes(vote(site = SiteState.UNREACHABLE)))
        assertTrue(VoteNote.SITE_MISMATCH in VoteRules.notes(vote(canCast = false, cannotCast = "site_mismatch", site = SiteState.MISMATCH)))
        assertTrue(VoteNote.SITE_MISMATCH_AFTER in VoteRules.notes(vote(ballot = ballot(BallotState.RECORDED), site = SiteState.MISMATCH)))
        assertTrue(VoteNote.UNPUBLISHED in VoteRules.notes(vote(site = SiteState.UNPUBLISHED)))
        assertTrue(VoteRules.hasProof(vote(site = SiteState.UNPUBLISHED)))
        assertFalse(VoteRules.hasProof(vote(site = SiteState.NOT_LISTED)))
        // A warning does not stop a cast.
        assertTrue(VoteRules.castAllowed(vote(site = SiteState.UNREACHABLE)))
    }

    @Test
    fun aRejectedVoteSaysWhyAndOffersTheProof() {
        val v = vote(VoteState.REJECTED, reason = "signature", canCast = false, cannotCast = "closed")
        assertEquals(listOf(VoteNote.REJECTED), VoteRules.notes(v))
        assertTrue(VoteRules.hasProof(v))
        assertEquals(RejectedWhy.SIGNATURE, VoteRules.rejectedWhy("signature"))
        assertEquals(RejectedWhy.ANCHOR, VoteRules.rejectedWhy("anchor"))
        assertEquals(RejectedWhy.RECORD, VoteRules.rejectedWhy("record"))
        assertEquals(RejectedWhy.OTHER, VoteRules.rejectedWhy("malformed"))
        assertEquals(VoteChip.PROBLEM, VoteRules.chip(v))
        assertFalse(VoteRules.inOpenSection(vote(VoteState.CLOSED)))
        assertTrue(VoteRules.inOpenSection(v))
    }

    @Test
    fun theProofIsOfferedForProblemsOnlyAndNotForAPlainFinalRefusal() {
        assertTrue(VoteRules.hasProof(vote(ballot = ballot(BallotState.CONFLICT))))
        assertTrue(VoteRules.hasProof(vote(ballot = ballot(BallotState.DISPUTED))))
        assertTrue(VoteRules.hasProof(vote(ballot = ballot(BallotState.REFUSED, "not_eligible"))))
        assertTrue(VoteRules.hasProof(vote(ballot = ballot(BallotState.REFUSED, "bad_ballot"))))
        assertFalse(VoteRules.hasProof(vote(ballot = ballot(BallotState.REFUSED, "closed"))))
        assertFalse(VoteRules.hasProof(vote(ballot = ballot(BallotState.REFUSED, "late"))))
        assertFalse(VoteRules.hasProof(vote(ballot = ballot(BallotState.REFUSED, "connection_ended"))))
        // The vault makes not_active a problem record: it needs the proof and then Release.
        assertTrue(VoteRules.hasProof(vote(ballot = ballot(BallotState.REFUSED, "not_active"))))
        assertFalse(VoteRules.hasProof(vote(ballot = ballot(BallotState.RECORDED))))
    }

    @Test
    fun chipsReadLikeTheScreenSays() {
        assertEquals(VoteChip.NOT_VOTED, VoteRules.chip(vote()))
        assertEquals(VoteChip.NOT_VOTED, VoteRules.chip(vote(VoteState.UPCOMING)))
        assertEquals(VoteChip.RECORDED, VoteRules.chip(vote(ballot = ballot(BallotState.RECORDED))))
        assertEquals(VoteChip.WAITING, VoteRules.chip(vote(ballot = ballot(BallotState.SENDING))))
        assertEquals(VoteChip.WAITING, VoteRules.chip(vote(ballot = ballot(BallotState.WAITING))))
        assertEquals(VoteChip.RESULTS, VoteRules.chip(vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.VERIFIED))))
        assertEquals(VoteChip.RESULTS, VoteRules.chip(vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.UNCONFIRMED))))
        assertEquals(VoteChip.CHECKING, VoteRules.chip(vote(VoteState.CLOSED, ballot(BallotState.RECORDED), check(CheckState.PENDING))))
        assertEquals(VoteChip.PROBLEM, VoteRules.chip(vote(ballot = ballot(BallotState.REFUSED, "closed"))))
        assertEquals(VoteChip.CHECKING, VoteRules.chip(vote(VoteState.UNKNOWN)))
    }

    @Test
    fun theBadgeCountsOpenVotesNotYetVotedOnAndNothingElse() {
        val list = listOf(
            vote(), // open, not voted
            vote(), // another
            vote(VoteState.UPCOMING),
            vote(VoteState.CHECKING),
            vote(VoteState.CLOSED),
            vote(ballot = ballot(BallotState.RECORDED)),
            vote(ballot = ballot(BallotState.SENDING)),
        )
        assertEquals(2, VoteRules.badge(list))
        assertEquals(0, VoteRules.badge(emptyList()))
    }

    @Test
    fun aVoteIsUnfinishedWhileOpenOrItsBallotIsNotFinal() {
        assertTrue(VoteRules.unfinished(vote()))
        assertTrue(VoteRules.unfinished(vote(VoteState.UPCOMING)))
        assertTrue(VoteRules.unfinished(vote(VoteState.CHECKING)))
        assertTrue(VoteRules.unfinished(vote(VoteState.CLOSED, ballot(BallotState.SENDING))))
        assertTrue(VoteRules.unfinished(vote(VoteState.CLOSED, ballot(BallotState.WAITING))))
        assertFalse(VoteRules.unfinished(vote(VoteState.CLOSED, ballot(BallotState.RECORDED))))
        assertFalse(VoteRules.unfinished(vote(VoteState.CLOSED)))
    }

    @Test
    fun theWireReadsAListingWithoutTrustingIt() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"votes":[{"proposal_id":"P1","connection_id":"C1","title":"A","state":"newstate","ballot":{"state":"x"},"check":{"state":"y"},"site":"z"},{"connection_id":"C2"},"junk"],"dropped":[{"count":2}],"problems":[],"reconnect":[{"connection_id":"C9","service_id":"s","name":"N"}],"list_version":4,"list_sha256":"AA"}""",
        ) as kotlinx.serialization.json.JsonObject
        val l = VoteWire.listing(o)
        // An entry without both ids is not listed; an unknown state is shown as being checked.
        assertEquals(1, l.votes.size)
        assertEquals(VoteState.UNKNOWN, l.votes[0].state)
        assertEquals(BallotState.UNKNOWN, l.votes[0].ballot?.state)
        assertEquals(CheckState.UNKNOWN, l.votes[0].check?.state)
        assertNull(l.votes[0].site)
        assertTrue(l.dropped.isEmpty())
        assertEquals(4L, l.listVersion)
        assertEquals("C9", l.reconnect.single().connectionId)
    }

    @Test
    fun theKeyOfAVoteIsThePairNeverTheProposalIdAlone() {
        assertEquals("c/p", VoteKeys.of("c", "p"))
        assertTrue(VoteKeys.of("a", "b") != VoteKeys.of("b", "a"))
    }
}
