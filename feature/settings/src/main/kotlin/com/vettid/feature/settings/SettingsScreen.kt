package com.vettid.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.SettingsAccountRow
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.SettingsSwitchRow
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.core.ui.theme.VettIdTheme
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

/** What Settings needs from the app shell. */
data class SettingsHost(
    val accountName: String,
    val accountDetail: String,
    val themeMode: ThemeMode,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onBack: () -> Unit,
    val onAccountClick: () -> Unit,
)

fun NavGraphBuilder.settingsDestination(host: SettingsHost) {
    composable<SettingsRoute> { SettingsScreen(host) }
}

/** The theme choice after [mode], cycling System → Light → Dark. */
fun nextThemeMode(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
    ThemeMode.Dark -> ThemeMode.System
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
    },
)

/**
 * Settings (ANDROID-PLAN §4): Vault, Security, Privacy, App. Phase A0: layout
 * only; apart from the theme, rows do nothing yet and nothing is persisted.
 */
@Composable
fun SettingsScreen(host: SettingsHost, modifier: Modifier = Modifier) {
    var appLock by rememberSaveable { mutableStateOf(false) }
    val notEnrolled = stringResource(R.string.settings_not_enrolled)
    DetailScaffold(
        onBackClick = host.onBack,
        modifier = modifier,
        background = VettIdTheme.colors.groupedBackground,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.settings_title))
            SettingsGroup {
                SettingsAccountRow(name = host.accountName, detail = host.accountDetail, onClick = host.onAccountClick)
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_vault))
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_vault_status), {}, icon = Icons.Outlined.Storage, supporting = notEnrolled)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_vault_release), {}, icon = Icons.Outlined.Update)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_vault_lock), {}, icon = Icons.Outlined.Lock)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_vault_pin), {}, icon = Icons.Outlined.Password)
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_security))
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_security_credential), {}, icon = Icons.Outlined.Key)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_security_recovery), {}, icon = Icons.Outlined.Restore)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_security_attestation), {}, icon = Icons.Outlined.VerifiedUser)
                SettingsDivider()
                SettingsSwitchRow(
                    label = stringResource(R.string.settings_security_app_lock),
                    supporting = stringResource(R.string.settings_security_app_lock_body),
                    checked = appLock,
                    onCheckedChange = { appLock = it },
                    icon = Icons.Outlined.Fingerprint,
                )
                SettingsDivider()
                SettingsRow(
                    stringResource(R.string.settings_security_lock_timeout),
                    {},
                    icon = Icons.Outlined.Timer,
                    supporting = stringResource(R.string.settings_timeout_5min),
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_privacy))
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_privacy_profile), {}, icon = Icons.Outlined.Person)
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_privacy_sharing), {}, icon = Icons.Outlined.Share)
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_app))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_app_theme),
                    onClick = { host.onThemeModeChange(nextThemeMode(host.themeMode)) },
                    icon = Icons.Outlined.DarkMode,
                    supporting = themeLabel(host.themeMode),
                )
                SettingsDivider()
                SettingsRow(stringResource(R.string.settings_app_notifications), {}, icon = Icons.Outlined.Notifications)
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
