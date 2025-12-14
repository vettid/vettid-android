package com.vettid.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DrawerView(
    isOpen: Boolean,
    onClose: () -> Unit,
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    userName: String = "VettID User",
    userEmail: String = "",
    vaultStatus: VaultStatus = VaultStatus.ACTIVE,
    isDarkTheme: Boolean = false,
    notificationsEnabled: Boolean = true,
    onThemeToggle: () -> Unit = {},
    onNotificationsToggle: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onSignOut: () -> Unit
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it })
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Drawer content (70% width)
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.7f),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Profile header
                    DrawerHeader(
                        userName = userName,
                        userEmail = userEmail,
                        vaultStatus = vaultStatus
                    )

                    Divider()

                    // Section navigation
                    Spacer(modifier = Modifier.height(8.dp))

                    AppSection.entries.forEach { section ->
                        DrawerSectionItem(
                            icon = section.icon,
                            title = section.title,
                            selected = currentSection == section,
                            onClick = {
                                onSectionChange(section)
                                onClose()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Settings section per mobile-ui-plan.md Section 2.2
                    Text(
                        text = "Quick Settings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )

                    // Theme toggle
                    QuickSettingToggle(
                        icon = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                        title = "Dark Theme",
                        checked = isDarkTheme,
                        onCheckedChange = { onThemeToggle() }
                    )

                    // Notifications toggle
                    QuickSettingToggle(
                        icon = if (notificationsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                        title = "Notifications",
                        checked = notificationsEnabled,
                        onCheckedChange = { onNotificationsToggle() }
                    )

                    // Help & Support
                    DrawerSectionItem(
                        icon = Icons.Default.Help,
                        title = "Help & Support",
                        selected = false,
                        onClick = onHelpClick
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Divider()

                    // Sign out
                    DrawerSectionItem(
                        icon = Icons.Default.Logout,
                        title = "Sign Out",
                        selected = false,
                        onClick = onSignOut
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Scrim (click to close)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        onClick = onClose,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
            )
        }
    }
}

@Composable
private fun DrawerHeader(
    userName: String,
    userEmail: String,
    vaultStatus: VaultStatus
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        // Profile avatar
        Surface(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // User name
        Text(
            text = userName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // User email
        if (userEmail.isNotBlank()) {
            Text(
                text = userEmail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Vault status
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (vaultStatus) {
                    VaultStatus.ACTIVE -> Icons.Default.CheckCircle
                    VaultStatus.INACTIVE -> Icons.Default.Cancel
                    VaultStatus.STARTING -> Icons.Default.HourglassTop
                    VaultStatus.STOPPING -> Icons.Default.HourglassBottom
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = when (vaultStatus) {
                    VaultStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    VaultStatus.INACTIVE -> MaterialTheme.colorScheme.error
                    VaultStatus.STARTING, VaultStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Vault ${vaultStatus.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerSectionItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun QuickSettingToggle(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

enum class VaultStatus(val displayName: String) {
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    STARTING("Starting..."),
    STOPPING("Stopping...")
}
