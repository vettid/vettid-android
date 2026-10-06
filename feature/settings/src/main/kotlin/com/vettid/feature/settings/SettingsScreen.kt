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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SettingsAccountRow
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsInfoRow
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.SettingsSwitchRow
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.core.ui.theme.VettIdTheme
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

@Serializable
data object VaultStatusRoute

@Serializable
data object ChangePinRoute

@Serializable
data object RecoveryRoute

@Serializable
data object AttestationRoute

@Serializable
data object TransferOutRoute

@Serializable
data object DeleteVaultRoute

/** What Settings needs from the app shell. */
data class SettingsHost(
    val onBack: () -> Unit,
    val navigate: (Any) -> Unit,
    val onOpenCredential: () -> Unit,
    /** Turns the app lock on through the activity's BiometricPrompt. */
    val onEnableAppLock: () -> Unit,
    val onAccountClick: () -> Unit,
    val onOpenAccountSite: () -> Unit,
)

/** Registers Settings and its sub-screens. */
fun NavGraphBuilder.settingsDestination(host: SettingsHost) {
    composable<SettingsRoute> {
        val vm: SettingsViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        SettingsContent(
            state,
            SettingsActions(
                back = host.onBack,
                account = host.onAccountClick,
                vaultStatus = { host.navigate(VaultStatusRoute) },
                lockVault = vm::lockVault,
                changePin = { host.navigate(ChangePinRoute) },
                credential = host.onOpenCredential,
                recovery = { host.navigate(RecoveryRoute) },
                transfer = { host.navigate(TransferOutRoute) },
                attestation = { host.navigate(AttestationRoute) },
                setAppLock = { on -> if (on) host.onEnableAppLock() else vm.disableAppLock() },
                acknowledgeInvalidated = vm::acknowledgeInvalidated,
                setTimeout = vm::setAppLockTimeout,
                setTheme = vm::setTheme,
                accountSite = host.onOpenAccountSite,
                deleteVault = { host.navigate(DeleteVaultRoute) },
                dismissError = vm::dismissError,
            ),
        )
    }
    composable<VaultStatusRoute> {
        val vm: VaultStatusViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        VaultStatusContent(state, vm::load, host.onBack)
    }
    composable<ChangePinRoute> {
        val vm: ChangePinViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ChangePinContent(state, vm::setCurrent, vm::setPin, vm::setConfirm, vm::submit, host.onBack)
    }
    composable<RecoveryRoute> {
        val vm: RecoveryViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        RecoveryContent(state, host.onOpenAccountSite, host.onBack, onTransfer = { host.navigate(TransferOutRoute) })
    }
    composable<TransferOutRoute> {
        val vm: TransferOutViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        TransferOutContent(state, vm, onOpenRecovery = { host.navigate(RecoveryRoute) }, onBack = host.onBack)
    }
    composable<AttestationRoute> {
        val vm: AttestationViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        val canary by vm.canary.collectAsStateWithLifecycle()
        AttestationContent(state, host.onBack, canary, vm::removeCanary)
    }
    composable<DeleteVaultRoute> {
        val vm: DeleteVaultViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        DeleteVaultContent(
            state,
            DeleteVaultActions(
                vm::setPhrase,
                vm::setPin,
                vm::setPassword,
                vm::setAcknowledged,
                vm::submit,
                vm::cancelConfirm,
                vm::confirm,
                host.onBack,
            ),
        )
    }
}

/** The theme choice after [mode], cycling System → Light → Dark. */
fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
    ThemeMode.Dark -> ThemeMode.System
}

@Composable
private fun themeLabel(t: ThemePreference): String = stringResource(
    when (t) {
        ThemePreference.SYSTEM -> R.string.settings_theme_system
        ThemePreference.LIGHT -> R.string.settings_theme_light
        ThemePreference.DARK -> R.string.settings_theme_dark
    },
)

@Composable
private fun timeoutLabel(t: AppLockTimeout): String = stringResource(
    when (t) {
        AppLockTimeout.IMMEDIATELY -> R.string.settings_timeout_immediately
        AppLockTimeout.ONE_MINUTE -> R.string.settings_timeout_1min
        AppLockTimeout.FIVE_MINUTES -> R.string.settings_timeout_5min
        AppLockTimeout.FIFTEEN_MINUTES -> R.string.settings_timeout_15min
        AppLockTimeout.ONE_HOUR -> R.string.settings_timeout_1h
    },
)

/** What the Settings screen can ask for. */
data class SettingsActions(
    val back: () -> Unit = {},
    val account: () -> Unit = {},
    val vaultStatus: () -> Unit = {},
    val lockVault: () -> Unit = {},
    val changePin: () -> Unit = {},
    val credential: () -> Unit = {},
    val recovery: () -> Unit = {},
    val transfer: () -> Unit = {},
    val attestation: () -> Unit = {},
    val setAppLock: (Boolean) -> Unit = {},
    val acknowledgeInvalidated: () -> Unit = {},
    val setTimeout: (AppLockTimeout) -> Unit = {},
    val setTheme: (ThemePreference) -> Unit = {},
    val accountSite: () -> Unit = {},
    val deleteVault: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

/**
 * Settings (ANDROID-PLAN §4): Vault, Security, Privacy, App, Account. Grouped
 * cards (Proton); every destructive action confirms first.
 */
@Suppress("LongMethod")
@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions) {
    var confirmLock by rememberSaveable { mutableStateOf(false) }
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var timeoutPicker by rememberSaveable { mutableStateOf(false) }
    val name = state.account?.emailHint ?: ""
    DetailScaffold(onBackClick = actions.back, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.settings_title))
            SettingsGroup {
                SettingsAccountRow(name = name, detail = stringResource(R.string.settings_account_detail), onClick = actions.account)
            }
            state.error?.let {
                NoticeCard(
                    NoticeKind.URGENT, stringResource(R.string.settings_title), stringResource(it.messageRes()),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                    actions = { TextButton(onClick = actions.dismissError) { Text(stringResource(R.string.settings_done)) } },
                )
            }
            if (state.appLockInvalidated) {
                NoticeCard(
                    NoticeKind.WARNING, stringResource(R.string.settings_security_app_lock), stringResource(
                        R.string.settings_security_app_lock_invalidated,
                    ),
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                    actions = { TextButton(onClick = actions.acknowledgeInvalidated) { Text(stringResource(R.string.settings_done)) } },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_vault))
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_vault_status), actions.vaultStatus, icon =
                    Icons.Outlined.Storage, modifier = Modifier.testTag("vault_status"))
                SettingsDivider()
                SettingsRow(
                    stringResource(R.string.settings_vault_lock), { confirmLock = true }, icon = Icons.Outlined.Lock,
                    supporting = stringResource(R.string.settings_vault_lock_body), showChevron = false, modifier =
                        Modifier.testTag("lock_vault"),
                )
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_vault_pin), actions.changePin, icon = Icons.Outlined.Password)
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_security))
            SettingsGroup {
                SettingsRow(
                    stringResource(R.string.settings_security_credential), actions.credential, icon = Icons.Outlined.Key,
                    supporting = stringResource(R.string.settings_security_credential_body),
                )
                SettingsDivider()
                SettingsRow(
                    stringResource(R.string.settings_security_transfer), actions.transfer, icon = Icons.Outlined.PhonelinkSetup,
                    supporting = stringResource(R.string.settings_security_transfer_body), modifier = Modifier.testTag("transfer"),
                )
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_security_recovery), actions.recovery, icon = Icons.Outlined.Restore)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_security_attestation), actions.attestation, icon = Icons.Outlined.VerifiedUser)
                SettingsDivider()
                SettingsSwitchRow(
                    label = stringResource(R.string.settings_security_app_lock),
                    supporting = stringResource(R.string.settings_security_app_lock_body),
                    checked = state.appLockOn,
                    onCheckedChange = actions.setAppLock,
                    icon = Icons.Outlined.Fingerprint,
                    modifier = Modifier.testTag("app_lock"),
                )
                if (state.appLockOn) {
                    SettingsDivider()
                    SettingsRow(
                        stringResource(R.string.settings_security_lock_timeout), { timeoutPicker = true },
                        icon = Icons.Outlined.Timer, supporting = timeoutLabel(state.preferences.appLockTimeout),
                    )
                }
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_privacy))
            SettingsGroup {
                SettingsInfoRow(
                    stringResource(R.string.settings_privacy_profile),
                    stringResource(R.string.settings_privacy_profile_body),
                    icon = Icons.Outlined.Person,
                )
                SettingsDivider()
                SettingsInfoRow(
                    stringResource(R.string.settings_privacy_sharing),
                    stringResource(R.string.settings_privacy_sharing_body),
                    icon = Icons.Outlined.Share,
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_app))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_app_theme),
                    onClick = { themePicker = true },
                    icon = Icons.Outlined.DarkMode,
                    supporting = themeLabel(state.preferences.theme),
                    modifier = Modifier.testTag("theme"),
                )
                SettingsDivider()
                SettingsInfoRow(
                    stringResource(R.string.settings_app_notifications),
                    stringResource(R.string.settings_app_notifications_body),
                    icon = Icons.Outlined.Notifications,
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_account))
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_account_site), actions.accountSite, icon =
                    Icons.AutoMirrored.Outlined.OpenInNew, showChevron = false)
                SettingsDivider()
                SettingsRow(
                    stringResource(R.string.settings_delete_vault), actions.deleteVault, icon = Icons.Outlined.DeleteForever,
                    iconTint = MaterialTheme.colorScheme.error, supporting = stringResource(R.string.settings_delete_vault_body),
                    modifier = Modifier.testTag("delete_vault"),
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
    if (confirmLock) {
        ConfirmDialog(
            title = stringResource(R.string.settings_vault_lock),
            text = stringResource(R.string.settings_vault_lock_body),
            confirmLabel = stringResource(R.string.settings_vault_lock),
            onConfirm = { confirmLock = false; actions.lockVault() },
            onDismiss = { confirmLock = false },
        )
    }
    if (themePicker) {
        ChoiceDialog(
            title = stringResource(R.string.settings_app_theme),
            options = ThemePreference.entries,
            selected = state.preferences.theme,
            label = { themeLabel(it) },
            onPick = { actions.setTheme(it); themePicker = false },
            onDismiss = { themePicker = false },
        )
    }
    if (timeoutPicker) {
        ChoiceDialog(
            title = stringResource(R.string.settings_security_lock_timeout),
            options = AppLockTimeout.entries,
            selected = state.preferences.appLockTimeout,
            label = { timeoutLabel(it) },
            onPick = { actions.setTimeout(it); timeoutPicker = false },
            onDismiss = { timeoutPicker = false },
        )
    }
}

/** A single-choice dialog (radio rows). */
@Composable
fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) ->
    Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { o ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .selectable(selected = o == selected, role = Role.RadioButton, onClick = { onPick(o) }),
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Spacer(Modifier.width(Spacing.m))
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
