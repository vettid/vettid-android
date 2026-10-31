package com.vettid.core.data.votes

/** The status chip of a row of Votes (ANDROID-PLAN 0.1.102 §4 "Votes", screen 1): always with its words, never colour alone. */
enum class VoteChip { NOT_VOTED, RECORDED, WAITING, CHECKING, RESULTS, PROBLEM, MISSED }

/**
 * What a vote screen says, in the order it says it (the texts are the screens' catalog: `vote_*`). [arg] is what the text
 * names: the service for most, a time for [NOT_OPEN]. Severity is [VoteNote.Severity]: a conflict found after a verified
 * check is a note, never an error ([IN_RESULTS_CONFLICT]).
 */
enum class VoteNote(val severity: Severity) {
    SENDING(Severity.INFO),
    WAITING(Severity.INFO),
    RECORDED(Severity.INFO),
    IN_RESULTS(Severity.INFO),

    /** `vote_conflict_after_verified`, under [IN_RESULTS]: the check is `verified` and the vault holds conflict evidence. */
    IN_RESULTS_CONFLICT(Severity.NOTE),
    UNCONFIRMED(Severity.WARNING),
    DIDNT_VOTE(Severity.INFO),
    CHECK_PENDING(Severity.INFO),

    /** A check the vault leaves `pending` with reason `input`, or the app's own check of the results failed: never "failed". */
    CHECK_RESULTS_PROBLEM(Severity.WARNING),
    CHECK_GAVE_UP_SITE(Severity.WARNING),
    CHECK_GAVE_UP_UNPUBLISHED(Severity.WARNING),
    CHECK_GAVE_UP(Severity.WARNING),
    FAILED(Severity.ERROR),
    CONFLICT(Severity.ERROR),
    DISPUTED(Severity.ERROR),
    LATE_PENDING(Severity.INFO),
    REFUSED_LATE(Severity.WARNING),
    REFUSED_NOT_ACTIVE(Severity.WARNING),
    REFUSED_NOT_ELIGIBLE(Severity.ERROR),
    REFUSED_BAD_BALLOT(Severity.ERROR),
    REFUSED_ENDED(Severity.WARNING),
    NOT_SIGNED_IN(Severity.WARNING),
    CLOSING_SOON(Severity.WARNING),
    NOT_OPEN(Severity.INFO),
    CLOSED(Severity.INFO),
    CHECKING_LIST(Severity.INFO),
    CHECKING_SITE(Severity.INFO),
    CHECKING_TRUST(Severity.INFO),
    CHECKING_CLOCK(Severity.INFO),
    CHECKING_RESTORE(Severity.INFO),
    CHECKING_OTHER(Severity.INFO),
    NO_CONNECTION(Severity.WARNING),
    CANNOT_CAST_OTHER(Severity.WARNING),
    REJECTED(Severity.ERROR),
    NOT_PUBLISHED(Severity.INFO),
    SITE_UNREACHABLE(Severity.INFO),
    SITE_MISMATCH(Severity.ERROR),
    SITE_MISMATCH_AFTER(Severity.ERROR),
    UNPUBLISHED(Severity.ERROR),
    NOT_LISTED_LATE(Severity.WARNING),
    ;

    enum class Severity { INFO, NOTE, WARNING, ERROR }
}

/** Why a rejected proposal was set aside, in the words of the screens (`reason` of a `rejected` vote, §10.19.3). */
enum class RejectedWhy { SIGNATURE, ANCHOR, RECORD, OTHER }

/**
 * The rules of how a vote reads (ANDROID-PLAN 0.1.108 §4 "Votes"), pure and in one place. The vault decides everything
 * that matters; these say what it decided in the member's words, and fail secure: a state or a code the app does not know
 * is shown as being checked or as a refusal, never as a vote that counts.
 */
@Suppress("TooManyFunctions")
object VoteRules {
    /** A ballot or check problem, a rejected proposal, or a site difference the vault set apart: "Needs your attention". */
    fun needsAttention(s: VoteSummary): Boolean {
        val b = s.ballot?.state
        return s.state == VoteState.REJECTED ||
            b == BallotState.CONFLICT || b == BallotState.DISPUTED || b == BallotState.REFUSED ||
            s.check?.state == CheckState.FAILED ||
            s.conflictEvidence ||
            s.site == SiteState.MISMATCH || s.site == SiteState.UNPUBLISHED
    }

    /**
     * Whether **Save the proof** is offered (screen 6): `failed`, `conflict`, `disputed`, `rejected`, `refused` (except a
     * plain final `closed`, `late` or `connection_ended`, which hold nothing to show; `not_active` is a problem record in
     * the vault, so it has the proof and then Release), a signed
     * `not_eligible`, conflict evidence and a site difference.
     */
    fun hasProof(s: VoteSummary): Boolean {
        val b = s.ballot
        val refusedWithProof = b?.state == BallotState.REFUSED && b.code !in PLAIN_REFUSALS
        return s.state == VoteState.REJECTED ||
            b?.state == BallotState.CONFLICT || b?.state == BallotState.DISPUTED || refusedWithProof ||
            s.check?.state == CheckState.FAILED || s.conflictEvidence ||
            s.site == SiteState.MISMATCH || s.site == SiteState.UNPUBLISHED
    }

    private val PLAIN_REFUSALS = setOf("closed", "late", "connection_ended")

    /** A row's chip. */
    fun chip(s: VoteSummary): VoteChip {
        val b = s.ballot?.state
        return when {
            needsAttention(s) -> VoteChip.PROBLEM
            // Refused with a plain code is not a problem to show a proof for, but it is not "You voted" either.
            b == BallotState.REFUSED -> VoteChip.PROBLEM
            s.state == VoteState.CHECKING || s.state == VoteState.UNKNOWN -> VoteChip.CHECKING
            s.state == VoteState.CLOSED -> closedChip(s)
            b == null -> VoteChip.NOT_VOTED
            b == BallotState.RECORDED -> VoteChip.RECORDED
            else -> VoteChip.WAITING
        }
    }

    private fun closedChip(s: VoteSummary): VoteChip {
        val b = s.ballot?.state
        val check = s.check?.state
        return when {
            b == null -> VoteChip.MISSED
            check == CheckState.VERIFIED || check == CheckState.UNCONFIRMED -> VoteChip.RESULTS
            check == CheckState.PENDING || check == CheckState.GAVE_UP -> VoteChip.CHECKING
            b == BallotState.RECORDED -> VoteChip.RECORDED
            else -> VoteChip.WAITING
        }
    }

    /** Whether the vote screen offers the choices and **Cast your vote** (their state in words says why not, when it can't be cast). */
    fun choosing(s: VoteSummary): Boolean = s.ballot == null && s.state != VoteState.CLOSED && s.state != VoteState.REJECTED

    /** The Open section: open, upcoming, being checked and rejected; the Closed section holds `closed`. */
    fun inOpenSection(s: VoteSummary): Boolean = s.state != VoteState.CLOSED

    /**
     * The drawer's Votes entry (ANDROID-PLAN 0.1.102): shown only when the vault answers `vote.list` and holds at least one
     * vote or lists a `reconnect` connection: a member with no service that votes sees nothing (no empty screen).
     */
    fun entry(votes: List<VoteSummary>, reconnect: List<ReconnectEntry>, availability: VotesAvailability): VotesEntry {
        val on = availability == VotesAvailability.AVAILABLE
        return VotesEntry(shown = on && (votes.isNotEmpty() || reconnect.isNotEmpty()), badge = if (on) badge(votes) else 0)
    }

    /** The drawer badge counts votes that are `open` and not yet voted on (not upcoming, checking or closed). */
    fun badge(votes: List<VoteSummary>): Int = votes.count { it.state == VoteState.OPEN && it.ballot == null }

    /** A vote that is not finished: removing its service connection first asks (screen 9). */
    fun unfinished(s: VoteSummary): Boolean {
        val b = s.ballot?.state
        return when {
            s.state == VoteState.OPEN || s.state == VoteState.UPCOMING || s.state == VoteState.CHECKING -> true
            b == BallotState.SENDING || b == BallotState.WAITING -> true
            else -> false
        }
    }

    /** Why a rejected proposal was set aside. */
    fun rejectedWhy(reason: String?): RejectedWhy = when (reason) {
        "signature" -> RejectedWhy.SIGNATURE
        "anchor" -> RejectedWhy.ANCHOR
        "record" -> RejectedWhy.RECORD
        else -> RejectedWhy.OTHER
    }

    /**
     * Whether **Cast your vote** may be pressed with a choice selected: the vault says so (`can_cast`), nothing waits on a
     * restore confirmation, and there is no ballot yet. The vault checks again.
     */
    fun castAllowed(s: VoteSummary): Boolean = s.canCast && !s.confirmPending && s.ballot == null && s.state == VoteState.OPEN

    /**
     * The notes of a vote screen, in the order they are said. [gaveUp] is why the app's last attempt at the results failed,
     * when it knows (a `gave_up` check says which of two things, ANDROID-PLAN 0.1.102 screen 4): null when it does not.
     * [resultsProblem] is a problem the app found itself in the site's results, kept until the next attempt.
     * [notListedLate]: the app has seen the vote missing from the site for 10 minutes or more (the summary carries no age).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun notes(s: VoteSummary, gaveUp: GaveUpWhy? = null, resultsProblem: Boolean = false, notListedLate: Boolean = false): List<VoteNote> {
        val out = ArrayList<VoteNote>()
        if (s.state == VoteState.CLOSED) out += VoteNote.CLOSED
        if (s.state == VoteState.REJECTED) out += VoteNote.REJECTED
        ballotNotes(s, out)
        checkNotes(s, gaveUp, resultsProblem, out)
        siteNotes(s, notListedLate, out)
        if (s.ballot == null && s.state != VoteState.CLOSED && s.state != VoteState.REJECTED) cannotCastNote(s)?.let { out += it }
        if (s.ballot == null && s.state == VoteState.CHECKING && out.none { it in CHECKING_NOTES }) out += checkingNote(s.reason)
        return out
    }

    private val CHECKING_NOTES = setOf(
        VoteNote.CHECKING_LIST, VoteNote.CHECKING_SITE, VoteNote.CHECKING_TRUST, VoteNote.CHECKING_CLOCK,
        VoteNote.CHECKING_RESTORE, VoteNote.CHECKING_OTHER,
    )

    private fun checkingNote(reason: String?): VoteNote = when (reason) {
        "service_list" -> VoteNote.CHECKING_LIST
        "site" -> VoteNote.CHECKING_SITE
        "trust_root" -> VoteNote.CHECKING_TRUST
        "clock" -> VoteNote.CHECKING_CLOCK
        "restore" -> VoteNote.CHECKING_RESTORE
        else -> VoteNote.CHECKING_OTHER
    }

    @Suppress("CyclomaticComplexMethod")
    private fun ballotNotes(s: VoteSummary, out: MutableList<VoteNote>) {
        val b = s.ballot ?: return
        out += when (b.state) {
            BallotState.SENDING -> VoteNote.SENDING
            BallotState.WAITING -> when (b.reason) {
                "not_signed_in" -> VoteNote.NOT_SIGNED_IN
                "closed" -> VoteNote.LATE_PENDING
                else -> VoteNote.WAITING
            }
            BallotState.RECORDED -> VoteNote.RECORDED
            BallotState.REFUSED -> when (b.code) {
                "closed", "late" -> VoteNote.REFUSED_LATE
                "not_active" -> VoteNote.REFUSED_NOT_ACTIVE
                "not_eligible" -> VoteNote.REFUSED_NOT_ELIGIBLE
                "connection_ended" -> VoteNote.REFUSED_ENDED
                else -> VoteNote.REFUSED_BAD_BALLOT
            }
            BallotState.CONFLICT -> VoteNote.CONFLICT
            BallotState.DISPUTED -> VoteNote.DISPUTED
            // A ballot state this app does not know is never a success: it reads as waiting for the service.
            BallotState.UNKNOWN -> VoteNote.WAITING
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun checkNotes(s: VoteSummary, gaveUp: GaveUpWhy?, resultsProblem: Boolean, out: MutableList<VoteNote>) {
        val check = s.check
        val recorded = s.ballot?.state == BallotState.RECORDED
        when (check?.state) {
            CheckState.VERIFIED -> when {
                s.ballot == null -> out += VoteNote.DIDNT_VOTE
                // "Include" only after the vault's check passed, and for a ballot it holds a receipt for.
                recorded -> {
                    out += VoteNote.IN_RESULTS
                    if (s.conflictEvidence) out += VoteNote.IN_RESULTS_CONFLICT
                }
            }
            CheckState.UNCONFIRMED -> out += VoteNote.UNCONFIRMED
            CheckState.FAILED -> out += VoteNote.FAILED
            CheckState.GAVE_UP -> out += when (gaveUp) {
                GaveUpWhy.SITE -> VoteNote.CHECK_GAVE_UP_SITE
                GaveUpWhy.UNPUBLISHED -> VoteNote.CHECK_GAVE_UP_UNPUBLISHED
                null -> VoteNote.CHECK_GAVE_UP
            }
            CheckState.PENDING -> out += when {
                check.reason == "input" || resultsProblem -> VoteNote.CHECK_RESULTS_PROBLEM
                // A `pending` check whose reason is the vault's own (service_list, trust_root, clock) says so.
                check.reason == "clock" -> VoteNote.CHECKING_CLOCK
                check.reason == "trust_root" -> VoteNote.CHECKING_TRUST
                check.reason == "service_list" -> VoteNote.CHECKING_LIST
                else -> VoteNote.CHECK_PENDING
            }
            CheckState.UNKNOWN, null -> Unit
        }
        // The app's own find in the site's results while the vault has not judged them yet.
        if (resultsProblem && check == null && s.state == VoteState.CLOSED) out += VoteNote.CHECK_RESULTS_PROBLEM
    }

    private fun siteNotes(s: VoteSummary, notListedLate: Boolean, out: MutableList<VoteNote>) {
        val after = s.ballot != null
        val note = when (s.site) {
            SiteState.MISMATCH -> if (after) VoteNote.SITE_MISMATCH_AFTER else VoteNote.SITE_MISMATCH
            SiteState.UNPUBLISHED -> VoteNote.UNPUBLISHED
            // A warning only (never stops a cast): the vault compares later.
            SiteState.NOT_LISTED -> if (s.state == VoteState.CLOSED || notListedLate) VoteNote.NOT_LISTED_LATE else VoteNote.NOT_PUBLISHED
            SiteState.UNREACHABLE -> VoteNote.SITE_UNREACHABLE
            SiteState.MATCHES, null -> null
        }
        if (note != null) out += note
    }

    /** The reason a cast is not offered (screen 2: the reason is written where the button would be). */
    @Suppress("ReturnCount")
    fun cannotCastNote(s: VoteSummary): VoteNote? {
        if (s.canCast && !s.confirmPending) return null
        if (s.confirmPending) return VoteNote.CHECKING_RESTORE
        return when (s.cannotCast) {
            null -> null
            "checking" -> checkingNote(s.reason)
            "not_open" -> VoteNote.NOT_OPEN
            "closed" -> VoteNote.CLOSED
            "closing" -> VoteNote.CLOSING_SOON
            "already_voted" -> null // the ballot's own note says it
            "site_mismatch" -> VoteNote.SITE_MISMATCH
            "connection_unavailable" -> VoteNote.NO_CONNECTION
            "not_signed_in" -> VoteNote.NOT_SIGNED_IN
            else -> VoteNote.CANNOT_CAST_OTHER
        }
    }
}

/** Why the app's last attempt at a vote's results failed (a `gave_up` check says which of two things). */
enum class GaveUpWhy { SITE, UNPUBLISHED }

/** The drawer's Votes entry: whether it is shown, and its badge. */
data class VotesEntry(val shown: Boolean = false, val badge: Int = 0)
