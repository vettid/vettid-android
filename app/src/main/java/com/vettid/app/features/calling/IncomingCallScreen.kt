package com.vettid.app.features.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val CallGradientStart = Color(0xFF1A1A2E)
private val CallGradientEnd = Color(0xFF16213E)
private val AnswerGreen = Color(0xFF4CAF50)
private val DeclineRed = Color(0xFFE53935)

/**
 * Full-screen incoming call UI.
 */
@Composable
fun IncomingCallScreen(
    viewModel: CallViewModel = hiltViewModel(),
    onDismiss: () -> Unit = {}
) {
    val callState by viewModel.callState.collectAsState()
    val incomingState = callState as? CallState.Incoming

    // Dismiss if no longer incoming
    LaunchedEffect(callState) {
        if (callState !is CallState.Incoming && callState !is CallState.Active) {
            onDismiss()
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CallEffect.ShowError -> {
                    // Could show a toast/snackbar
                }
                else -> {}
            }
        }
    }

    if (incomingState == null) {
        return
    }

    val call = incomingState.call

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(CallGradientStart, CallGradientEnd)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .systemBarsPadding()
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Caller info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Call type indicator
                Text(
                    text = if (call.callType == CallType.VIDEO) "Incoming Video Call" else "Incoming Call",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Avatar
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = call.peerDisplayName.take(2).uppercase(),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Name
                Text(
                    text = call.peerDisplayName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status
                Text(
                    text = "is calling you",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Answer/Reject buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Reject button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.rejectCall()
                        },
                        modifier = Modifier.size(72.dp),
                        containerColor = DeclineRed,
                        contentColor = Color.White
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Decline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Answer button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.answerCall()
                        },
                        modifier = Modifier.size(72.dp),
                        containerColor = AnswerGreen,
                        contentColor = Color.White
                    ) {
                        Icon(
                            imageVector = if (call.callType == CallType.VIDEO)
                                Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Answer",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Answer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
