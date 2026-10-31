// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.CheckState
import com.vettid.core.data.votes.DroppedNotice
import com.vettid.core.data.votes.ProblemsNotice
import com.vettid.core.data.votes.ReconnectEntry
import com.vettid.core.data.votes.SiteState
import com.vettid.core.data.votes.VoteBallot
import com.vettid.core.data.votes.VoteChoice
import com.vettid.core.data.votes.VoteCheck
import com.vettid.core.data.votes.VoteCounts
import com.vettid.core.data.votes.VoteDetail
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.votes.CastStep
import com.vettid.feature.votes.VoteActions
import com.vettid.feature.votes.VoteScreen
import com.vettid.feature.votes.VoteUiState
import com.vettid.feature.votes.VotesScreen
import com.vettid.feature.votes.VotesUiState
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Votes (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes"): the list (open and closed, a notice, "Connect again to vote"), a vote
 * to cast, the confirm sheet, each state in words, the results, and the conflict found after a verified check.
 */
internal object VotesCatalog {
    private val now = Instant.now()
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private const val SERVICE = "Example Cooperative"
    private const val CONN = "c1"

    private fun summary(
        id: String,
        title: String,
        state: VoteState = VoteState.OPEN,
        ballot: VoteBallot? = null,
        check: VoteCheck? = null,
        conflictEvidence: Boolean = false,
        reason: String? = null,
        site: SiteState? = null,
        closesIn: Long = 5 * 24,
    ) = VoteSummary(
        proposalId = id, connectionId = CONN, title = title, state = state, reason = reason,
        opensAt = now.minus(2, ChronoUnit.DAYS), closesAt = now.plus(closesIn, ChronoUnit.HOURS),
        ballot = ballot, check = check, conflictEvidence = conflictEvidence, canCast = state == VoteState.OPEN && ballot == null,
        site = site,
    )

    private val choices = listOf(VoteChoice("yes", "Yes"), VoteChoice("no", "No"), VoteChoice("abstain", "Abstain"))

    private fun detail(s: VoteSummary, choiceId: String? = null, tally: Map<String, Long>? = null) = VoteDetail(
        summary = s,
        text = "Adopt the budget for the coming year.\n\nThe board proposes the attached figures for approval by the members.",
        choices = choices, quorumText = "At least 40 ballots", minBallots = 40, binding = true, recordTag = null, choiceId = choiceId,
        extensions = 0, tally = tally, counts = tally?.let { VoteCounts(120, 84, 0, 0) }, serviceBallotChoiceId = null,
    )

    private val open = summary("p1", "Adopt the budget")
    private val recorded = summary("p2", "Elect the board", ballot = VoteBallot(BallotState.RECORDED))
    private val closed = summary("p3", "Change the bylaws", VoteState.CLOSED, VoteBallot(BallotState.RECORDED), VoteCheck(CheckState.VERIFIED), closesIn = -72)
    private val missed = summary("p4", "Choose the venue", VoteState.CLOSED, closesIn = -200)

    private fun list(vararg v: VoteSummary) = VotesUiState(votes = v.toList(), services = mapOf(CONN to SERVICE), load = ListLoad.LOADED)

    @Composable
    private fun Vote(s: VoteSummary, d: VoteDetail? = detail(s), selected: String? = null, cast: CastStep = CastStep.NONE, hint: com.vettid.core.data.votes.VoteHint = com.vettid.core.data.votes.VoteHint()) =
        VoteScreen(VoteUiState(connectionId = CONN, proposalId = s.proposalId, summary = s, detail = d, serviceName = SERVICE, loaded = true, selected = selected, cast = cast, hint = hint), VoteActions())

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "votes" to { VotesScreen(list(open, recorded, closed, missed), chrome) },
        "votes.notices" to {
            VotesScreen(
                list(open).copy(
                    reconnect = listOf(ReconnectEntry("c9", "svc", "Old Cooperative")),
                    dropped = listOf(DroppedNotice(CONN, 3)),
                    problems = listOf(ProblemsNotice(CONN, 16)),
                ),
                chrome,
            )
        },
        "votes.empty" to { VotesScreen(list(), chrome) },
        "vote" to { Vote(open, selected = "yes") },
        "vote.confirm" to { Vote(open, selected = "yes", cast = CastStep.CONFIRM) },
        "vote.password" to { Vote(open, selected = "yes", cast = CastStep.PASSWORD) },
        "vote.recorded" to { Vote(recorded, detail(recorded, "yes")) },
        "vote.sending" to { Vote(summary("p5", "Adopt the budget", ballot = VoteBallot(BallotState.SENDING)), detail(open, "no")) },
        "vote.waiting" to { Vote(summary("p5", "Adopt the budget", ballot = VoteBallot(BallotState.WAITING, reason = "not_open")), detail(open, "no")) },
        "vote.results" to { Vote(closed, detail(closed, "yes", mapOf("yes" to 52, "no" to 30, "abstain" to 2))) },
        "vote.conflict_after_verified" to {
            val s = closed.copy(conflictEvidence = true)
            Vote(s, detail(s, "yes", mapOf("yes" to 52, "no" to 30, "abstain" to 2)))
        },
        "vote.failed" to {
            val s = closed.copy(check = VoteCheck(CheckState.FAILED))
            Vote(s, detail(s, "yes"))
        },
        "vote.checking_clock" to { Vote(summary("p6", "Adopt the budget", VoteState.CHECKING, reason = "clock").copy(canCast = false, cannotCast = "checking"), null) },
        "vote.checking_list" to { Vote(summary("p6", "Adopt the budget", VoteState.CHECKING, reason = "service_list").copy(canCast = false, cannotCast = "checking"), null) },
        "vote.site_unreachable" to { Vote(open.copy(site = SiteState.UNREACHABLE), detail(open)) },
        "vote.refused" to { Vote(summary("p7", "Adopt the budget", ballot = VoteBallot(BallotState.REFUSED, "not_eligible")), detail(open, "yes")) },
        "vote.rejected" to { Vote(summary("p8", "Adopt the budget", VoteState.REJECTED, reason = "signature").copy(canCast = false), null) },
        "vote.results_problem" to {
            val s = closed.copy(check = VoteCheck(CheckState.PENDING, "input"))
            Vote(s, detail(s, "yes"))
        },
    )
}
