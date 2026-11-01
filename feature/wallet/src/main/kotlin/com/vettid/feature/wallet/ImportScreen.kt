package com.vettid.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoKeyboardLearning
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.components.visibleSecretKeyboard
import com.vettid.core.ui.theme.Spacing

data class ImportActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onNetwork: (Network) -> Unit = {},
    val onWords: (String) -> Unit = {},
    val onAdvanced: () -> Unit = {},
    val onPassphrase: (String) -> Unit = {},
    /** "Continue" on the first step (the checksum is checked) and on the limits step. */
    val onContinue: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onChoice: (Boolean) -> Unit = {},
    val onPerTx: (String) -> Unit = {},
    val onPerPeriod: (String) -> Unit = {},
    val onToggleUnit: () -> Unit = {},
    val onRetryCaps: () -> Unit = {},
    val onOpen: () -> Unit = {},
)

/**
 * Import a wallet (ANDROID-PLAN §4 "Wallet", screens table), one step at a time: name, network, the words and "Advanced"; the limits;
 * the password. `FLAG_SECURE` and out of autofill and content capture for the whole flow ([secure]; off only in the debug screen
 * catalog, which is screenshotted): the words are on screen.
 */
@Composable
fun ImportScreen(state: ImportUiState, actions: ImportActions, modifier: Modifier = Modifier, secure: Boolean = true) {
    if (secure) {
        SecureWindow()
        HiddenFromCapture()
    }
    val limits = LimitsActions(actions.onChoice, actions.onPerTx, actions.onPerPeriod, actions.onToggleUnit)
    when (state.step) {
        ImportStep.FORM -> ImportForm(state, actions, modifier)
        ImportStep.LIMITS -> LimitsStep(
            state.limits,
            state.limitsParse,
            limits,
            actions.onContinue,
            actions.onBack,
            state.busy,
            modifier.testTag("wallet_import_limits"),
        )
        ImportStep.PASSWORD -> ImportPassword(state, actions, modifier)
        ImportStep.DONE -> ImportDone(state, actions, modifier)
    }
}

@Composable
private fun ImportForm(state: ImportUiState, actions: ImportActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_import),
        primaryLabel = stringResource(R.string.wallet_continue),
        onPrimary = actions.onContinue,
        primaryEnabled = state.formReady && state.idle,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_import_form"),
    ) {
        NameAndNetwork(state.name, state.nameProblem, state.network, state.networks, actions.onName, actions.onNetwork)
        Words(state, actions)
        if (state.invalidPhrase) {
            Text(
                stringResource(R.string.wallet_import_invalid),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("import_invalid"),
            )
        }
        TextButton(onClick = actions.onAdvanced, modifier = Modifier.testTag("import_advanced")) {
            Text(stringResource(R.string.wallet_import_advanced))
        }
        if (state.advanced) Passphrase(state, actions)
    }
}

/** The words, in one field: password class, no suggestions, no learning, out of autofill; paste is taken, copy and cut are not. */
@Composable
private fun Words(state: ImportUiState, actions: ImportActions) {
    val unknown = state.status.unknown
    val count = state.status.count
    PasteOnly {
        NoKeyboardLearning {
            OutlinedTextField(
                value = state.typed,
                onValueChange = actions.onWords,
                label = { Text(stringResource(R.string.wallet_phrase_title)) },
                isError = unknown != null,
                supportingText = {
                    Text(
                        when {
                            unknown != null -> stringResource(R.string.wallet_import_unknown_word, unknown)
                            count > 0 -> pluralStringResource(R.plurals.wallet_import_count, count, count)
                            else -> stringResource(R.string.wallet_import_words_hint)
                        },
                        Modifier.testTag("import_words_status"),
                    )
                },
                minLines = WORD_LINES,
                enabled = state.idle,
                // A password-class text keyboard: no autocorrect, no suggestions and no personalized learning; the words stay visible.
                keyboardOptions = visibleSecretKeyboard(KeyboardCapitalization.None, ImeAction.Default),
                modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("import_words"),
            )
        }
    }
}

@Composable
private fun Passphrase(state: ImportUiState, actions: ImportActions) {
    PasteOnly {
        NoKeyboardLearning {
            OutlinedTextField(
                value = state.passphrase,
                onValueChange = actions.onPassphrase,
                label = { Text(stringResource(R.string.wallet_phrase_passphrase)) },
                isError = !state.passphraseValid,
                supportingText = {
                    val note = if (state.passphraseValid) R.string.wallet_import_passphrase_note else R.string.wallet_import_passphrase_bad
                    Text(stringResource(note), Modifier.testTag("import_passphrase_note"))
                },
                singleLine = true,
                keyboardOptions = visibleSecretKeyboard(),
                modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("import_passphrase"),
            )
        }
    }
}

@Composable
private fun ImportPassword(state: ImportUiState, actions: ImportActions, modifier: Modifier) {
    FormScaffold(
        title = stringResource(R.string.wallet_import),
        body = stringResource(R.string.wallet_password_body),
        primaryLabel = stringResource(R.string.wallet_import_action),
        onPrimary = actions.onImport,
        primaryEnabled = state.password.isNotEmpty() && state.idle,
        busy = state.busy,
        onBack = actions.onBack,
        modifier = modifier.testTag("wallet_import_password"),
    ) {
        SecretField(
            value = state.password,
            onValueChange = actions.onPassword,
            label = stringResource(R.string.wallet_password_label),
            error = when (state.problem) {
                ImportProblem.LIMIT -> stringResource(R.string.wallet_limit)
                ImportProblem.EXISTS -> stringResource(R.string.wallet_import_exists)
                null -> state.error?.let { stringResource(it.messageRes()) }
            },
            onImeAction = actions.onImport,
            modifier = Modifier.fillMaxWidth().testTag("wallet_password"),
        )
    }
}

/** Imported, but the spending limit chosen is not set yet: no Receive until it is. */
@Composable
private fun ImportDone(state: ImportUiState, actions: ImportActions, modifier: Modifier) {
    // Every line that applies: the limit not set, that it was not saved on this phone, that the wallet was not recorded as backed up.
    val lines = buildList {
        if (state.capsFailed) add(if (state.capsDuplicate) R.string.wallet_limits_duplicate else R.string.wallet_caps_not_set)
        if (state.capsNotSaved) add(R.string.wallet_caps_not_saved)
        if (state.notRecorded) add(R.string.wallet_import_not_recorded)
        if (state.capsUnsupported) add(R.string.wallet_caps_unsupported)
    }
    FormScaffold(
        title = stringResource(R.string.wallet_imported),
        body = lines.map { stringResource(it) }.joinToString("\n\n"),
        primaryLabel = stringResource(if (state.capsFailed) R.string.wallet_try_again else R.string.wallet_open),
        onPrimary = if (state.capsFailed) actions.onRetryCaps else actions.onOpen,
        busy = state.busy,
        secondaryLabel = if (state.capsFailed) stringResource(R.string.wallet_open) else null,
        onSecondary = actions.onOpen,
        modifier = modifier.testTag("wallet_import_done"),
    ) {}
}

/** The name and the network of a new or imported wallet (the same fields on both). */
@Composable
internal fun NameAndNetwork(
    name: String,
    problem: WalletNames.Problem?,
    network: Network?,
    networks: List<Network>,
    onName: (String) -> Unit,
    onNetwork: (Network) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.wallet_name_label)) },
        isError = problem != null,
        supportingText = problem?.let { p ->
            {
                Text(
                    stringResource(
                        when (p) {
                            WalletNames.Problem.BLANK -> R.string.wallet_name_blank
                            WalletNames.Problem.TOO_LONG -> R.string.wallet_name_too_long
                            WalletNames.Problem.CONTROL -> R.string.wallet_name_control
                        },
                    ),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("wallet_name"),
    )
    Text(stringResource(R.string.wallet_network_label), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for (n in networks) {
            FilterChip(
                selected = n == network,
                onClick = { onNetwork(n) },
                label = { Text(networkLabel(n).orEmpty()) },
                modifier = Modifier.testTag("wallet_network_${n.vaultName}"),
            )
        }
    }
}

/** "Set a spending limit?" for a new wallet and an import alike. */
@Composable
internal fun LimitsStep(
    form: LimitsForm,
    parse: LimitsParse,
    limits: LimitsActions,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
    modifier: Modifier,
) {
    FormScaffold(
        title = stringResource(R.string.wallet_limits_title),
        body = stringResource(R.string.wallet_limits_body),
        primaryLabel = stringResource(R.string.wallet_continue),
        onPrimary = onContinue,
        primaryEnabled = parse.ok,
        busy = busy,
        onBack = onBack,
        modifier = modifier,
    ) {
        LimitsFields(form, parse, limits)
    }
}

private const val WORD_LINES = 3

/**
 * The words never go to the clipboard: inside [content] the text menu offers no Copy or Cut, and whatever asks to write to the
 * clipboard writes nothing. Reading (a paste) is passed through.
 */
@Composable
private fun PasteOnly(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val toolbar = LocalTextToolbar.current
    val readOnly = remember(clipboard) { ReadOnlyClipboard(clipboard) }
    val pasteOnly = remember(toolbar) { PasteOnlyToolbar(toolbar) }
    CompositionLocalProvider(LocalClipboard provides readOnly, LocalTextToolbar provides pasteOnly, content = content)
}

private class ReadOnlyClipboard(private val real: Clipboard) : Clipboard {
    override val nativeClipboard get() = real.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}

private class PasteOnlyToolbar(private val real: TextToolbar) : TextToolbar by real {
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = real.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) = real.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)
}
