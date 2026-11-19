// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.votes

import androidx.test.core.app.ApplicationProvider
import com.vettid.core.data.votes.RejectedWhy
import com.vettid.core.data.votes.VoteChip
import com.vettid.core.data.votes.VoteChoice
import com.vettid.core.data.votes.VoteDecision
import com.vettid.core.data.votes.VoteNote
import com.vettid.core.data.votes.VoteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The texts of ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes" word for word (the owner approved them on 2026-10-30; a changed
 * text is the owner's to approve), and the rule that every state has one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoteTextsTest {
    private val r = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun theConflictFoundAfterAVerifiedCheckIsTheOwnersText() {
        assertEquals(
            "**Your vote is in the published results.** %1\$s also once showed a different vote under your code. Save the proof and show it to the organization.",
            r.getString(R.string.vote_conflict_after_verified),
        )
        assertEquals(
            "Your vote is in the published results. Example also once showed a different vote under your code. Save the proof and show it to the organization.",
            plainText(r.getString(R.string.vote_conflict_after_verified, "Example")),
        )
    }

    @Test
    fun theClockCheckIsTheOwnersText() {
        assertEquals(
            "VettID can't finish checking this vote yet because of a time check on VettID's side. It will try again.",
            r.getString(R.string.vote_checking_clock),
        )
    }

    @Test
    fun theApprovedTextsAreWordForWord() {
        val s = "Example"
        val table = listOf(
            R.string.vote_disclosure to "Example will see how you vote and keep that record. The published results show only an anonymous code for you.",
            R.string.vote_final to "Your vote can't be changed after you cast it.",
            R.string.vote_state_sending to "Sending your vote to Example…",
            R.string.vote_state_waiting to "Waiting for Example to confirm. Your vote is saved on your vault and is sent again until Example answers.",
            R.string.vote_state_recorded to "Example recorded your vote. It can't be changed.",
            R.string.vote_state_in_results to "The published results include your vote.",
            R.string.vote_state_unconfirmed to "Not confirmed. Example sent no receipt for your vote, and the published results don't settle whether it counted.",
            R.string.vote_failed_title to "The published results don't match your vote",
            R.string.vote_conflict_body to "Example holds a different vote under your code than the one you cast. Save the proof and show it to the organization.",
            R.string.vote_state_late_pending to "Example says the vote had closed. VettID is asking whether an earlier send arrived in time.",
            R.string.vote_refused_late to "The vote had closed when your vote arrived, so it was not counted.",
            R.string.vote_refused_not_active to "Example says you are no longer a member for this vote, so your vote was not counted.",
            R.string.vote_refused_not_eligible to "Example says you were not on the list for this vote. You can save the proof and show it to the organization.",
            R.string.vote_refused_bad_ballot to "Example did not accept your vote. You can save the proof and show it to the organization.",
            R.string.vote_refused_ended to "Your connection to Example ended before your vote was recorded.",
            R.string.vote_not_signed_in to "Before you can vote, sign in to Example once with VettID. This lets Example check that your vote is yours.",
            R.string.vote_closing_soon to "This vote closes in less than 2 minutes. It is too late to cast your vote from this phone.",
            R.string.vote_checking_list to "VettID is checking this vote. It needs the list of services from votes.vettid.org. Check that this phone is online.",
            R.string.vote_checking_trust to "VettID can't finish checking this vote yet: its list of trusted logs may be out of date. Update VettID if this lasts.",
            R.string.vote_checking_restore to "VettID is asking Example what it recorded for you before you can vote.",
            R.string.vote_site_mismatch_after to "The vote you voted on differs from the one published on the public site, so your vote may not count. Save the proof and show it to the organization.",
            R.string.vote_unpublished to "This vote was never published on the public site. Your vote may not count. Save the proof.",
            R.string.vote_not_listed_late to "This vote is still not on the public site, which is unusual. You can vote, but keep the proof.",
            R.string.vote_remove_warning to "You have a vote with Example that isn't finished. If you remove this connection you can't vote in it.",
            R.string.vote_dropped to "Example has more votes under way than your vault can keep, so 3 were not kept.",
        )
        for ((id, want) in table) {
            val text = if (id == R.string.vote_dropped) r.getString(id, s, 3) else r.getString(id, s)
            assertEquals(want, text)
        }
    }

    @Test
    fun noTextEverCallsTheVoteSecretOrVerifiedOrSafe() {
        // Of the service the app says nothing "verified", "safe" or "secret" (the approved "trusted logs" is VettID's own list).
        val forbidden = listOf("secret", "verified", "safe")
        val fields = R.string::class.java.fields.filter { it.name.startsWith("vote") }
        assertTrue(fields.isNotEmpty())
        for (f in fields) {
            val text = r.getString(f.getInt(null)).lowercase()
            for (w in forbidden) assertFalse("${f.name}: $w", Regex("\\b$w\\b").containsMatchIn(text))
        }
    }

    @Test
    fun everyNoteAndEveryChipHasItsText() {
        for (n in VoteNote.entries) {
            val text = VoteTexts.text(r, n, "Example", "tomorrow", if (n == VoteNote.REJECTED) RejectedWhy.SIGNATURE else null)
            assertTrue("$n", text.isNotBlank())
        }
        for (c in VoteChip.entries) assertTrue(r.getString(VoteTexts.chip(c)).isNotBlank())
        // A time is named only where the text names one.
        assertEquals("This vote opens tomorrow.", VoteTexts.text(r, VoteNote.NOT_OPEN, "Example", "tomorrow"))
    }

    @Test
    fun theDontAddUpLineAndAbstainAreTheOwnersTexts() {
        // ANDROID-PLAN 0.1.116 table, owner decision a, 2026-11-02.
        assertEquals(
            "The rest of the published results don't add up. The service may have published them wrongly.",
            r.getString(R.string.vote_results_dont_add_up),
        )
        assertEquals("Abstain", r.getString(R.string.vote_choice_abstain))
        assertEquals(r.getString(R.string.vote_results_dont_add_up), VoteTexts.text(r, VoteNote.RESULTS_DONT_ADD_UP, "Example", ""))
    }

    @Test
    fun theRuleAndOutcomeLinesAreTheOwnersTexts() {
        // Approved by the owner 2026-11-18, the same words as the iPhone app (the `cast` lines and the 1/1 lines as chosen then).
        val rows = listOf(
            R.string.vote_rule_majority_cast to "Decided by more than half of the votes, not counting abstentions.",
            R.string.vote_rule_majority_voted to "Decided by more than half of all ballots. Abstaining counts against.",
            R.string.vote_rule_majority_elig to "Decided by more than half of all eligible members. Not voting counts against.",
            R.string.vote_rule_all_cast to "Decided only if all the votes, not counting abstentions, are for one choice.",
            R.string.vote_rule_all_voted to "Decided only if all ballots are for one choice. Abstaining counts against.",
            R.string.vote_rule_all_elig to "Decided only if all eligible members vote for one choice. Not voting counts against.",
            R.string.vote_rule_plurality to "Decided by the most votes. A tie at the top decides nothing.",
            R.string.vote_outcome_not_decided to "Not decided: no choice met the rule.",
            R.string.vote_outcome_tied to "Tied: no choice decided.",
            R.string.vote_outcome_no_quorum to "No quorum: too few members voted.",
        )
        for ((id, text) in rows) assertEquals(text, r.getString(id))
        assertEquals("Decided by at least 2/3 of the votes, not counting abstentions.", r.getString(R.string.vote_rule_threshold_cast, "2/3"))
        assertEquals("Decided by at least 2/3 of all ballots. Abstaining counts against.", r.getString(R.string.vote_rule_threshold_voted, "2/3"))
        assertEquals("Decided by at least 2/3 of all eligible members. Not voting counts against.", r.getString(R.string.vote_rule_threshold_elig, "2/3"))
        assertEquals("Decided: Example", r.getString(R.string.vote_outcome_decided, "Example"))
    }

    @Test
    fun eachRuleReadsAsTheOwnerApproved() {
        fun rule(rule: VoteDecision.Rule, base: VoteDecision.Base?) = VoteTexts.rule(r, VoteDecision(rule, base))
        val maj = VoteDecision.Rule.Majority
        assertEquals("Decided by more than half of the votes, not counting abstentions.", rule(maj, VoteDecision.Base.CAST))
        assertEquals("Decided by more than half of all ballots. Abstaining counts against.", rule(maj, VoteDecision.Base.VOTED))
        assertEquals("Decided by more than half of all eligible members. Not voting counts against.", rule(maj, VoteDecision.Base.ELIG))
        assertEquals("Decided by at least 2/3 of the votes, not counting abstentions.", rule(VoteDecision.Rule.Threshold(2, 3), VoteDecision.Base.CAST))
        assertEquals("Decided by at least 3/4 of all ballots. Abstaining counts against.", rule(VoteDecision.Rule.Threshold(3, 4), VoteDecision.Base.VOTED))
        assertEquals("Decided by at least 9/10 of all eligible members. Not voting counts against.", rule(VoteDecision.Rule.Threshold(9, 10), VoteDecision.Base.ELIG))
        assertEquals("Decided only if all the votes, not counting abstentions, are for one choice.", rule(VoteDecision.Rule.Threshold(1, 1), VoteDecision.Base.CAST))
        assertEquals("Decided only if all ballots are for one choice. Abstaining counts against.", rule(VoteDecision.Rule.Threshold(1, 1), VoteDecision.Base.VOTED))
        assertEquals("Decided only if all eligible members vote for one choice. Not voting counts against.", rule(VoteDecision.Rule.Threshold(1, 1), VoteDecision.Base.ELIG))
        assertEquals("Decided by the most votes. A tie at the top decides nothing.", rule(VoteDecision.Rule.Plurality, null))
        assertNull(rule(maj, null))
        assertNull(rule(VoteDecision.Rule.Threshold(2, 3), null))
    }

    @Test
    fun eachOutcomeReadsAsTheOwnerApproved() {
        val choices = listOf(VoteChoice("yes", "Yes"), VoteChoice("no", ""), VoteChoice("abstain", "Abstain"))
        fun outcome(status: VoteOutcome.Status, choice: String? = null) = VoteTexts.outcome(r, VoteOutcome(status, choice), choices)
        assertEquals("Decided: \u2068Yes\u2069", outcome(VoteOutcome.Status.DECIDED, "yes"))
        assertEquals("Decided: no", outcome(VoteOutcome.Status.DECIDED, "no")?.replace("\u2068", "")?.replace("\u2069", ""))
        assertNull("abstain never decides", outcome(VoteOutcome.Status.DECIDED, "abstain"))
        assertNull("not a choice of the vote", outcome(VoteOutcome.Status.DECIDED, "maybe"))
        assertEquals("Not decided: no choice met the rule.", outcome(VoteOutcome.Status.NOT_DECIDED))
        assertEquals("Tied: no choice decided.", outcome(VoteOutcome.Status.TIED))
        assertEquals("No quorum: too few members voted.", outcome(VoteOutcome.Status.NO_QUORUM))
    }

    @Test
    fun theNotRecheckedLineIsTheOwnersText() {
        // ANDROID-PLAN 0.1.121 table, approved by the owner 2026-11-03.
        assertEquals(
            "VettID could not check the published results of this vote again just now, so their numbers are not shown. " +
                "It will try again when you open this vote.",
            r.getString(R.string.vote_results_not_rechecked),
        )
        assertEquals(r.getString(R.string.vote_results_not_rechecked), VoteTexts.text(r, VoteNote.RESULTS_NOT_RECHECKED, "Example", ""))
    }

    @Test
    fun theGiveUpFeedItemIsTheOwnersText() {
        // Owner decision a, 2026-11-06 (thread 01M6RH58PE19ZBEHX3JK5ZW06A), word for word; the second line is data_feed_vote_line.
        assertEquals("Vote results not checked yet", r.getString(com.vettid.core.data.R.string.data_feed_vote_result_gave_up))
        assertEquals(
            "VettID couldn't check the published results of this vote yet. It will check them again by itself when it can. " +
                "You don't need to do anything.",
            r.getString(com.vettid.core.data.R.string.data_feed_vote_result_gave_up_detail),
        )
        assertEquals("Example: Adopt the budget", r.getString(com.vettid.core.data.R.string.data_feed_vote_line, "Example", "Adopt the budget"))
    }

    @Test
    fun theReservedAbstainIsAlwaysTheAppsOwnWord() {
        assertEquals("Abstain", VoteTexts.choiceLabel(r, "abstain", "Skip this one"))
        assertEquals("Abstain", VoteTexts.choiceLabel(r, "abstain", null))
        assertEquals("Yes", VoteTexts.choiceLabel(r, "yes", "Yes"))
        assertEquals(null, VoteTexts.choiceLabel(r, "yes", null))
        assertEquals(null, VoteTexts.choiceLabel(r, null, null))
    }

    @Test
    fun boldMarkersAreBoldAndNeverShown() {
        val a = boldMarked("**Bold.** Rest")
        assertEquals("Bold. Rest", a.text)
        assertEquals(1, a.spanStyles.size)
        assertEquals("No markers", boldMarked("No markers").text)
        // An unmatched marker is shown as it is rather than swallowing text.
        assertEquals("a ** b", boldMarked("a ** b").text)
    }
}
