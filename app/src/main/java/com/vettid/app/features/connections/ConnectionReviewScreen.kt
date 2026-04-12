package com.vettid.app.features.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.connections.components.ConnectionPreviewCard

/**
 * Unified connection review screen used by both sides:
 * - Inviter reviewing scanner's profile before accepting
 * - Scanner reviewing inviter's profile before accepting (replaces inline preview)
 *
 * Shows the peer's full published profile with Accept/Decline buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionReviewScreen(
    viewModel: ConnectionReviewViewModel = hiltViewModel(),
    onAccepted: () -> Unit = {},
    onDeclined: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConnectionReviewEffect.Accepted -> onAccepted()
                is ConnectionReviewEffect.Declined -> onDeclined()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Request") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val currentState = state) {
            is ConnectionReviewState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ConnectionReviewState.Loaded -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    ConnectionPreviewCard(
                        profile = currentState.peerProfile,
                        onAccept = { viewModel.acceptConnection() },
                        onDecline = { viewModel.declineConnection() },
                        isProcessing = currentState.isProcessing
                    )
                }
            }

            is ConnectionReviewState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentState.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack) {
                            Text("Go Back")
                        }
                    }
                }
            }
        }
    }
}
