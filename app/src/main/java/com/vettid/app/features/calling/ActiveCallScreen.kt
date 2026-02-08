package com.vettid.app.features.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallGradientStart = Color(0xFF1A1A2E)
private val CallGradientEnd = Color(0xFF16213E)
private val DeclineRed = Color(0xFFE53935)
private val ControlActive = Color.White
private val ControlInactive = Color.White.copy(alpha = 0.2f)

/**
 * Full-screen active call UI with video surfaces.
 */
@Composable
fun ActiveCallScreen(
    viewModel: CallViewModel = hiltViewModel(),
    onDismiss: () -> Unit = {}
) {
    val callState by viewModel.callState.collectAsState()
    val activeState = callState as? CallState.Active
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    val localVideoTrack by viewModel.localVideoTrack.collectAsState()

    // Dismiss if no longer active
    LaunchedEffect(callState) {
        if (callState !is CallState.Active && callState !is CallState.Ended) {
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

    if (activeState == null) {
        // Show ended state briefly
        val endedState = callState as? CallState.Ended
        if (endedState != null) {
            EndedCallContent(
                call = endedState.call,
                reason = endedState.reason,
                duration = endedState.duration,
                viewModel = viewModel
            )
        }
        return
    }

    val call = activeState.call
    val isVideoCall = call.callType == CallType.VIDEO
    val showVideo = isVideoCall && (activeState.isRemoteVideoEnabled || activeState.isLocalVideoEnabled)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (showVideo) Modifier.background(Color.Black)
                else Modifier.background(Brush.verticalGradient(colors = listOf(CallGradientStart, CallGradientEnd)))
            )
    ) {
        // Remote video (full screen background)
        if (showVideo && activeState.isRemoteVideoEnabled && remoteVideoTrack != null) {
            VideoSurface(
                videoTrack = remoteVideoTrack,
                eglContext = viewModel.getEglContext(),
                mirror = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Content overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Top section: Peer info and duration
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 32.dp)
            ) {
                // Show avatar only when video is off
                if (!showVideo || !activeState.isRemoteVideoEnabled) {
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = call.peerDisplayName.take(2).uppercase(),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = call.peerDisplayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = viewModel.formatDuration(activeState.duration),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Local video PIP (bottom right corner)
            if (showVideo && activeState.isLocalVideoEnabled && localVideoTrack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, bottom = 16.dp)
                ) {
                    VideoSurface(
                        videoTrack = localVideoTrack,
                        eglContext = viewModel.getEglContext(),
                        mirror = activeState.isFrontCamera,
                        modifier = Modifier
                            .size(120.dp, 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            // Control buttons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                // First row: Mute, Speaker, and Video (if video call)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Mute button
                    ControlButton(
                        icon = if (activeState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (activeState.isMuted) "Unmute" else "Mute",
                        isActive = activeState.isMuted,
                        onClick = { viewModel.toggleMute() }
                    )

                    // Speaker button
                    ControlButton(
                        icon = if (activeState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        label = "Speaker",
                        isActive = activeState.isSpeakerOn,
                        onClick = { viewModel.toggleSpeaker() }
                    )

                    // Video toggle (only for video calls)
                    if (isVideoCall) {
                        ControlButton(
                            icon = if (activeState.isLocalVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            label = "Camera",
                            isActive = activeState.isLocalVideoEnabled,
                            onClick = { viewModel.toggleVideo() }
                        )
                    }

                    // Camera flip (only when video is on)
                    if (isVideoCall && activeState.isLocalVideoEnabled) {
                        ControlButton(
                            icon = Icons.Default.Cameraswitch,
                            label = "Flip",
                            isActive = false,
                            onClick = { viewModel.switchCamera() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // End call button
                FloatingActionButton(
                    onClick = { viewModel.endCall() },
                    modifier = Modifier.size(72.dp),
                    containerColor = DeclineRed,
                    contentColor = Color.White
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EndedCallContent(
    call: Call,
    reason: CallEndReason,
    duration: Long,
    viewModel: CallViewModel
) {
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
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = call.peerDisplayName.take(2).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = call.peerDisplayName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = viewModel.getStateDescription(CallState.Ended(call, reason, duration)),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            if (duration > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = viewModel.formatDuration(duration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isActive) ControlActive else ControlInactive,
                contentColor = if (isActive) Color.Black else Color.White
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun VideoSurface(
    videoTrack: VideoTrack?,
    eglContext: org.webrtc.EglBase.Context?,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var surfaceViewRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(videoTrack) {
        onDispose {
            videoTrack?.removeSink(surfaceViewRenderer)
            surfaceViewRenderer?.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                setMirror(mirror)
                setEnableHardwareScaler(true)
                eglContext?.let { init(it, null) }
                surfaceViewRenderer = this
                videoTrack?.addSink(this)
            }
        },
        modifier = modifier,
        update = { renderer ->
            renderer.setMirror(mirror)
        }
    )
}
