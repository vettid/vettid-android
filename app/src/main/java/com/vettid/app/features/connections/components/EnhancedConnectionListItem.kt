package com.vettid.app.features.connections.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.models.*

/**
 * Enhanced connection list item with all usability features.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EnhancedConnectionListItem(
    connection: ConnectionListItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onFavoriteToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 4.dp),
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = connection.peerName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Verification badges
                if (connection.emailVerified) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Email verified",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF4CAF50)
                    )
                }

                // Favorite star
                if (connection.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favorite",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFFB300)
                    )
                }
            }
        },
        supportingContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Status and last active
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(status = connection.status)

                    Text(
                        text = when {
                            connection.status.isPending -> connection.status.displayName
                            else -> "Active ${connection.lastActiveText}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                // Tags (if any)
                if (connection.tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        connection.tags.take(3).forEach { tag ->
                            MiniTagChip(tag = tag)
                        }
                        if (connection.tags.size > 3) {
                            Text(
                                text = "+${connection.tags.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        leadingContent = {
            Box {
                // Avatar
                ConnectionAvatar(
                    name = connection.peerName,
                    avatarUrl = connection.peerAvatarUrl,
                    size = 48
                )

                // Status indicator overlay
                if (connection.status.isPending || connection.hasUnreadActivity) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                    ) {
                        StatusDot(
                            status = connection.status,
                            size = 12
                        )
                    }
                }
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Time or action needed indicator
                if (connection.status == ConnectionStatus.PENDING_OUR_REVIEW ||
                    connection.status == ConnectionStatus.PENDING_OUR_ACCEPT
                ) {
                    Badge(
                        containerColor = Color(0xFF2196F3)
                    ) {
                        Text("Review")
                    }
                } else if (connection.unreadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = if (connection.unreadCount > 99) "99+" else "${connection.unreadCount}"
                        )
                    }
                } else {
                    // Show relative time
                    Text(
                        text = connection.lastActiveText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Health warning
                if (connection.credentialsExpiringSoon) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Credentials expiring soon",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFF9800)
                    )
                } else if (connection.isStale) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Inactive",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    )
}

/**
 * Avatar component for connections.
 */
@Composable
fun ConnectionAvatar(
    name: String,
    avatarUrl: String?,
    size: Int = 48,
    modifier: Modifier = Modifier
) {
    // TODO: Add image loading with Coil when avatar URLs are available
    Surface(
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.take(2).uppercase(),
                style = when {
                    size >= 48 -> MaterialTheme.typography.titleMedium
                    size >= 32 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.labelMedium
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Mini tag chip for inline display.
 */
@Composable
private fun MiniTagChip(tag: ConnectionTag) {
    val tagColor = Color(tag.color)

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = tagColor.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tagColor)
            )
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelSmall,
                color = tagColor
            )
        }
    }
}

/**
 * Section header for grouping connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Badge(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = count.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        action?.invoke()
    }
}

/**
 * Empty state for connections with call-to-action.
 */
@Composable
fun EmptyConnectionsState(
    onCreateInvitation: () -> Unit,
    onScanInvitation: () -> Unit,
    modifier: Modifier = Modifier,
    isFirstTime: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isFirstTime) "Welcome to Connections!" else "No Connections Yet",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isFirstTime) {
                    "Connect with others to securely share data and communicate. Your first connection is just a scan away!"
                } else {
                    "Connect with others by creating or scanning an invitation."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onCreateInvitation,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Invitation")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onScanInvitation,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Invitation")
            }

            if (isFirstTime) {
                Spacer(modifier = Modifier.height(24.dp))

                TextButton(onClick = { /* Connect with VettID Support */ }) {
                    Text("Or connect with VettID Support to test")
                }
            }
        }
    }
}
