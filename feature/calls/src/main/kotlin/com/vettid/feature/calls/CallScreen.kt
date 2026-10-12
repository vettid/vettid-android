package com.vettid.feature.calls

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.calls.CallEnd
import com.vettid.core.calls.CallPhase
import com.vettid.core.calls.CallState
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** What the call screen can do. */
data class CallActions(
    val onAnswer: () -> Unit,
    val onDecline: () -> Unit,
    val onHangUp: () -> Unit,
    val onMute: () -> Unit,
    val onSpeaker: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Puts a call over the open vault's screens: whenever a call is live or has just ended, the call screen covers
 * [content]; otherwise [content] is all there is. It belongs inside the part of the app that exists only while
 * the vault is open and the app lock is passed, so a call screen is never in front of a locked app, and Answer
 * never gets past the unlock (a call rings only once the vault is open on this phone).
 */
@Composable
fun CallOverlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val vm: CallViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        content()
        if (state.call !is CallState.Idle) {
            // Back does not leave a call behind: it does nothing while one is live, and closes an ended one.
            BackHandler { if (state.call is CallState.Ended) vm.dismiss() }
            CallScreen(
                state,
                CallActions(vm::answer, vm::decline, vm::hangUp, vm::toggleMute, vm::toggleSpeaker, vm::dismiss),
            )
        }
        if (state.microphone != null) MicrophonePrompt(state.name, vm::microphoneAnswered)
    }
}

/** Why the microphone is asked for, then Android's own question. */
@Composable
private fun MicrophonePrompt(name: String?, onAnswered: (Boolean) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onAnswered(granted) }
    ConfirmDialog(
        title = stringResource(R.string.calls_microphone_title),
        text = stringResource(R.string.calls_microphone_body, name ?: stringResource(R.string.calls_name_unknown)),
        confirmLabel = stringResource(R.string.calls_microphone_allow),
        onConfirm = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        onDismiss = { onAnswered(false) },
        dismissLabel = stringResource(R.string.calls_microphone_not_now),
    )
}

/** The call, full screen: who, what is happening, and what can be done about it. */
@Composable
fun CallScreen(state: CallUiState, actions: CallActions, modifier: Modifier = Modifier, now: () -> Instant = Instant::now) {
    val name = state.name ?: stringResource(R.string.calls_name_unknown)
    Surface(modifier.fillMaxSize().testTag("call_screen"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxxl))
            InitialTile(name = name, size = AVATAR_DP)
            Spacer(Modifier.height(Spacing.l))
            Text(
                name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag("call_name"),
            )
            Spacer(Modifier.height(Spacing.s))
            when (val call = state.call) {
                is CallState.Live -> LiveCall(call, state, name, actions, now)
                is CallState.Ended -> EndedCall(call, name, actions.onDismiss)
                CallState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.LiveCall(call: CallState.Live, state: CallUiState, name: String, actions: CallActions, now: () -> Instant) {
    val line = CallTexts.phase(call.phase, call.reconnecting)
    Text(
        text = if (line != null) stringResource(line, name) else elapsed(call.activeSince, now),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("call_status"),
    )
    if (call.phase == CallPhase.ACTIVE) {
        Spacer(Modifier.height(Spacing.l))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("call_encryption")) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.s))
            Text(
                stringResource(R.string.calls_encryption, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.weight(1f))
    if (call.phase == CallPhase.INCOMING) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(Icons.Outlined.CallEnd, stringResource(R.string.calls_decline), actions.onDecline, "call_decline", destructive = true)
            RoundAction(Icons.Outlined.Call, stringResource(R.string.calls_answer), actions.onAnswer, tag = "call_answer")
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Toggle(
                Icons.Outlined.MicOff,
                stringResource(R.string.calls_mute),
                stringResource(R.string.calls_muted_on),
                call.muted,
                actions.onMute,
                tag = "call_mute",
            )
            RoundAction(Icons.Outlined.CallEnd, stringResource(R.string.calls_hang_up), actions.onHangUp, "call_hang_up", destructive = true)
            Toggle(
                Icons.Outlined.VolumeUp,
                stringResource(R.string.calls_speaker),
                stringResource(R.string.calls_speaker_on),
                state.speaker,
                actions.onSpeaker,
                enabled = state.canSpeaker,
                tag = "call_speaker",
            )
        }
    }
}

/** The time since media first flowed, ticking. */
@Composable
private fun elapsed(since: Instant?, now: () -> Instant): String {
    var seconds by remember(since) { mutableLongStateOf(since?.let { Duration.between(it, now()).seconds } ?: 0L) }
    LaunchedEffect(since) {
        while (since != null) {
            seconds = Duration.between(since, now()).seconds
            delay(TICK_MS)
        }
    }
    return CallTexts.clock(seconds)
}

@Composable
private fun ColumnScope.EndedCall(call: CallState.Ended, name: String, onDismiss: () -> Unit) {
    val stays = CallTexts.staysOpen(call.reason)
    Text(
        stringResource(CallTexts.ended(call.reason), name),
        style = MaterialTheme.typography.titleMedium,
        color = if (call.reason == CallEnd.NOT_ENCRYPTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("call_ended"),
    )
    Spacer(Modifier.weight(1f))
    PrimaryButton(stringResource(R.string.calls_close), onDismiss, Modifier.testTag("call_close"))
    // An ending that only reports closes by itself; one that says what to do, or that something was wrong, waits.
    if (!stays) {
        LaunchedEffect(call) {
            delay(ENDED_SHOWN_MS)
            onDismiss()
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, destructive: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(ACTION_DP.dp).testTag(tag),
            colors = if (destructive) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors()
            },
        ) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Toggle(
    icon: ImageVector,
    label: String,
    onLabel: String,
    on: Boolean,
    onClick: () -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = on,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(ACTION_DP.dp).testTag(tag).semantics { contentDescription = if (on) onLabel else label },
        ) { Icon(icon, contentDescription = null) }
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private const val AVATAR_DP = 96
private const val ACTION_DP = 64
private const val TICK_MS = 1_000L
private const val ENDED_SHOWN_MS = 3_500L
