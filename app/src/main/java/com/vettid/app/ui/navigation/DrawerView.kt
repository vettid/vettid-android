package com.vettid.app.ui.navigation

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val TAG = "DrawerView"

@Composable
fun DrawerView(
    isOpen: Boolean,
    onClose: () -> Unit,
    currentItem: DrawerItem,
    onItemSelected: (DrawerItem) -> Unit,
    userName: String,
    userEmail: String = "",
    profilePhotoBase64: String? = null,
    badgeCounts: Map<DrawerItem, Int> = emptyMap()
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it })
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Drawer content (75% width)
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.75f),
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
                        profilePhotoBase64 = profilePhotoBase64
                    )

                    HorizontalDivider()

                    Spacer(modifier = Modifier.height(8.dp))

                    // Main navigation items
                    DrawerItem.entries.forEach { item ->
                        DrawerNavigationItem(
                            icon = item.icon,
                            title = item.title,
                            selected = currentItem == item,
                            badgeCount = badgeCounts[item] ?: 0,
                            onClick = {
                                Log.d(TAG, "Drawer item clicked: $item")
                                onItemSelected(item)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

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
    profilePhotoBase64: String? = null
) {
    // Decode profile photo if available
    Log.d(TAG, "DrawerHeader - profilePhotoBase64 length: ${profilePhotoBase64?.length ?: 0}")
    val profileBitmap = remember(profilePhotoBase64) {
        profilePhotoBase64?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                Log.d(TAG, "Decoded ${bytes.size} bytes for profile photo")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Log.d(TAG, "Bitmap decoded: ${bitmap != null}, size: ${bitmap?.width}x${bitmap?.height}")
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode profile photo", e)
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        // Profile avatar
        if (profileBitmap != null) {
            Image(
                bitmap = profileBitmap.asImageBitmap(),
                contentDescription = "Profile photo",
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Show initials if we have a name
                    val initials = userName.split(" ")
                        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                        .take(2)
                        .joinToString("")
                    if (initials.isNotEmpty()) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // User name
        Text(
            text = userName.ifBlank { "VettID User" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        // User email
        if (userEmail.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = userEmail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerNavigationItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (badgeCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text(
                                text = if (badgeCount > 99) "99+" else badgeCount.toString()
                            )
                        }
                    }
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
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
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

// Keep for backwards compatibility
enum class VaultStatus(val displayName: String) {
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    STARTING("Starting..."),
    STOPPING("Stopping...")
}
