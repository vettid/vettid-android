package com.vettid.feature.approvals

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.devices.ProgramAdvice
import com.vettid.core.data.social.Approval
import com.vettid.core.data.devices.RunFolder
import com.vettid.core.data.social.HeldRequest
import com.vettid.core.data.social.RunFlag
import com.vettid.core.data.social.SecretHow
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.ShownStringBox
import com.vettid.core.ui.text.Fingerprints
import com.vettid.core.ui.text.ShownString
import com.vettid.core.ui.theme.Spacing

/**
 * An agent's run or proxy held for approval (ANDROID-PLAN 0.1.137 AA8): everything the vault holds, each string by the
 * string rule, the flags' warnings at the top and again above the buttons; **Approve** only once the content was scrolled
 * to its end, and never while another app draws over the screen. **Deny** and scrolling always work.
 */
@Composable
@Suppress("LongMethod")
internal fun AgentWayScreen(
    a: Approval.DeviceRequest,
    held: HeldShow,
    state: ApprovalDetailUiState,
    actions: DecisionActions,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val expired = a.exp?.let { !it.isAfter(now) } == true
    val who = deviceSentenceStart(a)
    val rows = wayRows(a, held, who)
    // The gate re-arms when what is shown changes (not by the member's own taps), and on a new opening; a rotation keeps it.
    val contentKey = "${a.key}:${WayKey.of(held)}"
    var reached by rememberSaveable(contentKey) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val first = rememberSaveable(a.key) { arrayOf(contentKey) }
    LaunchedEffect(contentKey, list) {
        // A change of what is shown (not the first showing, not a rotation) starts the reading again from the top, so
        // the end must be scrolled to anew.
        if (first[0] != contentKey) {
            first[0] = contentKey
            list.scrollToItem(0)
        }
        snapshotFlow { atEnd(list) }.collect { if (it) reached = true }
    }
    var obscured by remember { mutableStateOf(false) }
    HideOverlays()
    val large = LocalDensity.current.fontScale >= LARGE_FONT
    DetailScaffold(onBackClick = actions.onBack, modifier = modifier.testTag("agent_way")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The buttons' panel never takes the list's last 120 dp; its lines scroll within it, the buttons never do.
            // The panel's text lines never take the list's last 120 dp; the button row itself is never capped, so in a
            // window too small for both Deny still shows whole.
            val linesMax = (maxHeight - MIN_SCROLL - MIN_PANEL).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("agent_way_content"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    if (expired) {
                        item("expired") { Line(stringResource(R.string.approvals_way_expired), "agent_way_expired", warning = true) }
                    }
                    rows.forEach { (key, row) -> item(key) { Column(Modifier.padding(horizontal = Spacing.gutter)) { row() } } }
                    item("end") { Text("", Modifier.testTag("agent_way_end")) }
                }
                if (!expired) {
                    val gate = Gate(reached, enabled = reached && state.canApprove && !state.busy)
                    Buttons(state, actions, gate, large, obscured, linesMax) { obscured = it }
                }
            }
        }
    }
}

/** Whether the end was reached, and whether Approve is offered (also loaded, not busy). */
private class Gate(val reached: Boolean, val enabled: Boolean)

/**
 * The buttons, fixed below the content: Deny always; Approve after the end. A touch Android marks as seen through another
 * app's window is taken from Approve in the first pass, before the button sees it, for every event of the gesture.
 */
@Composable
@Suppress("LongParameterList")
private fun Buttons(
    state: ApprovalDetailUiState,
    actions: DecisionActions,
    gate: Gate,
    large: Boolean,
    obscured: Boolean,
    linesMax: androidx.compose.ui.unit.Dp,
    /** True for a touch seen through another app's window, false for a clean one (the line goes). */
    onObscured: (Boolean) -> Unit,
) {
    val hint = stringResource(R.string.approvals_way_scroll_hint)
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(
            Modifier.heightIn(max = linesMax).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (obscured) Line(stringResource(R.string.approvals_way_obscured), "agent_way_obscured", warning = true)
            state.error?.let { Line(failureText(it, state.retryUntil, state.limit, state.errorCode), "failure", warning = true) }
            if (!gate.reached && large) Line(hint, "agent_way_hint")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SecondaryButton(
                label = stringResource(R.string.approvals_deny),
                onClick = actions.onDeny,
                enabled = !state.busy,
                modifier = Modifier.weight(1f).testTag("agent_way_deny"),
            )
            Box(
                Modifier.weight(1f).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.motionEvent?.let(::obscuredTouch) == true) {
                                e.changes.forEach { it.consume() }
                                onObscured(true)
                            } else if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                onObscured(false)
                            }
                        }
                    }
                },
            ) {
                PrimaryButton(
                    label = stringResource(R.string.approvals_approve),
                    onClick = actions.onApprove,
                    enabled = gate.enabled,
                    modifier = Modifier.semantics { if (!gate.reached) stateDescription = hint }.testTag("agent_way_approve"),
                )
            }
        }
        if (!gate.reached && !large) Line(hint, "agent_way_hint")
    }
}

/** A touch Android marks as seen through another app's window: Approve takes no part of it (AA8 "Overlays"). */
internal fun obscuredTouch(e: MotionEvent): Boolean =
    e.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0

/** Other apps' overlays are hidden while this screen shows (API 31+, the app's minimum; `HIDE_OVERLAY_WINDOWS`). */
@Composable
private fun HideOverlays() {
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window) {
        // Without the permission (it is in this module's manifest) Approve still refuses obscured touches.
        val hidden = runCatching { window?.setHideOverlayWindows(true) }
            .onFailure { android.util.Log.w(TAG, "overlays not hidden: ${it.javaClass.simpleName}") }
            .isSuccess
        onDispose { if (hidden) runCatching { window?.setHideOverlayWindows(false) } }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The end is reached when the last row is laid out and its bottom edge is inside the list (which ends above the buttons). */
internal fun atEnd(list: LazyListState): Boolean {
    val info = list.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    return endReached(last.index, info.totalItemsCount, last.offset, last.size, info.viewportEndOffset)
}

/** Pure: the last item of [total] is laid out ([lastIndex]) and its bottom edge ([offset] + [size]) is inside the list. */
internal fun endReached(lastIndex: Int, total: Int, offset: Int, size: Int, viewportEnd: Int): Boolean =
    lastIndex == total - 1 && offset + size <= viewportEnd

/** The rows of a run or a proxy, in the plan's order, each with a stable key. */
@Composable
private fun wayRows(a: Approval.DeviceRequest, held: HeldShow, who: String): List<Pair<String, @Composable () -> Unit>> =
    when (held) {
        is HeldShow.Run -> runRows(held, who)
        is HeldShow.Proxy -> proxyRows(a, held, who)
        else -> emptyList()
    }

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun runRows(h: HeldShow.Run, who: String): List<Pair<String, @Composable () -> Unit>> {
    val r = h.run
    val empty = stringResource(com.vettid.core.ui.R.string.core_ui_string_empty)
    val warnings = runWarnings(r, empty)
    val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()
    if (r.replaces != null) rows += "again" to { Warn(stringResource(R.string.approvals_run_asked_again), "agent_way_asked_again") }
    rows += "title" to { Title(stringResource(R.string.approvals_run_title, who, AccountNames.isolate(h.item))) }
    warnings.forEachIndexed { i, w -> rows += "warn_top_$i" to { Warn(w.text, "agent_way_warning_top_$i", w.stress) } }
    rows += "summary" to { Line(runSummary(r), "agent_way_summary") }
    rows += "field" to { Line(stringResource(R.string.approvals_way_field, AccountNames.isolate(h.field)), "agent_way_field") }
    rows += "program" to {
        Heading(stringResource(R.string.approvals_run_program))
        ShownStringBox(r.program, Modifier.testTag("agent_way_program"))
        PinLine(r.sha256)
        if (ProgramAdvice.batchFile(r.program)) Line(stringResource(R.string.approvals_run_batch_file), "agent_way_batch_file")
    }
    if (r.argv.first() != r.program) {
        rows += "asked_as" to {
            Line(stringResource(R.string.approvals_run_asked_as), "agent_way_asked_as_label")
            ShownStringBox(r.argv.first(), Modifier.testTag("agent_way_asked_as"))
        }
    }
    rows += "args" to { Heading(stringResource(R.string.approvals_run_arguments)) }
    val args = r.argv.drop(1)
    if (args.isEmpty()) rows += "no_args" to { Line(stringResource(R.string.approvals_run_no_arguments), "agent_way_no_arguments") }
    args.forEachIndexed { i, arg ->
        rows += "arg_$i" to {
            Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
            ShownStringBox(arg, Modifier.testTag("agent_way_arg_$i"))
            if (r.how == SecretHow.FILE && arg == SECRET_FILE) {
                Line(stringResource(R.string.approvals_run_secret_file), "agent_way_secret_file_$i")
            }
        }
    }
    rows += "cwd" to {
        Heading(stringResource(R.string.approvals_run_runs_in))
        r.cwd?.let { ShownStringBox(it, Modifier.testTag("agent_way_cwd")) }
            ?: Line(stringResource(R.string.approvals_run_new_folder), "agent_way_new_folder")
        // AA9: the matched entry, from the approval's `folders.cwd`.
        r.cwdEntry?.let { e -> Line(folderLine(e, empty), "agent_way_folder") }
    }
    rows += filesRows(r, empty)
    rows += "how" to {
        Heading(stringResource(R.string.approvals_run_secret_given))
        val name = ShownString.inline(r.name.orEmpty(), empty)
        Line(
            when (r.how) {
                SecretHow.ENV -> stringResource(R.string.approvals_run_how_env, name)
                SecretHow.STDIN -> stringResource(R.string.approvals_run_how_stdin)
                SecretHow.FILE -> stringResource(R.string.approvals_run_how_file, name)
            },
            "agent_way_how",
        )
    }
    rows += "env" to { Heading(stringResource(R.string.approvals_run_other_variables)) }
    if (r.env.isEmpty()) rows += "no_env" to { Line(stringResource(R.string.approvals_run_none), "agent_way_no_env") }
    r.env.forEachIndexed { i, (k, v) ->
        rows += "env_$i" to {
            Line(ShownString.inline(k, empty), "agent_way_env_name_$i")
            ShownStringBox(v, Modifier.testTag("agent_way_env_$i"))
        }
    }
    val prevPath = r.previousPath
    val prevSha = r.previousSha256
    if (RunFlag.PROGRAM_CHANGED in r.flags && prevSha != null && prevPath != null) {
        rows += "before" to {
            Heading(stringResource(R.string.approvals_run_approved_before))
            ShownStringBox(prevPath, Modifier.testTag("agent_way_before_path"))
            Line(Fingerprints.full(prevSha), "agent_way_before_pin", mono = true)
        }
        rows += "now" to {
            Heading(stringResource(R.string.approvals_run_now))
            ShownStringBox(r.program, Modifier.testTag("agent_way_now_path"))
            Line(Fingerprints.full(r.sha256), "agent_way_now_pin", mono = true)
        }
    }
    warnings.forEachIndexed { i, w -> rows += "warn_end_$i" to { Warn(w.text, "agent_way_warning_end_$i", w.stress) } }
    return rows
}

/** "Folder: <path> · Read only" or "· Can change files" (AA9), the entry's path by the string rule's inline form. */
@Composable
private fun folderLine(e: RunFolder, empty: String): String = stringResource(
    if (e.write) R.string.approvals_run_folder_can_change else R.string.approvals_run_folder_read_only,
    ShownString.inline(e.path, empty),
)

/**
 * AA9's files, from the approval: "Copied in, read only:" and each input's name (escaped as an argument is), size and the
 * folder it comes from; "Copied back to <folder> when it ends:" with the output folder's entry and the names.
 */
@Composable
private fun filesRows(r: HeldRequest.ItemRun, empty: String): List<Pair<String, @Composable () -> Unit>> {
    val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()
    if (r.inputs.isNotEmpty()) rows += "copied_in" to { Heading(stringResource(R.string.approvals_run_copied_in)) }
    r.inputs.forEachIndexed { i, f ->
        rows += "input_$i" to {
            Line(stringResource(R.string.approvals_run_input, ShownString.inline(f.name, empty), sizeText(f.size)), "agent_way_input_$i")
            ShownStringBox(f.folder, Modifier.testTag("agent_way_input_folder_$i"))
        }
    }
    r.output?.let { o ->
        rows += "copied_back" to {
            Heading(stringResource(R.string.approvals_run_copied_back, ShownString.inline(o.folder, empty)))
            Line(folderLine(o.entry, empty), "agent_way_output_entry")
        }
        o.names.forEachIndexed { i, n -> rows += "output_$i" to { ShownStringBox(n, Modifier.testTag("agent_way_output_$i")) } }
    }
    return rows
}

/** A warning's text and the part of it that is stressed ("moved to <path>"), if any. */
private class Warning(val text: String, val stress: String? = null)

/** One line per flag, each with its full warning, then the #636 line when it applies (AA8 "A run"). */
@Composable
private fun runWarnings(r: HeldRequest.ItemRun, empty: String): List<Warning> = buildList {
    val fp = Fingerprints.short(r.sha256)
    if (RunFlag.SHELL in r.flags) add(Warning(stringResource(R.string.approvals_run_warn_shell)))
    if (RunFlag.INLINE_CODE in r.flags) add(Warning(stringResource(R.string.approvals_run_warn_inline_code)))
    if (RunFlag.UNBOUND_PROGRAM in r.flags) add(Warning(stringResource(R.string.approvals_run_warn_unbound)))
    if (RunFlag.PROGRAM_UNRECORDED in r.flags) add(Warning(stringResource(R.string.approvals_run_warn_unrecorded, fp)))
    if (RunFlag.PROGRAM_CHANGED in r.flags) {
        val before = Fingerprints.short(r.previousSha256.orEmpty())
        if (r.previousPath == r.program) {
            add(Warning(stringResource(R.string.approvals_run_warn_changed, before, fp)))
        } else {
            val path = ShownString.inline(r.program, empty)
            val was = ShownString.inline(r.previousPath.orEmpty(), empty)
            val text = stringResource(R.string.approvals_run_warn_moved, path, was, before, fp)
            add(Warning(text, stress = stringResource(R.string.approvals_run_moved_to, path)))
        }
    }
    if (ProgramAdvice.runsCommands(r.program)) add(Warning(stringResource(R.string.approvals_run_runs_commands)))
    // AA9: a run in a member folder; files copied back (its warning names the output folder).
    if (RunFlag.MEMBER_FOLDER in r.flags) add(Warning(stringResource(R.string.approvals_run_warn_member_folder)))
    r.output?.let { add(Warning(stringResource(R.string.approvals_run_warn_member_files, ShownString.inline(it.folder, empty)))) }
}

/** "<N> arguments, <M> variables, <size> in all" (`argv` after the first; the bytes of all of `argv` and `env`). */
@Composable
private fun runSummary(r: HeldRequest.ItemRun): String {
    val args = r.argv.size - 1
    val vars = r.env.size
    return stringResource(
        R.string.approvals_run_summary,
        pluralStringResource(R.plurals.approvals_run_arguments_count, args, args),
        pluralStringResource(R.plurals.approvals_run_variables_count, vars, vars),
        sizeText(WaySizes.runBytes(r)),
    )
}

@Composable
private fun sizeText(bytes: Long): String = when (val u = WaySizes.unit(bytes)) {
    is WaySizes.Size.Bytes -> pluralStringResource(R.plurals.approvals_way_bytes, u.n.toInt(), u.n.toInt())
    is WaySizes.Size.Kb -> stringResource(R.string.approvals_way_kb, u.text)
    is WaySizes.Size.Mb -> stringResource(R.string.approvals_way_mb, u.text)
}

@Composable
private fun proxyRows(a: Approval.DeviceRequest, h: HeldShow.Proxy, who: String): List<Pair<String, @Composable () -> Unit>> {
    val p = h.proxy
    val empty = stringResource(com.vettid.core.ui.R.string.core_ui_string_empty)
    val host = ShownString.inline(p.host, empty)
    val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()
    if (p.replaces != null) rows += "again" to { Warn(stringResource(R.string.approvals_proxy_asked_again), "agent_way_asked_again") }
    rows += "title" to {
        Title(stringResource(R.string.approvals_proxy_title, who, AccountNames.isolate(h.item), host, proxyLength(p.seconds)))
    }
    rows += "body" to {
        Line(stringResource(R.string.approvals_proxy_body, host), "agent_way_proxy_body")
        Line(stringResource(R.string.approvals_proxy_ends_sooner, deviceSentenceInline(a)), "agent_way_ends_sooner")
    }
    rows += "host" to { ShownStringBox(p.host, Modifier.testTag("agent_way_host")) }
    rows += "field" to { Line(stringResource(R.string.approvals_way_field, AccountNames.isolate(h.field)), "agent_way_field") }
    rows += "sent_as" to {
        val header = ShownString.inline(p.header, empty)
        val prefix = if (p.prefix.isEmpty()) "" else ShownString.inline(p.prefix, "")
        Line(stringResource(R.string.approvals_proxy_sent_as, header, prefix), "agent_way_sent_as")
    }
    return rows
}

/** The fingerprint's first 16 digits; a tap shows all 64 (the member's own action: the gate stays as it is). */
@Composable
private fun PinLine(sha256: String) {
    var full by remember(sha256) { mutableStateOf(false) }
    Text(
        stringResource(R.string.approvals_run_fingerprint, if (full) Fingerprints.full(sha256) else Fingerprints.short(sha256)),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable(role = Role.Button) { full = !full }.testTag("agent_way_pin"),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }.testTag("agent_way_title"))
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }.padding(top = Spacing.s))
}

@Composable
private fun Warn(text: String, tag: String, stress: String? = null) = Line(text, tag, warning = true, stress = stress)

@Composable
private fun Line(text: String, tag: String, warning: Boolean = false, mono: Boolean = false, stress: String? = null) {
    val at = stress?.let { text.indexOf(it) } ?: -1
    Text(
        buildAnnotatedString {
            append(text)
            if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + stress!!.length)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        fontFamily = if (mono) FontFamily.Monospace else null,
        modifier = Modifier.testTag(tag),
    )
}

private const val TAG = "AgentWayScreen"

/** `{secret_file}`, the argument a `file` run puts the secret file's path in. */
private const val SECRET_FILE = "{secret_file}"

/** The font scale from which the hint goes above the buttons. */
private const val LARGE_FONT = 1.5f

/** The least height the scrolled content keeps (AA8, at the largest font scale). */
private val MIN_SCROLL = 120.dp

/** What the button row, its padding and a navigation bar take, kept out of the lines' share. */
private val MIN_PANEL = 96.dp
