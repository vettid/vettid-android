package com.vettid.app.features.voting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * My Votes screen showing user's voting history with verification.
 * Part of Issue #50: Vault-Based Voting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyVotesScreen(
    viewModel: MyVotesViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToProposal: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var selectedVote by remember { mutableStateOf<Vote?>(null) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MyVotesEffect.ShowReceiptDetail -> {
                    selectedVote = effect.vote
                }
                is MyVotesEffect.ShowVerificationResult -> {
                    // Could show a snackbar
                }
                is MyVotesEffect.ShowError -> {
                    // Could show a snackbar
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Votes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (val currentState = state) {
                is MyVotesState.Loading -> LoadingContent()
                is MyVotesState.Empty -> EmptyContent()
                is MyVotesState.Loaded -> VotesList(
                    votes = currentState.votes,
                    verificationStatus = currentState.verificationStatus,
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onEvent(MyVotesEvent.Refresh) },
                    onVoteClick = { viewModel.onEvent(MyVotesEvent.ViewReceipt(it.receipt.voteId)) },
                    onVerifyClick = { viewModel.onEvent(MyVotesEvent.VerifyVote(it.receipt.voteId)) },
                    onProposalClick = onNavigateToProposal
                )
                is MyVotesState.Error -> ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.onEvent(MyVotesEvent.LoadVotes) }
                )
            }
        }
    }

    // Receipt detail dialog
    selectedVote?.let { vote ->
        ReceiptDetailDialog(
            vote = vote,
            onDismiss = { selectedVote = null }
        )
    }
}

/**
 * My Votes content for embedding in other screens.
 */
@Composable
fun MyVotesContent(
    viewModel: MyVotesViewModel = hiltViewModel(),
    onNavigateToProposal: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var selectedVote by remember { mutableStateOf<Vote?>(null) }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MyVotesEffect.ShowReceiptDetail -> {
                    selectedVote = effect.vote
                }
                is MyVotesEffect.ShowVerificationResult -> { /* Handle */ }
                is MyVotesEffect.ShowError -> { /* Handle */ }
            }
        }
    }

    when (val currentState = state) {
        is MyVotesState.Loading -> LoadingContent()
        is MyVotesState.Empty -> EmptyContent()
        is MyVotesState.Loaded -> VotesList(
            votes = currentState.votes,
            verificationStatus = currentState.verificationStatus,
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.onEvent(MyVotesEvent.Refresh) },
            onVoteClick = { viewModel.onEvent(MyVotesEvent.ViewReceipt(it.receipt.voteId)) },
            onVerifyClick = { viewModel.onEvent(MyVotesEvent.VerifyVote(it.receipt.voteId)) },
            onProposalClick = onNavigateToProposal
        )
        is MyVotesState.Error -> ErrorContent(
            message = currentState.message,
            onRetry = { viewModel.onEvent(MyVotesEvent.LoadVotes) }
        )
    }

    // Receipt detail dialog
    selectedVote?.let { vote ->
        ReceiptDetailDialog(
            vote = vote,
            onDismiss = { selectedVote = null }
        )
    }
}

@Composable
private fun VotesList(
    votes: List<Vote>,
    verificationStatus: Map<String, VerificationStatus>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onVoteClick: (Vote) -> Unit,
    onVerifyClick: (Vote) -> Unit,
    onProposalClick: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(votes, key = { it.receipt.voteId }) { vote ->
                VoteCard(
                    vote = vote,
                    verificationStatus = verificationStatus[vote.receipt.voteId] ?: VerificationStatus.PENDING,
                    onClick = { onVoteClick(vote) },
                    onVerify = { onVerifyClick(vote) },
                    onViewProposal = { onProposalClick(vote.proposalId) }
                )
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
private fun VoteCard(
    vote: Vote,
    verificationStatus: VerificationStatus,
    onClick: () -> Unit,
    onVerify: () -> Unit,
    onViewProposal: () -> Unit
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
            // Header: Vote info and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Vote ID: ${vote.receipt.voteId.take(12)}...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatDate(vote.castAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VerificationStatusChip(status = verificationStatus)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Choice
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Your choice: ${vote.choiceId}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onViewProposal) {
                    Text("View Proposal")
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (verificationStatus != VerificationStatus.VERIFYING) {
                    OutlinedButton(onClick = onVerify) {
                        Icon(
                            imageVector = when (verificationStatus) {
                                VerificationStatus.VERIFIED -> Icons.Default.VerifiedUser
                                VerificationStatus.FAILED -> Icons.Default.Warning
                                else -> Icons.Default.Security
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            when (verificationStatus) {
                                VerificationStatus.VERIFIED -> "Verified"
                                VerificationStatus.FAILED -> "Retry"
                                else -> "Verify"
                            }
                        )
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun VerificationStatusChip(status: VerificationStatus) {
    val (color, icon, text) = when (status) {
        VerificationStatus.PENDING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            Icons.Default.HourglassEmpty,
            "Pending"
        )
        VerificationStatus.VERIFYING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            Icons.Default.Sync,
            "Verifying"
        )
        VerificationStatus.VERIFIED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Default.VerifiedUser,
            "Verified"
        )
        VerificationStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Default.Warning,
            "Failed"
        )
    }

    Surface(
        color = color,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptDetailDialog(
    vote: Vote,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vote Receipt") },
        text = {
            Column {
                ReceiptField("Vote ID", vote.receipt.voteId)
                ReceiptField("Proposal ID", vote.proposalId)
                ReceiptField("Choice", vote.choiceId)
                ReceiptField("Timestamp", formatDateTime(vote.castAt))
                ReceiptField("Voting Key", vote.receipt.votingPublicKey.take(32) + "...")
                ReceiptField("Signature", vote.receipt.signature.take(32) + "...")

                vote.receipt.merkleIndex?.let {
                    ReceiptField("Merkle Index", it.toString())
                }

                vote.receipt.merkleProof?.let {
                    ReceiptField("Merkle Root", it.rootHash.take(32) + "...")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ReceiptField(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatDate(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
}

private fun formatDateTime(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
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
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No Votes Yet",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your voting history will appear here after you cast your first vote.",
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
                text = "Error Loading Votes",
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
