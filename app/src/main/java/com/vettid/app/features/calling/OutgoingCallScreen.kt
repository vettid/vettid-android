package com.vettid.app.features.calling

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val CallGradientStart = Color(0xFF1A1A2E)
private val CallGradientEnd = Color(0xFF16213E)
private val DeclineRed = Color(0xFFE53935)
private val ControlActive = Color(0xFF4CAF50)
private val ControlInactive = Color.White.copy(alpha = 0.3f)

/**
 * Full-screen outgoing call UI (ringing state).
 */
@Composable
fun OutgoingCallScreen(
    viewModel: CallViewModel = hiltViewModel(),
    onDismiss: () -> Unit = {}
) {
    val callState by viewModel.callState.collectAsState()
    val outgoingState = callState as? CallState.Outgoing

    // Dismiss if no longer outgoing
    LaunchedEffect(callState) {
        if (callState !is CallState.Outgoing && callState !is CallState.Active) {
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

    if (outgoingState == null) {
        return
    }

    val call = outgoingState.call
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    // Pulsing animation for avatar
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

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

            // Callee info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Call type
                Text(
                    text = if (call.callType == CallType.VIDEO) "Video Call" else "Voice Call",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Avatar with pulsing animation
                Surface(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(scale),
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

                // Ringing status
                Text(
                    text = "Calling...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Ringing duration
                Text(
                    text = viewModel.formatDuration(outgoingState.ringingDuration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Control buttons row (mute + speaker)
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                // Mute button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            isMuted = !isMuted
                            viewModel.toggleMute()
                        },
                        modifier = Modifier.size(56.dp),
                        containerColor = if (isMuted) ControlActive else ControlInactive,
                        contentColor = Color.White
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isMuted) "Unmute" else "Mute",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Speaker button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            isSpeakerOn = !isSpeakerOn
                            viewModel.toggleSpeaker()
                        },
                        modifier = Modifier.size(56.dp),
                        containerColor = if (isSpeakerOn) ControlActive else ControlInactive,
                        contentColor = Color.White
                    ) {
                        Icon(
                            imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (isSpeakerOn) "Earpiece" else "Speaker",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isSpeakerOn) "Speaker" else "Earpiece",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Cancel button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FloatingActionButton(
                    onClick = {
                        viewModel.endCall()
                    },
                    modifier = Modifier.size(72.dp),
                    containerColor = DeclineRed,
                    contentColor = Color.White
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
