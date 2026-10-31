// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.votes

import androidx.test.core.app.ApplicationProvider
import com.vettid.core.data.votes.RejectedWhy
import com.vettid.core.data.votes.VoteChip
import com.vettid.core.data.votes.VoteNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "**Your vote is in the published results.** %1\$s also once showed a different vote under your code. Save the proof and show it to the organisation.",
            r.getString(R.string.vote_conflict_after_verified),
        )
        assertEquals(
            "Your vote is in the published results. Example also once showed a different vote under your code. Save the proof and show it to the organisation.",
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
            R.string.vote_conflict_body to "Example holds a different vote under your code than the one you cast. Save the proof and show it to the organisation.",
            R.string.vote_state_late_pending to "Example says the vote had closed. VettID is asking whether an earlier send arrived in time.",
            R.string.vote_refused_late to "The vote had closed when your vote arrived, so it was not counted.",
            R.string.vote_refused_not_active to "Example says you are no longer a member for this vote, so your vote was not counted.",
            R.string.vote_refused_not_eligible to "Example says you were not on the list for this vote. You can save the proof and show it to the organisation.",
            R.string.vote_refused_bad_ballot to "Example did not accept your vote. You can save the proof and show it to the organisation.",
            R.string.vote_refused_ended to "Your connection to Example ended before your vote was recorded.",
            R.string.vote_not_signed_in to "Before you can vote, sign in to Example once with VettID. This lets Example check that your vote is yours.",
            R.string.vote_closing_soon to "This vote closes in less than 2 minutes. It is too late to cast your vote from this phone.",
            R.string.vote_checking_list to "VettID is checking this vote. It needs the list of services from votes.vettid.org. Check that this phone is online.",
            R.string.vote_checking_trust to "VettID can't finish checking this vote yet: its list of trusted logs may be out of date. Update VettID if this lasts.",
            R.string.vote_checking_restore to "VettID is asking Example what it recorded for you before you can vote.",
            R.string.vote_site_mismatch_after to "The vote you voted on differs from the one published on the public site, so your vote may not count. Save the proof and show it to the organisation.",
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
    fun boldMarkersAreBoldAndNeverShown() {
        val a = boldMarked("**Bold.** Rest")
        assertEquals("Bold. Rest", a.text)
        assertEquals(1, a.spanStyles.size)
        assertEquals("No markers", boldMarked("No markers").text)
        // An unmatched marker is shown as it is rather than swallowing text.
        assertEquals("a ** b", boldMarked("a ** b").text)
    }
}
