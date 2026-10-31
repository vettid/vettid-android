package com.vettid.feature.votes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.ShellChrome
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.Instant

/** Votes (the drawer, after Approvals): ANDROID-PLAN 0.1.102 §4 "Votes". */
@Serializable
data object VotesRoute

/** One vote's screen, by the pair (connection, proposal) (0.81.2): never by the proposal id alone. */
@Serializable
data class VoteRoute(val connectionId: String, val proposalId: String) {
    companion object {
        const val CONNECTION = "connectionId"
        const val PROPOSAL = "proposalId"
    }
}

/** The votes from one service connection ("Votes from <name>" on its detail): only its votes and notices. */
@Serializable
data class ConnectionVotesRoute(val connectionId: String) {
    companion object {
        const val ARG = "connectionId"
    }
}

/** What the Votes destinations need from the app shell. */
data class VotesHost(
    val navigate: (Any) -> Unit,
    val onBack: () -> Unit,
    /** Opens a connection's detail ("Open the connection" of "Connect again to vote"). */
    val onOpenConnection: (String) -> Unit,
)

/** Registers the list and a vote's screen. */
fun NavGraphBuilder.votesDestination(chrome: ShellChrome, host: VotesHost) {
    composable<VotesRoute> {
        val vm: VotesViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        // Read when the screen opens and on return to it (the ViewModel outlives the screen).
        LaunchedEffect(Unit) { vm.refresh() }
        VotesScreen(
            state,
            chrome,
            onOpen = { host.navigate(VoteRoute(it.connectionId, it.proposalId)) },
            onOpenConnection = host.onOpenConnection,
            onRetry = vm::refresh,
            onNoticesShown = vm::noticesShown,
        )
    }
    composable<ConnectionVotesRoute> {
        val vm: VotesViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.refresh() }
        ConnectionVotesScreen(
            state,
            onBack = host.onBack,
            onOpen = { host.navigate(VoteRoute(it.connectionId, it.proposalId)) },
            onOpenConnection = host.onOpenConnection,
            onRetry = vm::refresh,
            onNoticesShown = vm::noticesShown,
        )
    }
    composable<VoteRoute> {
        val vm: VoteViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        VoteScreen(
            state,
            VoteActions(
                onBack = host.onBack,
                onRetry = vm::refresh,
                onSelect = vm::select,
                onCast = vm::askCast,
                onConfirmCast = vm::confirmCast,
                onDismissCast = vm::dismissCast,
                onPassword = vm::setPassword,
                onSubmitPassword = vm::submitPassword,
                onCheck = vm::checkWithService,
                onAnswerRestore = vm::answerRestore,
                onSaveProof = vm::askProof,
                onDismissProofWarning = vm::dismissProofWarning,
                onFetchProof = vm::fetchProof,
                onSaveProofTo = vm::saveProofTo,
                onProofShown = vm::proofShown,
                onRelease = vm::askRelease,
                onDismissRelease = vm::dismissRelease,
                onPin = vm::setPin,
                onConfirmRelease = vm::release,
                onDismissCastProblem = vm::dismissCastProblem,
                onWindowNoticeShown = vm::windowNoticeShown,
                proofFileName = vm::proofFileName,
            ),
            now = rememberNow(),
        )
    }
}

/** The minute clock the screen's countdowns and "late" notes follow while it is shown. */
@Composable
internal fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_MS)
            now = Instant.now()
        }
    }
    return now
}

private const val CLOCK_MS = 30_000L
