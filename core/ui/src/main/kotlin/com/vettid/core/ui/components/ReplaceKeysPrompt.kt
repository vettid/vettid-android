package com.vettid.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.core.ui.R

/**
 * (ANDROID-PLAN 0.1.88 "Replacing the vault's keys, once") The one-time prompt on the success screen of a PIN change or a
 * password change. [onReplace] opens the key replacement; [onNotNow] is "Not now" and a back press or a tap outside: no,
 * nothing stored, no reminder.
 */
@Composable
fun ReplaceKeysPrompt(onReplace: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    ConfirmDialog(
        title = stringResource(R.string.core_ui_replace_keys_title),
        text = stringResource(R.string.core_ui_replace_keys_body),
        confirmLabel = stringResource(R.string.core_ui_replace_keys_action),
        onConfirm = onReplace,
        onDismiss = onNotNow,
        modifier = modifier.testTag("replace_keys_prompt"),
        dismissLabel = stringResource(R.string.core_ui_replace_keys_not_now),
    )
}
