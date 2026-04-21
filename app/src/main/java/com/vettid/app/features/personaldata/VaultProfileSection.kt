package com.vettid.app.features.personaldata

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Persistent profile strip displayed above the Vault tab selector —
 * visible regardless of which tab (Data / Secrets / Wallets) is
 * active. Wraps the avatar + name header and the "profile needs
 * publishing" banner so every tab sees the same signals in a
 * consistent place.
 *
 * Uses the nav-destination-scoped PersonalDataViewModel so the
 * state stays in sync with the Data tab (same ViewModel instance).
 */
@Composable
fun VaultProfileSection(
    onEditPhoto: () -> Unit = {},
    onNameClick: () -> Unit = {},
    viewModel: PersonalDataViewModel = hiltViewModel(),
) {
    val photo by viewModel.profilePhoto.collectAsState()
    val systemFields by viewModel.systemFields.collectAsState()
    val hasUnpublishedChanges by viewModel.hasUnpublishedChanges.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        ProfileHeaderRow(
            firstName = systemFields?.firstName.orEmpty(),
            lastName = systemFields?.lastName.orEmpty(),
            photoBase64 = photo,
            onEditPhoto = onEditPhoto,
            onNameClick = onNameClick,
        )
        if (hasUnpublishedChanges) {
            PublishNeededBanner(
                onPublish = { viewModel.publishProfile() },
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
        )
    }
}

@Composable
private fun ProfileHeaderRow(
    firstName: String,
    lastName: String,
    photoBase64: String?,
    onEditPhoto: () -> Unit,
    onNameClick: () -> Unit,
) {
    val photoBitmap = remember(photoBase64) {
        photoBase64?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .clickable(onClick = onEditPhoto),
        ) {
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = "Profile photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val initials = listOf(firstName, lastName)
                            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                            .joinToString("").take(2)
                        Text(
                            text = initials.ifEmpty { "?" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Edit photo",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f).clickable(onClick = onNameClick)) {
            val fullName = listOf(firstName, lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            Text(
                text = fullName.ifEmpty { "Your Profile" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Tap to preview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shared "unpublished changes" banner. Same visual as the Secrets
 * tab's unpublished-keys banner so the user sees one consistent
 * affordance whether profile fields or public keys are dirty.
 * Callers pass their own title / subtitle so the message stays
 * accurate to the change source.
 */
@Composable
fun UnpublishedChangesBanner(
    title: String,
    subtitle: String = "Publish to update your public profile",
    onPublish: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Button(
                onClick = onPublish,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Publish") }
        }
    }
}

@Composable
private fun PublishNeededBanner(onPublish: () -> Unit) {
    UnpublishedChangesBanner(
        title = "Unpublished Profile Changes",
        subtitle = "Publish to update your public profile",
        onPublish = onPublish,
    )
}
