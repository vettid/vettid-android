package com.vettid.feature.settings

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.QrCode
import com.vettid.core.ui.components.SafetyCode
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import kotlinx.coroutines.launch

private const val SECONDS_PER_MINUTE = 60
private const val LINK_LINES = 3

/** `m:ss`. */
private fun mmss(seconds: Long): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

/** One step of the transfer on the old phone for [state] (stateless). [onBack] leaves Settings' sub-screen. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun TransferOutContent(state: TransferOutUiState, actions: TransferOutActions, onOpenRecovery: () -> Unit, onBack: () -> Unit) {
    val leave: () -> Unit = {
        actions.leave()
        onBack()
    }
    BackHandler { leave() }
    when (state.step) {
        TransferOutStep.INTRO -> FormScaffold(
            title = stringResource(R.string.settings_transfer_title),
            body = stringResource(R.string.settings_transfer_body),
            primaryLabel = stringResource(R.string.settings_transfer_start),
            onPrimary = actions::create,
            busy = state.busy,
            onBack = leave,
            secondaryLabel = stringResource(R.string.settings_security_recovery),
            onSecondary = onOpenRecovery,
        ) {
            NoticeCard(
                NoticeKind.INFO,
                stringResource(R.string.settings_transfer_steps_title),
                stringResource(R.string.settings_transfer_steps_body),
            )
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(R.string.settings_transfer_lost), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TransferError(state)
        }
        TransferOutStep.SHOWING -> ShowCode(state, leave)
        TransferOutStep.COMPARE -> FormScaffold(
            title = stringResource(R.string.settings_transfer_compare_title),
            body = stringResource(R.string.settings_transfer_compare_body, state.pending?.name ?: ""),
            primaryLabel = stringResource(R.string.settings_transfer_match),
            onPrimary = actions::codesMatch,
            secondaryLabel = stringResource(R.string.settings_transfer_reject),
            onSecondary = { actions.askReject(true) },
            busy = state.busy,
            onBack = leave,
        ) {
            state.pending?.let { SasBox(it.sas) }
            Spacer(Modifier.height(Spacing.m))
            Window(state.secondsLeft)
            TransferError(state)
        }
        TransferOutStep.APPROVE -> FormScaffold(
            title = stringResource(R.string.settings_transfer_approve_title),
            body = stringResource(R.string.settings_transfer_approve_body, state.pending?.name ?: ""),
            primaryLabel = stringResource(R.string.settings_transfer_approve),
            onPrimary = { actions.askApprove(true) },
            primaryEnabled = state.canApprove,
            busy = state.busy,
            destructive = true,
            secondaryLabel = stringResource(R.string.settings_transfer_reject),
            onSecondary = { actions.askReject(true) },
            onBack = leave,
        ) {
            val wait = if (state.waitSeconds > 0) stringResource(R.string.settings_transfer_wait, mmss(state.waitSeconds)) else null
            SecretField(
                value = state.pin,
                onValueChange = actions::setPin,
                label = stringResource(R.string.settings_transfer_pin),
                isPin = true,
                error = if (state.error == FailureKind.BAD_PIN) stringResource(R.string.settings_transfer_bad_pin) else null,
                supporting = wait,
                imeAction = ImeAction.Next,
                enabled = state.waitSeconds == 0L && !state.busy,
                modifier = Modifier.testTag("transfer_pin"),
            )
            Spacer(Modifier.height(Spacing.m))
            SecretField(
                value = state.password,
                onValueChange = actions::setPassword,
                label = stringResource(R.string.settings_transfer_password),
                error = if (state.error == FailureKind.BAD_PASSWORD) stringResource(R.string.settings_transfer_bad_password) else null,
                enabled = state.waitSeconds == 0L && !state.busy,
                onImeAction = { actions.askApprove(true) },
                modifier = Modifier.testTag("transfer_password"),
            )
            Spacer(Modifier.height(Spacing.m))
            Window(state.secondsLeft)
            if (state.error != FailureKind.BAD_PIN && state.error != FailureKind.BAD_PASSWORD) TransferError(state)
        }
        TransferOutStep.EXPIRED -> FormScaffold(
            title = stringResource(R.string.settings_transfer_expired_title),
            body = stringResource(R.string.settings_transfer_expired_body),
            primaryLabel = stringResource(R.string.settings_transfer_again),
            onPrimary = actions::create,
            busy = state.busy,
            onBack = leave,
            modifier = Modifier.testTag("transfer_expired"),
        ) { TransferError(state) }
        TransferOutStep.REJECTED -> FormScaffold(
            title = stringResource(R.string.settings_transfer_rejected_title),
            body = stringResource(R.string.settings_transfer_rejected_body),
            primaryLabel = stringResource(R.string.settings_done),
            onPrimary = leave,
            onBack = leave,
            modifier = Modifier.testTag("transfer_rejected"),
        ) {}
        TransferOutStep.MOVED -> FormScaffold(
            title = stringResource(R.string.settings_transfer_moved_title),
            body = stringResource(R.string.settings_transfer_moved_body, state.pending?.name ?: ""),
            primaryLabel = stringResource(R.string.settings_done),
            onPrimary = leave,
            onBack = leave,
            modifier = Modifier.testTag("transfer_moved"),
        ) {}
    }
    if (state.confirmReject) {
        ConfirmDialog(
            title = stringResource(R.string.settings_transfer_reject_title),
            text = stringResource(R.string.settings_transfer_reject_body),
            confirmLabel = stringResource(R.string.settings_transfer_reject),
            onConfirm = actions::reject,
            onDismiss = { actions.askReject(false) },
        )
    }
    if (state.confirmApprove) {
        ConfirmDialog(
            title = stringResource(R.string.settings_transfer_approve_confirm_title, state.pending?.name ?: ""),
            text = stringResource(R.string.settings_transfer_approve_confirm_body),
            confirmLabel = stringResource(R.string.settings_transfer_approve_confirm),
            onConfirm = actions::approve,
            onDismiss = { actions.askApprove(false) },
            destructive = true,
        )
    }
}

@Composable
private fun ShowCode(state: TransferOutUiState, leave: () -> Unit) {
    val offer = state.offer ?: return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val clipLabel = stringResource(R.string.settings_transfer_title)
    FormScaffold(
        title = stringResource(R.string.settings_transfer_show_title),
        body = stringResource(R.string.settings_transfer_show_body),
        primaryLabel = null,
        onPrimary = {},
        secondaryLabel = stringResource(R.string.settings_transfer_cancel),
        onSecondary = leave,
        onBack = leave,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCode(
                content = offer.qrPayload,
                contentDescription = stringResource(R.string.settings_transfer_qr_cd),
                modifier = Modifier.testTag("transfer_qr"),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.settings_transfer_expires, mmss(state.secondsLeft)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.m))
        Waiting(stringResource(R.string.settings_transfer_waiting))
        Spacer(Modifier.height(Spacing.l))
        Text(stringResource(R.string.settings_transfer_link_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.xs))
        Surface(shape = VettIdShape.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    offer.link,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = LINK_LINES,
                    modifier = Modifier.padding(Spacing.m).testTag("transfer_link"),
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        OutlinedButton(
            onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, offer.link))) }
                copied = true
            },
            shape = VettIdShape.pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.s))
            Text(
                stringResource(if (copied) R.string.settings_transfer_copied else R.string.settings_transfer_copy),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TransferError(state)
    }
}

@Composable
private fun Window(secondsLeft: Long) {
    Text(
        stringResource(R.string.settings_transfer_window, mmss(secondsLeft)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("transfer_window"),
    )
}

@Composable
private fun Waiting(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("waiting_new_phone"),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(Spacing.m))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The SAS, large, in a bordered box (§6.3); `sas` test tag. */
@Composable
private fun SasBox(sas: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, VettIdShape.card)
            .padding(vertical = Spacing.l)
            .testTag("sas"),
        contentAlignment = Alignment.Center,
    ) { SafetyCode(sas) }
}

@Composable
private fun TransferError(state: TransferOutUiState) {
    val kind = state.error ?: return
    val text = when {
        state.errorCode == "exists" -> stringResource(R.string.settings_transfer_exists)
        kind == FailureKind.CREDENTIAL_FROZEN || kind == FailureKind.ROTATION_REQUIRED -> stringResource(R.string.settings_transfer_alarm)
        else -> stringResource(kind.messageRes())
    }
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(NoticeKind.URGENT, stringResource(R.string.settings_transfer_title), text, Modifier.testTag("transfer_error"))
}
