package com.vettid.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.feed.AdvancedSecurityRow
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme

/**
 * Settings → Security → **Advanced security** (ANDROID-PLAN 0.1.124 §4 "Advanced security"): in the plan's order, each row
 * as it was on Security or App (texts, behavior and confirmations unchanged): the owner check's interval, the hold switch,
 * the idle lock, the app lock timeout (while the app lock is on), Attestation, New phone secret (when it applies) and Allow
 * screenshots (turning it on still asks first).
 *
 * [focus]: the row a notification or a link opened the screen at; it is scrolled into view once.
 * [askScreenshots]: the "Allow screenshots?" confirmation shows from the start (the debug screen catalog).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdvancedSecurityContent(
    state: SettingsUiState,
    actions: SettingsActions,
    ownerCheck: OwnerCheckSettingsUiState = OwnerCheckSettingsUiState(),
    ownerCheckActions: OwnerCheckSettingsActions = OwnerCheckSettingsActions(),
    focus: AdvancedSecurityRow? = null,
    askScreenshots: Boolean = false,
) {
    var timeoutPicker by rememberSaveable { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    var focused by rememberSaveable { mutableStateOf(false) }
    val rowModifier: (AdvancedSecurityRow) -> Modifier = { row ->
        if (row == focus) Modifier.bringIntoViewRequester(requester) else Modifier
    }
    val focusShown = focus?.let { shown(it, state, ownerCheck) } ?: false
    LaunchedEffect(focusShown) {
        if (focusShown && !focused) {
            withFrameNanos { }
            requester.bringIntoView()
            focused = true
        }
    }
    DetailScaffold(onBackClick = actions.back, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("advanced_security_screen")) {
            LargeTitle(stringResource(R.string.settings_security_advanced))
            SettingsGroup {
                // The owner check's interval, the hold and the idle lock, for a vault that reports its owner check.
                OwnerCheckAdvancedRows(ownerCheck, ownerCheckActions, rowModifier)
                val afterOwnerCheck = ownerCheck.view != null
                if (state.appLockOn) {
                    if (afterOwnerCheck) SettingsDivider()
                    SettingsRow(
                        stringResource(R.string.settings_security_lock_timeout), { timeoutPicker = true },
                        icon = Icons.Outlined.Timer, supporting = timeoutLabel(state.preferences.appLockTimeout),
                        modifier = Modifier.testTag("app_lock_timeout"),
                    )
                }
                if (afterOwnerCheck || state.appLockOn) SettingsDivider()
                SettingsRow(
                    stringResource(R.string.settings_security_attestation), actions.attestation, icon = Icons.Outlined.VerifiedUser,
                    modifier = Modifier.testTag("attestation"),
                )
                if (state.phoneSecret) {
                    SettingsDivider()
                    SettingsRow(
                        stringResource(R.string.settings_security_new_secret), actions.newPhoneSecret, icon = Icons.Outlined.Key,
                        supporting = stringResource(R.string.settings_security_new_secret_body),
                        modifier = rowModifier(AdvancedSecurityRow.NEW_PHONE_SECRET).testTag("new_phone_secret_row"),
                    )
                }
                SettingsDivider()
                AllowScreenshotsRow(state, actions.setScreenshotsAllowed, askScreenshots)
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
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

/** Whether [row] is on the screen: only once the state that shows it has arrived (the owner check's view, the phone secret). */
private fun shown(row: AdvancedSecurityRow, state: SettingsUiState, ownerCheck: OwnerCheckSettingsUiState): Boolean = when (row) {
    AdvancedSecurityRow.INTERVAL, AdvancedSecurityRow.HOLD -> ownerCheck.view != null
    AdvancedSecurityRow.IDLE_LOCK -> ownerCheck.view != null && ownerCheck.idleLock != null
    AdvancedSecurityRow.NEW_PHONE_SECRET -> state.phoneSecret
}
