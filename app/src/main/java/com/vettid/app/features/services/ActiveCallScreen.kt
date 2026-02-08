package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*

/**
 * Active call screen with controls overlay.
 *
 * Displays video views for video calls and audio-only UI for voice calls.
 *
 * Issue #38 [AND-025] - Call UI (voice/video).
 */
@Composable
fun ActiveCallScreen(
    viewModel: CallViewModel = hiltViewModel(),
    onCallEnded: () -> Unit = {}
) {
    val callState by viewModel.callState.collectAsState()

    // Handle call end
    LaunchedEffect(callState?.status) {
        if (callState?.status == CallStatus.ENDED) {
            onCallEnded()
        }
    }

    val state = callState
    if (state == null) {
        CallEndedContent()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main content - video or audio view
        if (state.callType == CallType.VIDEO && state.isVideoEnabled) {
            VideoCallView(state = state)
        } else {
            AudioCallView(state = state)
        }

        // Top bar overlay
        TopBarOverlay(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        )

        // Connection status overlay
        if (state.status == CallStatus.CONNECTING || state.status == CallStatus.RECONNECTING) {
            ConnectionStatusOverlay(status = state.status)
        }

        // Bottom controls overlay
        BottomControlsOverlay(
            state = state,
            onToggleMute = viewModel::toggleMute,
            onToggleVideo = viewModel::toggleVideo,
            onToggleSpeaker = viewModel::toggleSpeaker,
            onEndCall = viewModel::endCall,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(32.dp)
        )
    }
}

@Composable
private fun VideoCallView(state: ServiceCallState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Remote video (full screen)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            // TODO: Integrate actual WebRTC remote video track
            // For now showing placeholder
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.PersonOutline,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = Color.White.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.agentInfo?.name ?: state.serviceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // Local video (picture-in-picture)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp, end = 16.dp)
                .size(width = 120.dp, height = 160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            // TODO: Integrate actual WebRTC local video track
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = "You",
                modifier = Modifier.size(48.dp),
                tint = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun AudioCallView(state: ServiceCallState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A237E),
                        Color(0xFF0D47A1),
                        Color(0xFF1565C0)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Service/Agent avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (state.serviceLogoUrl != null) {
                    AsyncImage(
                        model = state.serviceLogoUrl,
                        contentDescription = state.serviceName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Name
            Text(
                text = state.agentInfo?.name ?: state.serviceName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            state.agentInfo?.let { agent ->
                Text(
                    text = "${state.serviceName} • ${agent.role}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Call duration
            Text(
                text = state.durationFormatted,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.9f)
            )

            // Quality indicator
            if (state.quality != CallQuality.GOOD && state.quality != CallQuality.EXCELLENT) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = when (state.quality) {
                        CallQuality.FAIR -> Color(0xFFFFA000)
                        CallQuality.POOR -> Color(0xFFE53935)
                        else -> Color.Transparent
                    }.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SignalCellularAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = when (state.quality) {
                                CallQuality.FAIR -> Color(0xFFFFA000)
                                CallQuality.POOR -> Color(0xFFE53935)
                                else -> Color.White
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Poor connection",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarOverlay(
    state: ServiceCallState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Service badge
        Surface(
            color = Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.serviceVerified) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = state.serviceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Duration
        Surface(
            color = Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = state.durationFormatted,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ConnectionStatusOverlay(status: CallStatus) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when (status) {
                    CallStatus.CONNECTING -> "Connecting..."
                    CallStatus.RECONNECTING -> "Reconnecting..."
                    else -> ""
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun BottomControlsOverlay(
    state: ServiceCallState,
    onToggleMute: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute button
        CallControlButton(
            icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            isActive = state.isMuted,
            onClick = onToggleMute,
            contentDescription = if (state.isMuted) "Unmute" else "Mute"
        )

        // Video toggle (only for video calls)
        if (state.callType == CallType.VIDEO) {
            CallControlButton(
                icon = if (state.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                isActive = !state.isVideoEnabled,
                onClick = onToggleVideo,
                contentDescription = if (state.isVideoEnabled) "Turn off video" else "Turn on video"
            )
        }

        // End call button
        FloatingActionButton(
            onClick = onEndCall,
            containerColor = Color(0xFFE53935),
            contentColor = Color.White,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "End call",
                modifier = Modifier.size(28.dp)
            )
        }

        // Speaker button
        CallControlButton(
            icon = if (state.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
            isActive = state.isSpeakerOn,
            onClick = onToggleSpeaker,
            contentDescription = if (state.isSpeakerOn) "Speaker off" else "Speaker on"
        )

        // More options placeholder
        CallControlButton(
            icon = Icons.Default.MoreVert,
            isActive = false,
            onClick = { /* TODO: Show more options */ },
            contentDescription = "More options"
        )
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (isActive) Color.White else Color.White.copy(alpha = 0.2f),
        contentColor = if (isActive) Color.Black else Color.White,
        modifier = Modifier.size(56.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CallEndedContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Call Ended",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
        }
    }
}
