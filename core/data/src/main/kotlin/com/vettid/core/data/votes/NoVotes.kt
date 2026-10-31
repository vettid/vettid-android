package com.vettid.core.data.votes

import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Votes where there are none (a screen's default, so that it needs no vault of votes to be built): empty, never available,
 * and every request is "not supported". Nothing is shown and nothing is sent.
 */
@Suppress("TooManyFunctions")
object NoVotes : VotesRepository {
    override val votes: StateFlow<List<VoteSummary>> = MutableStateFlow<List<VoteSummary>>(emptyList()).asStateFlow()
    override val reconnect: StateFlow<List<ReconnectEntry>> = MutableStateFlow<List<ReconnectEntry>>(emptyList()).asStateFlow()
    override val dropped: StateFlow<List<DroppedNotice>> = MutableStateFlow<List<DroppedNotice>>(emptyList()).asStateFlow()
    override val problems: StateFlow<List<ProblemsNotice>> = MutableStateFlow<List<ProblemsNotice>>(emptyList()).asStateFlow()
    override val availability: StateFlow<VotesAvailability> = MutableStateFlow(VotesAvailability.UNSUPPORTED).asStateFlow()
    override val load: StateFlow<ListLoad> = MutableStateFlow(ListLoad.NOT_LOADED).asStateFlow()
    override val hints: StateFlow<Map<String, VoteHint>> = MutableStateFlow<Map<String, VoteHint>>(emptyMap()).asStateFlow()
    override val windowNotClosed: StateFlow<WindowNotClosed?> = MutableStateFlow<WindowNotClosed?>(null).asStateFlow()

    override suspend fun closeWindowNow() = Unit

    private fun unsupported(): Nothing = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    override suspend fun refresh() = unsupported()

    override fun refreshQuietly() = Unit

    override suspend fun get(connectionId: String, proposalId: String): VoteDetail = unsupported()

    override suspend fun cast(connectionId: String, proposalId: String, choiceId: String, password: String?): BallotState = unsupported()

    override fun windowOpen(): Boolean = false

    override suspend fun check(connectionId: String, proposalId: String) = unsupported()

    override suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean): BallotState = unsupported()

    override suspend fun evidence(connectionId: String, proposalId: String): String = unsupported()

    override suspend fun release(connectionId: String, proposalId: String, pin: String) = unsupported()

    override suspend fun siteCheck(connectionId: String, proposalId: String) = Unit

    override suspend fun checkResults(connectionId: String, proposalId: String): ResultsOutcome = ResultsOutcome.SiteUnreachable

    override suspend fun diagnoseGaveUp(connectionId: String, proposalId: String) = Unit

    override fun acknowledgeDropped(connectionIds: List<String>) = Unit

    override fun titleOf(connectionId: String, proposalId: String): String? = null
}
