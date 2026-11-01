package com.vettid.feature.wallet

import android.view.View
import android.view.contentcapture.ContentCaptureManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.ExcludeScreenFromAutofill
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoKeyboardLearning
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.TopLevelScaffold
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.components.visibleSecretKeyboard
import com.vettid.core.ui.components.VettIdFab
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.theme.Spacing

data class WalletsActions(val onNew: () -> Unit = {}, val onOpen: (WalletRow) -> Unit = {}, val onRetry: () -> Unit = {})

/** The chip of a wallet's chain ("Signet", "Testnet4"); none on mainnet (screens table). */
@Composable
internal fun networkLabel(network: Network?): String? = when (network) {
    Network.SIGNET -> stringResource(R.string.wallet_network_signet)
    Network.TESTNET4 -> stringResource(R.string.wallet_network_testnet4)
    Network.TESTNET, null -> stringResource(R.string.wallet_network_testnet)
    Network.REGTEST -> stringResource(R.string.wallet_network_regtest)
    Network.MAINNET -> null
}

/**
 * The Wallet list (ANDROID-PLAN §4 screens table): each wallet with its chain's chip and "Not backed up" in the error colour; the
 * test-network banner; "No wallets yet"; **New wallet**. A failed read says so, with Try again.
 */
@Composable
fun WalletsScreen(state: WalletsUiState, chrome: ShellChrome, actions: WalletsActions, modifier: Modifier = Modifier) {
    TopLevelScaffold(
        title = stringResource(R.string.wallet_title),
        chrome = chrome,
        modifier = modifier,
        overlay = {
            if (!state.loading && state.failed == null) {
                VettIdFab(
                    Icons.Outlined.Add,
                    stringResource(R.string.wallet_new),
                    actions.onNew,
                    Modifier.align(Alignment.BottomEnd).padding(Spacing.l).testTag("wallet_new"),
                )
            }
        },
    ) {
        when {
            state.failed != null -> Column(
                Modifier.fillMaxSize().padding(Spacing.l).testTag("wallet_failed"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.wallet_list_failed), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(state.failed.messageRes()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = actions.onRetry, modifier = Modifier.testTag("wallet_retry")) {
                    Text(stringResource(R.string.wallet_try_again))
                }
            }
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.rows.isEmpty() -> EmptyState(
                icon = Icons.Outlined.AccountBalanceWallet,
                title = stringResource(R.string.wallet_empty_title),
                body = stringResource(R.string.wallet_empty_body),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (state.testNetwork) {
                    item {
                        // One sentence (the plan's draft), so not a NoticeCard (which wants a title and a body).
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(Spacing.l).testTag("wallet_test_banner"),
                        ) {
                            Text(stringResource(R.string.wallet_test_banner), modifier = Modifier.padding(Spacing.m))
                        }
                    }
                }
                items(state.rows, key = { it.id }) { row -> WalletRowItem(row) { actions.onOpen(row) } }
            }
        }
    }
}

@Composable
private fun WalletRowItem(row: WalletRow, onClick: () -> Unit) {
    VettIdListRow(
        title = row.name,
        meta = networkLabel(row.network),
        tileIcon = Icons.Outlined.AccountBalanceWallet,
        onClick = onClick,
        modifier = Modifier.testTag("wallet_row_${row.id}"),
        below = if (row.backedUp) {
            null
        } else {
            {
                Text(
                    stringResource(R.string.wallet_row_not_backed_up),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("wallet_not_backed_up_${row.id}"),
                )
            }
        },
    )
}

data class BackupActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onNetwork: (Network) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onReveal: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onTyped: (String) -> Unit = {},
    val onAnswer: (String) -> Unit = {},
    /** A candidate tapped: its position in the phrase. */
    val onPick: (Int) -> Unit = {},
    val onShowAgain: () -> Unit = {},
    val onSaveAgain: () -> Unit = {},
)

/** Make a wallet and back it up, or back up one (ANDROID-PLAN §4 "Creating and backing up"): one step at a time. */
@Composable
fun BackupScreen(state: BackupUiState, actions: BackupActions, modifier: Modifier = Modifier) {
    when (state.step) {
        BackupStep.FORM -> NewWalletForm(state, actions, modifier)
        BackupStep.PASSWORD -> FormScaffold(
            title = stringResource(R.string.wallet_backup_title),
            body = stringResource(R.string.wallet_backup_body),
            primaryLabel = stringResource(R.string.wallet_backup_show),
            onPrimary = actions.onReveal,
            primaryEnabled = state.password.isNotEmpty(),
            busy = state.busy,
            onBack = actions.onBack,
            modifier = modifier.testTag("wallet_backup_password"),
        ) {
            Text(stringResource(R.string.wallet_backup_password))
            Password(state, actions)
        }
        BackupStep.PHRASE -> PhraseStep(state, actions, modifier)
        BackupStep.CHECK -> CheckStep(state, actions, modifier)
        BackupStep.DONE -> FormScaffold(
            title = stringResource(if (state.backedUp) R.string.wallet_backed_up else R.string.wallet_backup_title),
            body = when {
                state.revealFailed -> stringResource(R.string.wallet_created_reveal_failed)
                state.saveFailed -> stringResource(R.string.wallet_save_failed)
                state.backedUp -> null
                else -> stringResource(R.string.wallet_backup_body)
            },
            primaryLabel = stringResource(if (state.saveFailed) R.string.wallet_try_again else R.string.wallet_done),
            onPrimary = if (state.saveFailed) actions.onSaveAgain else actions.onBack,
            busy = state.busy,
            secondaryLabel = if (state.saveFailed) stringResource(R.string.wallet_done) else null,
            onSecondary = actions.onBack,
            modifier = modifier.testTag("wallet_backup_done"),
        ) {}
    }
}

@Composable
private fun NewWalletForm(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_new_title),
        body = stringResource(R.string.wallet_password_body),
        primaryLabel = stringResource(R.string.wallet_create),
        onPrimary = actions.onCreate,
        primaryEnabled = state.nameValid && state.network != null && state.password.isNotEmpty(),
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_new_form"),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.wallet_name_label)) },
            isError = state.name.isNotEmpty() && !state.nameValid,
            supportingText = if (state.name.isNotEmpty() && !state.nameValid) {
                { Text(stringResource(R.string.wallet_name_error)) }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("wallet_name"),
        )
        Text(stringResource(R.string.wallet_network_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for (n in state.networks) {
                FilterChip(
                    selected = n == state.network,
                    onClick = { actions.onNetwork(n) },
                    label = { Text(networkLabel(n).orEmpty()) },
                    modifier = Modifier.testTag("wallet_network_${n.vaultName}"),
                )
            }
        }
        Password(state, actions)
    }
}

@Composable
private fun Password(state: BackupUiState, actions: BackupActions) {
    SecretField(
        value = state.password,
        onValueChange = actions.onPassword,
        label = stringResource(R.string.wallet_password_label),
        error = state.error?.let { stringResource(it.messageRes()) },
        onImeAction = { if (state.step == BackupStep.FORM) actions.onCreate() else actions.onReveal() },
        modifier = Modifier.fillMaxWidth().testTag("wallet_password"),
    )
}

/** The words, numbered, in two columns; no copy. The window is secure while it is shown. */
@Composable
private fun PhraseStep(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    SecureWindow()
    HiddenFromCapture()
    val words = state.words ?: return
    FormScaffold(
        title = stringResource(R.string.wallet_phrase_title),
        body = stringResource(R.string.wallet_phrase_body),
        primaryLabel = stringResource(R.string.wallet_continue),
        onPrimary = actions.onContinue,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_phrase"),
    ) {
        val half = (words.size + 1) / 2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            for (col in 0..1) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (i in col * half until minOf(words.size, (col + 1) * half)) {
                        Text(
                            "${i + 1}. ${words.word(i)}",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("phrase_word_${i + 1}"),
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.wallet_phrase_no_screenshots),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Which is word n?": four candidates, or typed. The window is secure while it is shown. */
@Composable
private fun CheckStep(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    SecureWindow()
    HiddenFromCapture()
    val words = state.words ?: return
    val live = !state.busy
    val at = state.asked.getOrNull(state.askIndex) ?: 0
    FormScaffold(
        title = stringResource(R.string.wallet_check_title),
        body = stringResource(R.string.wallet_check_which, at + 1),
        primaryLabel = null,
        onPrimary = {},
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_check"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            // Tagged by position, never by the word (debug builds expose test tags as resource ids).
            for (i in state.candidates) {
                OutlinedButton(
                    onClick = { actions.onPick(i) },
                    enabled = live,
                    modifier = Modifier.fillMaxWidth().testTag("check_candidate_$i"),
                ) {
                    Text(words.word(i))
                }
            }
        }
        NoKeyboardLearning {
            OutlinedTextField(
                value = state.typed,
                onValueChange = actions.onTyped,
                label = { Text(stringResource(R.string.wallet_check_type)) },
                singleLine = true,
                enabled = live,
                keyboardOptions = visibleSecretKeyboard(),
                modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("check_typed"),
            )
        }
        TextButton(onClick = { actions.onAnswer(state.typed) }, enabled = live && state.typed.isNotBlank()) {
            Text(stringResource(R.string.wallet_continue))
        }
        state.wrong?.let { w ->
            Text(
                stringResource(R.string.wallet_check_wrong, w + 1),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("check_wrong"),
            )
            TextButton(onClick = actions.onShowAgain, enabled = live, modifier = Modifier.testTag("check_show_again")) {
                Text(stringResource(R.string.wallet_check_show_again))
            }
        }
    }
}

/**
 * The phrase and the check are left out of Android's content capture and of autofill (ANDROID-PLAN §4 "Creating and backing up",
 * 3) while they are shown; TalkBack still reads them. Compose takes the activity's content-capture session once (at `onStart`) and
 * does not read the view's `importantForContentCapture` again, so the flag alone would not keep the words out: capture is also
 * switched off for the activity ([ContentCaptureManager.setContentCaptureEnabled]), which ends what its session sends, and on again
 * when the screens leave if it was on. Autofill: [ExcludeScreenFromAutofill] (the compose view always reports itself important for
 * autofill, so the hold is on its parent).
 */
@Composable
private fun HiddenFromCapture() {
    ExcludeScreenFromAutofill()
    val view = LocalView.current
    val capture = LocalContext.current.getSystemService(ContentCaptureManager::class.java)
    DisposableEffect(view, capture) {
        val captureWasOn = capture?.isContentCaptureEnabled == true
        val important = view.importantForContentCapture
        capture?.setContentCaptureEnabled(false)
        view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        onDispose {
            view.importantForContentCapture = important
            if (captureWasOn) capture?.setContentCaptureEnabled(true)
        }
    }
}
