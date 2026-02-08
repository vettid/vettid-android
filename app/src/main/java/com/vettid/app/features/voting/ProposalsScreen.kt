package com.vettid.app.features.voting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Proposals list screen showing available proposals for voting.
 * Part of Issue #50: Vault-Based Voting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalsScreen(
    viewModel: ProposalsViewModel = hiltViewModel(),
    onNavigateToProposal: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var showFilterDialog by remember { mutableStateOf(false) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProposalsEffect.NavigateToProposal -> onNavigateToProposal(effect.proposalId)
                is ProposalsEffect.ShowError -> {
                    // Show snackbar or toast
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proposals") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (val currentState = state) {
                is ProposalsState.Loading -> LoadingContent()
                is ProposalsState.Empty -> EmptyContent()
                is ProposalsState.Loaded -> ProposalsList(
                    proposals = currentState.proposals,
                    hasMore = currentState.hasMore,
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onEvent(ProposalsEvent.Refresh) },
                    onProposalClick = { viewModel.onEvent(ProposalsEvent.SelectProposal(it.id)) },
                    onLoadMore = { viewModel.onEvent(ProposalsEvent.LoadMore) }
                )
                is ProposalsState.Error -> ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.onEvent(ProposalsEvent.LoadProposals()) }
                )
            }
        }
    }

    // Filter dialog
    if (showFilterDialog) {
        val currentFilter = (state as? ProposalsState.Loaded)?.filter ?: ProposalFilter()
        FilterDialog(
            currentFilter = currentFilter,
            onDismiss = { showFilterDialog = false },
            onApply = { filter ->
                viewModel.onEvent(ProposalsEvent.UpdateFilter(filter))
                showFilterDialog = false
            }
        )
    }
}

/**
 * Proposals content for embedding in other screens.
 */
@Composable
fun ProposalsContent(
    viewModel: ProposalsViewModel = hiltViewModel(),
    onNavigateToProposal: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProposalsEffect.NavigateToProposal -> onNavigateToProposal(effect.proposalId)
                is ProposalsEffect.ShowError -> { /* Handle error */ }
            }
        }
    }

    when (val currentState = state) {
        is ProposalsState.Loading -> LoadingContent()
        is ProposalsState.Empty -> EmptyContent()
        is ProposalsState.Loaded -> ProposalsList(
            proposals = currentState.proposals,
            hasMore = currentState.hasMore,
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.onEvent(ProposalsEvent.Refresh) },
            onProposalClick = { viewModel.onEvent(ProposalsEvent.SelectProposal(it.id)) },
            onLoadMore = { viewModel.onEvent(ProposalsEvent.LoadMore) }
        )
        is ProposalsState.Error -> ErrorContent(
            message = currentState.message,
            onRetry = { viewModel.onEvent(ProposalsEvent.LoadProposals()) }
        )
    }
}

@Composable
private fun ProposalsList(
    proposals: List<Proposal>,
    hasMore: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onProposalClick: (Proposal) -> Unit,
    onLoadMore: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(proposals, key = { it.id }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onClick = { onProposalClick(proposal) }
                )
            }

            if (hasMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                    LaunchedEffect(Unit) {
                        onLoadMore()
                    }
                }
            }
        }

        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposalCard(
    proposal: Proposal,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: Status + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(status = proposal.status)
                Text(
                    text = formatVotingTime(proposal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Title
            Text(
                text = proposal.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Description preview
            Text(
                text = proposal.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Choices preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                proposal.choices.take(3).forEach { choice ->
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(choice.label, maxLines = 1) }
                    )
                }
                if (proposal.choices.size > 3) {
                    Text(
                        text = "+${proposal.choices.size - 3}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            // Unvoted active indicator
            if (proposal.status == ProposalStatus.ACTIVE && !proposal.userHasVoted) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Vote now",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Voted indicator
            if (proposal.userHasVoted) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "You voted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ProposalStatus) {
    val (color, text) = when (status) {
        ProposalStatus.UPCOMING -> MaterialTheme.colorScheme.secondaryContainer to "Upcoming"
        ProposalStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to "Active"
        ProposalStatus.ENDED -> MaterialTheme.colorScheme.surfaceVariant to "Ended"
        ProposalStatus.FINALIZED -> MaterialTheme.colorScheme.tertiaryContainer to "Finalized"
        ProposalStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer to "Cancelled"
    }

    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun formatVotingTime(proposal: Proposal): String {
    val now = Instant.now()
    return when (proposal.status) {
        ProposalStatus.UPCOMING -> {
            val duration = Duration.between(now, proposal.votingStartsAt)
            "Starts in ${formatDuration(duration)}"
        }
        ProposalStatus.ACTIVE -> {
            val duration = Duration.between(now, proposal.votingEndsAt)
            "Ends in ${formatDuration(duration)}"
        }
        ProposalStatus.ENDED, ProposalStatus.FINALIZED -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
            "Ended ${proposal.votingEndsAt.atZone(ZoneId.systemDefault()).format(formatter)}"
        }
        ProposalStatus.CANCELLED -> "Cancelled"
    }
}

private fun formatDuration(duration: Duration): String {
    return when {
        duration.toDays() > 0 -> "${duration.toDays()}d"
        duration.toHours() > 0 -> "${duration.toHours()}h"
        duration.toMinutes() > 0 -> "${duration.toMinutes()}m"
        else -> "< 1m"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDialog(
    currentFilter: ProposalFilter,
    onDismiss: () -> Unit,
    onApply: (ProposalFilter) -> Unit
) {
    var selectedStatuses by remember { mutableStateOf(currentFilter.status) }
    val allSelected = selectedStatuses.size == ProposalStatus.entries.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Proposals") },
        text = {
            Column {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // All / None toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedStatuses = if (allSelected) {
                                emptySet()
                            } else {
                                ProposalStatus.entries.toSet()
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            selectedStatuses = if (checked) {
                                ProposalStatus.entries.toSet()
                            } else {
                                emptySet()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "All",
                        fontWeight = FontWeight.Medium
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                ProposalStatus.entries.forEach { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedStatuses = if (status in selectedStatuses) {
                                    selectedStatuses - status
                                } else {
                                    selectedStatuses + status
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = status in selectedStatuses,
                            onCheckedChange = { checked ->
                                selectedStatuses = if (checked) {
                                    selectedStatuses + status
                                } else {
                                    selectedStatuses - status
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (status) {
                                ProposalStatus.UPCOMING -> "Upcoming"
                                ProposalStatus.ACTIVE -> "Active"
                                ProposalStatus.ENDED -> "Ended"
                                ProposalStatus.FINALIZED -> "Finalized"
                                ProposalStatus.CANCELLED -> "Cancelled"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(currentFilter.copy(status = selectedStatuses))
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.HowToVote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No Proposals",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "There are no proposals available for voting at this time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Error Loading Proposals",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}
