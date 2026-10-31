package com.vettid.core.testing

import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.DroppedNotice
import com.vettid.core.data.votes.ProblemsNotice
import com.vettid.core.data.votes.ReconnectEntry
import com.vettid.core.data.votes.ResultsOutcome
import com.vettid.core.data.votes.VoteDetail
import com.vettid.core.data.votes.VoteHint
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.data.votes.VotesAvailability
import com.vettid.core.data.votes.VotesRepository
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. An in-memory Votes repository (ANDROID-PLAN 0.1.102 "Votes") for ViewModel tests: [votes] is what `vote.list`
 * answers, [details] what `vote.get` answers by the pair; calls are recorded in [calls] (`cast:<connection>/<proposal>:<choice>`);
 * [fail] makes the next call of a name throw.
 */
@Suppress("TooManyFunctions")
class FakeVotes : VotesRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()
    val details = mutableMapOf<String, VoteDetail>()
    var evidenceText = """{"evidence":true}"""
    var windowIsOpen = false

    /** The password each cast was given (null: none was needed). */
    val castPasswords = mutableListOf<String?>()
    val releasePins = mutableListOf<String>()

    override val votes = MutableStateFlow<List<VoteSummary>>(emptyList())
    override val reconnect = MutableStateFlow<List<ReconnectEntry>>(emptyList())
    override val dropped = MutableStateFlow<List<DroppedNotice>>(emptyList())
    override val problems = MutableStateFlow<List<ProblemsNotice>>(emptyList())
    override val availability = MutableStateFlow(VotesAvailability.AVAILABLE)
    override val load = MutableStateFlow(ListLoad.LOADED)
    override val hints = MutableStateFlow<Map<String, VoteHint>>(emptyMap())

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    override suspend fun refresh() = call("refresh")

    override fun refreshQuietly() {
        calls += "refreshQuietly"
    }

    override suspend fun get(connectionId: String, proposalId: String): VoteDetail {
        call("get:$connectionId/$proposalId")
        return details["$connectionId/$proposalId"] ?: throw VaultFailure(FailureKind.NOT_FOUND, "not_found")
    }

    override suspend fun cast(connectionId: String, proposalId: String, choiceId: String, password: String?): BallotState {
        castPasswords += password
        call("cast:$connectionId/$proposalId:$choiceId")
        return BallotState.SENDING
    }

    override fun windowOpen(): Boolean = windowIsOpen

    override suspend fun check(connectionId: String, proposalId: String) = call("check:$connectionId/$proposalId")

    override suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean): BallotState {
        call("confirm:$connectionId/$proposalId:$mine")
        return if (mine) BallotState.RECORDED else BallotState.DISPUTED
    }

    override suspend fun evidence(connectionId: String, proposalId: String): String {
        call("evidence:$connectionId/$proposalId")
        return evidenceText
    }

    override suspend fun release(connectionId: String, proposalId: String, pin: String) {
        releasePins += pin
        call("release:$connectionId/$proposalId")
    }

    override suspend fun siteCheck(connectionId: String, proposalId: String) {
        calls += "siteCheck:$connectionId/$proposalId"
    }

    override suspend fun checkResults(connectionId: String, proposalId: String): ResultsOutcome {
        call("checkResults:$connectionId/$proposalId")
        return ResultsOutcome.SiteUnreachable
    }

    override suspend fun diagnoseGaveUp(connectionId: String, proposalId: String) {
        calls += "diagnose:$connectionId/$proposalId"
    }

    val acknowledged = mutableListOf<List<String>>()

    override fun acknowledgeDropped(connectionIds: List<String>) {
        acknowledged += connectionIds
        dropped.value = dropped.value.filterNot { it.connectionId in connectionIds }
    }

    override fun titleOf(connectionId: String, proposalId: String): String? =
        votes.value.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId }?.title
}
