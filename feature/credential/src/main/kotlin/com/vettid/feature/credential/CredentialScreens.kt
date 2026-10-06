package com.vettid.feature.credential

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.policy.labelRes
import com.vettid.core.data.policy.messageRes
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.FullScreenProgress
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsInfoRow
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.SettingsSwitchRow
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.StrengthMeter
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Type-safe navigation route of the Credential screen. */
@Serializable
data object CredentialRoute

@Serializable
data object CredentialPasswordRoute

@Serializable
data object CredentialRotateRoute

@Serializable
data object CredentialAlarmRoute

@Serializable
data object CredentialNewRoute

/** Registers the Credential destinations. [navigate] opens a route; [onBack] pops one. */
fun NavGraphBuilder.credentialDestination(
    chrome: ShellChrome,
    navigate: (Any) -> Unit,
    onBack: () -> Unit,
    /** Opens the owner check (§3.6): turning the backup on asks for the password at once so that the copy exists (§3.5.6). */
    onOwnerCheck: () -> Unit = {},
) {
    composable<CredentialRoute> {
        val vm: CredentialViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        CredentialContent(
            state = state,
            chrome = chrome,
            actions = CredentialActions(
                retry = vm::refresh,
                showWindowDialog = vm::showWindowDialog,
                setWindowPassword = vm::setWindowPassword,
                openWindow = vm::openWindow,
                closeWindow = vm::closeWindow,
                setTtl = vm::setTtl,
                setBackup = vm::setBackup,
                dismissNotice = vm::dismissNotice,
                changePassword = { navigate(CredentialPasswordRoute) },
                rotate = { navigate(CredentialRotateRoute) },
                reviewAlarm = { navigate(CredentialAlarmRoute) },
                newCredential = { navigate(CredentialNewRoute) },
            ),
        )
        LaunchedEffect(state.notice) { if (state.notice == CredentialNotice.BACKUP_ON) onOwnerCheck() }
    }
    composable<CredentialPasswordRoute> {
        val vm: ChangePasswordViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ChangePasswordContent(state, vm::setCurrent, vm::setPassword, vm::setConfirm, vm::submit, onBack)
    }
    composable<CredentialRotateRoute> {
        val vm: RotateViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        RotateContent(state, vm::setPassword, vm::submit, onBack)
    }
    composable<CredentialNewRoute> {
        val vm: NewCredentialViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        NewCredentialContent(
            state,
            NewCredentialActions(
                vm::setPin, vm::setCurrent, vm::setPassword, vm::setConfirm, vm::setAcknowledged,
                vm::submit, vm::cancelConfirm, vm::confirm, onBack,
            ),
        )
    }
    composable<CredentialAlarmRoute> {
        val vm: AlarmViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        AlarmContent(state, AlarmActions(vm::answer, vm::cancelAnswer, vm::confirm, vm::setPassword, vm::rotate, onBack))
    }
}

/** What the Credential screen can ask for. */
data class CredentialActions(
    val retry: () -> Unit = {},
    val showWindowDialog: (Boolean) -> Unit = {},
    val setWindowPassword: (String) -> Unit = {},
    val openWindow: () -> Unit = {},
    val closeWindow: () -> Unit = {},
    val setTtl: (Int) -> Unit = {},
    val setBackup: (Boolean) -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val changePassword: () -> Unit = {},
    val rotate: () -> Unit = {},
    val reviewAlarm: () -> Unit = {},
    /** A new credential (`credential.reset`, 0.15.2): the old one and every critical item are destroyed. */
    val newCredential: () -> Unit = {},
)

private val TTL_OPTIONS = listOf(30, 60, 300, 900, 3600)

@Composable
private fun ttlLabel(seconds: Int): String = when (seconds) {
    30 -> stringResource(R.string.credential_ttl_30s)
    60 -> stringResource(R.string.credential_ttl_1m)
    300 -> stringResource(R.string.credential_ttl_5m)
    900 -> stringResource(R.string.credential_ttl_15m)
    3600 -> stringResource(R.string.credential_ttl_1h)
    else -> stringResource(R.string.credential_ttl_seconds, seconds)
}

private fun formatTime(i: Instant): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(i)

private fun formatDate(s: String?): String? = s?.let {
    val f = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    runCatching { f.format(Instant.parse(it)) }.getOrNull()
}

/** The Credential screen for [state] (stateless). */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun CredentialContent(state: CredentialUiState, chrome: ShellChrome, actions: CredentialActions) {
    var ttlPicker by rememberSaveable { mutableStateOf(false) }
    var backupOffDialog by rememberSaveable { mutableStateOf(false) }
    TopLevelScaffold(title = stringResource(R.string.credential_title), chrome = chrome) {
        val st = state.status
        when {
            state.loading -> FullScreenProgress(stringResource(R.string.credential_loading))
            st == null && state.error != null -> Column(
                Modifier.fillMaxSize().padding(Spacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                NoticeCard(NoticeKind.WARNING, stringResource(R.string.credential_title), stringResource(state.error.messageRes()))
                Spacer(Modifier.height(Spacing.l))
                SecondaryButton(stringResource(R.string.credential_retry), actions.retry)
            }
            st == null || !st.exists -> EmptyState(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.credential_empty_title),
                body = stringResource(R.string.credential_empty_body),
            )
            else -> Box(Modifier.fillMaxSize().background(VettIdTheme.colors.groupedBackground)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.alarm?.let { a ->
                        Spacer(Modifier.height(Spacing.s))
                        NoticeCard(
                            NoticeKind.URGENT,
                            stringResource(R.string.credential_alarm_card_title),
                            stringResource(
                                if (a.rotationRequired) R.string.credential_alarm_card_rotate else R.string.credential_alarm_card_frozen,
                            ),
                            modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("credential_alarm"),
                            actions = { TextButton(onClick = actions.reviewAlarm) {
                                Text(stringResource(R.string.credential_alarm_review)) } },
                        )
                    }
                    if (state.error != null || state.notice != null) {
                        Spacer(Modifier.height(Spacing.s))
                        val (kind, text) = when {
                            state.error != null -> NoticeKind.URGENT to stringResource(state.error.messageRes())
                            state.notice == CredentialNotice.BACKUP_ON ->
                                NoticeKind.SUCCESS to stringResource(R.string.credential_backup_on_done)
                            state.notice == CredentialNotice.BACKUP_OFF ->
                                NoticeKind.SUCCESS to stringResource(R.string.credential_backup_off_done)
                            else -> NoticeKind.SUCCESS to ""
                        }
                        NoticeCard(
                            kind, stringResource(R.string.credential_title), text,
                            modifier = Modifier.padding(horizontal = Spacing.gutter),
                            actions = { TextButton(onClick = actions.dismissNotice) { Text(stringResource(R.string.credential_done)) } },
                        )
                    }
                    if (state.status?.backup == false) {
                        // §3.5.6: the warning repeated while the backup is off.
                        Spacer(Modifier.height(Spacing.s))
                        NoticeCard(
                            NoticeKind.WARNING,
                            stringResource(R.string.credential_backup),
                            stringResource(R.string.credential_backup_off_notice),
                            modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("backup_off_notice"),
                        )
                    }
                    SettingsSectionHeader(stringResource(R.string.credential_section_status))
                    SettingsGroup {
                        SettingsInfoRow(
                            stringResource(R.string.credential_version),
                            st.version?.toString() ?: stringResource(R.string.credential_unknown),
                            icon = Icons.Outlined.Shield,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.credential_key),
                            st.keyFingerprint ?: stringResource(R.string.credential_unknown),
                            icon = Icons.Outlined.Key,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.credential_updated),
                            formatDate(st.updatedAt) ?: stringResource(R.string.credential_unknown),
                            icon = Icons.Outlined.Sync,
                        )
                        SettingsDivider()
                        SettingsInfoRow(
                            stringResource(R.string.credential_critical_items),
                            st.criticalItems?.toString() ?: stringResource(R.string.credential_unknown),
                            icon = Icons.Outlined.Password,
                        )
                    }
                    SettingsSectionHeader(stringResource(R.string.credential_section_window))
                    SettingsGroup {
                        val until = state.windowUntil?.takeIf { it.isAfter(Instant.now()) }
                        SettingsInfoRow(
                            stringResource(R.string.credential_window_state),
                            until?.let { stringResource(R.string.credential_window_open_until, formatTime(it)) }
                                ?: stringResource(R.string.credential_window_closed),
                            icon = if (until != null) Icons.Outlined.LockOpen else Icons.Outlined.LockClock,
                        )
                        SettingsDivider()
                        if (until == null) {
                            SettingsRow(
                                stringResource(R.string.credential_window_open), { actions.showWindowDialog(true) },
                                icon = Icons.Outlined.LockOpen, supporting = stringResource(R.string.credential_window_open_body),
                                showChevron = false, modifier = Modifier.testTag("open_window"),
                            )
                        } else {
                            SettingsRow(
                                stringResource(R.string.credential_window_close),
                                actions.closeWindow,
                                icon = Icons.Outlined.LockClock,
                                showChevron = false,
                            )
                        }
                        SettingsDivider()
                        SettingsRow(
                            stringResource(R.string.credential_window_length), { ttlPicker = true },
                            icon = Icons.Outlined.Timer, supporting = ttlLabel(st.unlockTtlSeconds),
                        )
                    }
                    SettingsSectionHeader(stringResource(R.string.credential_section_protect))
                    SettingsGroup {
                        SettingsRow(
                            stringResource(R.string.credential_change_password),
                            actions.changePassword,
                            icon = Icons.Outlined.Password,
                        )
                        SettingsDivider()
                        SettingsRow(
                            stringResource(R.string.credential_rotate), actions.rotate, icon = Icons.Outlined.Sync,
                            supporting = stringResource(R.string.credential_rotate_supporting),
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            label = stringResource(R.string.credential_backup),
                            checked = st.backup,
                            onCheckedChange = { on -> if (on) actions.setBackup(true) else backupOffDialog = true },
                            icon = Icons.Outlined.Backup,
                            supporting = stringResource(
                                if (st.backup) R.string.credential_backup_supporting_on else R.string.credential_backup_supporting_off,
                            ),
                            modifier = Modifier.testTag("backup_switch"),
                        )
                        SettingsDivider()
                        SettingsRow(
                            stringResource(R.string.credential_new_title),
                            actions.newCredential,
                            icon = Icons.Outlined.RestartAlt,
                            iconTint = MaterialTheme.colorScheme.error,
                            supporting = stringResource(R.string.credential_new_supporting),
                            modifier = Modifier.testTag("new_credential_row"),
                        )
                    }
                    Spacer(Modifier.height(Spacing.xxl))
                }
            }
        }
    }
    if (state.windowDialog) {
        PasswordDialog(
            title = stringResource(R.string.credential_window_dialog_title),
            body = stringResource(R.string.credential_window_dialog_body),
            password = state.windowPassword,
            onPassword = actions.setWindowPassword,
            confirmLabel = stringResource(R.string.credential_open),
            busy = state.busy,
            error = state.windowError,
            onConfirm = actions.openWindow,
            onDismiss = { actions.showWindowDialog(false) },
        )
    }
    if (ttlPicker) {
        TtlDialog(state.status?.unlockTtlSeconds ?: 300, { actions.setTtl(it); ttlPicker = false }, { ttlPicker = false })
    }
    if (backupOffDialog) {
        BackupOffDialog(onConfirm = { actions.setBackup(false); backupOffDialog = false }, onDismiss = { backupOffDialog = false })
    }
}

/** A credential password prompt (an operation that needs the password once). */
@Composable
fun PasswordDialog(
    title: String,
    body: String,
    password: String,
    onPassword: (String) -> Unit,
    confirmLabel: String,
    busy: Boolean,
    error: FailureKind?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.m))
                SecretField(
                    value = password,
                    onValueChange = onPassword,
                    label = stringResource(R.string.credential_password_label),
                    error = error?.let { stringResource(it.messageRes()) },
                    onImeAction = onConfirm,
                    enabled = !busy,
                    modifier = Modifier.testTag("dialog_password"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && password.isNotEmpty()) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.credential_cancel), color =
                MaterialTheme.colorScheme.onSurface) }
        },
    )
}

@Composable
private fun TtlDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text(stringResource(R.string.credential_ttl_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.selectableGroup()) {
                TTL_OPTIONS.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .selectable(selected = s == current, role = Role.RadioButton, onClick = { onPick(s) }),
                    ) {
                        RadioButton(selected = s == current, onClick = null)
                        Spacer(Modifier.width(Spacing.m))
                        Text(ttlLabel(s))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.credential_cancel)) } },
    )
}

/** §3.5.6: warn clearly before the backup goes off, and ask the member to confirm. */
@Composable
fun BackupOffDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var ack by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text(stringResource(R.string.credential_backup_off_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                // §3.5.6 (0.16.0): the warning as ANDROID-PLAN 0.1.7 gives it, with its emphasis.
                Text(
                    AnnotatedString.fromHtml(stringResource(R.string.credential_backup_off_body)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.m))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget)
                        .toggleable(value = ack, role = Role.Checkbox, onValueChange = { ack = it }),
                ) {
                    Checkbox(checked = ack, onCheckedChange = null)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.credential_backup_off_ack), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = ack) {
                Text(
                    stringResource(R.string.credential_backup_off_confirm),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.credential_cancel), color =
            MaterialTheme.colorScheme.onSurface) } },
    )
}

@Composable
private fun ErrorCard(error: FailureKind?) {
    if (error == null) return
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(NoticeKind.URGENT, stringResource(R.string.credential_title), stringResource(error.messageRes()), Modifier.testTag("error"))
}

/** The password change (stateless). */
@Composable
fun ChangePasswordContent(
    state: ChangePasswordUiState,
    onCurrent: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(
        title = stringResource(if (state.done) R.string.credential_change_done else R.string.credential_change_title),
        body = if (state.done) null else stringResource(R.string.credential_change_body),
        primaryLabel = stringResource(if (state.done) R.string.credential_done else R.string.credential_change_submit),
        onPrimary = if (state.done) onBack else onSubmit,
        primaryEnabled = state.done || (state.current.isNotEmpty() && state.password.isNotEmpty() && state.confirm.isNotEmpty()),
        busy = state.busy,
        onBack = onBack,
    ) {
        if (state.done) return@FormScaffold
        SecretField(
            state.current,
            onCurrent,
            stringResource(R.string.credential_password_current),
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
        )
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.password, onPassword, stringResource(R.string.credential_password_new),
            error = state.problem?.let { stringResource(it.messageRes()) }
                ?: if (state.same) stringResource(R.string.credential_password_same) else null,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
        )
        Spacer(Modifier.height(Spacing.s))
        StrengthMeter(level = state.strength.ordinal, label = stringResource(state.strength.labelRes()))
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.confirm, onConfirm, stringResource(R.string.credential_password_confirm),
            error = if (state.mismatch) stringResource(R.string.credential_password_mismatch) else null,
            onImeAction = onSubmit,
        )
        ErrorCard(state.error)
    }
}

/** The credential rotation (stateless). */
@Composable
fun RotateContent(state: RotateUiState, onPassword: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    FormScaffold(
        title = stringResource(if (state.done) R.string.credential_rotate_done else R.string.credential_rotate_title),
        body = if (state.done) null else stringResource(R.string.credential_rotate_body),
        primaryLabel = stringResource(if (state.done) R.string.credential_done else R.string.credential_rotate_submit),
        onPrimary = if (state.done) onBack else onSubmit,
        primaryEnabled = state.done || state.password.isNotEmpty(),
        busy = state.busy,
        onBack = onBack,
    ) {
        if (state.done) return@FormScaffold
        SecretField(
            state.password,
            onPassword,
            stringResource(R.string.credential_password_label),
            onImeAction = onSubmit,
            modifier = Modifier.testTag("password"),
        )
        ErrorCard(state.error)
    }
}

/** What the alarm screen can ask for. */
data class AlarmActions(
    val answer: (Boolean) -> Unit = {},
    val cancelAnswer: () -> Unit = {},
    val confirm: () -> Unit = {},
    val setPassword: (String) -> Unit = {},
    val rotate: () -> Unit = {},
    val back: () -> Unit = {},
)

/** The clone alarm (§3.5.9), stateless. */
@Suppress("LongMethod")
@Composable
fun AlarmContent(state: AlarmUiState, actions: AlarmActions) {
    val a = state.alarm
    when (state.step) {
        AlarmStep.ASK, AlarmStep.CONFIRM_MINE, AlarmStep.CONFIRM_NOT_MINE -> {
            val presenter = stringResource(
                when (a?.presenter) {
                    "holder" -> R.string.credential_alarm_presenter_holder
                    "other" -> R.string.credential_alarm_presenter_other
                    else -> R.string.credential_alarm_presenter_unknown
                },
            )
            FormScaffold(
                title = stringResource(R.string.credential_alarm_title),
                body = stringResource(R.string.credential_alarm_body, formatDate(a?.at) ?: a?.at ?: "-", presenter),
                primaryLabel = stringResource(R.string.credential_alarm_not_mine),
                onPrimary = { actions.answer(false) },
                destructive = true,
                busy = state.busy,
                secondaryLabel = stringResource(R.string.credential_alarm_mine),
                onSecondary = { actions.answer(true) },
                onBack = actions.back,
            ) {
                NoticeCard(
                    NoticeKind.INFO,
                    stringResource(R.string.credential_alarm_mine),
                    stringResource(R.string.credential_alarm_mine_body),
                )
                ErrorCard(state.error)
            }
            if (state.step != AlarmStep.ASK) {
                com.vettid.core.ui.components.ConfirmDialog(
                    title = stringResource(
                        if (state.step == AlarmStep.CONFIRM_MINE) R.string.credential_alarm_confirm_mine_title
                            else R.string.credential_alarm_confirm_not_mine_title,
                    ),
                    text = stringResource(R.string.credential_alarm_confirm_body),
                    confirmLabel = stringResource(R.string.credential_alarm_confirm),
                    onConfirm = actions.confirm,
                    onDismiss = actions.cancelAnswer,
                )
            }
        }
        AlarmStep.ROTATE -> FormScaffold(
            title = stringResource(R.string.credential_alarm_rotate_title),
            body = stringResource(R.string.credential_alarm_rotate_body),
            primaryLabel = stringResource(R.string.credential_rotate_submit),
            onPrimary = actions.rotate,
            primaryEnabled = state.password.isNotEmpty(),
            busy = state.busy,
            onBack = actions.back,
        ) {
            if (state.notMine) {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.credential_alarm_not_mine_advice_title),
                    stringResource(R.string.credential_alarm_not_mine_advice),
                )
                Spacer(Modifier.height(Spacing.l))
            }
            SecretField(
                state.password,
                actions.setPassword,
                stringResource(R.string.credential_password_label),
                onImeAction = actions.rotate,
                modifier = Modifier.testTag("password"),
            )
            ErrorCard(state.error)
        }
        AlarmStep.RESOLVED -> FormScaffold(
            title = stringResource(R.string.credential_alarm_resolved),
            primaryLabel = stringResource(R.string.credential_done),
            onPrimary = actions.back,
            onBack = actions.back,
        ) {
            if (state.notMine) {
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.credential_alarm_not_mine_advice_title),
                    stringResource(R.string.credential_alarm_not_mine_advice),
                )
            }
        }
    }
}
