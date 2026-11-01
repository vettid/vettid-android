package com.vettid.feature.wallet

import android.os.Build
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
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

data class WalletsActions(
    val onNew: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onOpen: (WalletRow) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** The chip of a wallet's chain ("Signet", "Testnet4"); none on mainnet (screens table). */
@Composable
internal fun networkLabel(network: Network?): String? = when (network) {
    Network.SIGNET -> stringResource(R.string.wallet_network_signet)
    Network.TESTNET4 -> stringResource(R.string.wallet_network_testnet4)
    Network.TESTNET -> stringResource(R.string.wallet_network_testnet3)
    null -> stringResource(R.string.wallet_network_testnet)
    Network.REGTEST -> stringResource(R.string.wallet_network_regtest)
    Network.MAINNET -> null
}

/**
 * A string only `:feature:wallet` has, kept by R8 (the list's test tag): `walletMarkers` in app/build.gradle.kts fails a release APK
 * that contains it, and a release built with `-PvettidWalletInRelease=true` must contain it. The DEX check's package names alone
 * would miss the screens once R8 renames their packages.
 */
internal const val WALLET_SCREENS_MARKER = "vettid_wallet_screens"

/**
 * The Wallet list (ANDROID-PLAN §4 screens table): each wallet with its chain's chip and "Not backed up" in the error colour; the
 * test-network banner; "No wallets yet"; **New wallet**. A failed read says so, with Try again.
 */
@Composable
fun WalletsScreen(state: WalletsUiState, chrome: ShellChrome, actions: WalletsActions, modifier: Modifier = Modifier) {
    TopLevelScaffold(
        title = stringResource(R.string.wallet_title),
        chrome = chrome,
        modifier = modifier.testTag(WALLET_SCREENS_MARKER),
        overlay = {
            if (!state.loading && state.failed == null) {
                // The floating action button: New wallet / Import a wallet (screens table).
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.BottomEnd).padding(Spacing.l)) {
                    VettIdFab(Icons.Outlined.Add, stringResource(R.string.wallet_add), { menu = true }, Modifier.testTag("wallet_new"))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.wallet_new)) },
                            onClick = {
                                menu = false
                                actions.onNew()
                            },
                            modifier = Modifier.testTag("wallet_new_item"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.wallet_import)) },
                            onClick = {
                                menu = false
                                actions.onImport()
                            },
                            modifier = Modifier.testTag("wallet_import_item"),
                        )
                    }
                }
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
            state.rows.isEmpty() -> Column(Modifier.fillMaxSize()) {
                // Every wallet this build can make is on a test network: the banner is there before the first one too.
                if (state.testBuild) TestBanner()
                EmptyState(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = stringResource(R.string.wallet_empty_title),
                    body = stringResource(R.string.wallet_empty_body),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (state.testNetwork) {
                    item {
                        TestBanner()
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
    val onPin: (String) -> Unit = {},
    /** "Continue" from the name and network, and from the limits. */
    val onNext: () -> Unit = {},
    /** One step back inside making a wallet (the limits, the password). */
    val onPrevious: () -> Unit = {},
    val onChoice: (Boolean) -> Unit = {},
    val onPerTx: (String) -> Unit = {},
    val onPerPeriod: (String) -> Unit = {},
    val onToggleUnit: () -> Unit = {},
    val onRetryCaps: () -> Unit = {},
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
    // One hold for the phrase and the check together, at one place in the composition: moving between them toggles nothing.
    if (state.step == BackupStep.PHRASE || state.step == BackupStep.CHECK) {
        SecureWindow()
        HiddenFromCapture()
    }
    when (state.step) {
        BackupStep.FORM -> NewWalletForm(state, actions, modifier)
        BackupStep.LIMITS -> LimitsStep(
            state.limits,
            state.limitsParse,
            LimitsActions(actions.onChoice, actions.onPerTx, actions.onPerPeriod, actions.onToggleUnit),
            actions.onNext,
            actions.onPrevious,
            state.busy,
            modifier.testTag("wallet_new_limits"),
        )
        BackupStep.CREATE -> CreateStep(state, actions, modifier)
        BackupStep.PASSWORD -> PasswordStep(state, actions, modifier)
        BackupStep.PHRASE -> PhraseStep(state, actions, modifier)
        BackupStep.CHECK -> CheckStep(state, actions, modifier)
        BackupStep.DONE -> DoneStep(state, actions, modifier)
    }
}

/**
 * A wallet that is not backed up yet: the password, to show the phrase again; for a wallet that has a spending limit the PIN as well
 * ("To protect your spending limit, enter your PIN too.").
 */
@Composable
private fun PasswordStep(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_backup_title),
        body = stringResource(R.string.wallet_backup_body),
        primaryLabel = stringResource(R.string.wallet_backup_show),
        onPrimary = actions.onReveal,
        primaryEnabled = state.password.isNotEmpty() && state.pinReady,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_backup_password"),
    ) {
        Text(stringResource(R.string.wallet_backup_password))
        if (state.capped) {
            Text(stringResource(R.string.wallet_pin_too), modifier = Modifier.testTag("wallet_pin_too"))
            SecretField(
                value = state.pin,
                onValueChange = actions.onPin,
                label = stringResource(R.string.wallet_pin_label),
                isPin = true,
                error = state.error?.takeIf { it == FailureKind.BAD_PIN }?.let { stringResource(it.messageRes()) },
                modifier = Modifier.fillMaxWidth().testTag("wallet_pin"),
            )
        }
        Password(state, actions)
    }
}

/** The line under [DoneStep]'s title; the chosen spending limit that could not be set says that Receive stays off until it is. */
private fun doneBody(state: BackupUiState): Int? = when {
    state.revealFailed -> R.string.wallet_created_reveal_failed
    state.saveFailed -> R.string.wallet_save_failed
    state.capsFailed -> R.string.wallet_caps_not_set
    state.backedUp -> null
    else -> R.string.wallet_backup_body
}

/** The end: "Backed up", or why not (the reveal failed, or the check passed but could not be recorded: Try again). */
@Composable
private fun DoneStep(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    val title = when {
        state.backedUp -> R.string.wallet_backed_up
        state.saveFailed -> R.string.wallet_not_recorded
        else -> R.string.wallet_backup_title
    }
    val again = state.saveFailed || state.capsFailed
    FormScaffold(
        title = stringResource(title),
        body = doneBody(state)?.let { stringResource(it) },
        primaryLabel = stringResource(if (again) R.string.wallet_try_again else R.string.wallet_done),
        onPrimary = if (state.saveFailed) actions.onSaveAgain else if (state.capsFailed) actions.onRetryCaps else actions.onBack,
        busy = state.busy,
        secondaryLabel = if (again) stringResource(R.string.wallet_done) else null,
        onSecondary = actions.onBack,
        modifier = modifier.testTag("wallet_backup_done"),
    ) {}
}

/** New wallet, first step: the name and the network; the limits step and the password follow. */
@Composable
private fun NewWalletForm(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_new_title),
        primaryLabel = stringResource(R.string.wallet_continue),
        onPrimary = actions.onNext,
        primaryEnabled = state.nameValid && state.network != null,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_new_form"),
    ) {
        NameAndNetwork(state.name, state.nameProblem, state.network, state.networks, actions.onName, actions.onNetwork)
    }
}

/** New wallet, last step: the password, and the wallet is created (without limits; the ones chosen follow the phrase check). */
@Composable
private fun CreateStep(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_new_title),
        body = stringResource(R.string.wallet_password_body),
        primaryLabel = stringResource(R.string.wallet_create),
        onPrimary = actions.onCreate,
        primaryEnabled = state.nameValid && state.network != null && state.password.isNotEmpty(),
        busy = state.busy,
        onBack = actions.onPrevious,
        modifier = modifier.testTag("wallet_new_password"),
    ) {
        Password(state, actions)
    }
}

@Composable
private fun Password(state: BackupUiState, actions: BackupActions) {
    SecretField(
        value = state.password,
        onValueChange = actions.onPassword,
        label = stringResource(R.string.wallet_password_label),
        error = when (state.problem) {
            BackupProblem.LIMIT -> stringResource(R.string.wallet_limit)
            BackupProblem.PIN_REQUIRED -> null
            // A wrong PIN is shown on the PIN field.
            null -> state.error?.takeIf { it != FailureKind.BAD_PIN }?.let { stringResource(it.messageRes()) }
        },
        onImeAction = { if (state.step == BackupStep.CREATE) actions.onCreate() else actions.onReveal() },
        modifier = Modifier.fillMaxWidth().testTag("wallet_password"),
    )
}

/** The words, numbered, in two columns; no copy. The window is secure while it is shown. */
@Composable
private fun PhraseStep(state: BackupUiState, actions: BackupActions, modifier: Modifier) {
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
        if (words.hasPassphrase) {
            Column(Modifier.testTag("phrase_passphrase")) {
                Text(stringResource(R.string.wallet_phrase_passphrase), style = MaterialTheme.typography.labelLarge)
                Text(words.passphrase(), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.wallet_phrase_passphrase_note), style = MaterialTheme.typography.bodySmall)
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
    val words = state.words ?: return
    val live = state.answering
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
        // A wrong answer ends the check: only "Show the words again" is left (the next check asks new positions).
        state.wrong?.let { w ->
            Text(
                stringResource(R.string.wallet_check_wrong, w + 1),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("check_wrong"),
            )
            TextButton(onClick = actions.onShowAgain, enabled = !state.busy, modifier = Modifier.testTag("check_show_again")) {
                Text(stringResource(R.string.wallet_check_show_again))
            }
            return@FormScaffold
        }
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
        // No copy, cut or paste menu on the typed word: nothing of the phrase reaches the clipboard from here.
        NoKeyboardLearning {
            CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
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
        }
        TextButton(onClick = { actions.onAnswer(state.typed) }, enabled = live && state.typed.isNotBlank()) {
            Text(stringResource(R.string.wallet_continue))
        }
    }
}

/** "Test network: these coins have no value." One sentence (the plan's draft), so not a NoticeCard (which wants a title and a body). */
@Composable
private fun TestBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(Spacing.l).testTag("wallet_test_banner"),
    ) {
        Text(stringResource(R.string.wallet_test_banner), modifier = Modifier.padding(Spacing.m))
    }
}

/** A text toolbar that never shows: no copy, cut, paste or select-all menu. */
private object NoTextToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
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
internal fun HiddenFromCapture() {
    ExcludeScreenFromAutofill()
    val view = LocalView.current
    val capture = LocalContext.current.getSystemService(ContentCaptureManager::class.java)
    DisposableEffect(view, capture) {
        val captureWasOn = capture?.isContentCaptureEnabled == true
        val important = view.importantForContentCapture
        capture?.setContentCaptureEnabled(false)
        view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        // API 34+: only accessibility services that are accessibility tools (TalkBack) read the words; others are refused them. The
        // view has no other setting of its own (the app sets it nowhere else), so it goes back to AUTO, the default.
        val dataSensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (dataSensitive) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        onDispose {
            view.importantForContentCapture = important
            if (dataSensitive) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
            if (captureWasOn) capture?.setContentCaptureEnabled(true)
        }
    }
}
