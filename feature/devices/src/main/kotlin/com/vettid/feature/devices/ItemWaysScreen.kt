package com.vettid.feature.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vettid.core.data.devices.HostProblem
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWays
import com.vettid.core.data.devices.ItemWaysRules
import com.vettid.core.data.devices.ProgramProblem
import com.vettid.core.data.devices.RunProgram
import com.vettid.core.data.devices.VariableProblem
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.AlertDialog
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.components.ModalBottomSheet
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.delay

/** What the item's page can ask for. */
data class ItemWaysActions(
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onShowCurrent: () -> Unit = {},
    val onRun: (Boolean) -> Unit = {},
    val onProxy: (Boolean) -> Unit = {},
    val onReveal: (Boolean) -> Unit = {},
    val onEach: (Boolean) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onProgramInput: (String) -> Unit = {},
    val onAddProgram: () -> Unit = {},
    val onOpenProgram: (Int) -> Unit = {},
    val onCloseProgram: () -> Unit = {},
    val onEditInput: (String) -> Unit = {},
    val onApplyEdit: () -> Unit = {},
    val onRemoveProgram: (Int) -> Unit = {},
    val onForgetPin: (Int) -> Unit = {},
    val onShowPin: (String?) -> Unit = {},
    val onVariableInput: (String) -> Unit = {},
    val onAddVariable: () -> Unit = {},
    val onRemoveVariable: (String) -> Unit = {},
    val onHostInput: (String) -> Unit = {},
    val onAddHost: () -> Unit = {},
    val onRemoveHost: (String) -> Unit = {},
    val onHeader: (String) -> Unit = {},
    val onPrefix: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onSubmitPassword: () -> Unit = {},
    val onDismissPrompt: () -> Unit = {},
    val onOpenAlarm: () -> Unit = {},
    val onNoticeShown: () -> Unit = {},
    val onCloseWindowNow: () -> Unit = {},
)

/** The fields of this page: what is typed is what is saved (no suggestions, autocorrection or capitals; AA7). */
/** The fields' keyboard: no autocorrect, no capitals (AA7). Internal for its test. */
internal val PLAIN = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done)

/** How long the page stays after `not_found` to a save before it goes back (the accessibility time of a notice). */
private const val NOT_INCLUDED_MS = 4_000L

/**
 * How an agent may use an item (ANDROID-PLAN 0.1.137 AA7): the four ways, the run settings with each program's page, the
 * API settings, and **Save**. A read-only page (settings this version can't show) offers only to turn a whole way off.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ItemWaysScreen(state: ItemWaysUiState, actions: ItemWaysActions, modifier: Modifier = Modifier) {
    // Unsaved changes, typed fields and the password prompt: the shell does not cover them in place (an overlay keeps them).
    HoldsInput(state.holdsInput)
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let { noticeText(it) }
    LaunchedEffect(state.notice) {
        if (noticeText == null) return@LaunchedEffect
        actions.onNoticeShown()
        snackbar.showSnackbar(noticeText)
    }
    LaunchedEffect(state.leaving) {
        // After a save's not_found the page says so, then goes back; an event's says so and stays.
        if (!state.leaving) return@LaunchedEffect
        delay(NOT_INCLUDED_MS)
        actions.onBack()
    }
    val program = state.openProgram
    BackHandler(enabled = program != null) { actions.onCloseProgram() }
    DetailScaffold(
        onBackClick = { if (program != null) actions.onCloseProgram() else actions.onBack() },
        modifier = modifier,
        actions = {
            if (!state.readOnly && state.draft != null) {
                TextButton(onClick = actions.onSave, enabled = state.canSave, modifier = Modifier.testTag("ways_save")) {
                    Text(stringResource(R.string.ways_save))
                }
            }
        },
        overlay = { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Spacing.l)) },
    ) {
        val e = state.loadError
        when {
            state.read == null && e != null -> Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.ways_load_failed),
                    body = failureText(e.kind.messageRes(), e.kind.vaultCode(e.shownCode)),
                    modifier = Modifier.testTag("ways_load_failed"),
                    actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.ways_retry)) } },
                )
            }
            state.read == null && !state.notIncluded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Text(
                    stringResource(R.string.ways_title, state.agentName, itemName(state.itemName)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).semantics { heading() }.testTag("ways_title"),
                )
                // A re-read that failed while settings are shown: they may be out of date (a conflict's reload, an event's).
                if (e != null) {
                    NoticeCard(
                        kind = NoticeKind.WARNING,
                        title = stringResource(R.string.ways_load_failed),
                        body = failureText(e.kind.messageRes(), e.kind.vaultCode(e.shownCode)),
                        modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("ways_reload_failed"),
                        actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.ways_retry)) } },
                    )
                }
                PageNotices(state, actions)
                val d = state.draft
                when {
                    state.notIncluded -> Unit
                    program != null && d != null -> d.run?.programs?.getOrNull(program)?.let { ProgramPage(state, it, program, actions) }
                    else -> MainPage(state, actions)
                }
            }
        }
    }
    WaysDialogs(state, actions)
    state.prompt?.let { PasswordSheet(state, it, actions) }
}

@Composable
private fun itemName(name: String?): String = name ?: stringResource(R.string.ways_item_gone)

@Composable
private fun noticeText(n: WaysNotice): String = when (n) {
    WaysNotice.Saved -> stringResource(R.string.ways_saved)
    WaysNotice.LockedAgain -> stringResource(R.string.ways_locked_again)
    WaysNotice.Conflict -> stringResource(R.string.ways_conflict)
    WaysNotice.ReadOnlyLocked -> stringResource(R.string.ways_read_only_locked)
    is WaysNotice.Failed -> failureText(n.failure.kind.messageRes(), n.failure.kind.vaultCode(n.failure.shownCode))
}

@Composable
private fun PageNotices(state: ItemWaysUiState, actions: ItemWaysActions) {
    state.refused?.let { RefusedCard(it) }
    if (state.notIncluded) {
        NoticeCard(
            kind = NoticeKind.WARNING,
            title = stringResource(R.string.ways_not_included, state.agentName),
            body = "",
            modifier = Modifier.testTag("ways_not_included"),
        )
    }
    if (state.changedElsewhere) {
        NoticeCard(
            kind = NoticeKind.INFO,
            title = stringResource(R.string.ways_changed_elsewhere),
            body = "",
            modifier = Modifier.testTag("ways_changed_elsewhere"),
            actions = { TextButton(onClick = actions.onShowCurrent) { Text(stringResource(R.string.ways_show_current)) } },
        )
    }
    if (state.readOnly) {
        NoticeCard(
            kind = NoticeKind.INFO,
            title = stringResource(R.string.ways_read_only),
            body = "",
            modifier = Modifier.testTag("ways_read_only"),
        )
    }
    state.windowNotClosed?.let { w ->
        val minutes = w.ttlSeconds?.let { ((it + 59) / 60).toInt() }
        NoticeCard(
            kind = NoticeKind.WARNING,
            title = stringResource(R.string.ways_window_title),
            body = minutes?.let { pluralStringResource(R.plurals.ways_window_open, it, it) } ?: stringResource(R.string.ways_window_plain),
            modifier = Modifier.testTag("ways_window_open"),
            actions = { TextButton(onClick = actions.onCloseWindowNow) { Text(stringResource(R.string.ways_window_close)) } },
        )
    }
}

/** The four switches and, under the ones that are on, their settings. */
@Composable
private fun MainPage(state: ItemWaysUiState, actions: ItemWaysActions) {
    val read = state.read ?: return
    val d = state.draft
    // A read-only page shows the ways as read, and only Turn off on them.
    val on: (ItemWay) -> Boolean = { w -> d?.let { w in it.ways } ?: (w.wire in read.rawWays) }
    val ro = state.readOnly
    // A read-only page whose turn-off could never be sent (no strict reading, or another agent's item) offers none.
    val canTurnOff = !ro || state.canTurnOff
    val may: (ItemWay) -> Boolean = { w -> !state.saving && canTurnOff && (!ro || on(w)) }
    DetailCard(Modifier.testTag("ways_card")) {
        WaySwitch(stringResource(R.string.ways_use), checked = true, enabled = false, onChange = {}, tag = "ways_use")
        Note(stringResource(R.string.ways_use_note))
        WaySwitch(
            stringResource(R.string.ways_run), on(ItemWay.RUN), enabled = may(ItemWay.RUN),
            onChange = actions.onRun, tag = "ways_run",
        )
        WaySwitch(
            stringResource(R.string.ways_proxy), on(ItemWay.PROXY), enabled = may(ItemWay.PROXY),
            onChange = actions.onProxy, tag = "ways_proxy",
        )
        WaySwitch(
            stringResource(R.string.ways_reveal), on(ItemWay.REVEAL), enabled = may(ItemWay.REVEAL),
            onChange = actions.onReveal, tag = "ways_reveal",
        )
        Note(stringResource(R.string.ways_reveal_note))
    }
    if (d != null) {
        d.run?.let { RunCard(state, d, actions) }
        d.proxy?.let { ProxyCard(state, d, actions) }
    }
}

@Composable
private fun RunCard(state: ItemWaysUiState, d: ItemWays, actions: ItemWaysActions) {
    val run = d.run ?: return
    DetailCard(Modifier.testTag("ways_run_card")) {
        CardHeading(stringResource(R.string.ways_programs_title))
        if (run.programs.isEmpty()) {
            Note(stringResource(R.string.ways_programs_none), Modifier.testTag("ways_programs_none"))
        }
        run.programs.forEachIndexed { i, p -> ProgramRow(state, p, i, run.each, actions) }
        if (run.programs.size < ItemWaysRules.MAX_PROGRAMS) {
            Spacer(Modifier.heightIn(min = Spacing.s))
            Text(stringResource(R.string.ways_add_program), style = MaterialTheme.typography.titleSmall)
            PlainField(
                value = state.programInput,
                onValue = actions.onProgramInput,
                label = stringResource(R.string.ways_program_field),
                supporting = stringResource(R.string.ways_program_hint),
                error = state.programProblem?.let { programProblemText(it) },
                onDone = actions.onAddProgram,
                tag = "ways_program_input",
            )
            SecondaryButton(
                stringResource(R.string.ways_add),
                actions.onAddProgram,
                enabled = state.programInput.isNotEmpty(),
                modifier = Modifier.testTag("ways_program_add"),
            )
        } else {
            Note(stringResource(R.string.ways_program_too_many))
        }
        Spacer(Modifier.heightIn(min = Spacing.s))
        WaySwitch(
            stringResource(R.string.ways_each),
            run.each,
            enabled = !state.saving && run.programs.isNotEmpty(),
            onChange = actions.onEach,
            tag = "ways_each",
        )
        if (run.programs.isEmpty()) Note(stringResource(R.string.ways_each_needs_program), Modifier.testTag("ways_each_needs_program"))
        if (!run.each) Note(stringResource(R.string.ways_each_off_help), Modifier.testTag("ways_each_off_help"))
    }
}

/** A program's row: the program as AA8 shows strings, its variables, its fingerprint, the advice; tap: its page. */
@Composable
private fun ProgramRow(state: ItemWaysUiState, p: RunProgram, index: Int, each: Boolean, actions: ItemWaysActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button) { actions.onOpenProgram(index) }
            .padding(vertical = Spacing.xs)
            .testTag("ways_program_$index"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ShownStringBox(p.program)
        Text(
            if (p.env.isEmpty()) stringResource(R.string.ways_program_no_variables) else p.env.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PinLine(state, p, actions.onShowPin)
        ProgramAdviceLines(p, each)
    }
}

@Composable
private fun ProgramAdviceLines(p: RunProgram, each: Boolean) {
    if (ProgramAdvice.batchFile(p.program)) Note(stringResource(R.string.ways_batch_file), Modifier.testTag("ways_batch_file"))
    if (!each && ProgramAdvice.runsCommands(p.program)) {
        Text(
            stringResource(R.string.ways_runs_commands, ShownString.inline(ItemWaysRules.lastElement(p.program))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("ways_runs_commands"),
        )
    }
}

/** The fingerprint line: recorded (16 digits, a tap shows all 64), forgotten when saved, or none yet. */
@Composable
private fun PinLine(state: ItemWaysUiState, p: RunProgram, onShow: (String?) -> Unit) {
    val sha = p.sha256
    when {
        sha != null && p.path != null -> Text(
            stringResource(R.string.ways_pin_recorded, Fingerprints.short(sha), ShownString.inline(p.path.orEmpty())),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable(role = Role.Button) { onShow(sha) }.testTag("ways_pin"),
        )
        state.pinForgotten(p) -> Note(stringResource(R.string.ways_pin_forgotten), Modifier.testTag("ways_pin_forgotten"))
        else -> Note(stringResource(R.string.ways_pin_none), Modifier.testTag("ways_pin_none"))
    }
}

/** A program's page: the program (changing it forgets the fingerprint), its variables, its fingerprint, Remove program. */
@Composable
private fun ProgramPage(state: ItemWaysUiState, p: RunProgram, index: Int, actions: ItemWaysActions) {
    val each = state.draft?.run?.each ?: true
    DetailCard(Modifier.testTag("ways_program_page")) {
        CardHeading(stringResource(R.string.ways_program_field))
        ShownStringBox(p.program)
        PlainField(
            value = state.editInput,
            onValue = actions.onEditInput,
            label = stringResource(R.string.ways_program_field),
            supporting = stringResource(R.string.ways_change_program_note),
            error = state.editProblem?.let { programProblemText(it) },
            onDone = actions.onApplyEdit,
            tag = "ways_program_edit",
        )
        val sha = p.sha256
        if (sha != null && p.path != null) {
            Text(
                stringResource(R.string.ways_pin_recorded, Fingerprints.short(sha), ShownString.inline(p.path.orEmpty())),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(role = Role.Button) { actions.onShowPin(sha) }.testTag("ways_pin"),
            )
            TextButton(onClick = { actions.onForgetPin(index) }, modifier = Modifier.testTag("ways_pin_forget")) {
                Text(stringResource(R.string.ways_pin_forget))
            }
        } else {
            PinLine(state, p, actions.onShowPin)
        }
        ProgramAdviceLines(p, each)
    }
    DetailCard(Modifier.testTag("ways_variables_card")) {
        CardHeading(stringResource(R.string.ways_variables_title))
        if (p.env.isEmpty()) Note(stringResource(R.string.ways_variables_none), Modifier.testTag("ways_variables_none"))
        p.env.forEach { name ->
            Row(Modifier.fillMaxWidth().testTag("ways_variable_$name"), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.onRemoveVariable(name) }) { Text(stringResource(R.string.ways_remove)) }
            }
        }
        Text(stringResource(R.string.ways_add_variable), style = MaterialTheme.typography.titleSmall)
        PlainField(
            value = state.variableInput,
            onValue = actions.onVariableInput,
            label = stringResource(R.string.ways_variable_field),
            supporting = stringResource(R.string.ways_variable_note),
            error = state.variableProblem?.let {
                stringResource(if (it == VariableProblem.FORM) R.string.ways_variable_form else R.string.ways_variable_too_many)
            },
            onDone = actions.onAddVariable,
            tag = "ways_variable_input",
        )
        SecondaryButton(
            stringResource(R.string.ways_add),
            actions.onAddVariable,
            enabled = state.variableInput.isNotEmpty(),
            modifier = Modifier.testTag("ways_variable_add"),
        )
    }
    Box(Modifier.padding(horizontal = Spacing.gutter)) {
        PrimaryButton(
            stringResource(R.string.ways_remove_program),
            { actions.onRemoveProgram(index) },
            destructive = true,
            modifier = Modifier.testTag("ways_program_remove"),
        )
    }
}

@Composable
private fun ProxyCard(state: ItemWaysUiState, d: ItemWays, actions: ItemWaysActions) {
    val proxy = d.proxy ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.addingHost) { if (state.addingHost) runCatching { focus.requestFocus() } }
    DetailCard(Modifier.testTag("ways_proxy_card")) {
        CardHeading(stringResource(R.string.ways_hosts_title))
        proxy.hosts.forEach { h ->
            Row(Modifier.fillMaxWidth().testTag("ways_host_$h"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ShownStringBox(h) }
                TextButton(onClick = { actions.onRemoveHost(h) }, enabled = proxy.hosts.size > 1) {
                    Text(stringResource(R.string.ways_remove))
                }
            }
        }
        if (proxy.hosts.size < ItemWaysRules.MAX_HOSTS) {
            Text(stringResource(R.string.ways_add_host), style = MaterialTheme.typography.titleSmall)
            PlainField(
                value = state.hostInput,
                onValue = actions.onHostInput,
                label = stringResource(R.string.ways_host_field),
                supporting = stringResource(R.string.ways_host_hint),
                error = state.hostProblem?.let { hostProblemText(it) },
                onDone = actions.onAddHost,
                tag = "ways_host_input",
                modifier = Modifier.focusRequester(focus),
            )
            SecondaryButton(
                stringResource(R.string.ways_add),
                actions.onAddHost,
                enabled = state.hostInput.isNotEmpty(),
                modifier = Modifier.testTag("ways_host_add"),
            )
        } else {
            Note(stringResource(R.string.ways_host_too_many))
        }
        PlainField(
            value = proxy.header,
            onValue = actions.onHeader,
            label = stringResource(R.string.ways_header_field),
            error = if (ItemWaysRules.headerValid(proxy.header)) null else stringResource(R.string.ways_header_bad),
            onDone = {},
            tag = "ways_header",
        )
        PlainField(
            value = proxy.prefix,
            onValue = actions.onPrefix,
            label = stringResource(R.string.ways_prefix_field),
            error = if (ItemWaysRules.prefixValid(proxy.prefix)) null else stringResource(R.string.ways_prefix_bad),
            onDone = {},
            tag = "ways_prefix",
        )
        Text(
            stringResource(R.string.ways_sent_as, proxy.header, markSpaces(proxy.prefix)),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("ways_sent_as"),
        )
        Note(stringResource(R.string.ways_sent_note))
    }
}

/** A space at the end of the prefix shown as a visible mark (AA7 "Before the key"). */
private fun markSpaces(s: String): String = s.replace(' ', '␣')

@Composable
private fun programProblemText(p: ProgramProblem): String = stringResource(
    when (p) {
        ProgramProblem.NOT_PATH_OR_NAME -> R.string.ways_program_not_path
        ProgramProblem.CHARACTERS -> R.string.ways_program_characters
        ProgramProblem.TOO_LONG -> R.string.ways_program_too_long
        ProgramProblem.PATH_PARTS -> R.string.ways_program_parts
        ProgramProblem.NETWORK_PATH -> R.string.ways_program_network
        ProgramProblem.TILDE -> R.string.ways_program_tilde
        ProgramProblem.DUPLICATE -> R.string.ways_program_duplicate
        ProgramProblem.TOO_MANY -> R.string.ways_program_too_many
    },
)

@Composable
private fun hostProblemText(p: HostProblem): String = stringResource(
    when (p) {
        HostProblem.NOT_A_HOST -> R.string.ways_host_not_host
        HostProblem.IP_ADDRESS -> R.string.ways_host_ip
        HostProblem.LOCAL -> R.string.ways_host_local
        HostProblem.WILDCARD -> R.string.ways_host_wildcard
        HostProblem.DUPLICATE -> R.string.ways_host_duplicate
        HostProblem.TOO_MANY -> R.string.ways_host_too_many
    },
)

@Composable
@Suppress("LongMethod")
private fun WaysDialogs(state: ItemWaysUiState, actions: ItemWaysActions) {
    val item = itemName(state.itemName)
    val agent = state.agentName
    when (val c = state.confirm) {
        WaysConfirm.RunOff -> ConfirmDialog(
            title = stringResource(R.string.ways_run_off_title, item),
            text = stringResource(
                if (state.read?.answerHasFolders == true) R.string.ways_run_off_body_folders else R.string.ways_run_off_body,
            ),
            confirmLabel = stringResource(R.string.ways_turn_off),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            dismissLabel = stringResource(R.string.ways_cancel),
            modifier = Modifier.testTag("ways_confirm_run_off"),
        )
        WaysConfirm.ProxyOff -> ConfirmDialog(
            title = stringResource(R.string.ways_proxy_off_title, item),
            text = stringResource(R.string.ways_proxy_off_body),
            confirmLabel = stringResource(R.string.ways_turn_off),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            dismissLabel = stringResource(R.string.ways_cancel),
            modifier = Modifier.testTag("ways_confirm_proxy_off"),
        )
        WaysConfirm.RevealOn -> ConfirmDialog(
            title = stringResource(R.string.ways_reveal_on_title, agent, item),
            text = stringResource(R.string.ways_reveal_on_body),
            confirmLabel = stringResource(R.string.ways_turn_on),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            dismissLabel = stringResource(R.string.ways_cancel),
            modifier = Modifier.testTag("ways_confirm_reveal_on"),
        )
        WaysConfirm.EachOff -> ConfirmDialog(
            title = stringResource(R.string.ways_each_off_title),
            text = stringResource(R.string.ways_each_off_body, agent),
            confirmLabel = stringResource(R.string.ways_turn_off),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            dismissLabel = stringResource(R.string.ways_cancel),
            modifier = Modifier.testTag("ways_confirm_each_off"),
        )
        is WaysConfirm.RemoveLastProgram -> ConfirmDialog(
            title = stringResource(R.string.ways_remove_last_title),
            text = stringResource(R.string.ways_remove_last_body, agent),
            confirmLabel = stringResource(R.string.ways_remove),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            dismissLabel = stringResource(R.string.ways_cancel),
            destructive = true,
            modifier = Modifier.testTag("ways_confirm_remove_last"),
        )
        is WaysConfirm.ForgetPin -> ConfirmDialog(
            title = stringResource(R.string.ways_pin_forget_title),
            text = stringResource(
                R.string.ways_pin_forget_body,
                state.draft?.run?.programs?.getOrNull(c.index)?.program
                    ?.let { ShownString.inline(ItemWaysRules.lastElement(it)) }.orEmpty(),
            ),
            confirmLabel = stringResource(R.string.ways_pin_forget_confirm),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            dismissLabel = stringResource(R.string.ways_cancel),
            modifier = Modifier.testTag("ways_confirm_forget_pin"),
        )
        null -> Unit
    }
    state.fullPin?.let { sha ->
        AlertDialog(
            onDismissRequest = { actions.onShowPin(null) },
            text = {
                Text(Fingerprints.full(sha), fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("ways_pin_full"))
            },
            confirmButton = { TextButton(onClick = { actions.onShowPin(null) }) { Text(stringResource(R.string.ways_pin_close)) } },
        )
    }
}

/** The unlock window prompt of a widening save (AA7 "Saving"): its own body text; the alarm with a link to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordSheet(state: ItemWaysUiState, p: WaysPrompt, actions: ItemWaysActions) {
    ModalBottomSheet(onDismissRequest = actions.onDismissPrompt, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl).testTag("ways_password_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                stringResource(R.string.ways_password_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.ways_password_body, state.agentName, itemName(state.itemName)),
                style = MaterialTheme.typography.bodyMedium,
            )
            val e = p.error
            SecretField(
                value = p.password,
                onValueChange = actions.onPassword,
                label = stringResource(R.string.ways_password_label),
                error = e?.let {
                    if (p.alarm) stringResource(R.string.ways_alarm) else failureText(it.messageRes(), it.vaultCode(p.errorCode))
                },
                onImeAction = actions.onSubmitPassword,
                modifier = Modifier.fillMaxWidth().testTag("ways_password"),
            )
            if (p.alarm) {
                TextButton(onClick = actions.onOpenAlarm, modifier = Modifier.testTag("ways_alarm")) {
                    Text(stringResource(R.string.ways_alarm_open))
                }
            }
            PrimaryButton(
                stringResource(R.string.ways_password_confirm),
                actions.onSubmitPassword,
                enabled = p.password.isNotEmpty() && !p.sending && !p.alarm,
                busy = p.sending,
                modifier = Modifier.testTag("ways_password_confirm"),
            )
            SecondaryButton(stringResource(R.string.ways_cancel), actions.onDismissPrompt, enabled = !p.sending)
        }
    }
}

@Composable
private fun WaySwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag("${tag}_switch"))
    }
}

@Composable
private fun CardHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
@Suppress("LongParameterList")
private fun PlainField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    onDone: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { t -> { Text(t) } },
        keyboardOptions = PLAIN,
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
        modifier = modifier.fillMaxWidth().excludeFromAutofill().testTag(tag),
    )
}

/** Left-to-right isolate and override (AA8: the box's text in its logical order, kept from reordering what is around it). */
private const val LRI = '⁦'
private const val LRO = '‭'
private const val PDF = '‬'
private const val PDI = '⁩'

/**
 * A string as AA8 "How a string is shown" draws it: in full in a monospace box, left to right and isolated, escapes in
 * a highlighted box, spaces marked, other than printable ASCII highlighted with the look-alike line under it.
 */
@Composable
fun ShownStringBox(s: String, modifier: Modifier = Modifier) {
    val pieces = remember(s) { ShownString.pieces(s) }
    val colors = MaterialTheme.colorScheme
    val escapedWord = stringResource(R.string.ways_string_escaped, "%s")
    val empty = stringResource(R.string.ways_string_empty)
    val shown: AnnotatedString = buildAnnotatedString {
        append(LRI)
        append(LRO)
        if (pieces.isEmpty()) {
            withStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)) { append(empty) }
        }
        pieces.forEach { p ->
            when (p) {
                is ShownPiece.Text -> if (p.plain) {
                    append(p.text)
                } else {
                    withStyle(SpanStyle(background = colors.secondaryContainer, color = colors.onSecondaryContainer)) { append(p.text) }
                }
                is ShownPiece.Escape ->
                    withStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)) { append(p.label) }
                ShownPiece.Space -> withStyle(SpanStyle(color = colors.outline)) { append('·') }
            }
        }
        append(PDF)
        append(PDI)
    }
    // What a screen reader says: the text, each escape as "escaped character U+202E".
    val spoken = if (pieces.isEmpty()) {
        empty
    } else {
        pieces.joinToString("") {
            when (it) {
                is ShownPiece.Text -> it.text
                is ShownPiece.Escape -> " ${escapedWord.replace("%s", it.label)} "
                ShownPiece.Space -> " "
            }
        }
    }
    Column(modifier) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, colors.outline),
                color = colors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    shown,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    softWrap = true,
                    modifier = Modifier.padding(Spacing.s).semantics { contentDescription = spoken }.testTag("shown_string"),
                )
            }
        }
        if (ShownString.lookalike(pieces)) {
            Note(stringResource(R.string.ways_string_lookalike), Modifier.testTag("shown_string_lookalike"))
        }
    }
}

/**
 * A save refused with `bad_request` (AA7 "Saving", 0.1.138): the vault's message as it is, in a monospace box, inside the
 * approved sentence; or, when [message] is "" (none, or not one [VaultReason] shows), the fallback text.
 */
@Composable
private fun RefusedCard(message: String) {
    val colors = MaterialTheme.colorScheme
    val text = if (message.isEmpty()) {
        AnnotatedString(stringResource(R.string.ways_bad_request_fallback))
    } else {
        // The approved sentence around its argument; the argument drawn as code.
        val (before, after) = stringResource(R.string.ways_bad_request, ARG).split(ARG, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        buildAnnotatedString {
            append(before)
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)) { append(message) }
            append(after)
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.error),
        modifier = Modifier.fillMaxWidth().testTag("ways_refused")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/** Stands for the vault's message while the approved sentence is split around it; never shown. */
private const val ARG = "\u0000"
