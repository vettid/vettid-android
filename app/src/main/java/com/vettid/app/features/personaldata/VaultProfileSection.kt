package com.vettid.app.features.personaldata

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.secrets.SecretsState
import com.vettid.app.features.secrets.SecretsViewModel
import com.vettid.app.features.secrets.SecretsEvent
import com.vettid.app.ui.components.ProfilePhotoCapture

/**
 * Persistent profile strip displayed above the Vault tab selector —
 * visible regardless of which tab (Data / Secrets / Wallets) is
 * active. Wraps the avatar + name header and the unified
 * "profile / public keys need publishing" banners so every tab sees
 * the same signals in a consistent place.
 *
 * Uses nav-destination-scoped ViewModels so state stays in sync with
 * the Data and Secrets tabs (same ViewModel instances).
 */
@Composable
fun VaultProfileSection(
    personalDataViewModel: PersonalDataViewModel = hiltViewModel(),
    secretsViewModel: SecretsViewModel = hiltViewModel(),
) {
    val photo by personalDataViewModel.profilePhoto.collectAsState()
    val systemFields by personalDataViewModel.systemFields.collectAsState()
    val hasUnpublishedProfile by personalDataViewModel.hasUnpublishedChanges.collectAsState()
    val showPublicProfilePreview by personalDataViewModel.showPublicProfilePreview.collectAsState()
    val showPhotoCapture by personalDataViewModel.showPhotoCapture.collectAsState()
    val secretsState by secretsViewModel.state.collectAsState()
    val hasUnpublishedKeys = (secretsState as? SecretsState.Loaded)?.hasUnpublishedChanges == true

    Column(modifier = Modifier.fillMaxWidth()) {
        ProfileHeaderRow(
            firstName = systemFields?.firstName.orEmpty(),
            lastName = systemFields?.lastName.orEmpty(),
            photoBase64 = photo,
            onEditPhoto = { personalDataViewModel.showPhotoCaptureDialog() },
            onNameClick = { personalDataViewModel.showPublicProfilePreview() },
        )
        if (hasUnpublishedProfile) {
            UnpublishedChangesBanner(
                title = "Unpublished Profile Changes",
                onPublish = { personalDataViewModel.publishProfile() },
            )
        }
        if (hasUnpublishedKeys) {
            UnpublishedChangesBanner(
                title = "Unpublished Public Keys",
                onPublish = { secretsViewModel.onEvent(SecretsEvent.PublishPublicKeys) },
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
        )
    }

    if (showPublicProfilePreview) {
        val publishedProfile by personalDataViewModel.publishedProfile.collectAsState()
        val isLoadingPublishedProfile by personalDataViewModel.isLoadingPublishedProfile.collectAsState()
        val publicSecrets by personalDataViewModel.publicSecrets.collectAsState()
        val publicPersonalData by personalDataViewModel.publicPersonalData.collectAsState()
        val installedHandlers by personalDataViewModel.installedHandlers.collectAsState()

        androidx.compose.ui.window.Dialog(
            onDismissRequest = personalDataViewModel::hidePublicProfilePreview,
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            PublicProfileFullScreen(
                publishedProfile = publishedProfile,
                isLoading = isLoadingPublishedProfile,
                publicSecrets = publicSecrets,
                publicPersonalData = publicPersonalData,
                handlers = installedHandlers,
                onBack = personalDataViewModel::hidePublicProfilePreview,
            )
        }
    }

    if (showPhotoCapture) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = personalDataViewModel::hidePhotoCaptureDialog,
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            androidx.activity.compose.BackHandler(enabled = true) {
                personalDataViewModel.hidePhotoCaptureDialog()
            }
            ProfilePhotoCapture(
                onPhotoCapture = { bytes -> personalDataViewModel.uploadPhoto(bytes) },
                onCancel = personalDataViewModel::hidePhotoCaptureDialog,
            )
        }
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
 * Shared "unpublished changes" banner. Callers pass their own title
 * so the message stays accurate to the change source.
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
