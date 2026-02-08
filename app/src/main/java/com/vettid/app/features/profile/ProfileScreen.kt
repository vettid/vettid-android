package com.vettid.app.features.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.core.network.Profile

/**
 * Screen for viewing and editing own profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val editDisplayName by viewModel.editDisplayName.collectAsState()
    val editBio by viewModel.editBio.collectAsState()
    val editLocation by viewModel.editLocation.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProfileEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        duration = SnackbarDuration.Short
                    )
                }
                is ProfileEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Profile" else "Profile") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing) {
                            viewModel.cancelEditing()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isEditing) "Cancel" else "Back"
                        )
                    }
                },
                actions = {
                    if (!isEditing && state is ProfileState.Loaded) {
                        IconButton(onClick = { viewModel.startEditing() }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit profile"
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val currentState = state) {
            is ProfileState.Loading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }

            is ProfileState.Loaded -> {
                if (isEditing) {
                    EditProfileContent(
                        displayName = editDisplayName,
                        bio = editBio,
                        location = editLocation,
                        isSaving = isSaving,
                        isValid = viewModel.isFormValid(),
                        onDisplayNameChanged = { viewModel.onDisplayNameChanged(it) },
                        onBioChanged = { viewModel.onBioChanged(it) },
                        onLocationChanged = { viewModel.onLocationChanged(it) },
                        onSave = { viewModel.saveProfile() },
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    ViewProfileContent(
                        profile = currentState.profile,
                        isPublishing = currentState.isPublishing,
                        onEdit = { viewModel.startEditing() },
                        onPublish = { viewModel.publishProfile() },
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            is ProfileState.Error -> {
                ErrorContent(
                    message = currentState.message,
                    onRetry = { viewModel.loadProfile() },
                    modifier = Modifier.padding(padding)
                )
            }
        }
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
private fun ViewProfileContent(
    profile: Profile,
    isPublishing: Boolean,
    onEdit: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = profile.displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Name
        Text(
            text = profile.displayName,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Profile info card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                if (profile.bio != null && profile.bio.isNotBlank()) {
                    ProfileInfoRow(
                        icon = Icons.Default.Info,
                        label = "Bio",
                        value = profile.bio
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (profile.location != null && profile.location.isNotBlank()) {
                    ProfileInfoRow(
                        icon = Icons.Default.LocationOn,
                        label = "Location",
                        value = profile.location
                    )
                }

                if (profile.bio.isNullOrBlank() && profile.location.isNullOrBlank()) {
                    Text(
                        text = "Add a bio and location to personalize your profile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Button(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Edit Profile")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onPublish,
            enabled = !isPublishing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isPublishing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Publish to Connections")
        }
    }
}

@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun EditProfileContent(
    displayName: String,
    bio: String,
    location: String,
    isSaving: Boolean,
    isValid: Boolean,
    onDisplayNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onLocationChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Avatar placeholder
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (displayName.isNotBlank()) {
                        Text(
                            text = displayName.take(2).uppercase(),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Display name field
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChanged,
            label = { Text("Display Name *") },
            placeholder = { Text("Enter your name") },
            singleLine = true,
            isError = displayName.isBlank(),
            supportingText = if (displayName.isBlank()) {
                { Text("Display name is required") }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Bio field
        OutlinedTextField(
            value = bio,
            onValueChange = onBioChanged,
            label = { Text("Bio") },
            placeholder = { Text("Tell others about yourself") },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Location field
        OutlinedTextField(
            value = location,
            onValueChange = onLocationChanged,
            label = { Text("Location") },
            placeholder = { Text("City, Country") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Save button
        Button(
            onClick = onSave,
            enabled = isValid && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isSaving) "Saving..." else "Save Profile")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
