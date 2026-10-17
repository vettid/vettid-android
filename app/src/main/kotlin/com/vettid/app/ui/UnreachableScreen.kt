package com.vettid.app.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.app.R
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.wipe.AppDataClear
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold

/**
 * The phase could not be read ([com.vettid.core.data.vault.AppPhase.Unreachable]): why, and "Try again". Three
 * causes are not about the connection and say so in the title: the phone's clock ([FailureKind.CLOCK]); the account
 * service saying that the account has no vault ([FailureKind.NO_VAULT], A6 audit N6); and stored keys that cannot be
 * read ([FailureKind.LOCAL_STATE], P4). The last two are dead ends for this phone as it is, so they also offer
 * "Start over" ([onStartOver]), after a confirmation that says what it costs: nothing is forgotten or erased by
 * itself. A wipe of this phone that did not finish ([FailureKind.ERASE_FAILED]) offers "Erase VettID's data on this
 * phone" ([com.vettid.core.data.wipe.AppDataClear]); [clearFailed]: the system refused it, and the member is told how
 * to clear VettID's storage themselves. [confirming]: the confirmation shows from the start (the debug screen catalog).
 */
@Composable
internal fun UnreachableScreen(
    kind: FailureKind?,
    busy: Boolean,
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
    confirming: Boolean = false,
    clearFailed: Boolean = false,
) {
    var confirm by rememberSaveable { mutableStateOf(confirming) }
    val way = WayOut.of(kind)
    FormScaffold(
        title = stringResource(titleOf(kind)),
        body = kind?.let { stringResource(it.messageRes()) },
        primaryLabel = stringResource(R.string.root_retry),
        onPrimary = onRetry,
        busy = busy,
        secondaryLabel = way?.let { stringResource(it.label) },
        onSecondary = { confirm = true },
        modifier = Modifier.testTag("unreachable"),
    ) {
        if (clearFailed && way == WayOut.CLEAR_DATA) {
            Text(
                stringResource(R.string.root_clear_data_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("clear_data_failed"),
            )
        }
    }
    if (confirm && way != null) {
        ConfirmDialog(
            title = stringResource(way.title),
            text = stringResource(way.body),
            confirmLabel = stringResource(way.confirm),
            onConfirm = {
                confirm = false
                onStartOver()
            },
            onDismiss = { confirm = false },
            modifier = Modifier.testTag("start_over_dialog"),
            destructive = true,
        )
    }
}

@StringRes
private fun titleOf(kind: FailureKind?): Int = when (kind) {
    FailureKind.CLOCK -> R.string.root_clock_title
    FailureKind.NO_VAULT -> R.string.root_no_vault_title
    FailureKind.LOCAL_STATE -> R.string.root_local_state_title
    FailureKind.ERASE_FAILED -> R.string.root_erase_failed_title
    else -> R.string.root_unreachable_title
}

/** The confirmed way out of a dead end: its button, and the confirmation that says what it costs. */
internal enum class WayOut(
    @param:StringRes val label: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val confirm: Int = label,
) {
    START_OVER(R.string.root_start_over, R.string.root_start_over_title, R.string.root_start_over_body),
    ERASE(R.string.root_erase, R.string.root_erase_title, R.string.root_erase_body),

    /** After a wipe that did not finish: erase VettID's data on this phone ([com.vettid.core.data.wipe.AppDataClear]). */
    CLEAR_DATA(R.string.root_clear_data, R.string.root_clear_data_title, R.string.root_clear_data_body, R.string.root_clear_data_confirm),
    ;

    companion object {
        fun of(kind: FailureKind?): WayOut? = when {
            kind == FailureKind.NO_VAULT -> START_OVER
            kind == FailureKind.LOCAL_STATE -> ERASE
            AppDataClear.offeredFor(kind) -> CLEAR_DATA
            else -> null
        }
    }
}
