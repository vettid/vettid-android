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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.LockNotice
import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseNotes
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
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
fun UnlockRoute(viewModel: UnlockViewModel = hiltViewModel(), onOpenAccountSite: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UnlockContent(state, viewModel, onOpenAccountSite)
}

/** `m:ss` for a backoff countdown. */
fun formatWait(seconds: Long): String = "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60

/** The unlock screen for [state] (stateless). [onOpenAccountSite]: "Recover your vault" (keys gone, or recover-only). */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun UnlockContent(state: UnlockUiState, actions: UnlockActions, onOpenAccountSite: () -> Unit = {}) {
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
        primaryEnabled = if (state.preflightError != null) state.retryAllowed else state.submitAllowed,
        busy = state.busy || state.loading || state.erasing,
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
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (state.loading) {
                val label = stringResource(R.string.unlock_checking)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.m))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.keptTransfer) {
                // Release R (§11.14.10): the old phone after a transfer's approval.
                NoticeCard(
                    NoticeKind.INFO,
                    stringResource(R.string.onboarding_move_didnt_finish),
                    stringResource(R.string.onboarding_kept_transfer) + "\n\n" + stringResource(R.string.onboarding_move_didnt_finish_body),
                    modifier = Modifier.testTag("kept_transfer"),
                )
            }
            state.preflightError?.let {
                NoticeCard(
                    // The vault's software did not verify: alarming, unlike a list that is stale or could not be loaded.
                    if (blocked || it == FailureKind.ENCLAVE) NoticeKind.URGENT else NoticeKind.WARNING,
                    stringResource(
                        when (it) {
                            FailureKind.RELEASE_UNKNOWN -> R.string.unlock_release_unknown_title
                            FailureKind.ENCLAVE -> R.string.unlock_enclave_title
                            else -> R.string.unlock_title
                        },
                    ),
                    stringResource(it.messageRes()),
                    modifier = Modifier.testTag("unlock_preflight_error"),
                )
            }
            if (state.serviceWaitSeconds > 0) {
                Text(
                    stringResource(R.string.unlock_service_wait, formatWait(state.serviceWaitSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("unlock_service_wait"),
                )
            }
            if (p != null) {
                PreflightNotices(
                    p, state.updateAcknowledged, actions::acknowledgeUpdate, state.approveOffer, actions::setApproveOffer, state.notes,
                )
            }
            // The lock the member asked for, until the vault has confirmed it: said here, wherever they locked from.
            // These appear and change by themselves, so each is announced to a screen reader when it does.
            when (val lock = state.lock) {
                // Nothing has failed yet: no warning, only that the lock is on its way.
                is LockNotice.Unconfirmed -> if (!lock.inDoubt) {
                    Text(
                        stringResource(R.string.unlock_lock_locking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("unlock_lock_locking"),
                    )
                } else {
                    NoticeCard(
                        NoticeKind.URGENT,
                        stringResource(R.string.unlock_lock_unconfirmed_title),
                        stringResource(R.string.unlock_lock_unconfirmed_body),
                        modifier = Modifier.testTag("unlock_lock_unconfirmed"),
                        announce = LiveRegionMode.Assertive,
                        actions = { TextButton(onClick = actions::retryLock) { Text(stringResource(R.string.unlock_lock_try_again)) } },
                    )
                }
                LockNotice.ConfirmedLate -> NoticeCard(
                    NoticeKind.SUCCESS,
                    stringResource(R.string.unlock_lock_confirmed),
                    "",
                    modifier = Modifier.testTag("unlock_lock_confirmed"),
                    announce = LiveRegionMode.Polite,
                    actions = { TextButton(onClick = actions::dismissLockNotice) { Text(stringResource(R.string.unlock_lock_ok)) } },
                )
                LockNotice.None -> Unit
            }
            if (state.refused && !state.notRecognised) {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.unlock_refused_title),
                    stringResource(R.string.unlock_refused_body),
                    modifier = Modifier.testTag("unlock_refused"),
                )
            }
            if (state.lockedByOwnerCheck) {
                NoticeCard(
                    NoticeKind.URGENT,
                    stringResource(R.string.unlock_owner_check_locked_title),
                    stringResource(R.string.unlock_owner_check_locked_body),
                    modifier = Modifier.testTag("unlock_owner_check_locked"),
                )
            } else if (state.checkDue) {
                NoticeCard(
                    NoticeKind.INFO,
                    stringResource(R.string.unlock_owner_check_title),
                    stringResource(R.string.unlock_owner_check_body),
                    modifier = Modifier.testTag("unlock_owner_check"),
                )
            }
            state.moveFailure?.let { f ->
                // Release R (§11.14.10): "Finish the move" locked the vault, and its unlock did not open it.
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.onboarding_finish_move_title),
                    stringResource(f.messageRes()),
                    modifier = Modifier.testTag("unlock_move_failure"),
                    announce = LiveRegionMode.Polite,
                    actions = { TextButton(onClick = actions::dismissMoveFailure) { Text(stringResource(R.string.onboarding_continue)) } },
                )
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
                        TextButton(onClick = actions::cancelRecoveryAndUnlock, enabled = state.cancelAllowed) {
                            Text(stringResource(R.string.unlock_recovery_cancel), color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
                if (state.phoneSecret) {
                    // Release R: the owner check follows the cancel at once (the PIN, kept from the unlock, and the password).
                    SecretField(
                        value = state.password,
                        onValueChange = actions::setPassword,
                        label = stringResource(R.string.unlock_password_label),
                        enabled = !state.busy,
                        onImeAction = actions::cancelRecoveryAndUnlock,
                        modifier = Modifier.testTag("unlock_cancel_password"),
                    )
                }
            }
            // Never a PIN field while the last check of the vault's software failed.
            val verified = p != null && state.preflightError == null
            if (!blocked && !state.recoveryPending && verified) {
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
                    imeAction = if (state.checkDue) ImeAction.Next else ImeAction.Done,
                    onImeAction = if (state.checkDue) ({}) else actions::submit,
                    modifier = Modifier.testTag("unlock_pin"),
                )
                if (state.checkDue) {
                    // §3.6.5: a locked vault past its deadline asks for the PIN and the password on one screen.
                    SecretField(
                        value = state.password,
                        onValueChange = actions::setPassword,
                        label = stringResource(R.string.unlock_password_label),
                        enabled = state.pinAllowed,
                        onImeAction = actions::submit,
                        modifier = Modifier.testTag("unlock_password"),
                    )
                }
                when (val m = state.message) {
                    // Release R (§11.14.10): the old phone's "The move didn't finish" could not open the vault.
                    UnlockMessage.MoveBackFailed -> NoticeCard(
                        NoticeKind.URGENT,
                        stringResource(R.string.onboarding_move_didnt_finish),
                        stringResource(R.string.onboarding_move_back_failed),
                        modifier = Modifier.testTag("move_back_failed"),
                    )
                    is UnlockMessage.UpdateRefused -> NoticeCard(
                        NoticeKind.WARNING,
                        stringResource(R.string.unlock_title),
                        stringResource(R.string.unlock_update_refused, m.code),
                    )
                    // An unreadable result is the "not recognised" notice below, with its erase action.
                    is UnlockMessage.Failed -> if (m.code != UnlockViewModel.CODE_UNREADABLE || !state.notRecognised) {
                        NoticeCard(
                            // The service paused for maintenance or not there yet: temporary, not alarming.
                            if (m.kind == FailureKind.SERVICE_PAUSED || m.kind == FailureKind.VAULT_UNAVAILABLE) NoticeKind.WARNING
                                else NoticeKind.URGENT,
                            stringResource(R.string.unlock_title),
                            when (m.code) {
                                UnlockViewModel.CODE_UNREADABLE -> stringResource(R.string.unlock_unreadable)
                                UnlockAttempt.CODE_NO_USER_GUID -> stringResource(R.string.unlock_no_user_guid)
                                else -> stringResource(m.kind.messageRes())
                            },
                            modifier = Modifier.testTag("unlock_failed"),
                        )
                    }
                    else -> Unit
                }
            }
            if (state.signerRefused) SignerRefusedNotice(state.signerHelp, actions::showSignerHelp)
            when {
                state.notRecognised -> NotRecognisedNotice(state.erasing, actions::askErase)
                state.keysGone -> StuckNotice(state.erasing, actions::askErase, onOpenAccountSite)
                state.recoverOnly -> RecoverOnlyNotice(onOpenAccountSite)
            }
        }
    }
    if (state.eraseConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.unlock_erase_confirm_title),
            // A phone without its vault's keys: the root screen's text, which says what a recovery then needs.
            text = stringResource(
                if (state.keysGone && !state.notRecognised) com.vettid.core.ui.R.string.core_ui_erase_phone_body
                else R.string.unlock_erase_confirm_body,
            ),
            confirmLabel = stringResource(R.string.unlock_erase_confirm),
            onConfirm = actions::confirmErase,
            onDismiss = actions::dismissErase,
            modifier = Modifier.testTag("erase_confirm"),
            destructive = true,
        )
    }
}

/**
 * The vault did not recognise this phone at unlock (an unreadable result): the phone may have been replaced
 * while it was offline longer than the relay keeps its `device.unlinked`. Offers "Erase VettID from this
 * phone" (owner decision, 2026-10-05); [erasing] shows the erase running.
 */
@Composable
private fun NotRecognisedNotice(erasing: Boolean, onErase: () -> Unit) {
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.unlock_not_recognised_title),
        stringResource(R.string.unlock_not_recognised_body),
        modifier = Modifier.testTag("unlock_not_recognised"),
        actions = {
            TextButton(onClick = onErase, enabled = !erasing, modifier = Modifier.testTag("erase_phone")) {
                Text(stringResource(R.string.unlock_erase), color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (erasing) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.m))
            Text(stringResource(R.string.unlock_erasing), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * This phone no longer holds the vault's keys ([UnlockUiState.keysGone]): recover-or-erase. "Recover your vault" first
 * (the account site, where a recovery starts); "Erase VettID from this phone" opens the erase confirmation. Neither
 * unlocks or opens anything.
 */
@Composable
private fun StuckNotice(erasing: Boolean, onErase: () -> Unit, onRecover: () -> Unit) {
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.unlock_stuck_title),
        stringResource(R.string.unlock_stuck_body),
        modifier = Modifier.testTag("unlock_stuck"),
        actions = {
            TextButton(onClick = onRecover, modifier = Modifier.testTag("recover_vault")) {
                Text(stringResource(R.string.unlock_recover))
            }
            TextButton(onClick = onErase, enabled = !erasing, modifier = Modifier.testTag("erase_phone")) {
                Text(stringResource(R.string.unlock_erase), color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (erasing) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.m))
            Text(stringResource(R.string.unlock_erasing), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The vault cannot be opened and erasing this phone would not help ([UnlockUiState.recoverOnly]: an ended release, a
 * rollback): "Recover your vault" (the account site) and contacting VettID support. No erase.
 */
@Composable
private fun RecoverOnlyNotice(onRecover: () -> Unit) {
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.unlock_stuck_title),
        stringResource(R.string.unlock_recover_only_body),
        modifier = Modifier.testTag("unlock_recover_only"),
        actions = {
            TextButton(onClick = onRecover, modifier = Modifier.testTag("recover_vault")) {
                Text(stringResource(R.string.unlock_recover))
            }
        },
    )
}

/**
 * The vault's release does not accept this installation of VettID (`signer_refused`, VAULT-MESSAGING 0.27.0
 * §11.7.1; the text is the spec's). Not a wrong PIN: nothing counts and nothing waits. [help] shows how to recover
 * or transfer the vault (both start elsewhere: the account site, or another phone where the vault is open).
 */
@Composable
private fun SignerRefusedNotice(help: Boolean, onHelp: (Boolean) -> Unit) {
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.unlock_signer_refused_title),
        stringResource(R.string.unlock_signer_refused_body),
        modifier = Modifier.testTag("unlock_signer_refused"),
        actions = {
            TextButton(onClick = { onHelp(!help) }, modifier = Modifier.testTag("signer_refused_help")) {
                Text(stringResource(if (help) R.string.unlock_signer_refused_help_hide else R.string.unlock_signer_refused_help))
            }
        },
    )
    if (help) {
        NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.unlock_signer_refused_help_title),
            stringResource(R.string.unlock_signer_refused_help_body),
            modifier = Modifier.testTag("signer_refused_help_text"),
        )
    }
}

/** The app lock's screen (D6): the prompt opens on its own; the button opens it again. Its text follows [method]. */
@Composable
fun AppLockScreen(onUnlock: () -> Unit, method: AppLockMethod = AppLockMethod.BIOMETRICS) {
    FormScaffold(
        title = stringResource(R.string.app_lock_title),
        body = stringResource(
            if (method == AppLockMethod.SCREEN_LOCK) R.string.app_lock_body_screen_lock else R.string.app_lock_body,
        ),
        primaryLabel = stringResource(R.string.app_lock_unlock),
        onPrimary = onUnlock,
        header = {
            Spacer(Modifier.height(Spacing.xxl))
            com.vettid.core.ui.components.RookLogo(height = 72.dp)
            Spacer(Modifier.height(Spacing.l))
        },
    ) {}
}

/**
 * The release check's notices before the PIN (§11.10.6): an older release (the PIN is not sent), a newer
 * one to acknowledge, a deprecated or retired release, and the newest active release to approve with
 * this unlock. Shared by the unlock and the recovery's PIN step.
 */
@Composable
internal fun PreflightNotices(
    p: PreflightInfo,
    updateAcknowledged: Boolean,
    onAcknowledgeUpdate: () -> Unit,
    approveOffer: Boolean,
    onApproveOffer: (Boolean) -> Unit,
    notes: ReleaseNotes?,
) {
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
            actions = if (!updateAcknowledged) {
                { TextButton(onClick = onAcknowledgeUpdate) { Text(stringResource(R.string.unlock_updated_ack)) } }
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
        // What's new first (ANDROID-PLAN 0.1.31), then the fingerprint and the approval.
        WhatsNew(offer.number, notes, offer.notes)
        NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.unlock_offer_title, offer.number.toInt()),
            stringResource(R.string.unlock_offer_body, p.routed.number.toInt(), offer.number.toInt(), offer.fingerprint),
            modifier = Modifier.testTag("release_offer"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .toggleable(value = approveOffer, role = Role.Checkbox, onValueChange = onApproveOffer)
                .testTag("approve_offer"),
        ) {
            Checkbox(checked = approveOffer, onCheckedChange = null)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.unlock_offer_approve), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
