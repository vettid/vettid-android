package com.vettid.feature.votes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.data.votes.VoteChip
import com.vettid.core.data.votes.VoteRules
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.CategoryHue
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.categoryColor

/**
 * Votes (ANDROID-PLAN 0.1.102 §4 "Votes", screen 1): a section **Open** (open, upcoming, being checked) and a section
 * **Closed**, as the vault orders them; a row is the title (one line), the service's name with its label, a written time
 * and a status chip in words. At the top: "Connect again to vote" for a `reconnect` connection, and the vault's notices of
 * votes it did not keep and of services at their cap of problems. Never an empty screen of nothing: with no vote, no
 * connection to connect again and no notice, it says there are no votes.
 */
@Composable
@Suppress("LongMethod")
fun VotesScreen(
    state: VotesUiState,
    chrome: ShellChrome,
    modifier: Modifier = Modifier,
    onOpen: (VoteSummary) -> Unit = {},
    onOpenConnection: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onNoticesShown: (List<String>) -> Unit = {},
) {
    TopLevelScaffold(title = stringResource(R.string.votes_title), chrome = chrome, modifier = modifier) {
        VotesContent(state, onOpen, onOpenConnection, onRetry, onNoticesShown)
    }
}

/** The votes of one service connection, from its detail ("Votes from <name>"): the same list under a back arrow. */
@Composable
fun ConnectionVotesScreen(
    state: VotesUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (VoteSummary) -> Unit = {},
    onOpenConnection: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onNoticesShown: (List<String>) -> Unit = {},
) {
    DetailScaffold(onBackClick = onBack, modifier = modifier) {
        VotesContent(state, onOpen, onOpenConnection, onRetry, onNoticesShown)
    }
}

@Composable
private fun BoxScope.VotesContent(
    state: VotesUiState,
    onOpen: (VoteSummary) -> Unit,
    onOpenConnection: (String) -> Unit,
    onRetry: () -> Unit,
    onNoticesShown: (List<String>) -> Unit,
) {
    // A `dropped` notice shown is acknowledged to the vault once, so that it does not come back.
    val droppedIds = state.dropped.map { it.connectionId }
    LaunchedEffect(droppedIds) { if (droppedIds.isNotEmpty() && state.load == ListLoad.LOADED) onNoticesShown(droppedIds) }
    run {
        val error = state.error
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            state.votes.isEmpty() && state.reconnect.isEmpty() && error != null -> Column(
                Modifier.fillMaxSize().padding(Spacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.votes_error_title),
                    body = failureText(error.messageRes(), error.vaultCode(state.errorCode)),
                    modifier = Modifier.testTag("votes_error"),
                    actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.votes_retry)) } },
                )
            }
            state.empty -> EmptyState(
                icon = Icons.Outlined.HowToVote,
                title = stringResource(R.string.votes_empty_title),
                body = stringResource(R.string.votes_empty_body),
            )
            else -> VoteList(state, onOpen, onOpenConnection, onRetry)
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun VoteList(state: VotesUiState, onOpen: (VoteSummary) -> Unit, onOpenConnection: (String) -> Unit, onRetry: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("votes")) {
        state.error?.let { e ->
            // The list on screen is what was read before; a refresh that failed says so above it.
            item(key = "error") {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.votes_error_title),
                    body = failureText(e.messageRes(), e.vaultCode(state.errorCode)),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                    actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.votes_retry)) } },
                )
            }
        }
        items(state.reconnect, key = { "reconnect-${it.connectionId}" }) { r ->
            ReconnectCard(
                AccountNames.isolate(r.name),
                { onOpenConnection(r.connectionId) },
                Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            )
        }
        items(state.dropped, key = { "dropped-${it.connectionId}" }) { d ->
            NoticeCard(
                kind = NoticeKind.WARNING,
                title = stringResource(R.string.votes_title),
                body = stringResource(R.string.vote_dropped, AccountNames.isolate(state.services[d.connectionId].orEmpty()), d.count),
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("vote_dropped"),
            )
        }
        items(state.problems, key = { "problems-${it.connectionId}" }) { p ->
            NoticeCard(
                kind = NoticeKind.URGENT,
                title = stringResource(R.string.votes_title),
                body = stringResource(R.string.vote_problems, AccountNames.isolate(state.services[p.connectionId].orEmpty()), p.count),
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("vote_problems"),
            )
        }
        val open = state.open
        if (open.isNotEmpty()) {
            item(key = "open-header") { SectionHeader(stringResource(R.string.votes_section_open)) }
            items(open, key = { it.key }) { v -> VoteRow(v, state.services[v.connectionId].orEmpty(), onOpen) }
        }
        val closed = state.closed
        if (closed.isNotEmpty()) {
            item(key = "closed-header") { SectionHeader(stringResource(R.string.votes_section_closed)) }
            items(closed, key = { it.key }) { v -> VoteRow(v, state.services[v.connectionId].orEmpty(), onOpen) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s).semantics { heading() },
    )
}

/**
 * The "Connect again to vote" card (screen 8): the connection was made before it said it was a service, so it can't take
 * part in votes. The app does not change the connection's kind or call it a service on its own: the card says only what
 * VettID's service list says.
 */
@Composable
internal fun ReconnectCard(name: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(
        kind = NoticeKind.INFO,
        title = stringResource(R.string.vote_reconnect_title),
        body = stringResource(R.string.vote_reconnect_body, name),
        modifier = modifier.testTag("vote_reconnect"),
        actions = { TextButton(onClick = onOpen) { Text(stringResource(R.string.vote_reconnect_open)) } },
    )
}

/**
 * One row: the title, the service with its label, a written time and the chip. Read whole: "Adopt the budget, from the
 * service Example, you voted".
 */
@Composable
private fun VoteRow(v: VoteSummary, serviceName: String, onOpen: (VoteSummary) -> Unit) {
    val chip = VoteRules.chip(v)
    val chipText = stringResource(VoteTexts.chip(chip))
    val service = AccountNames.isolateOrNull(serviceName).orEmpty()
    val title = AccountNames.isolateOrNull(v.title) ?: stringResource(R.string.votes_title)
    val time = timeLine(v)
    val description = stringResource(R.string.vote_row_description, title, service, chipText) + (time?.let { ", $it" } ?: "")
    VettIdListRow(
        title = title,
        supporting = listOfNotNull(
            service.takeIf { it.isNotEmpty() }?.let { "$it · ${stringResource(R.string.vote_service_label)}" },
            time,
        ).joinToString(" · ").ifEmpty { null },
        tileIcon = Icons.Outlined.HowToVote,
        tileColors = categoryColor(CategoryHue.BLUE),
        onClick = { onOpen(v) },
        modifier = Modifier.testTag("vote_${v.key}").semantics(mergeDescendants = true) { contentDescription = description },
        below = { StatusChip(chip, chipText) },
    )
}

@Composable
private fun timeLine(v: VoteSummary): String? = when {
    v.state == VoteState.CLOSED -> v.closesAt?.let { stringResource(R.string.vote_row_closed, Times.full(it)) }
    v.state == VoteState.UPCOMING -> v.opensAt?.let { stringResource(R.string.vote_row_opens, Times.full(it)) }
    else -> v.closesAt?.let { stringResource(R.string.vote_row_closes, Times.full(it)) }
}

/** A status chip: always its words (never colour alone); the error colour only for a problem. */
@Composable
internal fun StatusChip(chip: VoteChip, text: String, modifier: Modifier = Modifier) {
    val problem = chip == VoteChip.PROBLEM
    Surface(
        shape = VettIdShape.pill,
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.testTag("vote_chip"),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
