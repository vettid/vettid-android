package com.vettid.app.features.connections.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Status of an invitation.
 */
enum class InvitationStatus {
    PENDING,      // Waiting to be scanned
    VIEWED,       // Scanned but not accepted
    ACCEPTED,     // Successfully connected
    EXPIRED,      // Time ran out
    REVOKED       // Manually cancelled
}

/**
 * Direction of invitation.
 */
enum class InvitationDirection {
    OUTBOUND,     // Created by user
    INBOUND       // Received from others
}

/**
 * Data class for an invitation.
 */
data class TrackedInvitation(
    val id: String,
    val direction: InvitationDirection,
    val status: InvitationStatus,
    val peerName: String?,           // Known after connection or if provided
    val createdAt: Instant,
    val expiresAt: Instant,
    val acceptedAt: Instant? = null,
    val connectionId: String? = null  // Set after successful connection
)

/**
 * Card showing pending invitations summary.
 */
@Composable
fun PendingInvitationsSummaryCard(
    outboundCount: Int,
    inboundCount: Int,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (outboundCount == 0 && inboundCount == 0) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Pending,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Column {
                    Text(
                        text = "Pending Invitations",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = buildString {
                            if (outboundCount > 0) append("$outboundCount sent")
                            if (outboundCount > 0 && inboundCount > 0) append(" • ")
                            if (inboundCount > 0) append("$inboundCount received")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(onClick = onViewAll) {
                Text("View")
            }
        }
    }
}

/**
 * Detailed invitation tracking list.
 */
@Composable
fun InvitationTrackingList(
    invitations: List<TrackedInvitation>,
    onInvitationClick: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        // Group by status
        val grouped = invitations.groupBy { it.status }

        // Pending first
        grouped[InvitationStatus.PENDING]?.let { pending ->
            item {
                SectionHeader(
                    title = "Pending",
                    count = pending.size,
                    color = Color(0xFFFF9800)
                )
            }
            items(pending, key = { it.id }) { invitation ->
                InvitationTrackingItem(
                    invitation = invitation,
                    onClick = { onInvitationClick(invitation.id) },
                    onRevoke = { onRevokeInvitation(invitation.id) }
                )
            }
        }

        // Accepted
        grouped[InvitationStatus.ACCEPTED]?.let { accepted ->
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = "Accepted",
                    count = accepted.size,
                    color = Color(0xFF4CAF50)
                )
            }
            items(accepted, key = { it.id }) { invitation ->
                InvitationTrackingItem(
                    invitation = invitation,
                    onClick = { onInvitationClick(invitation.id) },
                    onRevoke = null
                )
            }
        }

        // Expired/Revoked
        val inactive = (grouped[InvitationStatus.EXPIRED] ?: emptyList()) +
                (grouped[InvitationStatus.REVOKED] ?: emptyList())
        if (inactive.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = "History",
                    count = inactive.size,
                    color = Color(0xFF9E9E9E)
                )
            }
            items(inactive, key = { it.id }) { invitation ->
                InvitationTrackingItem(
                    invitation = invitation,
                    onClick = { onInvitationClick(invitation.id) },
                    onRevoke = null
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    color: Color
) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = color
        ) {
            Box(modifier = Modifier.size(12.dp, 12.dp))
        }
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvitationTrackingItem(
    invitation: TrackedInvitation,
    onClick: () -> Unit,
    onRevoke: (() -> Unit)?
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(20.dp),
                color = getStatusColor(invitation.status).copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (invitation.direction == InvitationDirection.OUTBOUND)
                            Icons.Default.CallMade else Icons.Default.CallReceived,
                        contentDescription = null,
                        tint = getStatusColor(invitation.status),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = invitation.peerName ?: "Pending connection",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    InvitationStatusChip(status = invitation.status)
                }

                Text(
                    text = getInvitationSubtext(invitation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions
            if (invitation.status == InvitationStatus.PENDING && onRevoke != null) {
                IconButton(onClick = onRevoke) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Revoke invitation",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InvitationStatusChip(status: InvitationStatus) {
    val (color, text) = when (status) {
        InvitationStatus.PENDING -> Color(0xFFFF9800) to "Pending"
        InvitationStatus.VIEWED -> Color(0xFF2196F3) to "Viewed"
        InvitationStatus.ACCEPTED -> Color(0xFF4CAF50) to "Accepted"
        InvitationStatus.EXPIRED -> Color(0xFF9E9E9E) to "Expired"
        InvitationStatus.REVOKED -> Color(0xFFF44336) to "Revoked"
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun getStatusColor(status: InvitationStatus): Color {
    return when (status) {
        InvitationStatus.PENDING -> Color(0xFFFF9800)
        InvitationStatus.VIEWED -> Color(0xFF2196F3)
        InvitationStatus.ACCEPTED -> Color(0xFF4CAF50)
        InvitationStatus.EXPIRED -> Color(0xFF9E9E9E)
        InvitationStatus.REVOKED -> Color(0xFFF44336)
    }
}

private fun getInvitationSubtext(invitation: TrackedInvitation): String {
    val direction = if (invitation.direction == InvitationDirection.OUTBOUND) "Sent" else "Received"
    val time = formatRelativeTime(invitation.createdAt)

    return when (invitation.status) {
        InvitationStatus.PENDING -> {
            val remaining = formatTimeRemaining(invitation.expiresAt)
            "$direction $time • Expires in $remaining"
        }
        InvitationStatus.VIEWED -> "$direction $time • Awaiting response"
        InvitationStatus.ACCEPTED -> {
            val acceptedTime = invitation.acceptedAt?.let { formatRelativeTime(it) } ?: "recently"
            "Connected $acceptedTime"
        }
        InvitationStatus.EXPIRED -> "$direction $time • Expired"
        InvitationStatus.REVOKED -> "$direction $time • Cancelled"
    }
}

private fun formatRelativeTime(instant: Instant): String {
    val now = Instant.now()
    val minutes = ChronoUnit.MINUTES.between(instant, now)
    val hours = ChronoUnit.HOURS.between(instant, now)
    val days = ChronoUnit.DAYS.between(instant, now)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 7 -> "$days days ago"
        else -> "${days / 7} weeks ago"
    }
}

private fun formatTimeRemaining(expiresAt: Instant): String {
    val now = Instant.now()
    val seconds = ChronoUnit.SECONDS.between(now, expiresAt)
    val minutes = seconds / 60

    return when {
        seconds <= 0 -> "expired"
        minutes < 1 -> "${seconds}s"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/**
 * Invitation detail card.
 */
@Composable
fun InvitationDetailCard(
    invitation: TrackedInvitation,
    onRevoke: (() -> Unit)?,
    onResend: (() -> Unit)?,
    onViewConnection: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Invitation Details",
                    style = MaterialTheme.typography.titleMedium
                )
                InvitationStatusChip(status = invitation.status)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Divider()

            Spacer(modifier = Modifier.height(16.dp))

            // Details
            DetailRow(label = "Direction", value = if (invitation.direction == InvitationDirection.OUTBOUND) "Sent" else "Received")
            DetailRow(label = "Created", value = formatRelativeTime(invitation.createdAt))

            if (invitation.status == InvitationStatus.PENDING) {
                DetailRow(
                    label = "Expires",
                    value = formatTimeRemaining(invitation.expiresAt),
                    valueColor = if (ChronoUnit.MINUTES.between(Instant.now(), invitation.expiresAt) < 5)
                        MaterialTheme.colorScheme.error else null
                )
            }

            invitation.acceptedAt?.let {
                DetailRow(label = "Accepted", value = formatRelativeTime(it))
            }

            invitation.peerName?.let {
                DetailRow(label = "Connected to", value = it)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (invitation.status == InvitationStatus.PENDING) {
                    onRevoke?.let {
                        OutlinedButton(
                            onClick = it,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Revoke")
                        }
                    }
                }

                if (invitation.status == InvitationStatus.EXPIRED) {
                    onResend?.let {
                        Button(
                            onClick = it,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Resend")
                        }
                    }
                }

                if (invitation.status == InvitationStatus.ACCEPTED && invitation.connectionId != null) {
                    onViewConnection?.let {
                        Button(
                            onClick = it,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View Connection")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
