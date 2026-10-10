package com.vettid.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.notify.KeeperStatus
import com.vettid.core.notify.PushAvailability
import com.vettid.core.notify.PushProvider
import com.vettid.core.notify.ServiceStatus
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Settings → App → Notifications (ANDROID-PLAN 0.1.23, Notification modes 1). */
@Serializable
data object NotificationSettingsRoute

/** What the phone says of notifications for VettID (read again on return from the system settings). */
data class PhoneNotifyState(
    /** POST_NOTIFICATIONS granted and notifications on for the app. */
    val allowed: Boolean = true,
    /** Exempt from battery optimisation ("Allow in background"). */
    val background: Boolean = true,
)

/** Immutable UI state of Settings → Notifications. */
data class NotificationSettingsUiState(
    val mode: NotificationMode = NotificationMode.SERVICE,
    val previews: NotificationPreviews = NotificationPreviews.NAMES,
    /** The on-phone service's state; null while it does not run. */
    val service: KeeperStatus? = null,
    val push: PushAvailability = PushAvailability.NOT_AVAILABLE_YET,
    val phone: PhoneNotifyState = PhoneNotifyState(),
) {
    /** The status line (ANDROID-PLAN 0.1.23, Notification modes 1), most important first; null in Off. */
    val status: NotifyStatusLine?
        get() = when {
            mode == NotificationMode.OFF -> null
            !phone.allowed -> NotifyStatusLine.BLOCKED
            service == KeeperStatus.LOCKED -> NotifyStatusLine.LOCKED
            service == KeeperStatus.CHECK_DUE -> NotifyStatusLine.CHECK_DUE
            service == KeeperStatus.WAITING_FOR_NETWORK -> NotifyStatusLine.WAITING
            mode == NotificationMode.SERVICE && !phone.background -> NotifyStatusLine.BATTERY
            service == KeeperStatus.CONNECTED -> NotifyStatusLine.CONNECTED
            else -> NotifyStatusLine.CONNECTING
        }
}

enum class NotifyStatusLine { CONNECTED, CONNECTING, WAITING, LOCKED, CHECK_DUE, BLOCKED, BATTERY }

/** Settings → Notifications: the mode and what notifications show are device preferences (DataStore), not vault settings. */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    service: ServiceStatus,
    private val push: PushProvider,
) : ViewModel() {
    private val phone = kotlinx.coroutines.flow.MutableStateFlow(PhoneNotifyState())

    val uiState: StateFlow<NotificationSettingsUiState> = combine(prefs.preferences, service.status, phone) { p, s, ph ->
        NotificationSettingsUiState(p.notificationMode ?: NotificationMode.SERVICE, p.notificationPreviews, s, push.availability(), ph)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettingsUiState(push = push.availability()))

    fun setMode(m: NotificationMode) {
        if (m == NotificationMode.PUSH && push.availability() != PushAvailability.AVAILABLE) return
        viewModelScope.launch { prefs.setNotificationMode(m) }
    }

    fun setPreviews(p: NotificationPreviews) {
        viewModelScope.launch { prefs.setNotificationPreviews(p) }
    }

    fun phoneState(s: PhoneNotifyState) {
        phone.value = s
    }
}

/** What Settings → Notifications can ask for. */
data class NotificationSettingsActions(
    val back: () -> Unit = {},
    val setMode: (NotificationMode) -> Unit = {},
    val setPreviews: (NotificationPreviews) -> Unit = {},
    val requestPermission: () -> Unit = {},
    val openAppNotifications: () -> Unit = {},
    val allowInBackground: () -> Unit = {},
)

/** Reads what the phone says of VettID's notifications: the permission, the switch, the battery exemption. */
fun phoneNotifyState(context: Context): PhoneNotifyState {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    return PhoneNotifyState(
        allowed = granted && NotificationManagerCompat.from(context).areNotificationsEnabled(),
        background = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
    )
}

/** Registers Settings → Notifications (with the system intents it opens). */
@Composable
fun NotificationSettingsRouteContent(vm: NotificationSettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.phoneState(phoneNotifyState(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.phoneState(phoneNotifyState(context)) }
    fun open(i: Intent) = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    NotificationSettingsContent(
        state,
        NotificationSettingsActions(
            back = onBack,
            setMode = { m ->
                vm.setMode(m)
                if (m != NotificationMode.OFF && !state.phone.allowed) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            setPreviews = vm::setPreviews,
            requestPermission = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
            openAppNotifications = {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            // No special permission (Play restricts REQUEST_IGNORE_BATTERY_OPTIMIZATIONS): the system's list.
            allowInBackground = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        ),
    )
}

/**
 * Settings → Notifications (ANDROID-PLAN 0.1.23, Notification modes 1): "How notifications reach this phone" (the
 * on-phone service, recommended and the default; Google push, "Not available yet" until N4 or without Google Play
 * services; Off, not recommended, confirmed), the status line, **Show in notifications** and the system's
 * notification categories.
 */
@Suppress("LongMethod")
@Composable
fun NotificationSettingsContent(state: NotificationSettingsUiState, actions: NotificationSettingsActions) {
    var confirmOff by remember { mutableStateOf(false) }
    DetailScaffold(onBackClick = actions.back, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            LargeTitle(stringResource(R.string.settings_notify_title))
            SettingsSectionHeader(stringResource(R.string.settings_notify_how))
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        stringResource(R.string.settings_notify_service),
                        stringResource(R.string.settings_notify_service_body),
                        selected = state.mode == NotificationMode.SERVICE,
                        tag = "notify_mode_service",
                    ) { actions.setMode(NotificationMode.SERVICE) }
                    ChoiceRow(
                        stringResource(R.string.settings_notify_push),
                        stringResource(R.string.settings_notify_push_body) + "\n" + stringResource(pushNote(state.push)),
                        selected = state.mode == NotificationMode.PUSH,
                        enabled = state.push == PushAvailability.AVAILABLE,
                        tag = "notify_mode_push",
                    ) { actions.setMode(NotificationMode.PUSH) }
                    ChoiceRow(
                        stringResource(R.string.settings_notify_off),
                        stringResource(R.string.settings_notify_off_body),
                        selected = state.mode == NotificationMode.OFF,
                        tag = "notify_mode_off",
                    ) { if (state.mode != NotificationMode.OFF) confirmOff = true }
                }
            }
            StatusLine(state, actions)
            SettingsSectionHeader(stringResource(R.string.settings_notify_show))
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    NotificationPreviews.entries.forEach { p ->
                        ChoiceRow(
                            stringResource(previewLabel(p)),
                            stringResource(previewBody(p)),
                            selected = state.previews == p,
                            tag = "notify_show_${p.name.lowercase()}",
                        ) { actions.setPreviews(p) }
                    }
                }
            }
            SettingsGroup(Modifier.padding(top = Spacing.l)) {
                SettingsRow(
                    label = stringResource(R.string.settings_notify_categories),
                    onClick = actions.openAppNotifications,
                    icon = Icons.Outlined.Category,
                    supporting = stringResource(R.string.settings_notify_categories_body),
                    modifier = Modifier.testTag("notify_categories"),
                )
            }
            Text(
                stringResource(R.string.settings_notify_history_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gutter + Spacing.xs, vertical = Spacing.l),
            )
        }
    }
    if (confirmOff) {
        ConfirmDialog(
            title = stringResource(R.string.settings_notify_off_confirm),
            text = stringResource(R.string.settings_notify_off_body),
            confirmLabel = stringResource(R.string.settings_notify_off_turn),
            onConfirm = {
                confirmOff = false
                actions.setMode(NotificationMode.OFF)
            },
            onDismiss = { confirmOff = false },
            destructive = true,
        )
    }
}

@Composable
private fun StatusLine(state: NotificationSettingsUiState, actions: NotificationSettingsActions) {
    val line = state.status ?: return
    val (kind, text) = when (line) {
        NotifyStatusLine.CONNECTED -> NoticeKind.SUCCESS to R.string.settings_notify_status_connected
        NotifyStatusLine.CONNECTING -> NoticeKind.INFO to R.string.settings_notify_status_connecting
        NotifyStatusLine.WAITING -> NoticeKind.INFO to R.string.settings_notify_status_waiting
        NotifyStatusLine.LOCKED -> NoticeKind.WARNING to R.string.settings_notify_status_locked
        NotifyStatusLine.CHECK_DUE -> NoticeKind.WARNING to R.string.settings_notify_status_check_due
        NotifyStatusLine.BLOCKED -> NoticeKind.URGENT to R.string.settings_notify_status_blocked
        NotifyStatusLine.BATTERY -> NoticeKind.WARNING to R.string.settings_notify_status_battery
    }
    NoticeCard(
        kind,
        stringResource(R.string.settings_notify_status),
        stringResource(text),
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m).testTag("notify_status"),
        actions = when (line) {
            NotifyStatusLine.BLOCKED -> {
                { TextButton(onClick = actions.openAppNotifications) { Text(stringResource(R.string.settings_notify_open_android)) } }
            }
            NotifyStatusLine.BATTERY -> {
                { TextButton(onClick = actions.allowInBackground) { Text(stringResource(R.string.settings_notify_allow_background)) } }
            }
            else -> null
        },
    )
}

@Composable
private fun ChoiceRow(label: String, body: String, selected: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.m)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            val colors = MaterialTheme.colorScheme
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

private fun pushNote(p: PushAvailability): Int = when (p) {
    PushAvailability.AVAILABLE -> R.string.settings_notify_push_available
    PushAvailability.NOT_AVAILABLE_YET -> R.string.settings_notify_push_not_yet
    PushAvailability.MISSING_SERVICES -> R.string.settings_notify_push_missing
    PushAvailability.SERVICES_OUTDATED -> R.string.settings_notify_push_outdated
}

private fun previewLabel(p: NotificationPreviews): Int = when (p) {
    NotificationPreviews.NAMES -> R.string.settings_notify_show_names
    NotificationPreviews.NAMES_AND_TEXT -> R.string.settings_notify_show_text
    NotificationPreviews.NOTHING -> R.string.settings_notify_show_nothing
}

private fun previewBody(p: NotificationPreviews): Int = when (p) {
    NotificationPreviews.NAMES -> R.string.settings_notify_show_names_body
    NotificationPreviews.NAMES_AND_TEXT -> R.string.settings_notify_show_text_body
    NotificationPreviews.NOTHING -> R.string.settings_notify_show_nothing_body
}

/** The Settings row's summary of the mode. */
fun modeSummary(m: NotificationMode): Int = when (m) {
    NotificationMode.SERVICE -> R.string.settings_notify_service
    NotificationMode.PUSH -> R.string.settings_notify_push
    NotificationMode.OFF -> R.string.settings_notify_off
}
