package com.vettid.feature.votes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.CheckState
import com.vettid.core.data.votes.GaveUpWhy
import com.vettid.core.data.votes.ResultsOutcome
import com.vettid.core.data.votes.VoteDetail
import com.vettid.core.data.votes.VoteNote
import com.vettid.core.data.votes.VoteRules
import com.vettid.core.data.votes.VoteState
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.VettIdTheme
import java.time.Instant

/** What the vote screen can ask for. */
data class VoteActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onCast: () -> Unit = {},
    val onConfirmCast: () -> Unit = {},
    val onDismissCast: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onSubmitPassword: () -> Unit = {},
    val onCheck: () -> Unit = {},
    val onAnswerRestore: (Boolean) -> Unit = {},
    val onSaveProof: () -> Unit = {},
    val onDismissProofWarning: () -> Unit = {},
    val onFetchProof: () -> Unit = {},
    val onSaveProofTo: (String?) -> Unit = {},
    val onProofShown: () -> Unit = {},
    val onRelease: () -> Unit = {},
    val onDismissRelease: () -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onConfirmRelease: () -> Unit = {},
    val onDismissCastProblem: () -> Unit = {},
    val proofFileName: () -> String = { "vettid-vote.json" },
)

/** `ACTION_CREATE_DOCUMENT` with a JSON type and the suggested name (the Storage Access Framework's "Save to…"). */
internal object CreateProofDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

/**
 * One vote (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes", screens 2 to 7). The service's words are shown as plain text; the
 * disclosure sentence is the app's own and fixed. The window is held out of screenshots and the recent-apps preview
 * (`FLAG_SECURE`): the vote's choice is shown here.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun VoteScreen(state: VoteUiState, actions: VoteActions, modifier: Modifier = Modifier, now: Instant = Instant.now()) {
    SecureWindow()
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.vote_proof_saved)
    val saveFailed = stringResource(R.string.vote_proof_failed)
    LaunchedEffect(state.proofSaved, state.proofSaveFailed) {
        val text = if (state.proofSaved) saved else if (state.proofSaveFailed) saveFailed else return@LaunchedEffect
        actions.onProofShown()
        snackbar.showSnackbar(text)
    }
    val launcher = rememberLauncherForActivityResult(CreateProofDocument) { uri -> actions.onSaveProofTo(uri?.toString()) }
    LaunchedEffect(state.proofReady) { if (state.proofReady) launcher.launch(actions.proofFileName()) }
    DetailScaffold(
        onBackClick = actions.onBack,
        modifier = modifier,
        overlay = { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Spacing.l)) },
    ) {
        val s = state.summary
        when {
            s == null && state.loadError != null -> Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                val e = state.loadError
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.vote_error_title),
                    body = failureText(e.kind.messageRes(), e.kind.vaultCode(e.shownCode)),
                    modifier = Modifier.testTag("vote_load_failed"),
                    actions = { TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.votes_retry)) } },
                )
            }
            s == null && state.loaded -> Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                NoticeCard(
                    kind = NoticeKind.INFO,
                    title = stringResource(R.string.vote_gone_title),
                    body = stringResource(R.string.votes_empty_body),
                    modifier = Modifier.testTag("vote_gone"),
                )
            }
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            else -> VoteContent(state, actions, now)
        }
    }
    // While a cast is on its way the sheet it was started from stays, busy; a password is held only while the request runs.
    val sendingWithPassword = state.cast == CastStep.SENDING && state.password.isNotEmpty()
    if (state.cast == CastStep.CONFIRM || state.cast == CastStep.SENDING && !sendingWithPassword) ConfirmSheet(state, actions)
    if (state.cast == CastStep.PASSWORD || sendingWithPassword) PasswordSheet(state, actions, now)
    if (state.restoreAsked) RestoreDialog(state, actions)
    if (state.proofWarning) {
        ConfirmDialog(
            title = stringResource(R.string.vote_save_proof),
            text = stringResource(R.string.vote_save_proof_warning),
            confirmLabel = stringResource(R.string.vote_proof_save),
            onConfirm = actions.onFetchProof,
            onDismiss = actions.onDismissProofWarning,
        )
    }
    if (state.releaseDialog) ReleaseDialog(state, actions)
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun VoteContent(state: VoteUiState, actions: VoteActions, now: Instant) {
    val s = checkNotNull(state.summary)
    val d = state.detail
    val resources = rememberResources()
    val service = AccountNames.isolateOrNull(state.serviceName) ?: stringResource(R.string.vote_service_fallback)
    val title = AccountNames.isolateOrNull(s.title).orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        LargeTitle(title)
        // The service, as the connection shows it: its name with the label, and that the name is the service's own.
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            Text(
                "$service · ${stringResource(R.string.vote_service_label)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("vote_service"),
            )
            Text(
                stringResource(R.string.vote_name_by_service),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (d != null) ProposalBody(d, service, s.closesAt?.let { Times.full(it) }, state)
        // The states in words, in order; a failure that arrives while the screen is open is announced.
        val notes = VoteRules.notes(
            s,
            gaveUp = state.hint.gaveUp,
            resultsProblem = state.hint.resultsProblem,
            notListedLate = state.hint.notListedLate(now),
        )
        val time = s.opensAt?.let { Times.full(it) }.orEmpty()
        Column(
            Modifier.padding(horizontal = Spacing.gutter).semantics { liveRegion = LiveRegionMode.Polite }.testTag("vote_notes"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            for (n in notes) NoteView(n, resources, service, time, s.reason)
            state.castProblem?.let { CastProblem(it, actions.onDismissCastProblem, resources, service, time) }
            state.checkProblem?.let { p ->
                NoticeCard(
                    NoticeKind.WARNING,
                    stringResource(R.string.vote_error_title),
                    failureText(p.kind.messageRes(), p.kind.vaultCode(p.shownCode)),
                )
            }
            when (state.checkOutcome) {
                ResultsOutcome.SiteUnreachable ->
                    NoticeCard(
                        NoticeKind.INFO,
                        stringResource(R.string.vote_check_with_service),
                        stringResource(R.string.vote_check_site_unreachable_now),
                    )
                ResultsOutcome.NotPublished ->
                    NoticeCard(
                        NoticeKind.INFO,
                        stringResource(R.string.vote_check_with_service),
                        stringResource(R.string.vote_check_not_published_now),
                    )
                else -> Unit
            }
        }
        if (d != null) {
            state.choiceLabel?.let { label ->
                Text(
                    stringResource(R.string.vote_your_choice, AccountNames.isolateOrNull(label).orEmpty()),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("vote_your_choice"),
                )
            }
            Results(d, service)
        }
        if (d != null && VoteRules.choosing(s)) {
            Choices(d, state, actions)
            PrimaryButton(
                label = stringResource(R.string.vote_cast_button),
                onClick = actions.onCast,
                enabled = state.canCast,
                modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("vote_cast"),
            )
        }
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val ballot = s.ballot?.state
            val waiting = ballot == BallotState.SENDING || ballot == BallotState.WAITING
            val pending = s.check?.state == CheckState.PENDING || s.check?.state == CheckState.GAVE_UP
            if (waiting && !s.closed) {
                SecondaryButton(
                    stringResource(R.string.vote_check_with_service),
                    actions.onCheck,
                    enabled = !state.checking,
                    modifier = Modifier.testTag("vote_check"),
                )
            }
            if (s.closed && (pending || waiting)) {
                SecondaryButton(
                    stringResource(R.string.vote_check_again),
                    actions.onCheck,
                    enabled = !state.checking,
                    modifier = Modifier.testTag("vote_check_again"),
                )
            }
            if (VoteRules.hasProof(s)) {
                SecondaryButton(
                    stringResource(R.string.vote_save_proof),
                    actions.onSaveProof,
                    enabled = !state.proofBusy,
                    modifier = Modifier.testTag("vote_save_proof"),
                )
                state.proofProblem?.let { p ->
                    NoticeCard(
                        NoticeKind.WARNING,
                        stringResource(R.string.vote_save_proof),
                        failureText(p.kind.messageRes(), p.kind.vaultCode(p.shownCode)),
                    )
                }
                // A problem record, once its proof is saved: Release (the vault refuses it until then, and for an open vote).
                val releasable = s.closed || s.state == VoteState.REJECTED
                if (state.proofExported && releasable && !state.released) {
                    SecondaryButton(stringResource(R.string.vote_release), actions.onRelease, modifier = Modifier.testTag("vote_release"))
                }
                if (state.released) Text(stringResource(R.string.vote_released), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The proposal's text and facts (screen 2): the service's words as plain text, in full, captioned "Written by <service>". */
@Composable
private fun ProposalBody(d: VoteDetail, service: String, closes: String?, state: VoteUiState) {
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        // A plain Text: no links, no markup. The whole text scrolls with the screen and is never truncated.
        Text(d.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("vote_text"))
        Text(
            stringResource(R.string.vote_written_by, service),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        d.quorumText?.let {
            Text(
                stringResource(R.string.vote_quorum_line, AccountNames.isolateOrNull(it).orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        d.binding?.let {
            val text = stringResource(if (it) R.string.vote_binding_yes else R.string.vote_binding_no)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        closes?.let {
            Text(
                stringResource(R.string.vote_closes_line, it),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("vote_closes"),
            )
        }
        val closesAt = state.summary?.closesAt
        if (d.extensions > 0 && closesAt != null) {
            Text(stringResource(R.string.vote_extended, Times.full(closesAt)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The choices in the order sent, each with its label as sent (the reserved `abstain` too), as one radio group. */
@Composable
private fun Choices(d: VoteDetail, state: VoteUiState, actions: VoteActions) {
    val enabled = state.cast == CastStep.NONE && state.summary?.let { VoteRules.castAllowed(it) } == true
    Column(Modifier.padding(horizontal = Spacing.gutter).testTag("vote_choices")) {
        Text(
            stringResource(R.string.vote_choose),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }.padding(bottom = Spacing.xs),
        )
        Column(Modifier.selectableGroup()) {
            for (c in d.choices) {
                val label = AccountNames.isolateOrNull(c.label) ?: c.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = state.selected == c.id,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { actions.onSelect(c.id) },
                        )
                        .testTag("vote_choice_${c.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.selected == c.id, onClick = null, enabled = enabled)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.m))
                }
            }
        }
    }
}

/** A note: the table's words for the vault's state. A conflict found after a verified check is plain text under the results line. */
@Composable
private fun NoteView(n: VoteNote, resources: android.content.res.Resources, service: String, time: String, rejectedReason: String?) {
    val why = VoteRules.rejectedWhy(rejectedReason)
    val body = VoteTexts.text(resources, n, service, time, if (n == VoteNote.REJECTED) why else null)
    if (n == VoteNote.IN_RESULTS_CONFLICT) {
        // "Your vote is in the published results." (bold) and what else the service showed: no error colour.
        Text(boldMarked(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("vote_note_${n.name}"))
        return
    }
    val heading = VoteTexts.title(n)?.let { resources.getString(it) }
    NoteCard(n.severity, heading, body, Modifier.testTag("vote_note_${n.name}"))
}

/** A state in words on a card: the icon and colour follow the severity, the words are what says it. */
@Composable
private fun NoteCard(severity: VoteNote.Severity, heading: String?, body: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (accent, icon) = when (severity) {
        VoteNote.Severity.ERROR -> colors.error to Icons.Outlined.ReportProblem
        VoteNote.Severity.WARNING -> VettIdTheme.colors.warning to Icons.Outlined.WarningAmber
        else -> colors.primary to Icons.Outlined.Info
    }
    Surface(
        shape = VettIdShape.card,
        color = colors.surfaceContainer,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(Spacing.l)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                if (heading != null) Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A cast the vault refused or did not answer: said plainly (the plan's words for each code), never silent. */
@Composable
private fun CastProblem(p: VoteProblem, onDismiss: () -> Unit, resources: android.content.res.Resources, service: String, time: String) {
    val note = when (p.code) {
        "not_open" -> VoteNote.NOT_OPEN
        "closed" -> VoteNote.CLOSED
        "closing" -> VoteNote.CLOSING_SOON
        "site_mismatch" -> VoteNote.SITE_MISMATCH
        "not_signed_in" -> VoteNote.NOT_SIGNED_IN
        "connection_unavailable" -> VoteNote.NO_CONNECTION
        "checking" -> VoteNote.CHECKING_OTHER
        else -> null
    }
    val text = when {
        note != null -> VoteTexts.text(resources, note, service, time)
        p.kind == FailureKind.NO_RESPONSE -> stringResource(R.string.vote_cast_unanswered)
        else -> failureText(p.kind.messageRes(), p.kind.vaultCode(p.shownCode))
    }
    NoticeCard(
        kind = NoticeKind.WARNING,
        title = stringResource(R.string.vote_cast_failed_title),
        body = text,
        modifier = Modifier.testTag("vote_cast_problem"),
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vote_confirm_back)) } },
    )
}

/** The results (screen 5): only from the verified results; before that, no number at all. */
@Composable
private fun Results(d: VoteDetail, service: String) {
    val tally = d.tally ?: return
    val counts = d.counts
    Column(Modifier.padding(horizontal = Spacing.gutter).testTag("vote_results"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.vote_results_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        for (c in d.choices) {
            val n = tally[c.id] ?: continue
            Text(
                stringResource(R.string.vote_results_row, AccountNames.isolateOrNull(c.label) ?: c.id, n),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (counts != null) {
            Text(
                stringResource(R.string.vote_results_turnout, counts.ballots, counts.eligible),
                style = MaterialTheme.typography.bodyMedium,
            )
            d.minBallots?.let { min ->
                val met = counts.ballots >= min
                Text(
                    stringResource(if (met) R.string.vote_quorum_met else R.string.vote_quorum_not_met, counts.ballots, min),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (counts.unhandled > 0) {
                val text = stringResource(R.string.vote_results_unhandled, counts.unhandled, service)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The confirm sheet (screen 3): "Vote “<label>”?", the fixed disclosure sentence (read first: the sheet takes focus on it,
 * never on **Cast vote**), "Your vote can't be changed after you cast it.", **Cast vote** and **Back**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(state: VoteUiState, actions: VoteActions) {
    val label = state.detail?.choices?.firstOrNull { it.id == state.selected }?.label
    val service = AccountNames.isolateOrNull(state.serviceName) ?: stringResource(R.string.vote_service_fallback)
    val focus = remember { FocusRequester() }
    val sending = state.cast == CastStep.SENDING
    ModalBottomSheet(
        onDismissRequest = { if (!sending) actions.onDismissCast() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl).testTag("vote_confirm_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            // The app's own sentence, never the proposal's, in its own element and read first.
            Text(
                stringResource(R.string.vote_disclosure, service),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.focusRequester(focus).focusable().testTag("vote_disclosure"),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Text(
                stringResource(R.string.vote_confirm_title, AccountNames.isolateOrNull(label).orEmpty()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.vote_final), style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(
                stringResource(R.string.vote_confirm_yes),
                actions.onConfirmCast,
                enabled = !sending,
                busy = sending,
                modifier = Modifier.testTag("vote_confirm_yes"),
            )
            SecondaryButton(
                stringResource(R.string.vote_confirm_back),
                actions.onDismissCast,
                enabled = !sending,
                modifier = Modifier.testTag("vote_confirm_back"),
            )
        }
    }
}

/** The unlock window prompt (ANDROID-PLAN §4), before `vote.cast` when the window is closed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordSheet(state: VoteUiState, actions: VoteActions, now: Instant) {
    val waiting = state.retryUntil?.isAfter(now) == true
    ModalBottomSheet(onDismissRequest = actions.onDismissCast, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl).testTag("vote_password_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                stringResource(R.string.vote_password_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.vote_password_body), style = MaterialTheme.typography.bodyMedium)
            val e = state.passwordError
            SecretField(
                value = state.password,
                onValueChange = actions.onPassword,
                label = stringResource(R.string.vote_password_label),
                error = e?.let { failureText(it.kind.messageRes(), it.kind.vaultCode(it.shownCode)) },
                onImeAction = actions.onSubmitPassword,
                modifier = Modifier.fillMaxWidth().testTag("vote_password"),
            )
            PrimaryButton(
                stringResource(R.string.vote_password_confirm),
                actions.onSubmitPassword,
                enabled = state.password.isNotEmpty() && !waiting && state.cast != CastStep.SENDING,
                busy = state.cast == CastStep.SENDING,
                modifier = Modifier.testTag("vote_password_confirm"),
            )
            SecondaryButton(stringResource(com.vettid.core.ui.R.string.core_ui_cancel), actions.onDismissCast)
        }
    }
}

/** "Is this your vote?" (screen 7): the service, the recorded choice and the title. */
@Composable
private fun RestoreDialog(state: VoteUiState, actions: VoteActions) {
    val service = AccountNames.isolateOrNull(state.serviceName) ?: stringResource(R.string.vote_service_fallback)
    val choice = AccountNames.isolateOrNull(state.restoreLabel).orEmpty()
    val title = AccountNames.isolateOrNull(state.summary?.title).orEmpty()
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.vote_restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.vote_restore_body, service, choice, title))
                state.restoreProblem?.let { p ->
                    Text(failureText(p.kind.messageRes(), p.kind.vaultCode(p.shownCode)), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onAnswerRestore(true) },
                enabled = !state.restoreBusy,
                modifier = Modifier.testTag("restore_yes"),
            ) {
                Text(stringResource(R.string.vote_restore_yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { actions.onAnswerRestore(false) },
                enabled = !state.restoreBusy,
                modifier = Modifier.testTag("restore_no"),
            ) {
                Text(stringResource(R.string.vote_restore_no))
            }
        },
    )
}

/** Release (screen 6): the vault PIN, as History's export asks it; nothing is sent anywhere by the app. */
@Composable
private fun ReleaseDialog(state: VoteUiState, actions: VoteActions) {
    val service = AccountNames.isolateOrNull(state.serviceName) ?: stringResource(R.string.vote_service_fallback)
    val e = state.releaseProblem
    AlertDialog(
        onDismissRequest = actions.onDismissRelease,
        title = { Text(stringResource(R.string.vote_release_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.vote_release_body, service))
                SecretField(
                    value = state.pin,
                    onValueChange = actions.onPin,
                    label = stringResource(R.string.vote_release_pin),
                    isPin = true,
                    error = e?.let {
                        if (it.code == "evidence_not_exported") {
                            stringResource(R.string.vote_release_not_saved)
                        } else {
                            failureText(it.kind.messageRes(), it.kind.vaultCode(it.shownCode))
                        }
                    },
                    onImeAction = actions.onConfirmRelease,
                    modifier = Modifier.fillMaxWidth().testTag("vote_release_pin"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmRelease,
                enabled = state.pin.isNotEmpty() && !state.releaseBusy,
                modifier = Modifier.testTag("vote_release_confirm"),
            ) {
                Text(stringResource(R.string.vote_release))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissRelease) { Text(stringResource(com.vettid.core.ui.R.string.core_ui_cancel)) }
        },
    )
}
