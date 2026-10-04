package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The unlock screen, driven by [UnlockViewModel]. */
@Composable
fun UnlockRoute(viewModel: UnlockViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UnlockContent(state, viewModel)
}

/** `m:ss` for a backoff countdown. */
fun formatWait(seconds: Long): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60

/** The unlock screen for [state] (stateless). */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun UnlockContent(state: UnlockUiState, actions: UnlockActions) {
    val p = state.preflight
    val blocked = state.preflightError == FailureKind.RELEASE_ENDED || p?.rollback == true || state.stateRollback
    FormScaffold(
        title = stringResource(R.string.unlock_title),
        body = state.email?.let { stringResource(R.string.unlock_body_account, it) } ?: stringResource(R.string.unlock_body),
        primaryLabel = when {
            state.preflightError != null && !blocked -> stringResource(R.string.unlock_retry)
            blocked || state.recoveryPending -> null
            else -> stringResource(R.string.unlock_submit)
        },
        onPrimary = if (state.preflightError != null) actions::retryPreflight else actions::submit,
        primaryEnabled = state.preflightError != null || (state.pinAllowed && state.pin.length >= 4),
        busy = state.busy || state.loading,
        secondaryLabel = stringResource(R.string.unlock_sign_out),
        onSecondary = actions::signOut,
        header = {
            Spacer(Modifier.height(Spacing.l))
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(40.dp),
            )
        },
    ) {
        val uri = LocalUriHandler.current
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (state.loading) {
                val label = stringResource(R.string.unlock_checking)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.m))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.preflightError?.let { NoticeCard(if (blocked) NoticeKind.URGENT
                else NoticeKind.WARNING, stringResource(R.string.unlock_title), stringResource(it.messageRes())) }
            if (p != null) {
                if (p.rollback) {
                    NoticeCard(
                        NoticeKind.URGENT,
                        stringResource(R.string.unlock_rollback_title),
                        stringResource(R.string.unlock_rollback_body, p.routed.number.toInt(), p.lastNumber.toInt()),
                    )
                } else if (p.softwareUpdated) {
                    NoticeCard(
                        NoticeKind.WARNING,
                        stringResource(R.string.unlock_updated_title),
                        stringResource(R.string.unlock_updated_body, p.routed.number.toInt(), p.routed.fingerprint, p.lastNumber.toInt()),
                        modifier = Modifier.testTag("software_updated"),
                        actions = if (!state.updateAcknowledged) {
                            { TextButton(onClick = actions::acknowledgeUpdate) { Text(stringResource(R.string.unlock_updated_ack)) } }
                        } else {
                            null
                        },
                    )
                }
                if (p.routed.status == "deprecated" || p.routed.status == "retired") {
                    val ends = p.routed.endsAt?.let {
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(it) }
                    NoticeCard(
                        if (p.routed.status == "retired") NoticeKind.URGENT else NoticeKind.WARNING,
                        stringResource(R.string.unlock_status_title, p.routed.number.toInt(), p.routed.status),
                        ends?.let { stringResource(R.string.unlock_status_body_ends, it) } ?: stringResource(R.string.unlock_status_body),
                    )
                }
                val offer = p.offer
                if (offer != null && !p.rollback) {
                    NoticeCard(
                        NoticeKind.INFO,
                        stringResource(R.string.unlock_offer_title, offer.number.toInt()),
                        stringResource(R.string.unlock_offer_body, p.routed.number.toInt(), offer.number.toInt(), offer.fingerprint),
                        modifier = Modifier.testTag("release_offer"),
                        actions = { TextButton(onClick = { uri.openUri(offer.notes) }) {
                            Text(stringResource(R.string.unlock_offer_notes)) } },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .toggleable(value = state.approveOffer, role = Role.Checkbox, onValueChange = actions::setApproveOffer)
                            .testTag("approve_offer"),
                    ) {
                        Checkbox(checked = state.approveOffer, onCheckedChange = null)
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.unlock_offer_approve), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.stateRollback) {
                NoticeCard(
                    NoticeKind.URGENT,
                    stringResource(R.string.unlock_rollback_state_title),
                    stringResource(R.string.unlock_rollback_state_body),
                )
            }
            if (state.recoveryPending) {
                NoticeCard(
                    NoticeKind.URGENT,
                    stringResource(R.string.unlock_recovery_title),
                    stringResource(R.string.unlock_recovery_body),
                    modifier = Modifier.testTag("recovery_pending"),
                    actions = {
                        TextButton(onClick = actions::cancelRecoveryAndUnlock, enabled = !state.busy) {
                            Text(stringResource(R.string.unlock_recovery_cancel), color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
            if (!blocked && !state.recoveryPending && p != null) {
                Spacer(Modifier.height(Spacing.s))
                val waitText = if (state.waitSeconds > 0) stringResource(R.string.unlock_wait, formatWait(state.waitSeconds)) else null
                SecretField(
                    value = state.pin,
                    onValueChange = actions::setPin,
                    label = stringResource(R.string.unlock_pin_label),
                    isPin = true,
                    enabled = state.pinAllowed,
                    error = if (state.message == UnlockMessage.BadPin) stringResource(R.string.unlock_bad_pin) else null,
                    supporting = waitText,
                    onImeAction = actions::submit,
                    modifier = Modifier.testTag("unlock_pin"),
                )
                when (val m = state.message) {
                    is UnlockMessage.UpdateRefused -> NoticeCard(
                        NoticeKind.WARNING,
                        stringResource(R.string.unlock_title),
                        stringResource(R.string.unlock_update_refused, m.code),
                    )
                    is UnlockMessage.Failed -> NoticeCard(
                        NoticeKind.URGENT,
                        stringResource(R.string.unlock_title),
                        if (m.code == "unreadable_result") stringResource(R.string.unlock_unreadable)
                            else stringResource(m.kind.messageRes()),
                        modifier = Modifier.testTag("unlock_failed"),
                    )
                    else -> Unit
                }
            }
        }
    }
}

/** The biometric app lock's screen (D6): the prompt opens on its own; the button opens it again. */
@Composable
fun AppLockScreen(onUnlock: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.app_lock_title),
        body = stringResource(R.string.app_lock_body),
        primaryLabel = stringResource(R.string.app_lock_unlock),
        onPrimary = onUnlock,
        header = {
            Spacer(Modifier.height(Spacing.xxl))
            com.vettid.core.ui.components.RookLogo(height = 72.dp)
            Spacer(Modifier.height(Spacing.l))
        },
    ) {}
}
