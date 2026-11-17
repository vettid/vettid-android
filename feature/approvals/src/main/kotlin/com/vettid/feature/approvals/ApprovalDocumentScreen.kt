package com.vettid.feature.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.social.Approval
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.VettIdBackTopBar
import com.vettid.core.ui.theme.Spacing

/** The list's items before the document's rows: the title and the request's facts. */
internal const val ROWS_FROM = 1

/** With the keyboard up, the least share of the window's height the document's list keeps. */
internal const val LIST_MIN_FRACTION = 0.25f

/** The list of an approval's document (for tests: it scrolls). */
const val DOCUMENT_LIST_TAG = "document_list"

/**
 * A member authentication that carries a document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"): "<name> asks you to
 * approve", the request's facts, then the document's parts in a lazy list, each under the app's own label and each value in a
 * block of its own; or the document's loading, its failed fetch with Try again, or "This request can't be shown", Deny only.
 * Approve is enabled only once every row of the document has been on screen whole, from its top down ([SeenCoverage] per
 * row: a jump to the end leaves the rows between unseen), and another text size or width starts that again. The password
 * field, the hint and the choices stay below the list (always composed, so the owner check still blanks the password).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun DocumentApprovalScreen(
    a: Approval.Authentication,
    state: ApprovalDetailUiState,
    actions: DecisionActions,
    modifier: Modifier,
    /** The keyboard is up (a parameter so tests can say so: Robolectric has no keyboard insets). */
    imeVisible: Boolean = WindowInsets.isImeVisible,
    /** A screen reader explores by touch (TalkBack): the document is cut smaller ([DocCutting]). */
    screenReader: Boolean = rememberTouchExploration(),
) {
    val title = titleOf(a)
    val step = state.document
    val shown = step as? DocumentStep.Shown
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    val sha = shown?.document?.sha256
    val generation = state.documentGeneration
    val layout = Triple(density.density, density.fontScale, width)
    // With a screen reader, halved for each row still taller than the list; at the smallest, "can't be shown at this text
    // size" (as iOS). Another document, text size, width or reader starts from the usual cutting again.
    var shrink by remember(sha, generation, layout, screenReader) { mutableIntStateOf(0) }
    var tooTall by remember(sha, generation, layout, screenReader) { mutableStateOf(false) }
    val cutting = DocCutting.of(screenReader, shrink)
    val cut = "${if (screenReader) "r" else "u"}$shrink"
    val rows = remember(shown, cutting) { shown?.let { DocumentRows.of(it.presentation, cutting) }.orEmpty() }
    var seenHere by remember(sha, generation, layout, cut) { mutableStateOf(false) }
    val report by rememberUpdatedState(actions.onDocumentSeen)
    // The showing last brought to the top: a new one (a new document after Changed, the same hash sent again) starts there.
    var toppedFor by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(sha, generation, layout, cut, rows) {
        if (sha == null) return@LaunchedEffect
        // Nothing of this showing seen yet, whatever an earlier one (another text size, width or composition) saw: said
        // before anything else, so that nothing reads as seen for a frame.
        report(sha, generation, false)
        if (width == 0) return@LaunchedEffect
        if (toppedFor != generation) {
            listState.scrollToItem(0)
            toppedFor = generation
        }
        val coverage = HashMap<Int, SeenCoverage>()
        val whole = HashSet<Int>()
        // The facts, the rows and the end: a layout of another list (an earlier showing, a step before Shown) is no credit.
        val expected = ROWS_FROM + rows.size + 1
        snapshotFlow { listState.layoutInfo }.collect { info ->
            if (info.totalItemsCount != expected) return@collect
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            for (item in info.visibleItemsInfo) {
                // Only the rows of this showing and cutting count, by their keys.
                val row = rowOf(item.key, generation, cut)?.takeIf { it in rows.indices && it !in whole } ?: continue
                // With a screen reader a row of the document's text taller than the list is never seen: moving from node
                // to node would leave part of it off screen. Cut smaller, or at the smallest say so (Approve stays off).
                if (screenReader && rows[row].isContent && item.size > viewport) {
                    if (cutting.smallest) tooTall = true else shrink += 1
                    return@collect
                }
                val top = maxOf(info.viewportStartOffset - item.offset, 0).toFloat()
                val bottom = minOf(info.viewportEndOffset - item.offset, item.size).toFloat()
                val c = (coverage[row] ?: SeenCoverage()).saw(if (top < bottom) top..bottom else null, item.size.toFloat())
                coverage[row] = c
                if (c.whole) whole += row
            }
            if (!seenHere && rows.isNotEmpty() && whole.size == rows.size) {
                seenHere = true
                report(sha, generation, true)
            }
        }
    }
    val seen = seenHere && state.documentToApprove != null
    Surface(
        modifier.fillMaxSize().semantics { paneTitle = title }.testTag("approval_detail"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            VettIdBackTopBar(onBackClick = actions.onBack)
            // With the keyboard up the list keeps at least LIST_MIN_FRACTION of the window's height (at most half the room
            // above the keyboard), and the panel below scrolls in what is left.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val window = LocalConfiguration.current.screenHeightDp.dp
                val minList = if (imeVisible) minOf(window * LIST_MIN_FRACTION, maxHeight / 2) else 0.dp
                val panelMax = (maxHeight - minList).coerceAtLeast(0.dp)
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { width = it.width }.testTag(DOCUMENT_LIST_TAG),
                    ) {
                        item(key = "facts") {
                            Column(Modifier.padding(horizontal = Spacing.xl)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.m).semantics { heading() },
                                )
                                Facts(a)
                            }
                        }
                        when (step) {
                            is DocumentStep.Shown -> itemsIndexed(rows, key = { i, _ -> rowKey(generation, cut, i) }) { _, row ->
                                DocRowView(row, a)
                            }
                            DocumentStep.Loading, null -> item(key = "loading") {
                                Row(
                                    Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l).semantics(mergeDescendants = true) {}
                                        .testTag("document_loading"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(Spacing.s))
                                    Text(stringResource(R.string.approvals_doc_loading), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            is DocumentStep.Failed -> item(key = "failed") {
                                Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l)) {
                                    Text(
                                        failureText(step.kind, null, null, step.code),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.testTag("document_failure"),
                                    )
                                    TextButton(onClick = actions.onRetryDocument, modifier = Modifier.testTag("document_retry")) {
                                        Text(stringResource(R.string.approvals_doc_retry))
                                    }
                                }
                            }
                            is DocumentStep.Unshowable -> item(key = "cant_show") {
                                NoticeCard(
                                    kind = NoticeKind.WARNING,
                                    title = "",
                                    body = stringResource(R.string.approvals_doc_cant_show),
                                    modifier = Modifier
                                        .padding(horizontal = Spacing.xl, vertical = Spacing.l)
                                        .testTag("document_cant_show"),
                                )
                            }
                        }
                        item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = panelMax)
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        // With the keyboard up the hint goes (Approve's own reading still says it), so the document keeps room.
                        if (shown != null && !seen && tooTall) {
                            // Never silent: a row that cannot be read whole is never counted as seen.
                            NoticeCard(
                                kind = NoticeKind.WARNING,
                                title = stringResource(R.string.approvals_doc_too_tall),
                                body = stringResource(R.string.approvals_doc_too_tall_body),
                                modifier = Modifier.testTag("document_too_tall"),
                            )
                        } else if (shown != null && !seen && !imeVisible) {
                            Text(
                                stringResource(R.string.approvals_doc_read_to_end),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("document_read_to_end"),
                            )
                        }
                        if (!state.onlyDeny) {
                            SecretField(
                                value = state.password,
                                onValueChange = actions.onPassword,
                                label = stringResource(R.string.approvals_password),
                                // With the keyboard up the caption goes too.
                                supporting = stringResource(R.string.approvals_password_hint).takeUnless { imeVisible },
                                // The keyboard's action goes through the same guard as the button.
                                onImeAction = { if (seen) actions.onApprove() },
                                modifier = Modifier.testTag("approval_password"),
                            )
                        }
                        state.error?.let {
                            Text(
                                failureText(it, state.retryUntil, state.limit, state.errorCode),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("failure"),
                            )
                        }
                        if (!state.onlyDeny) {
                            // Disabled because the document was not seen whole: TalkBack says why, in the hint's own words.
                            val readToEnd = stringResource(R.string.approvals_doc_read_to_end)
                            PrimaryButton(
                                stringResource(R.string.approvals_auth_purpose_approve),
                                { if (seen) actions.onApprove() },
                                enabled = state.canApprove && seen && !state.busy,
                                busy = state.busy,
                                modifier = if (seen) Modifier else Modifier.semantics { stateDescription = readToEnd },
                            )
                        }
                        TextButton(
                            onClick = actions.onDeny,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).testTag("deny_button"),
                        ) {
                            Text(stringResource(R.string.approvals_deny), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** One row of a document. */
@Composable
private fun DocRowView(row: DocRow, a: Approval.Authentication) {
    val gutter = Modifier.padding(horizontal = Spacing.xl)
    val colors = MaterialTheme.colorScheme
    when (row) {
        is DocRow.Heading -> Text(
            stringResource(row.label),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = gutter.padding(top = Spacing.l, bottom = Spacing.s).semantics { heading() },
        )
        // A form's label is a heading too, so TalkBack tells it from the value under it (no new words).
        is DocRow.Label -> Text(
            stringResource(row.label),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = gutter.padding(top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
        )
        is DocRow.Note -> Text(
            stringResource(row.text),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = gutter.padding(top = Spacing.s),
        )
        DocRow.NoneLabel -> Text(
            stringResource(R.string.approvals_doc_none),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = gutter,
        )
        DocRow.UnknownWarning -> NoticeCard(
            kind = NoticeKind.WARNING,
            title = "",
            body = stringResource(
                R.string.approvals_doc_unknown_warning,
                AccountNames.isolateOrNull(a.connectionName) ?: stringResource(R.string.approvals_a_connection),
            ),
            modifier = gutter.padding(top = Spacing.s).testTag("document_unknown"),
        )
        DocRow.Abstain -> Row(
            gutter.padding(top = Spacing.xs).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.approvals_doc_abstain), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.approvals_doc_abstain_added),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        is DocRow.Title -> Block(first = true, last = true) {
            Text(stringResource(titleRes(row.action)), style = MaterialTheme.typography.bodyLarge)
        }
        is DocRow.Ballots -> Block(first = true, last = true) {
            // The count itself, never clamped, as the format's argument; the plural chosen from an Int with its low digits.
            Text(
                pluralStringResource(R.plurals.approvals_doc_ballots, pluralQuantity(row.count), row.count),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is DocRow.Word -> Block(first = true, last = true) {
            Text(stringResource(row.text), style = MaterialTheme.typography.bodyLarge)
        }
        is DocRow.Time -> Block(first = true, last = true, merged = true) {
            val t = remember(row.seconds) { DocumentTime.shown(row.seconds) }
            Text(t.local, style = MaterialTheme.typography.bodyLarge)
            Text(t.utc, style = MaterialTheme.typography.bodyMedium)
        }
        is DocRow.Value -> {
            val spoken = DocSpoken.of(row.piece, raw = false, spokenLevel())
            Block(row.first, row.last, Modifier.clearAndSetSemantics { text = spoken }) {
                Text(
                    pieceText(row.piece, raw = false, docStyle()),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                )
            }
        }
        is DocRow.Raw -> {
            val spoken = DocSpoken.of(row.piece, raw = true, spokenLevel())
            // The raw view's base direction is left-to-right, whatever its isolates hold.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Block(row.first, row.last, Modifier.clearAndSetSemantics { text = spoken }) {
                    Text(
                        pieceText(row.piece, raw = true, docStyle()),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            textDirection = TextDirection.Ltr,
                        ),
                    )
                }
            }
        }
    }
}

/** A row of a value block: the block's fill, rounded at its first and last row only, so that its rows join into one block. */
@Composable
private fun Block(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    merged: Boolean = false,
    content: @Composable () -> Unit,
) {
    val r = 8.dp
    val top = if (first) r else 0.dp
    val bottom = if (last) r else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(top = if (first) Spacing.xs else 0.dp)) {
        Column(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .padding(horizontal = Spacing.l)
                .padding(top = if (first) Spacing.m else 0.dp, bottom = if (last) Spacing.m else 0.dp)
                .then(if (merged) Modifier.semantics(mergeDescendants = true) {} else Modifier),
        ) { content() }
    }
}

/** How the document's text is drawn: markers boxed (filled), underlined and monospaced, so the cue is not color alone. */
private class DocStyle(val marker: Color, val markerFill: Color, val stringFill: Color, val continuation: Color)

@Composable
private fun docStyle(): DocStyle {
    val c = MaterialTheme.colorScheme
    return DocStyle(
        marker = c.error,
        markerFill = c.error.copy(alpha = MARKER_FILL_ALPHA),
        stringFill = c.secondaryContainer,
        continuation = c.primary,
    )
}

private const val MARKER_FILL_ALPHA = 0.14f

/** "level N", as TalkBack reads a raw view's line. */
@Composable
private fun spokenLevel(): (Int) -> String {
    val level = stringResource(R.string.approvals_doc_level_spoken)
    return { level.format(it) }
}

/** Whether a screen reader explores by touch (TalkBack), followed while the screen is shown. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val am = remember(context) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var on by remember(am) { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    androidx.compose.runtime.DisposableEffect(am) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(listener)
        onDispose { am?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

private const val LRM = "\u200E"
private const val FSI = "\u2068"
private const val PDI = "\u2069"

/**
 * A piece as drawn. A raw view's piece starts with a left-to-right mark, so its base direction is left-to-right whatever its
 * isolates hold; its keys and strings sit on their own fill inside their isolates (U+2068 … U+2069), the punctuation and
 * numbers outside them.
 */
@Suppress("CyclomaticComplexMethod") // one case per kind of token
private fun pieceText(p: DocPiece, raw: Boolean, style: DocStyle): AnnotatedString = buildAnnotatedString {
    if (raw) append(LRM)
    var isolated = false
    for (t in p.tokens) {
        when (t) {
            is DocPiece.Token.Punct -> if (t.text == DocPiece.CONTINUED || t.text == DocPiece.CONTINUES) {
                withStyle(SpanStyle(color = style.continuation, fontWeight = FontWeight.Bold)) { append(t.text) }
            } else {
                append(t.text)
            }
            is DocPiece.Token.Literal -> append(t.text)
            is DocPiece.Token.Indent -> append("  ".repeat(t.levels))
            is DocPiece.Token.Open -> {
                isolated = true
                append(FSI)
            }
            is DocPiece.Token.Close -> {
                isolated = false
                append(PDI)
            }
            is DocPiece.Token.Content -> if (isolated) {
                withStyle(SpanStyle(background = style.stringFill)) { append(t.text) }
            } else {
                append(t.text)
            }
            is DocPiece.Token.Marker -> withStyle(
                SpanStyle(
                    color = style.marker,
                    background = style.markerFill,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                ),
            ) { append(ShownText.marker(t.codePoint)) }
            DocPiece.Token.LineBreak -> append('\n')
        }
    }
}

/**
 * A document row's item key: its showing, its cutting and its index, so an item of an earlier showing or cutting is never
 * counted for this one.
 */
internal fun rowKey(generation: Int, cut: String, index: Int): String = "$generation.$cut-$index"

/** The index of the row of [generation] and [cut] whose item key is [key], or null for any other item. */
internal fun rowOf(key: Any, generation: Int, cut: String): Int? = (key as? String)?.removePrefix("$generation.$cut-")
    ?.takeIf { it != key }?.toIntOrNull()

/**
 * The plural quantity for a count of [n] (0 to 2^53 - 1): [n] itself when it fits an Int, else a number past 2^30 with the
 * same last nine digits, which plural rules (by the last digits, or "large") read the same way.
 */
internal fun pluralQuantity(n: Long): Int =
    if (n <= Int.MAX_VALUE) n.toInt() else (n % PLURAL_DIGITS + PLURAL_DIGITS).toInt()

private const val PLURAL_DIGITS = 1_000_000_000L

/** The app's own title for a known action (§10.4). */
internal fun titleRes(a: DocumentPresentation.KnownAction): Int = when (a) {
    DocumentPresentation.KnownAction.PROPOSAL_CREATE -> R.string.approvals_doc_title_proposal_create
    DocumentPresentation.KnownAction.VOTE_EXTEND -> R.string.approvals_doc_title_vote_extend
    DocumentPresentation.KnownAction.ADMIN_SET_ADD -> R.string.approvals_doc_title_admin_set_add
    DocumentPresentation.KnownAction.ADMIN_SET_REMOVE -> R.string.approvals_doc_title_admin_set_remove
    DocumentPresentation.KnownAction.ADMIN_REPIN -> R.string.approvals_doc_title_admin_repin
    DocumentPresentation.KnownAction.CONTRACT_TEMPLATE_CHANGE -> R.string.approvals_doc_title_contract_template_change
    DocumentPresentation.KnownAction.ASSIGNMENT_PUSH -> R.string.approvals_doc_title_assignment_push
}
