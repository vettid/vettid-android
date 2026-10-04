package com.vettid.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.vettid.app.R
import com.vettid.core.ui.components.AvatarSheet
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow

/** Avatar sheet (ANDROID-PLAN §4): vault status, lock, account portal, sign out. */
@Composable
fun AccountSheet(
    name: String,
    detail: String,
    onDismiss: () -> Unit,
    onLockVault: () -> Unit,
    onSignOut: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val portal = stringResource(R.string.account_portal_url)
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    AvatarSheet(
        name = name,
        detail = detail,
        onDismiss = onDismiss,
        optionsHeader = stringResource(R.string.account_options),
    ) {
        SettingsGroup {
            SettingsRow(
                label = stringResource(R.string.account_vault_status),
                supporting = stringResource(R.string.account_vault_status_value),
                onClick = {},
                icon = Icons.Outlined.Storage,
                showChevron = false,
            )
            SettingsDivider()
            SettingsRow(stringResource(R.string.account_lock_vault), onLockVault, icon = Icons.Outlined.Lock, showChevron = false)
            SettingsDivider()
            SettingsRow(
                stringResource(R.string.account_portal),
                { uri.openUri(portal) },
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                showChevron = false,
            )
        }
        SettingsGroup {
            SettingsRow(
                label = stringResource(R.string.account_sign_out),
                onClick = { confirmSignOut = true },
                icon = Icons.AutoMirrored.Outlined.Logout,
                iconTint = MaterialTheme.colorScheme.error,
                showChevron = false,
            )
        }
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.account_sign_out_title),
            text = stringResource(R.string.account_sign_out_body),
            confirmLabel = stringResource(R.string.account_sign_out_confirm),
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}
