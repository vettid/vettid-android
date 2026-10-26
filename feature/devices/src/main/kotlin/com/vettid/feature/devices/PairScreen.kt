package com.vettid.feature.devices

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.devices.PairingCode
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PendingPairing
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.components.QrCode
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** What the pairing screens do. */
data class PairActions(
    val onBack: () -> Unit = {},
    val onNewCode: () -> Unit = {},
    val onCopied: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onCodesMatch: () -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onChoose: (SessionChoice) -> Unit = {},
    val onPair: () -> Unit = {},
    val onReject: () -> Unit = {},
    val onDone: () -> Unit = {},
    /** Opens the new agent's page. */
    val onOpenPage: (String) -> Unit = {},
    val onOwnerCheck: () -> Unit = {},
)

/**
 * Pairing an agent (ANDROID-PLAN 0.1.53 AA2): the code (QR and text, Copy, Share, its countdown), the comparison of the
 * six digits with the agent's, the choice of first access, and the end of it. The window is kept out of screenshots and
 * the recent-apps preview for the whole flow (the code is a bearer secret for 10 minutes, decision 1).
 */
@Composable
@Suppress("CyclomaticComplexMethod") // one branch per stage of the pairing
fun PairScreen(state: PairUiState, actions: PairActions, modifier: Modifier = Modifier, now: () -> Instant = Instant::now) {
    SecureWindow()
    val context = LocalContext.current
    val stage = state.stage
    // The code is worthless once a pairing is over, however it ended: the clipboard is cleared as soon as it can be.
    // Leaving the screen ends the code's use, however it is left.
    DisposableEffect(Unit) { onDispose { PairingClipboard.ended(context) } }
    LaunchedEffect(stage) {
        if (stage !is PairingStage.Code && stage !is PairingStage.Creating) PairingClipboard.ended(context)
    }
    when {
        stage is PairingStage.Code -> CodeStep(stage.code, state, actions, now, modifier)
        stage is PairingStage.CodeExpired -> Plain(
            R.string.pair_title,
            actions,
            modifier,
            primary = R.string.pair_new_code to actions.onNewCode,
            secondary = R.string.pair_cancel to actions.onCancel,
            tag = "pair_expired",
        ) { Text(stringResource(R.string.pair_expired), style = MaterialTheme.typography.bodyLarge) }
        stage is PairingStage.Compare -> CompareStep(stage.pending, state, actions, modifier)
        stage is PairingStage.Rejected -> Plain(
            R.string.pair_rejected_title,
            actions,
            modifier,
            primary = R.string.pair_done to actions.onDone,
            tag = "pair_rejected",
        ) {
            Text(
                stringResource(if (stage.told) R.string.pair_rejected_body else R.string.pair_rejected_unsent),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        stage is PairingStage.Paired -> PairedStep(stage, actions, modifier)
        stage is PairingStage.Ended -> EndedStep(state, actions, modifier)
        // A lock or a hold took the pairing away while this screen was open: it was interrupted, nothing is paired.
        stage is PairingStage.Idle && state.interrupted -> Plain(
            R.string.pair_ended_title,
            actions,
            modifier,
            primary = R.string.pair_new_code to actions.onNewCode,
            secondary = R.string.pair_cancel to actions.onCancel,
            tag = "pair_interrupted",
        ) { Text(stringResource(R.string.pair_ended), style = MaterialTheme.typography.bodyLarge) }
        stage is PairingStage.Idle && state.pairing.error != null -> CreateFailed(state, actions, modifier)
        stage is PairingStage.Idle && state.held -> Plain(
            R.string.pair_lost_title,
            actions,
            modifier,
            primary = R.string.pair_done to actions.onDone,
            tag = "pair_lost",
        ) {
            Text(stringResource(R.string.pair_lost_body), style = MaterialTheme.typography.bodyLarge)
        }
        else -> Plain(R.string.pair_title, actions, modifier, tag = "pair_creating") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val cd = stringResource(R.string.pair_creating)
                CircularProgressIndicator(Modifier.semantics { contentDescription = cd }, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A step with a title, an optional primary and secondary button, and [content]. */
@Composable
private fun Plain(
    title: Int,
    actions: PairActions,
    modifier: Modifier,
    primary: Pair<Int, () -> Unit>? = null,
    secondary: Pair<Int, () -> Unit>? = null,
    tag: String,
    content: @Composable () -> Unit,
) {
    FormScaffold(
        title = stringResource(title),
        primaryLabel = primary?.let { stringResource(it.first) },
        onPrimary = primary?.second ?: {},
        secondaryLabel = secondary?.let { stringResource(it.first) },
        onSecondary = secondary?.second ?: {},
        onBack = actions.onBack,
        modifier = modifier.testTag(tag),
    ) { content() }
}

@Composable
private fun CodeStep(code: PairingCode, state: PairUiState, actions: PairActions, now: () -> Instant, modifier: Modifier) {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(code) {
        while (true) {
            clock = now()
            delay(TICK_MS)
        }
    }
    val left = Duration.between(clock, code.exp).coerceAtLeast(Duration.ZERO)
    // Announced to a screen reader once a minute, not every second.
    val minutes = ((left.seconds + SECONDS - 1) / SECONDS).toInt()
    val clipLabel = stringResource(R.string.pair_clip_label)
    val shareTitle = stringResource(R.string.pair_share_title)
    FormScaffold(
        title = stringResource(R.string.pair_title),
        body = stringResource(R.string.pair_code_body),
        primaryLabel = null,
        onPrimary = {},
        secondaryLabel = stringResource(R.string.pair_cancel),
        onSecondary = actions.onCancel,
        onBack = actions.onCancel,
        modifier = modifier.testTag("pair_code"),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCode(content = code.link, contentDescription = stringResource(R.string.pair_qr_cd), modifier = Modifier.testTag("pair_qr"))
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.pair_expires_in, "%d:%02d".format(left.toMinutes(), left.seconds % SECONDS)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("pair_countdown"),
        )
        Text(
            pluralStringResource(R.plurals.pair_minutes_left, minutes, minutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("pair_minutes_left"),
        )
        Spacer(Modifier.height(Spacing.l))
        Surface(shape = VettIdShape.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    code.link,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.m).testTag("pair_link"),
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(
                onClick = {
                    PairingClipboard.copy(context, code.link, code.exp, clipLabel)
                    actions.onCopied()
                },
                shape = VettIdShape.pill,
                modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget).testTag("pair_copy"),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(
                    stringResource(if (state.copied) R.string.pair_copied else R.string.pair_copy),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            OutlinedButton(
                onClick = {
                    // The code only, nothing around it.
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.link)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                },
                shape = VettIdShape.pill,
                modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget).testTag("pair_share"),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.pair_share), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun CreateFailed(state: PairUiState, actions: PairActions, modifier: Modifier) {
    val kind = state.pairing.error
    Plain(
        R.string.pair_title,
        actions,
        modifier,
        primary = R.string.pair_try_again to actions.onNewCode,
        secondary = R.string.pair_cancel to actions.onBack,
        tag = "pair_create_failed",
    ) {
        if (kind == FailureKind.OWNER_CHECK_REQUIRED) {
            OwnerCheckNotice(kind, state.pairing.errorCode, actions)
        } else {
            NoticeCard(NoticeKind.WARNING, stringResource(R.string.pair_title), stringResource(R.string.pair_create_failed))
        }
    }
}

/** The daily check is due: the vault's own words, and the way to the check. */
@Composable
private fun OwnerCheckNotice(kind: FailureKind, code: String?, actions: PairActions) {
    NoticeCard(
        kind = NoticeKind.WARNING,
        title = stringResource(R.string.pair_title),
        body = failureText(kind.messageRes(), kind.vaultCode(code)),
        modifier = Modifier.testTag("pair_owner_check"),
        actions = { TextButton(onClick = actions.onOwnerCheck) { Text(stringResource(R.string.pair_check_now)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareStep(p: PendingPairing, state: PairUiState, actions: PairActions, modifier: Modifier) {
    val sas = p.sas
    val name = DeviceText.agentName(p.name)
    FormScaffold(
        title = stringResource(R.string.pair_compare_title),
        primaryLabel = stringResource(if (sas != null) R.string.pair_codes_match else R.string.pair_reject),
        onPrimary = if (sas != null) actions.onCodesMatch else actions.onReject,
        destructive = sas == null,
        secondaryLabel = if (sas != null) stringResource(R.string.pair_codes_differ) else null,
        onSecondary = actions.onReject,
        // Leaving without an answer rejects (the view model): nothing is approved by leaving.
        onBack = actions.onBack,
        busy = state.pairing.busy,
        modifier = modifier.testTag("pair_compare"),
    ) {
        if (sas == null) {
            Text(
                stringResource(R.string.pair_cannot_compare),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("pair_cannot_compare"),
            )
        } else {
            SasSentence(sas)
        }
        Spacer(Modifier.height(Spacing.l))
        Text(
            stringResource(R.string.pair_calls_itself, isolate(name)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("pair_name"),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.pair_calls_itself_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.sheet && sas != null) AllowSheet(state, actions)
}

/** The name inside a sentence: isolated, a self-asserted name may run either way (§10.8). */
private fun isolate(name: String): String = AccountNames.isolate(name)

/** "Does the agent show **123 456**?": the digits in two groups of three, large; read digit by digit by a screen reader. */
@Composable
private fun SasSentence(sas: String) {
    val grouped = "${sas.substring(0, GROUP)} ${sas.substring(GROUP)}"
    val spoken = sas.toList().joinToString(" ")
    val text = stringResource(R.string.pair_compare_body, grouped)
    val start = text.indexOf(grouped)
    val annotated = buildAnnotatedString {
        if (start < 0) {
            append(text)
        } else {
            append(text.substring(0, start))
            val big = SpanStyle(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = SAS_SP.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            withStyle(big) {
                append(grouped)
            }
            append(text.substring(start + grouped.length))
        }
    }
    val description = if (start < 0) text else text.replace(grouped, spoken)
    Text(
        annotated,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { contentDescription = description }.testTag("pair_sas"),
    )
}

/** Step 3: "Allow access now?" (decision 5); **Pair** sends the approval with no grants. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowSheet(state: PairUiState, actions: PairActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onCloseSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("pair_sheet"),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(stringResource(R.string.pair_allow_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.pair_allow_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SessionChoice.entries.forEach { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget)
                        .selectable(
                            selected = c == state.session,
                            enabled = !state.pairing.busy,
                            role = Role.RadioButton,
                            onClick = { actions.onChoose(c) },
                        )
                        .testTag("pair_session_${c.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = c == state.session, onClick = null, enabled = !state.pairing.busy)
                    Spacer(Modifier.size(Spacing.m))
                    Text(stringResource(sessionLabel(c)), style = MaterialTheme.typography.bodyLarge)
                }
            }
            val kind = state.pairing.error
            if (kind == FailureKind.OWNER_CHECK_REQUIRED) OwnerCheckNotice(kind, state.pairing.errorCode, actions)
            PrimaryButton(
                stringResource(R.string.pair_confirm),
                actions.onPair,
                enabled = !state.pairing.busy,
                busy = state.pairing.busy,
                modifier = Modifier.testTag("pair_confirm"),
            )
            Spacer(Modifier.height(Spacing.l))
        }
    }
}

private fun sessionLabel(c: SessionChoice): Int = when (c) {
    SessionChoice.NONE -> R.string.pair_session_none
    SessionChoice.MINUTES_15 -> R.string.pair_session_15m
    SessionChoice.HOUR_1 -> R.string.pair_session_1h
    SessionChoice.HOURS_4 -> R.string.pair_session_4h
    SessionChoice.HOURS_8 -> R.string.pair_session_8h
    SessionChoice.HOURS_24 -> R.string.pair_session_24h
}

@Composable
private fun PairedStep(stage: PairingStage.Paired, actions: PairActions, modifier: Modifier) {
    val name = isolate(DeviceText.agentName(stage.name))
    FormScaffold(
        title = stringResource(R.string.pair_paired_title, name),
        body = stringResource(R.string.pair_paired_body),
        // What it may use is chosen from AA4 on; until then there is nothing to choose, only its page.
        primaryLabel = stringResource(R.string.pair_done),
        onPrimary = actions.onDone,
        secondaryLabel = stage.deviceId?.let { stringResource(R.string.pair_open_page) },
        onSecondary = { stage.deviceId?.let(actions.onOpenPage) },
        onBack = actions.onDone,
        modifier = modifier.testTag("pair_paired"),
    ) {}
}

@Composable
private fun EndedStep(state: PairUiState, actions: PairActions, modifier: Modifier) {
    val unknown = state.pairing.unknownOutcome
    // No answer from the vault asserts nothing: not "Not paired", but that it was not confirmed.
    val title = if (unknown) R.string.pair_unconfirmed_title else R.string.pair_ended_title
    Plain(title, actions, modifier, primary = R.string.pair_done to actions.onDone, tag = "pair_ended") {
        if (unknown) {
            val name = isolate(DeviceText.agentName(state.pairing.agentName.orEmpty()))
            Text(stringResource(R.string.pair_unconfirmed_body, name), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(stringResource(R.string.pair_ended), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private const val TICK_MS = 1_000L
private const val SECONDS = 60
private const val GROUP = 3
private const val SAS_SP = 28
