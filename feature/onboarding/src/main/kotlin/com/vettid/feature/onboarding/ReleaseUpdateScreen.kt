package com.vettid.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.ReleaseUpdateManager
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.UpdateNoticeKind
import com.vettid.core.data.vault.UpdateProgress
import com.vettid.core.data.vault.UpdateStep
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.StepList
import com.vettid.core.ui.components.StepState
import com.vettid.core.ui.theme.Spacing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The update screen: "Update now" from the banner, the notification or Settings → Vault. */
@Serializable
data object ReleaseUpdateRoute

/** Registers [ReleaseUpdateRoute] in the app shell. */
fun NavGraphBuilder.releaseUpdateDestination(onBack: () -> Unit) {
    composable<ReleaseUpdateRoute> {
        val vm: ReleaseUpdateViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ReleaseUpdateContent(state, vm, onBack)
    }
}

/** A release's end date for display (the unlock screen's format). */
fun releaseDate(t: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(t)

/** The update screen for [state] (stateless). */
@Composable
fun ReleaseUpdateContent(state: ReleaseUpdateUiState, actions: ReleaseUpdateActions, onBack: () -> Unit) {
    val offer = state.offer
    FormScaffold(
        title = stringResource(R.string.release_update_title),
        body = offer?.let { stringResource(R.string.release_update_body, it.target.number.toInt()) },
        primaryLabel = if (offer != null) stringResource(R.string.release_update_approve) else null,
        onPrimary = actions::approve,
        primaryEnabled = state.submitAllowed,
        busy = state.loading && offer == null,
        onBack = onBack,
        header = { UpdateIcon() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            when {
                offer != null -> {
                    OfferNotices(offer)
                    if (state.checkDue) {
                        NoticeCard(
                            NoticeKind.WARNING,
                            stringResource(R.string.release_update_check_first_title),
                            stringResource(R.string.release_update_check_first_body),
                            modifier = Modifier.testTag("release_update_check_first"),
                        )
                    }
                    SecretField(
                        value = state.pin,
                        onValueChange = actions::setPin,
                        label = stringResource(R.string.unlock_pin_label),
                        isPin = true,
                        enabled = !state.checkDue,
                        onImeAction = actions::approve,
                        modifier = Modifier.testTag("release_update_pin"),
                    )
                }
                state.loading -> Checking(stringResource(R.string.unlock_checking))
                else -> NoticeCard(
                    NoticeKind.SUCCESS,
                    stringResource(R.string.release_update_none_title),
                    stringResource(R.string.release_update_none_body),
                    modifier = Modifier.testTag("release_update_none"),
                )
            }
        }
    }
}

@Composable
private fun UpdateIcon() {
    Spacer(Modifier.height(Spacing.l))
    Icon(
        Icons.Outlined.SystemUpdate,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.height(40.dp),
    )
}

@Composable
private fun Checking(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.m))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The release offered (§11.10.3: number, fingerprint, notes) and, for an ending release, its end. */
@Composable
private fun OfferNotices(offer: ReleaseUpdateOffer) {
    val uri = LocalUriHandler.current
    when (offer.kind) {
        UpdateNoticeKind.ENDING -> NoticeCard(
            NoticeKind.URGENT,
            stringResource(R.string.unlock_status_title, offer.current.number.toInt(), offer.current.status),
            offer.endsAt?.let { stringResource(R.string.unlock_status_body_ends, releaseDate(it)) }
                ?: stringResource(R.string.unlock_status_body),
            modifier = Modifier.testTag("release_update_ending"),
        )
        UpdateNoticeKind.ENDED -> NoticeCard(
            NoticeKind.URGENT,
            stringResource(R.string.unlock_status_title, offer.current.number.toInt(), offer.current.status),
            stringResource(R.string.release_update_ended_body),
            modifier = Modifier.testTag("release_update_ending"),
        )
        UpdateNoticeKind.AVAILABLE -> Unit
    }
    NoticeCard(
        NoticeKind.INFO,
        stringResource(R.string.unlock_offer_title, offer.target.number.toInt()),
        stringResource(R.string.unlock_offer_body, offer.current.number.toInt(), offer.target.number.toInt(), offer.target.fingerprint),
        modifier = Modifier.testTag("release_offer"),
        actions = { TextButton(onClick = { uri.openUri(offer.target.notes) }) { Text(stringResource(R.string.unlock_offer_notes)) } },
    )
    Text(
        stringResource(R.string.release_update_how),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The update's own screens, shown by the root over every phase while [ReleaseUpdateFlowUiState.progress] is set. */
@Composable
fun ReleaseUpdateFlowRoute(viewModel: ReleaseUpdateFlowViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReleaseUpdateFlowContent(state, viewModel)
}

/** The update's screens for [state] (stateless): progress, the outcome, a PIN to try again, the way back. */
@Suppress("CyclomaticComplexMethod", "LongMethod") // one layout per step
@Composable
fun ReleaseUpdateFlowContent(state: ReleaseUpdateFlowUiState, actions: ReleaseUpdateFlowActions) {
    val p = state.progress ?: return
    val from = p.from.number.toInt()
    val to = p.to.number.toInt()
    val badPin = p.step == UpdateStep.BAD_PIN
    // While a step runs, back does nothing (the vault's answer decides where it is); afterwards it leaves.
    BackHandler { if (p.waiting) actions.finish() }
    when (p.step) {
        UpdateStep.LOCKING, UpdateStep.APPROVING, UpdateStep.REOPENING, UpdateStep.ABANDONING -> FormScaffold(
            title = stringResource(
                if (p.step == UpdateStep.ABANDONING) R.string.release_update_returning_title else R.string.release_update_running_title,
                if (p.step == UpdateStep.ABANDONING) from else to,
            ),
            primaryLabel = null,
            onPrimary = {},
            modifier = Modifier.testTag("release_update_progress"),
            header = { UpdateIcon() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                if (p.step == UpdateStep.ABANDONING) {
                    Checking(stringResource(R.string.release_update_step_return, from))
                } else {
                    StepList(steps(p, from, to))
                }
                if (p.starting) {
                    Text(
                        stringResource(R.string.release_update_starting, if (p.step == UpdateStep.APPROVING) from else to),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("release_update_starting").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    stringResource(R.string.release_update_keep_open),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        UpdateStep.DONE -> FormScaffold(
            title = stringResource(R.string.release_update_done_title),
            body = stringResource(R.string.release_update_done_body, to, p.to.fingerprint),
            primaryLabel = stringResource(R.string.release_update_continue),
            onPrimary = actions::finish,
            modifier = Modifier.testTag("release_update_done"),
            header = { UpdateIcon() },
        ) {}
        UpdateStep.REFUSED -> FormScaffold(
            title = stringResource(R.string.release_update_refused_title),
            body = stringResource(R.string.release_update_refused_body, from),
            primaryLabel = stringResource(R.string.release_update_continue),
            onPrimary = actions::finish,
            modifier = Modifier.testTag("release_update_refused"),
            header = { UpdateIcon() },
        ) {
            NoticeCard(NoticeKind.WARNING, stringResource(R.string.release_update_refused_why), refusalText(p.refusal ?: "", to))
        }
        UpdateStep.ABANDONED -> FormScaffold(
            title = stringResource(R.string.release_update_abandoned_title, from),
            body = stringResource(R.string.release_update_abandoned_body, from, to),
            primaryLabel = stringResource(R.string.release_update_continue),
            onPrimary = actions::finish,
            modifier = Modifier.testTag("release_update_abandoned"),
            header = { UpdateIcon() },
        ) {}
        UpdateStep.BAD_PIN, UpdateStep.FAILED -> FormScaffold(
            title = stringResource(if (badPin) R.string.release_update_title else R.string.release_update_failed_title),
            body = stringResource(
                if (p.vaultOpen) R.string.release_update_failed_body_open else R.string.release_update_failed_body_locked,
                from,
            ),
            primaryLabel = stringResource(if (badPin) R.string.release_update_approve else R.string.release_update_retry),
            onPrimary = actions::retry,
            primaryEnabled = state.retryAllowed,
            secondaryLabel = stringResource(if (p.vaultOpen) R.string.release_update_not_now else R.string.release_update_unlock_only),
            onSecondary = actions::finish,
            modifier = Modifier.testTag(if (badPin) "release_update_bad_pin" else "release_update_failed"),
            header = { UpdateIcon() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (p.step == UpdateStep.FAILED) {
                    NoticeCard(NoticeKind.WARNING, stringResource(R.string.release_update_failed_title), failureText(p))
                }
                UpdatePin(state, actions, p)
            }
        }
        UpdateStep.NEW_RELEASE_FAILED -> FormScaffold(
            title = stringResource(R.string.release_update_new_failed_title, to),
            body = stringResource(
                if (p.canAbandon) R.string.release_update_new_failed_body else R.string.release_update_new_failed_body_no_return,
                to,
                from,
            ),
            primaryLabel = stringResource(R.string.release_update_retry),
            onPrimary = actions::retry,
            primaryEnabled = state.retryAllowed,
            secondaryLabel = if (p.canAbandon) stringResource(R.string.release_update_return, from) else null,
            onSecondary = actions::askAbandon,
            onBack = actions::finish,
            modifier = Modifier.testTag("release_update_new_failed"),
            header = { UpdateIcon() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                NoticeCard(NoticeKind.URGENT, stringResource(R.string.release_update_new_failed_why), failureText(p))
                UpdatePin(state, actions, p)
            }
        }
    }
    if (state.abandonConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.release_update_return_confirm_title, from),
            text = stringResource(R.string.release_update_return_confirm_body, from, to),
            confirmLabel = stringResource(R.string.release_update_return, from),
            onConfirm = actions::confirmAbandon,
            onDismiss = actions::dismissAbandon,
            modifier = Modifier.testTag("release_update_return_confirm"),
        )
    }
}

@Composable
private fun UpdatePin(state: ReleaseUpdateFlowUiState, actions: ReleaseUpdateFlowActions, p: UpdateProgress) {
    SecretField(
        value = state.pin,
        onValueChange = actions::setPin,
        label = stringResource(R.string.unlock_pin_label),
        isPin = true,
        enabled = state.pinAllowed,
        error = if (p.failure == FailureKind.BAD_PIN) stringResource(R.string.unlock_bad_pin) else null,
        supporting = if (state.waitSeconds > 0) stringResource(R.string.unlock_wait, formatWait(state.waitSeconds)) else null,
        onImeAction = actions::retry,
        modifier = Modifier.testTag("release_update_pin"),
    )
}

@Composable
private fun steps(p: UpdateProgress, from: Int, to: Int): List<Pair<String, StepState>> {
    val order = listOf(UpdateStep.LOCKING, UpdateStep.APPROVING, UpdateStep.REOPENING)
    val at = order.indexOf(p.step)
    val labels = listOf(
        stringResource(R.string.release_update_step_lock),
        stringResource(R.string.release_update_step_approve, from, to),
        stringResource(R.string.release_update_step_open, to),
    )
    return labels.mapIndexed { i, l -> l to if (i < at) StepState.DONE else if (i == at) StepState.ACTIVE else StepState.PENDING }
}

@Composable
private fun failureText(p: UpdateProgress): String = when {
    p.failure == FailureKind.BAD_PIN -> stringResource(R.string.unlock_bad_pin)
    p.failure == FailureKind.BACKOFF -> stringResource(R.string.release_update_backoff)
    p.failureCode == ReleaseUpdateManager.CODE_RELEASE_STARTING -> stringResource(R.string.release_update_start_failed)
    else -> stringResource((p.failure ?: FailureKind.OTHER).messageRes())
}

/** The refusal codes of §11.10.4 step 4, and a step 6 write failure, in the member's words. */
@Composable
private fun refusalText(code: String, to: Int): String = when (code) {
    "target" -> stringResource(R.string.release_update_refused_target, to)
    "downgrade" -> stringResource(R.string.release_update_refused_downgrade, to)
    "approval" -> stringResource(R.string.release_update_refused_approval)
    "seal_key" -> stringResource(R.string.release_update_refused_seal_key, to)
    "pending" -> stringResource(R.string.release_update_refused_pending)
    "write" -> stringResource(R.string.release_update_refused_write)
    else -> stringResource(R.string.release_update_refused_other, code)
}
