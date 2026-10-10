package com.vettid.feature.settings

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vettid.core.data.prefs.AppLockMethod
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
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
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
import com.vettid.core.data.vault.UpdateNoticeKind
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
import kotlinx.coroutines.launch
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
    /**
     * Turns the app lock on with a method, or changes the method of a lock that is on, through the activity's
     * BiometricPrompt (a new key, asked for at once).
     */
    val onEnableAppLock: (AppLockMethod) -> Unit,
    val onAccountClick: () -> Unit,
    val onOpenAccountSite: () -> Unit,
    /** Opens the owner check (VAULT-MESSAGING §3.6.5): [holdOff] true turns the hold off with it (§3.6.7). */
    val onOwnerCheck: (holdOff: Boolean) -> Unit = {},
    /** Opens an item of the Vault (items feature): the shared profile's `@profile` items. */
    val onOpenItem: (String) -> Unit = {},
    /** Opens the update screen (ANDROID-PLAN 0.1.19): Settings → Vault → "Update available". */
    val onReleaseUpdate: () -> Unit = {},
    /** The Notifications bell (ANDROID-PLAN 0.1.23): Settings is a drawer screen; null hides it. */
    val bell: com.vettid.core.ui.components.NotificationBell? = null,
)

/** Registers Settings and its sub-screens. */
@Suppress("LongMethod") // one composable per sub-screen
fun NavGraphBuilder.settingsDestination(host: SettingsHost) {
    composable<SettingsRoute> {
        val vm: SettingsViewModel = hiltViewModel()
        val loaded by vm.uiState.collectAsStateWithLifecycle()
        val ocVm: OwnerCheckSettingsViewModel = hiltViewModel()
        val ownerCheck by ocVm.uiState.collectAsStateWithLifecycle()
        // "Phone screen lock" needs a screen lock on the phone; read again on return from the system settings.
        val context = LocalContext.current
        var screenLockSet by remember { mutableStateOf(screenLockSet(context)) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { screenLockSet = screenLockSet(context) }
        val state = loaded.copy(screenLockSet = screenLockSet)
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
                setAppLock = { on -> if (on) host.onEnableAppLock(state.appLockMethod) else vm.disableAppLock() },
                setAppLockMethod = { m ->
                    when {
                        m == state.appLockMethod -> Unit
                        state.appLockOn -> host.onEnableAppLock(m)
                        else -> vm.chooseAppLockMethod(m)
                    }
                },
                openSecuritySettings = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                acknowledgeInvalidated = vm::acknowledgeInvalidated,
                setTimeout = vm::setAppLockTimeout,
                setTheme = vm::setTheme,
                accountSite = host.onOpenAccountSite,
                deleteVault = { host.navigate(DeleteVaultRoute) },
                dismissError = vm::dismissError,
                sharedProfile = { host.navigate(SharedProfileRoute) },
                releaseUpdate = host.onReleaseUpdate,
                notifications = { host.navigate(NotificationSettingsRoute) },
            ),
            ownerCheck = ownerCheck,
            ownerCheckActions = OwnerCheckSettingsActions(
                setIntervalHours = ocVm::setIntervalHours,
                setHold = { on -> if (on) ocVm.turnHoldOn() else host.onOwnerCheck(true) },
                checkNow = { host.onOwnerCheck(false) },
                dismissOffer = ocVm::dismissOffer,
                dismissError = ocVm::dismissError,
            ),
            bell = host.bell,
        )
    }
    composable<SharedProfileRoute> {
        val vm: SharedProfileViewModel = hiltViewModel()
        val capture: PhotoCaptureViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // The profile photo is taken with the in-app camera, never chosen from the gallery (owner feedback
        // 2026-10-08). Saved across process death: the screen comes back to the live camera (the shot is never stored).
        var capturing by rememberSaveable { mutableStateOf(false) }
        if (capturing) {
            PhotoCaptureScreen(
                capture.machine,
                onClose = { capturing = false },
                onUse = { selection ->
                    capturing = false
                    // The square under the round frame, scaled and re-encoded off the main thread (VAULT-MESSAGING
                    // §10.8: at most 65,536 bytes).
                    vm.photoEncoding()
                    scope.launch {
                        val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            encodeShot(selection)
                        }
                        vm.photoTaken(encoded)
                    }
                },
            )
            return@composable
        }
        SharedProfileContent(
            state,
            SharedProfileActions(
                onBack = host.onBack,
                onDisplayName = vm::setDisplayName,
                onSave = vm::save,
                onChangeName = { host.navigate(ChangeNameRoute) },
                onDismiss = vm::dismiss,
                onTakePhoto = {
                    capture.machine.reset()
                    capturing = true
                },
                onSavePhoto = vm::savePhoto,
                onDiscardPhoto = vm::discardPhoto,
                onRemovePhoto = vm::removePhoto,
                onPickItem = vm::pickItem,
                onAddItem = vm::addToProfile,
                onRemoveItem = vm::removeFromProfile,
                onOpenItem = host.onOpenItem,
            ),
        )
    }
    composable<ChangeNameRoute> {
        val vm: ChangeNameViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        val close = {
            vm.cancel()
            host.onBack()
        }
        // Back from the PIN and password returns to the names; from the names (or the result) it leaves.
        BackHandler { if (state.step == ChangeNameStep.CONFIRM) vm.back() else close() }
        ChangeNameContent(
            state,
            ChangeNameActions(
                onFirst = vm::setFirst,
                onLast = vm::setLast,
                onNext = vm::next,
                onPin = vm::setPin,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onBackToNames = vm::back,
                onClose = close,
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
    composable<NotificationSettingsRoute> {
        NotificationSettingsRouteContent(hiltViewModel(), host.onBack)
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
    val setAppLockMethod: (AppLockMethod) -> Unit = {},
    val openSecuritySettings: () -> Unit = {},
    val acknowledgeInvalidated: () -> Unit = {},
    val setTimeout: (AppLockTimeout) -> Unit = {},
    val setTheme: (ThemePreference) -> Unit = {},
    val accountSite: () -> Unit = {},
    val deleteVault: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val sharedProfile: () -> Unit = {},
    val releaseUpdate: () -> Unit = {},
    val notifications: () -> Unit = {},
)

/**
 * The account card (ANDROID-PLAN 0.1.11): the first and last name, the full address (VAULT-MESSAGING 0.20.0
 * `email`, the masked hint from an older vault) and the membership state; it opens the avatar sheet.
 */
@Composable
private fun AccountCard(state: SettingsUiState, onClick: () -> Unit) {
    val a = state.account
    val membership = a?.takeIf { it.hasSnapshot }?.let {
        stringResource(
            when {
                it.canceled -> R.string.settings_account_canceled
                it.state == "member" -> R.string.settings_account_member
                else -> R.string.settings_account_registered
            },
        )
    }
    val detail = listOfNotNull(a?.fullName?.let { a.displayEmail }, membership)
        .joinToString("\n")
        .ifEmpty { stringResource(R.string.settings_account_detail) }
    SettingsAccountRow(
        name = a?.fullName ?: a?.displayEmail ?: "",
        detail = detail,
        onClick = onClick,
        modifier = Modifier.testTag("account_card"),
        photo = com.vettid.core.ui.components.rememberProfilePhoto(state.photo),
    )
}

/**
 * Settings (ANDROID-PLAN §4): Vault, Security, Privacy, App, Account. Grouped
 * cards (Proton); every destructive action confirms first.
 */
@Suppress("LongMethod")
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    ownerCheck: OwnerCheckSettingsUiState = OwnerCheckSettingsUiState(),
    ownerCheckActions: OwnerCheckSettingsActions = OwnerCheckSettingsActions(),
    bell: com.vettid.core.ui.components.NotificationBell? = null,
) {
    var confirmLock by rememberSaveable { mutableStateOf(false) }
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var timeoutPicker by rememberSaveable { mutableStateOf(false) }
    var methodPicker by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(
        onBackClick = actions.back,
        background = VettIdTheme.colors.groupedBackground,
        actions = { bell?.let { com.vettid.core.ui.components.NotificationBellButton(it) } },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.settings_title))
            SettingsGroup { AccountCard(state, actions.account) }
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
                // The proactive release update (owner decision 2026-10-09): while the vault has one to approve.
                state.update?.let { o ->
                    SettingsRow(
                        stringResource(R.string.settings_vault_update),
                        actions.releaseUpdate,
                        icon = Icons.Outlined.SystemUpdate,
                        supporting = stringResource(R.string.settings_vault_update_body, o.target.number.toInt(), o.current.number.toInt()),
                        iconTint = if (o.kind == UpdateNoticeKind.AVAILABLE) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.testTag("release_update_row"),
                    )
                    SettingsDivider()
                }
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
                // The daily owner check (VAULT-MESSAGING §3.6.2, §3.6.7): the interval, a check now, the hold switch.
                OwnerCheckSettingsRows(ownerCheck, ownerCheckActions)
                SettingsDivider()
                SettingsSwitchRow(
                    label = stringResource(R.string.settings_security_app_lock),
                    supporting = stringResource(
                        if (state.appLockMethod == AppLockMethod.SCREEN_LOCK) {
                            R.string.settings_security_app_lock_body_screen_lock
                        } else {
                            R.string.settings_security_app_lock_body
                        },
                    ),
                    checked = state.appLockOn,
                    onCheckedChange = actions.setAppLock,
                    icon = if (state.appLockMethod == AppLockMethod.SCREEN_LOCK) Icons.Outlined.Pin else Icons.Outlined.Fingerprint,
                    modifier = Modifier.testTag("app_lock"),
                )
                SettingsDivider()
                SettingsRow(
                    stringResource(R.string.settings_security_lock_method), { methodPicker = true },
                    icon = Icons.Outlined.LockOpen, supporting = methodLabel(state.appLockMethod),
                    modifier = Modifier.testTag("app_lock_method"),
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
                SettingsRow(
                    stringResource(R.string.settings_privacy_profile),
                    actions.sharedProfile,
                    icon = SharedProfileIcon,
                    supporting = state.account?.fullName ?: stringResource(R.string.settings_privacy_profile_body),
                    modifier = Modifier.testTag("shared_profile_row"),
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
                // ANDROID-PLAN 0.1.23: how notifications reach this phone, what they show, the channels.
                SettingsRow(
                    label = stringResource(R.string.settings_app_notifications),
                    onClick = actions.notifications,
                    icon = Icons.Outlined.Notifications,
                    supporting = stringResource(modeSummary(state.preferences.effectiveNotificationMode)),
                    modifier = Modifier.testTag("notifications"),
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
    if (methodPicker) {
        AppLockMethodDialog(
            selected = state.appLockMethod,
            screenLockSet = state.screenLockSet,
            onPick = { actions.setAppLockMethod(it); methodPicker = false },
            onOpenSecuritySettings = { actions.openSecuritySettings(); methodPicker = false },
            onDismiss = { methodPicker = false },
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

/** Whether the phone has a screen lock (PIN, pattern or password): "Phone screen lock" needs one. */
private fun screenLockSet(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

@Composable
private fun methodLabel(m: AppLockMethod): String = stringResource(
    when (m) {
        AppLockMethod.BIOMETRICS -> R.string.settings_lock_method_biometrics
        AppLockMethod.SCREEN_LOCK -> R.string.settings_lock_method_screen_lock
    },
)

/**
 * The app lock's method (owner request 2026-10-09): Biometrics (fingerprint or face, the screen lock as the
 * fallback) or Phone screen lock (the phone's PIN, pattern or password only). Without a screen lock on the phone the
 * second is unavailable, with the reason and the system security settings.
 */
@Composable
fun AppLockMethodDialog(
    selected: AppLockMethod,
    screenLockSet: Boolean,
    onPick: (AppLockMethod) -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag("app_lock_method_dialog"),
        title = { Text(stringResource(R.string.settings_security_lock_method), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLockMethod.entries.forEach { m ->
                    val enabled = m != AppLockMethod.SCREEN_LOCK || screenLockSet
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .selectable(selected = m == selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(m) })
                            .testTag("app_lock_method_${m.name.lowercase()}"),
                    ) {
                        RadioButton(selected = m == selected, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(Spacing.m))
                        Column(Modifier.weight(1f)) {
                            val colors = MaterialTheme.colorScheme
                            Text(methodLabel(m), color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
                            Text(
                                stringResource(
                                    when {
                                        !enabled -> R.string.settings_lock_method_screen_lock_unavailable
                                        m == AppLockMethod.BIOMETRICS -> R.string.settings_lock_method_biometrics_body
                                        else -> R.string.settings_lock_method_screen_lock_body
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!screenLockSet) {
                    TextButton(onClick = onOpenSecuritySettings, modifier = Modifier.testTag("app_lock_security_settings")) {
                        Text(stringResource(R.string.settings_lock_method_open_security))
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Text(
                    stringResource(R.string.settings_lock_method_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
