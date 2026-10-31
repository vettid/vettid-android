package com.vettid.feature.votes

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.vettid.core.data.votes.RejectedWhy
import com.vettid.core.data.votes.VoteChip
import com.vettid.core.data.votes.VoteNote

/**
 * The words of the Votes screens (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes"): which text a state of the vault reads as.
 * The vault decides; this maps what it decided to the plan's table, and nothing here says more than the table.
 */
internal object VoteTexts {
    @StringRes
    fun chip(c: VoteChip): Int = when (c) {
        VoteChip.NOT_VOTED -> R.string.vote_chip_not_voted
        VoteChip.RECORDED -> R.string.vote_chip_recorded
        VoteChip.WAITING -> R.string.vote_chip_waiting
        VoteChip.CHECKING -> R.string.vote_chip_checking
        VoteChip.RESULTS -> R.string.vote_chip_results
        VoteChip.PROBLEM -> R.string.vote_chip_problem
        VoteChip.MISSED -> R.string.vote_chip_missed
    }

    /** The text of a note. `FAILED` has a title and a body ([title], [body]); `REJECTED` a title and the reason after it. */
    @StringRes
    @Suppress("CyclomaticComplexMethod")
    fun body(n: VoteNote): Int = when (n) {
        VoteNote.SENDING -> R.string.vote_state_sending
        VoteNote.WAITING -> R.string.vote_state_waiting
        VoteNote.RECORDED -> R.string.vote_state_recorded
        VoteNote.IN_RESULTS -> R.string.vote_state_in_results
        VoteNote.IN_RESULTS_CONFLICT -> R.string.vote_conflict_after_verified
        VoteNote.UNCONFIRMED -> R.string.vote_state_unconfirmed
        VoteNote.DIDNT_VOTE -> R.string.vote_state_didnt_vote
        VoteNote.CHECK_PENDING -> R.string.vote_check_pending
        VoteNote.CHECK_RESULTS_PROBLEM -> R.string.vote_check_results_problem
        VoteNote.CHECK_GAVE_UP_SITE -> R.string.vote_check_gave_up_site
        VoteNote.CHECK_GAVE_UP_UNPUBLISHED -> R.string.vote_check_gave_up_unpublished
        VoteNote.CHECK_GAVE_UP -> R.string.vote_check_gave_up
        VoteNote.FAILED -> R.string.vote_failed_body
        VoteNote.CONFLICT -> R.string.vote_conflict_body
        VoteNote.DISPUTED -> R.string.vote_disputed_body
        VoteNote.LATE_PENDING -> R.string.vote_state_late_pending
        VoteNote.REFUSED_LATE -> R.string.vote_refused_late
        VoteNote.REFUSED_NOT_ACTIVE -> R.string.vote_refused_not_active
        VoteNote.REFUSED_NOT_ELIGIBLE -> R.string.vote_refused_not_eligible
        VoteNote.REFUSED_BAD_BALLOT -> R.string.vote_refused_bad_ballot
        VoteNote.REFUSED_ENDED -> R.string.vote_refused_ended
        VoteNote.NOT_SIGNED_IN -> R.string.vote_not_signed_in
        VoteNote.CLOSING_SOON -> R.string.vote_closing_soon
        VoteNote.NOT_OPEN -> R.string.vote_not_open
        VoteNote.CLOSED -> R.string.vote_closed_banner
        VoteNote.CHECKING_LIST -> R.string.vote_checking_list
        VoteNote.CHECKING_SITE -> R.string.vote_checking_site
        VoteNote.CHECKING_TRUST -> R.string.vote_checking_trust
        VoteNote.CHECKING_CLOCK -> R.string.vote_checking_clock
        VoteNote.CHECKING_RESTORE -> R.string.vote_checking_restore
        VoteNote.CHECKING_OTHER -> R.string.vote_checking_other
        VoteNote.NO_CONNECTION -> R.string.vote_no_connection
        VoteNote.CANNOT_CAST_OTHER -> R.string.vote_cannot_cast_other
        VoteNote.REJECTED -> R.string.vote_rejected_other
        VoteNote.NOT_PUBLISHED -> R.string.vote_not_published
        VoteNote.SITE_UNREACHABLE -> R.string.vote_site_unreachable
        VoteNote.SITE_MISMATCH -> R.string.vote_site_mismatch
        VoteNote.SITE_MISMATCH_AFTER -> R.string.vote_site_mismatch_after
        VoteNote.UNPUBLISHED -> R.string.vote_unpublished
        VoteNote.NOT_LISTED_LATE -> R.string.vote_not_listed_late
    }

    /** A heading over a note's body, for the two that have one. */
    @StringRes
    fun title(n: VoteNote): Int? = when (n) {
        VoteNote.FAILED -> R.string.vote_failed_title
        VoteNote.REJECTED -> R.string.vote_rejected_title
        else -> null
    }

    @StringRes
    fun rejected(why: RejectedWhy): Int = when (why) {
        RejectedWhy.SIGNATURE -> R.string.vote_rejected_signature
        RejectedWhy.ANCHOR -> R.string.vote_rejected_anchor
        RejectedWhy.RECORD -> R.string.vote_rejected_record
        RejectedWhy.OTHER -> R.string.vote_rejected_other
    }

    /**
     * A note's text. [service] is the service's name (isolated), [time] the time `vote_not_open` names. A note whose text
     * names neither ignores the arguments.
     */
    fun text(r: Resources, n: VoteNote, service: String, time: String, rejectedReason: RejectedWhy? = null): String {
        if (n == VoteNote.REJECTED && rejectedReason != null) return r.getString(rejected(rejectedReason))
        return r.getString(body(n), if (n == VoteNote.NOT_OPEN) time else service)
    }
}

/** `**bold**` in a text of the table, as bold; the asterisks are never shown (`vote_conflict_after_verified`). */
internal fun boldMarked(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val a = rest.indexOf(BOLD)
        val b = if (a < 0) -1 else rest.indexOf(BOLD, a + BOLD.length)
        if (a < 0 || b < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, a))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(a + BOLD.length, b)) }
        rest = rest.substring(b + BOLD.length)
    }
}

/** The same text as the screen reader reads it: without the markers. */
internal fun plainText(text: String): String = text.replace(BOLD, "")

private const val BOLD = "**"

@Composable
internal fun rememberResources(): Resources = LocalContext.current.resources
