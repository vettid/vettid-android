package com.vettid.app.features.services

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*

/**
 * Service notifications screen.
 *
 * Displays all notifications from connected services with:
 * - Unread indicators
 * - Swipe to delete
 * - Mark as read
 * - Navigation to relevant screens
 *
 * Issue #36 [AND-023] - Notification display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel = hiltViewModel(),
    onNotificationClick: (ServiceNotification) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val notifications by viewModel.notifications.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Notifications")
                        if (unreadCount > 0) {
                            Text(
                                text = "$unreadCount unread",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(onClick = viewModel::markAllAsRead) {
                            Text("Mark all read")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            notifications.isEmpty() -> {
                EmptyContent(modifier = Modifier.padding(padding))
            }
            else -> {
                NotificationsList(
                    notifications = notifications,
                    onRead = { notification ->
                        viewModel.markAsRead(notification.id)
                        onNotificationClick(notification)
                    },
                    onDelete = viewModel::delete,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun NotificationsList(
    notifications: List<ServiceNotification>,
    onRead: (ServiceNotification) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Group by date
    val grouped = notifications.groupBy { notification ->
        val now = java.time.Instant.now()
        val diff = now.toEpochMilli() - notification.createdAt.toEpochMilli()
        val days = diff / (24 * 60 * 60 * 1000)

        when {
            days < 1 -> "Today"
            days < 2 -> "Yesterday"
            days < 7 -> "This Week"
            else -> "Earlier"
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        grouped.forEach { (dateGroup, groupNotifications) ->
            item {
                Text(
                    text = dateGroup,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            items(
                items = groupNotifications,
                key = { it.id }
            ) { notification ->
                NotificationItemWithMenu(
                    notification = notification,
                    onClick = { onRead(notification) },
                    onDelete = { onDelete(notification.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NotificationItemWithMenu(
    notification: ServiceNotification,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = notification.serviceName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!notification.isRead) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            supportingContent = {
                Column {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (!notification.isRead) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = notification.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (notification.serviceLogoUrl != null) {
                        AsyncImage(
                            model = notification.serviceLogoUrl,
                            contentDescription = notification.serviceName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = notificationTypeIcon(notification.type),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = notificationTypeColor(notification.type)
                        )
                    }
                }
            },
            trailingContent = {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = notification.timeAgo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    when (notification.type) {
                        NotificationType.AUTH_REQUEST,
                        NotificationType.DATA_REQUEST,
                        NotificationType.PAYMENT_REQUEST -> {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Action",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = if (!notification.isRead) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
        )

        // Context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    showMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}

private fun notificationTypeIcon(type: NotificationType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        NotificationType.INFO -> Icons.Outlined.Info
        NotificationType.AUTH_REQUEST -> Icons.Outlined.Security
        NotificationType.DATA_REQUEST -> Icons.Outlined.Description
        NotificationType.PAYMENT_REQUEST -> Icons.Outlined.Payment
        NotificationType.CONTRACT_UPDATE -> Icons.Outlined.Description
        NotificationType.MESSAGE -> Icons.Outlined.Message
        NotificationType.ALERT -> Icons.Outlined.Warning
    }
}

@Composable
private fun notificationTypeColor(type: NotificationType): Color {
    return when (type) {
        NotificationType.INFO -> MaterialTheme.colorScheme.primary
        NotificationType.AUTH_REQUEST -> MaterialTheme.colorScheme.secondary
        NotificationType.DATA_REQUEST -> MaterialTheme.colorScheme.tertiary
        NotificationType.PAYMENT_REQUEST -> Color(0xFF4CAF50)
        NotificationType.CONTRACT_UPDATE -> MaterialTheme.colorScheme.primary
        NotificationType.MESSAGE -> MaterialTheme.colorScheme.onSurfaceVariant
        NotificationType.ALERT -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
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
                imageVector = Icons.Outlined.NotificationsNone,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No notifications",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Notifications from your connected services will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
