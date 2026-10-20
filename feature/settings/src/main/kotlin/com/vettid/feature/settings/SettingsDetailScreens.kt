package com.vettid.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.vettid.core.data.policy.messageArg
import com.vettid.core.data.policy.messageRes
import com.vettid.core.data.vault.AttestationInfo
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.ReleaseInfoView
import com.vettid.core.data.vault.SignerView
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.titleRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.FullScreenProgress
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsInfoRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.format.Durations
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.RECOVERY_QUIET_S
import com.vettid.core.ui.format.StagingTimeNoteIfShown
import com.vettid.core.ui.format.durationText
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.format.scaledDurationText
import com.vettid.core.ui.format.stagingNoteSuffix
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun formatDate(s: String?): String? = s?.let {
    val f = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    runCatching { f.format(Instant.parse(it)) }.getOrNull() ?: it
}

@Composable
private fun LoadFailure(error: FailureKind, retry: (() -> Unit)?) {
    Column(Modifier.padding(Spacing.gutter)) {
        NoticeCard(NoticeKind.WARNING, stringResource(R.string.settings_title), failureText(error.messageRes()))
        if (retry != null) {
            Spacer(Modifier.height(Spacing.l))
            SecondaryButton(stringResource(R.string.settings_retry), retry)
        }
    }
}

@Composable
private fun yesNo(b: Boolean?): String = when (b) {
    true -> stringResource(R.string.settings_yes)
    false -> stringResource(R.string.settings_no)
    null -> stringResource(R.string.settings_unknown)
}

@Composable
private fun releaseNotice(r: ReleaseInfoView): String? = when (r.notice) {
    "update_available" -> stringResource(R.string.settings_notice_update_available, (r.newestActive ?: 0).toInt())
    "final_warning" -> stringResource(R.string.settings_notice_final_warning, formatDate(r.endsAt) ?: "-")
    "ended" -> stringResource(R.string.settings_notice_ended)
    "rescue" -> stringResource(R.string.settings_notice_rescue)
    "unavailable" -> stringResource(R.string.settings_notice_unavailable)
    else -> null
}

/**
 * What the "Installed from" line shows when tapped (ANDROID-PLAN 0.1.36 §7): what the two versions mean and how
 * to change, with the signing certificate's digest (public; for support, VAULT-MESSAGING §11.7.1).
 */
@Composable
private fun SignerDetails(signer: SignerView) {
    NoticeCard(
        NoticeKind.INFO,
        stringResource(signer.signerClass.installedFromRes()),
        stringResource(R.string.settings_signer_explanation, scaledDurationText(Durations.DAY)) + "\n\n" +
            stringResource(R.string.settings_signer_digest, signer.digest.chunked(DIGEST_GROUP).joinToString(" ")),
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s).testTag("signer_details"),
    )
}

private const val DIGEST_GROUP = 8

/** Vault status (stateless). */
@Suppress("CyclomaticComplexMethod")
@Composable
fun VaultStatusContent(
    state: LoadState<VaultOverview>,
    retry: () -> Unit,
    onBack: () -> Unit,
    signerDetails: Boolean = false,
    onChangePin: () -> Unit = {},
    onChangePassword: () -> Unit = {},
) {
    DetailScaffold(onBackClick = onBack, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.settings_status_title))
            val v = state.value
            var signerOpen by rememberSaveable { mutableStateOf(signerDetails) }
            when {
                state.loading -> FullScreenProgress(stringResource(R.string.settings_loading))
                v == null -> LoadFailure(state.error ?: FailureKind.OTHER, retry)
                else -> {
                    v.release?.let { r ->
                        releaseNotice(r)?.let {
                            NoticeCard(
                                if (r.notice == "update_available") NoticeKind.INFO else NoticeKind.URGENT,
                                stringResource(R.string.settings_notice_title), it,
                                modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("release_notice"),
                            )
                        }
                    }
                    if (v.recoveryOffered) v.recovery?.let { RecoveryNotices(it, onChangePin, onChangePassword) }
                    SettingsGroup {
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_state),
                            when (v.state) {
                                "unlocked" -> stringResource(R.string.settings_status_state_unlocked)
                                "locked" -> stringResource(R.string.settings_status_state_locked)
                                "enrolling" -> stringResource(R.string.settings_status_state_enrolling)
                                else -> stringResource(R.string.settings_unknown)
                            },
                            icon = Icons.Outlined.Lock,
                        )
                        // ANDROID-PLAN §7: under the vault's status, from the app's own device.list entry; no line when
                        // the vault reports no signer.
                        v.signer?.let { sg ->
                            SettingsDivider()
                            val shown = stringResource(
                                if (signerOpen) R.string.settings_signer_details_shown else R.string.settings_signer_details_hidden,
                            )
                            SettingsRow(
                                label = stringResource(sg.signerClass.installedFromRes()),
                                onClick = { signerOpen = !signerOpen },
                                icon = Icons.Outlined.VerifiedUser,
                                showChevron = false,
                                // TalkBack: the line says whether its details are shown, and offers expand / collapse.
                                modifier = Modifier.testTag("signer_line").semantics {
                                    stateDescription = shown
                                    if (signerOpen) {
                                        collapse {
                                            signerOpen = false
                                            true
                                        }
                                    } else {
                                        expand {
                                            signerOpen = true
                                            true
                                        }
                                    }
                                },
                            )
                            // Directly under the line that was tapped (owner report 2026-10-11: below all the other
                            // rows, at the bottom of the screen, nothing visibly happened on a phone).
                            if (signerOpen) SignerDetails(sg)
                        }
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_vault_id),
                            v.vaultId?.chunked(8)?.joinToString(" ") ?: "-",
                            icon = Icons.Outlined.Tag,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_release),
                            v.release?.let { r -> r.number?.let {
                                stringResource(R.string.settings_status_release_value, it.toInt(), r.status) } }
                                ?: stringResource(R.string.settings_unknown),
                            icon = Icons.Outlined.Update,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_release_last),
                            if (v.lastRelease > 0) stringResource(R.string.settings_status_release_last_value, v.lastRelease.toInt())
                                else "-",
                            icon = Icons.Outlined.VerifiedUser,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_recovery),
                            if (!v.recoveryOffered) {
                                stringResource(R.string.settings_recovery_not_offered)
                            } else {
                                v.recovery?.let { r -> statusLine(recoveryStatusText(r)) }
                                    ?: v.recoveryState ?: stringResource(R.string.settings_status_recovery_none)
                            },
                            icon = Icons.Outlined.Restore,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_provisional),
                            yesNo(v.provisional?.not()),
                            icon = Icons.Outlined.Storage,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_devices),
                            v.devices?.toString() ?: "-",
                            icon = Icons.Outlined.Devices,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_status_connections),
                            v.connections?.toString() ?: "-",
                            icon = Icons.Outlined.People,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_status_advisory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.gutter + Spacing.xs, vertical = Spacing.l),
                    )
                }
            }
        }
    }
}

/**
 * Release R (ANDROID-PLAN "Owner check and status"): the recovery counter unavailable at every read for a day, with the
 * urgency of a used attempt; and used attempts ("if that was not you"), with the password and PIN changes. The PIN change
 * opened from here warns first that the PIN alone gives whoever is guessing ten new tries at the same password.
 */
@Composable
private fun RecoveryNotices(r: com.vettid.core.data.vault.RecoveryStatusView, onChangePin: () -> Unit, onChangePassword: () -> Unit) {
    val title = stringResource(R.string.settings_status_recovery)
    val pad = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
    if (r.unknownForADay) {
        // Real time (production, and every vault at scale 1): the approved words. STAGING-TIME-SCALE S8, S7 otherwise:
        // the clock's period as it started, and the quiet period as the vault runs it, with the staging note.
        val realTime = LocalTimeScale.current.scale <= 1 && r.unknownForSeconds == Durations.DAY
        val text = if (realTime) {
            stringResource(R.string.settings_recovery_unknown_day)
        } else {
            stringResource(
                R.string.settings_recovery_unknown_day_scaled,
                durationText(r.unknownForSeconds),
                scaledDurationText(RECOVERY_QUIET_S),
            ) + stagingNoteSuffix()
        }
        NoticeCard(NoticeKind.URGENT, title, text, pad.testTag("recovery_unknown_day"))
    }
    val used = r.used
    if (r.state == "armed" && used != null && used in 1 until RECOVERY_ATTEMPTS) {
        NoticeCard(
            NoticeKind.URGENT,
            title,
            stringResource(R.string.settings_recovery_status_used, used),
            pad.testTag("recovery_attempts_used"),
            actions = {
                androidx.compose.material3.TextButton(onClick = onChangePassword, modifier = Modifier.testTag("attempts_change_password")) {
                    Text(stringResource(R.string.settings_recovery_change_password))
                }
                androidx.compose.material3.TextButton(onClick = onChangePin, modifier = Modifier.testTag("attempts_change_pin")) {
                    Text(stringResource(R.string.settings_pin_submit))
                }
            },
        )
    }
}

private const val RECOVERY_ATTEMPTS = 10

/** A [StatusText] in words. */
@Composable
private fun statusLine(t: StatusText): String = t.arg?.let { stringResource(t.res, it) } ?: stringResource(t.res)

/** The vault PIN change (stateless). */
@Suppress("CyclomaticComplexMethod")
@Composable
fun ChangePinContent(
    state: ChangePinUiState,
    onCurrent: (String) -> Unit,
    onPin: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onPassword: (String) -> Unit = {},
    onLockAndUnlock: () -> Unit = {},
) {
    FormScaffold(
        title = stringResource(if (state.done) R.string.settings_pin_done else R.string.settings_pin_title),
        body = when {
            state.done && state.withPassword -> stringResource(R.string.settings_pin_done_recovery)
            state.done -> null
            else -> stringResource(R.string.settings_pin_body)
        },
        primaryLabel = stringResource(if (state.done) R.string.settings_done else R.string.settings_pin_submit),
        onPrimary = if (state.done) onBack else onSubmit,
        primaryEnabled = state.done ||
            (!state.checking && state.current.isNotEmpty() && state.pin.isNotEmpty() && state.confirm.isNotEmpty()),
        busy = state.busy,
        onBack = onBack,
    ) {
        if (state.done) return@FormScaffold
        // VAULT-MESSAGING 0.44.0 §11.14.9: a change whose answer was lost is decided, not repeated.
        state.check?.let { c ->
            val text = when (c) {
                is ChangeCheck.Checking -> R.string.settings_pin_checking
                is ChangeCheck.Unreachable -> R.string.settings_pin_unreachable
                is ChangeCheck.NotApplied -> R.string.settings_pin_not_changed
                is ChangeCheck.Ended -> R.string.settings_pin_ended
                // (ANDROID-PLAN 0.1.55) "Your vault moved to a new phone", no "Try again".
                is ChangeCheck.Moved -> com.vettid.core.data.R.string.data_feed_device_replaced
                else -> null
            }
            if (text != null) {
                NoticeCard(
                    if (c is ChangeCheck.NotApplied || c is ChangeCheck.Ended || c is ChangeCheck.Moved) {
                        NoticeKind.WARNING
                    } else {
                        NoticeKind.INFO
                    },
                    stringResource(R.string.settings_pin_title),
                    stringResource(text),
                    Modifier.testTag("pin_change_check"),
                    announce = LiveRegionMode.Polite,
                    // (0.45.0 step 3) No "Try again": the lock and two-step unlock settles the PIN without an attempt.
                    actions = if (c is ChangeCheck.Ended) {
                        { TextButton(onClick = onLockAndUnlock) { Text(stringResource(R.string.settings_pin_lock_unlock)) } }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(Spacing.l))
            }
        }
        if (state.fromAttempts && state.withPassword) {
            NoticeCard(
                NoticeKind.WARNING,
                stringResource(R.string.settings_pin_title),
                stringResource(R.string.settings_pin_password_too),
                Modifier.testTag("pin_password_too"),
            )
            Spacer(Modifier.height(Spacing.l))
        }
        SecretField(state.current, onCurrent, stringResource(R.string.settings_pin_current), isPin = true, imeAction = ImeAction.Next)
        Spacer(Modifier.height(Spacing.l))
        val problem = state.problem
        SecretField(
            state.pin, onPin, stringResource(R.string.settings_pin_new), isPin = true, imeAction = ImeAction.Next,
            error = when {
                problem != null -> problem.messageArg()?.let { stringResource(problem.messageRes(), it) }
                    ?: stringResource(problem.messageRes())
                state.same -> stringResource(R.string.settings_pin_same)
                else -> null
            },
        )
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.confirm, onConfirm, stringResource(R.string.settings_pin_confirm), isPin = true,
            imeAction = if (state.withPassword) ImeAction.Next else ImeAction.Done,
            onImeAction = if (state.withPassword) ({}) else onSubmit,
            error = if (state.mismatch) stringResource(R.string.settings_pin_mismatch) else null,
        )
        if (state.withPassword) {
            // Release R (§11.14.9): the credential password goes with the change.
            Spacer(Modifier.height(Spacing.l))
            SecretField(state.password, onPassword, stringResource(R.string.settings_pin_password), onImeAction = onSubmit)
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.l))
            NoticeCard(
                NoticeKind.URGENT,
                stringResource(it.titleRes(R.string.settings_pin_title)),
                failureText(it.messageRes(), it.vaultCode(state.errorCode), com.vettid.core.data.R.string.error_vault_code),
                Modifier.testTag("error"),
                // (0.45.0 §11.14.9) An uncertain PIN or an unconfirmed new phone secret: settled by a lock and unlock.
                actions = if (it == FailureKind.RENEW_UNCONFIRMED || it == FailureKind.PIN_UNCERTAIN) {
                    { TextButton(onClick = onLockAndUnlock) { Text(stringResource(R.string.settings_pin_lock_unlock)) } }
                } else {
                    null
                },
            )
        }
    }
}

/** Recovery info (stateless). */
@Suppress("CyclomaticComplexMethod")
@Composable
fun RecoveryContent(
    state: RecoveryUiState,
    onOpenAccountSite: () -> Unit,
    onBack: () -> Unit,
    onTransfer: () -> Unit = {},
) {
    val r = state.recovery
    val active = r != null && r.state in setOf("pending", "available", "registered")
    if (!state.offered) {
        // (0.34.0, §11.10.9) A release with recovery_r and without recovery_offered: no recovery to start.
        FormScaffold(
            title = stringResource(R.string.settings_recovery_title),
            body = stringResource(R.string.settings_recovery_not_offered),
            primaryLabel = stringResource(R.string.settings_recovery_transfer),
            onPrimary = onTransfer,
            onBack = onBack,
            modifier = Modifier.testTag("recovery_not_offered"),
        ) {}
        return
    }
    FormScaffold(
        title = stringResource(R.string.settings_recovery_title),
        body = stringResource(R.string.settings_recovery_body, scaledDurationText(Durations.DAY)),
        primaryLabel = stringResource(R.string.settings_recovery_open),
        onPrimary = onOpenAccountSite,
        busy = state.busy || state.loading,
        onBack = onBack,
        secondaryLabel = if (active) null else stringResource(R.string.settings_recovery_transfer),
        onSecondary = onTransfer,
    ) {
        StagingTimeNoteIfShown()
        if (state.backupOff) {
            NoticeCard(
                NoticeKind.WARNING,
                stringResource(R.string.settings_recovery_title),
                stringResource(R.string.settings_recovery_backup_off),
            )
            Spacer(Modifier.height(Spacing.m))
        }
        when {
            active && r != null -> NoticeCard(
                NoticeKind.URGENT,
                stringResource(R.string.settings_recovery_active_title),
                stringResource(R.string.settings_recovery_active_body, r.state, formatDate(r.availableAt) ?: "-"),
                Modifier.testTag("recovery_active"),
            )
            !state.loading && state.error == null -> NoticeCard(
                NoticeKind.INFO,
                stringResource(R.string.settings_recovery_title),
                stringResource(R.string.settings_recovery_none),
            )
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.m))
            NoticeCard(NoticeKind.WARNING, stringResource(R.string.settings_recovery_title), failureText(it.messageRes()))
        }
    }
}

/** Attestation details (stateless); [canary]: the installed canary manifest, shown only when there is one. */
@Composable
fun AttestationContent(
    state: LoadState<AttestationInfo>,
    onBack: () -> Unit,
    canary: CanaryManifestView? = null,
    onRemoveCanary: () -> Unit = {},
) {
    DetailScaffold(onBackClick = onBack, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.settings_attestation_title))
            Text(
                stringResource(R.string.settings_attestation_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter + Spacing.xs),
            )
            val a = state.value
            when {
                state.loading -> FullScreenProgress(stringResource(R.string.settings_loading))
                a == null -> LoadFailure(state.error ?: FailureKind.OTHER, null)
                else -> {
                    SettingsSectionHeader(stringResource(R.string.settings_attestation_device))
                    SettingsGroup {
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_key),
                            if (a.keyPresent) a.keyLevel ?: stringResource(R.string.settings_unknown)
                                else stringResource(R.string.settings_attestation_key_none),
                            icon = Icons.Outlined.Key,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_strongbox),
                            yesNo(a.strongBoxAvailable),
                            icon = Icons.Outlined.Fingerprint,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_version),
                            a.attestationVersion?.toString() ?: "-",
                            icon = Icons.Outlined.Tag,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_boot),
                            a.verifiedBoot ?: "-",
                            icon = Icons.Outlined.VerifiedUser,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_locked),
                            yesNo(a.deviceLocked),
                            icon = Icons.Outlined.Lock,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_boot_key),
                            a.bootKeyFingerprint ?: "-",
                            icon = Icons.Outlined.Key,
                        )
                    }
                    SettingsSectionHeader(stringResource(R.string.settings_attestation_enclave))
                    SettingsGroup {
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_release),
                            if (a.lastRelease > 0) {
                                val fp = a.lastReleaseFingerprint ?: "-"
                                stringResource(R.string.settings_attestation_release_value, a.lastRelease.toInt(), fp)
                            } else {
                                "-"
                            },
                            icon = Icons.Outlined.Update,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.settings_attestation_environment),
                            a.environment,
                            icon = Icons.Outlined.Storage,
                        )
                    }
                    if (canary != null) CanaryManifestSection(canary, onRemoveCanary)
                    Spacer(Modifier.height(Spacing.xxl))
                }
            }
        }
    }
}

/**
 * The canary manifest installed on this phone (VAULT-RELEASES §10.1 step 9): its serial and key, the releases
 * it lists, and "Stop using it" (after a confirmation; the published manifest is used again).
 */
@Composable
private fun CanaryManifestSection(canary: CanaryManifestView, onRemove: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    SettingsSectionHeader(stringResource(R.string.settings_canary_title))
    SettingsGroup {
        SettingsInfoRow(
            stringResource(R.string.settings_canary_serial),
            stringResource(R.string.settings_canary_serial_value, canary.serial, canary.keyId),
            icon = Icons.Outlined.Tag,
        )
        SettingsDivider()
        SettingsInfoRow(
            stringResource(R.string.settings_canary_releases),
            canary.releases.map { stringResource(R.string.settings_canary_release, it.number.toInt(), it.status) }.joinToString(", "),
            icon = Icons.Outlined.Update,
        )
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.settings_canary_remove),
            onClick = { confirm = true },
            icon = Icons.Outlined.Storage,
            showChevron = false,
            modifier = Modifier.testTag("canary_remove"),
        )
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_canary_remove_title),
            text = stringResource(R.string.settings_canary_remove_body),
            confirmLabel = stringResource(R.string.settings_canary_remove),
            destructive = true,
            onConfirm = { confirm = false; onRemove() },
            onDismiss = { confirm = false },
        )
    }
}

/** What the deletion screen can ask for. */
data class DeleteVaultActions(
    val setPhrase: (String) -> Unit = {},
    val setPin: (String) -> Unit = {},
    val setPassword: (String) -> Unit = {},
    val setAcknowledged: (Boolean) -> Unit = {},
    val submit: () -> Unit = {},
    val cancelConfirm: () -> Unit = {},
    val confirm: () -> Unit = {},
    val back: () -> Unit = {},
)

/** The vault deletion (§12.5), stateless: phrase, PIN, password, acknowledgement, then a final confirmation. */
@Composable
fun DeleteVaultContent(state: DeleteVaultUiState, actions: DeleteVaultActions) {
    FormScaffold(
        title = stringResource(R.string.settings_delete_title),
        body = stringResource(R.string.settings_delete_body),
        primaryLabel = stringResource(R.string.settings_delete_submit),
        onPrimary = actions.submit,
        primaryEnabled = state.ready,
        destructive = true,
        busy = state.busy,
        onBack = actions.back,
    ) {
        OutlinedTextField(
            value = state.phrase,
            onValueChange = actions.setPhrase,
            label = { Text(stringResource(R.string.settings_delete_phrase_label)) },
            singleLine = true,
            isError = state.phraseWrong,
            supportingText = if (state.phraseWrong) ({ Text(stringResource(R.string.settings_delete_phrase_wrong)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("delete_phrase"),
        )
        Spacer(Modifier.height(Spacing.l))
        SecretField(state.pin, actions.setPin, stringResource(R.string.settings_delete_pin), isPin = true, imeAction = ImeAction.Next)
        Spacer(Modifier.height(Spacing.l))
        SecretField(state.password, actions.setPassword, stringResource(R.string.settings_delete_password))
        Spacer(Modifier.height(Spacing.m))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .toggleable(value = state.acknowledged, role = Role.Checkbox, onValueChange = actions.setAcknowledged)
                .testTag("delete_ack"),
        ) {
            Checkbox(checked = state.acknowledged, onCheckedChange = null)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.settings_delete_ack), style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.l))
            NoticeCard(
                NoticeKind.URGENT,
                stringResource(it.titleRes(R.string.settings_delete_title)),
                failureText(it.messageRes(), it.vaultCode(state.errorCode), com.vettid.core.data.R.string.error_vault_code),
                Modifier.testTag("error"),
            )
        }
    }
    if (state.confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_confirm_title),
            text = stringResource(R.string.settings_delete_confirm_body),
            confirmLabel = stringResource(R.string.settings_delete_confirm),
            destructive = true,
            onConfirm = actions.confirm,
            onDismiss = actions.cancelConfirm,
        )
    }
}
