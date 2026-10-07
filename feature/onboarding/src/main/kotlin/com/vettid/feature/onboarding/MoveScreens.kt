package com.vettid.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.QrScanner
import com.vettid.core.ui.components.RookLogo
import com.vettid.core.ui.components.SafetyCode
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.components.rememberCameraPermission
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

// --- shared pieces ---

private val SCANNER_HEIGHT = 320.dp

/** A local date and time for an RFC 3339 instant from the member API, or null. */
internal fun localDateTime(rfc3339: String?): String? = try {
    rfc3339?.takeIf { it.isNotEmpty() }?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
            .format(Instant.parse(it))
    }
} catch (_: DateTimeParseException) {
    null
}

/** The camera with a gold frame, or the permission notice; [camera] is replaced in the screen catalog. */
@Composable
internal fun ScannerBox(onText: (String) -> Unit, camera: (@Composable (Modifier) -> Unit)?) {
    val permission = rememberCameraPermission()
    LaunchedEffect(Unit) { if (!permission.granted && camera == null) permission.request() }
    if (permission.granted || camera != null) {
        val cd = stringResource(R.string.move_cd_qr_scanner)
        Box(
            Modifier
                .fillMaxWidth()
                .height(SCANNER_HEIGHT)
                .border(2.dp, MaterialTheme.colorScheme.primary, VettIdShape.card)
                .padding(2.dp)
                .clip(VettIdShape.card)
                .semantics { contentDescription = cd }
                .testTag("scanner"),
        ) {
            if (camera != null) camera(Modifier.fillMaxSize()) else QrScanner(onText = onText, modifier = Modifier.fillMaxSize())
        }
    } else {
        NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.recover_scan_permission_title),
            stringResource(R.string.recover_scan_permission_body),
            actions = { TextButton(onClick = permission.request) { Text(stringResource(R.string.recover_scan_allow)) } },
        )
    }
}

@Composable
private fun Waiting(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(Spacing.m))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Failure(kind: FailureKind?, text: String? = null, modifier: Modifier = Modifier) {
    if (kind == null && text == null) return
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.recover_failed_title),
        text ?: stringResource(kind!!.messageRes()),
        modifier.testTag("error"),
    )
}

@Composable
private fun Link(label: String, onClick: () -> Unit, tag: String) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Spacing.touchTarget).testTag(tag)) {
        Text(label, color = MaterialTheme.colorScheme.primary)
    }
}

// --- recovery (§11.11) ---

/** The recovery flow on this phone, driven by [RecoverViewModel]. [onLeave] returns to the onboarding choice. */
@Composable
fun RecoverFlow(
    onLeave: () -> Unit,
    onNewVault: () -> Unit,
    onOpenAccountSite: () -> Unit,
    viewModel: RecoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecoverContent(state, viewModel, onLeave, onNewVault, onOpenAccountSite)
}

/** One recovery step for [state] (stateless). [camera] replaces the camera preview (screen catalog). */
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
@Composable
fun RecoverContent(
    state: RecoverUiState,
    actions: RecoverActions,
    onLeave: () -> Unit,
    onNewVault: () -> Unit,
    onOpenAccountSite: () -> Unit,
    camera: (@Composable (Modifier) -> Unit)? = null,
) {
    val leaveable = state.step in setOf(RecoverStep.INTRO, RecoverStep.LOADING)
    val back: () -> Unit = { if (!actions.back() && leaveable) onLeave() }
    val canBack = leaveable || state.step == RecoverStep.SCAN
    BackHandler(enabled = canBack) { back() }
    val onBack: (() -> Unit)? = if (canBack) back else null
    when (state.step) {
        RecoverStep.LOADING, RecoverStep.INTRO -> RecoverIntro(state, actions, onBack, onOpenAccountSite)
        RecoverStep.SCAN -> FormScaffold(
            title = stringResource(R.string.recover_scan_title),
            body = stringResource(R.string.recover_scan_body),
            primaryLabel = null,
            onPrimary = {},
            onBack = onBack,
        ) {
            ScannerBox(actions::scanned, camera)
            CodeRefusalNotice(state)
            Failure(state.error)
        }
        RecoverStep.REGISTERING -> FormScaffold(title = stringResource(R.string.recover_title), primaryLabel = null, onPrimary = {}) {
            Waiting(stringResource(R.string.recover_registering), Modifier.testTag("registering"))
        }
        RecoverStep.PIN -> RecoverPin(state, actions)
        RecoverStep.PASSWORD -> FormScaffold(
            title = stringResource(R.string.recover_password_title),
            body = stringResource(R.string.recover_password_body),
            primaryLabel = stringResource(R.string.recover_password_submit),
            onPrimary = actions::submitPassword,
            primaryEnabled = state.password.isNotEmpty() && state.waitSeconds == 0L,
            busy = state.busy,
        ) {
            SecretField(
                value = state.password,
                onValueChange = actions::setPassword,
                label = stringResource(R.string.recover_password_label),
                error = if (state.error == FailureKind.BAD_PASSWORD) stringResource(R.string.recover_password_wrong) else null,
                supporting = if (state.waitSeconds > 0) {
                    stringResource(R.string.recover_password_wait, formatWait(state.waitSeconds))
                } else {
                    null
                },
                enabled = state.waitSeconds == 0L && !state.busy,
                onImeAction = actions::submitPassword,
                modifier = Modifier.testTag("recover_password"),
            )
            when {
                state.errorCode == RecoverViewModel.CODE_REQUIRED -> Failure(null, stringResource(R.string.recover_required))
                state.error != null && state.error != FailureKind.BAD_PASSWORD -> Failure(state.error)
            }
        }
        RecoverStep.NO_BACKUP -> {
            val uri = LocalUriHandler.current
            FormScaffold(
                title = stringResource(R.string.recover_no_backup_title),
                body = stringResource(R.string.recover_no_backup_body),
                primaryLabel = stringResource(R.string.recover_no_backup_start_over),
                onPrimary = { uri.openUri(state.startOverUrl) },
                secondaryLabel = stringResource(R.string.recover_no_backup_new_vault),
                onSecondary = onNewVault,
                modifier = Modifier.testTag("recover_no_backup"),
            ) {
                NoticeCard(
                    NoticeKind.URGENT,
                    stringResource(R.string.recover_no_backup_title),
                    stringResource(R.string.recover_no_backup_notice),
                )
            }
        }
        RecoverStep.DONE -> MovedScreen(
            body = stringResource(R.string.onboarding_moved_recovered_body),
            onGo = actions::finish,
        )
    }
}

private const val MIN_PIN = 6

@Composable
private fun RecoverIntro(state: RecoverUiState, actions: RecoverActions, onBack: (() -> Unit)?, onOpenAccountSite: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.recover_title),
        body = stringResource(R.string.recover_body),
        primaryLabel = if (state.step == RecoverStep.LOADING) null else stringResource(R.string.recover_scan),
        onPrimary = actions::scan,
        busy = state.busy || state.step == RecoverStep.LOADING,
        secondaryLabel = stringResource(R.string.recover_open_site),
        onSecondary = onOpenAccountSite,
        onBack = onBack,
    ) {
        NoticeCard(NoticeKind.INFO, stringResource(R.string.recover_need_title), stringResource(R.string.recover_need_body))
        CodeRefusalNotice(state)
        Failure(state.error)
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun CodeRefusalNotice(state: RecoverUiState) {
    val r = state.refusal ?: return
    val text = when (r) {
        CodeRefusal.BAD_CODE -> stringResource(R.string.recover_bad_code, state.wrongCodes)
        CodeRefusal.VOIDED -> stringResource(R.string.recover_voided)
        CodeRefusal.EXPIRED -> stringResource(R.string.recover_code_expired)
        CodeRefusal.TOO_EARLY -> stringResource(R.string.recover_code_early_unknown)
        CodeRefusal.GONE -> stringResource(R.string.recover_code_gone)
        CodeRefusal.ATTESTATION -> stringResource(R.string.recover_code_attestation)
        CodeRefusal.RETRY -> stringResource(R.string.recover_code_retry)
        CodeRefusal.NOT_A_CODE -> stringResource(R.string.recover_scan_not_code)
        CodeRefusal.OTHER_ENVIRONMENT -> stringResource(R.string.recover_scan_other_environment, state.otherApi ?: "")
        CodeRefusal.NO_API -> stringResource(R.string.recover_scan_no_api)
        CodeRefusal.NOT_AVAILABLE -> stringResource(R.string.recover_not_available)
    }
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(
        if (r == CodeRefusal.BAD_CODE || r == CodeRefusal.NOT_A_CODE || r == CodeRefusal.RETRY) NoticeKind.WARNING else NoticeKind.URGENT,
        stringResource(R.string.recover_failed_title),
        text,
        Modifier.testTag("code_refused"),
    )
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun RecoverPin(state: RecoverUiState, actions: RecoverActions) {
    val p = state.preflight
    val blocked = state.preflightError == FailureKind.RELEASE_ENDED || p?.rollback == true
    FormScaffold(
        title = stringResource(R.string.recover_pin_title),
        body = stringResource(R.string.recover_pin_body),
        primaryLabel = when {
            blocked -> null
            state.preflightError != null -> stringResource(R.string.unlock_retry)
            else -> stringResource(R.string.recover_pin_submit)
        },
        onPrimary = if (state.preflightError != null) actions::retryPreflight else actions::submitPin,
        primaryEnabled = state.preflightError != null || (state.pinAllowed && state.pin.length >= MIN_PIN),
        busy = state.busy,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (state.emailHint.isNotEmpty()) {
                NoticeCard(
                    NoticeKind.INFO, stringResource(R.string.recover_account_title), state.emailHint, Modifier.testTag("recover_account"),
                )
            }
            if (p == null && state.preflightError == null) Waiting(stringResource(R.string.unlock_checking))
            state.preflightError?.let {
                NoticeCard(
                    if (blocked) NoticeKind.URGENT else NoticeKind.WARNING,
                    stringResource(R.string.recover_pin_title),
                    stringResource(it.messageRes()),
                    Modifier.testTag("preflight_error"),
                )
            }
            if (p != null) PreflightNotices(p, true, {}, state.approveOffer, actions::setApproveOffer)
            if (!blocked && p != null) {
                SecretField(
                    value = state.pin,
                    onValueChange = actions::setPin,
                    label = stringResource(R.string.unlock_pin_label),
                    isPin = true,
                    enabled = state.pinAllowed,
                    error = if (state.pinWrong) stringResource(R.string.unlock_bad_pin) else null,
                    supporting = if (state.waitSeconds > 0) stringResource(R.string.unlock_wait, formatWait(state.waitSeconds)) else null,
                    onImeAction = actions::submitPin,
                    modifier = Modifier.testTag("recover_pin"),
                )
            }
            when {
                state.errorCode == RecoverViewModel.CODE_UNKNOWN_DEVICE ->
                    Failure(null, stringResource(R.string.recover_pin_cancelled), Modifier.testTag("recovery_cancelled_at_pin"))
                state.error != null -> Failure(state.error)
            }
        }
    }
}

/** "Your vault is on this phone now", after a recovery or a transfer. */
@Composable
fun MovedScreen(body: String, onGo: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.onboarding_moved_title),
        body = body,
        primaryLabel = stringResource(R.string.onboarding_moved_go),
        onPrimary = onGo,
        modifier = Modifier.testTag("moved"),
        header = {
            Spacer(Modifier.height(Spacing.xl))
            RookLogo(Modifier.align(Alignment.CenterHorizontally), height = 72.dp)
            Spacer(Modifier.height(Spacing.l))
        },
    ) {}
}

// --- direct transfer, the new phone (§6.7.1) ---

/** The transfer on this (new) phone, driven by [TransferInViewModel]. [onLeave] returns to the onboarding choice. */
@Composable
fun TransferInFlow(onLeave: () -> Unit, viewModel: TransferInViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransferInContent(state, viewModel, onLeave)
}

/** One transfer step on the new phone for [state] (stateless). */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun TransferInContent(
    state: TransferInUiState,
    actions: TransferInActions,
    onLeave: () -> Unit,
    camera: (@Composable (Modifier) -> Unit)? = null,
) {
    val back: () -> Unit = { if (!actions.back()) onLeave() }
    BackHandler(enabled = state.step != TransferInStep.DONE) { back() }
    when (state.step) {
        TransferInStep.INTRO -> FormScaffold(
            title = stringResource(R.string.transfer_in_title),
            body = stringResource(R.string.transfer_in_body),
            primaryLabel = stringResource(R.string.transfer_in_scan),
            onPrimary = actions::scan,
            secondaryLabel = stringResource(R.string.transfer_in_paste),
            onSecondary = actions::paste,
            onBack = back,
        ) {
            NoticeCard(NoticeKind.INFO, stringResource(R.string.recover_need_title), stringResource(R.string.transfer_in_need_body))
            Failure(state.error, if (state.error == FailureKind.NO_RESPONSE) stringResource(R.string.transfer_in_no_answer) else null)
        }
        TransferInStep.SCAN -> FormScaffold(
            title = stringResource(R.string.transfer_in_scan_title),
            body = stringResource(R.string.transfer_in_scan_body),
            primaryLabel = null,
            onPrimary = {},
            onBack = back,
            secondaryLabel = stringResource(R.string.transfer_in_paste),
            onSecondary = actions::paste,
        ) {
            ScannerBox(actions::scanned, camera)
            InputProblem(state.inputProblem)
            Failure(state.error, if (state.error == FailureKind.NO_RESPONSE) stringResource(R.string.transfer_in_no_answer) else null)
        }
        TransferInStep.PASTE -> FormScaffold(
            title = stringResource(R.string.transfer_in_paste_title),
            body = stringResource(R.string.transfer_in_paste_body),
            primaryLabel = stringResource(R.string.transfer_in_paste_submit),
            onPrimary = actions::submitInput,
            primaryEnabled = state.input.isNotBlank(),
            busy = state.busy,
            onBack = back,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = actions::setInput,
                label = { Text(stringResource(R.string.transfer_in_paste_label)) },
                minLines = 3,
                isError = state.inputProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { actions.submitInput() }),
                modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("transfer_code"),
            )
            InputProblem(state.inputProblem)
            Failure(state.error, if (state.error == FailureKind.NO_RESPONSE) stringResource(R.string.transfer_in_no_answer) else null)
        }
        TransferInStep.CONNECTING -> FormScaffold(
            title = stringResource(R.string.transfer_in_title),
            primaryLabel = null,
            onPrimary = {},
            secondaryLabel = stringResource(R.string.transfer_in_cancel),
            onSecondary = actions::cancel,
        ) {
            Waiting(stringResource(R.string.transfer_in_connecting), Modifier.testTag("connecting"))
        }
        TransferInStep.COMPARE -> FormScaffold(
            title = stringResource(R.string.transfer_in_compare_title),
            body = stringResource(R.string.transfer_in_compare_body),
            primaryLabel = null,
            onPrimary = {},
            secondaryLabel = stringResource(R.string.transfer_in_cancel),
            onSecondary = actions::cancel,
        ) {
            state.sas?.let { SasBox(it) }
            Spacer(Modifier.height(Spacing.l))
            Waiting(stringResource(R.string.transfer_in_waiting, formatWait(state.secondsLeft)), Modifier.testTag("waiting_old_phone"))
        }
        TransferInStep.DONE -> MovedScreen(
            stringResource(
                if (state.canUnlockLater) {
                    R.string.onboarding_moved_transferred_body
                } else {
                    R.string.onboarding_moved_transferred_no_guid_body
                },
            ),
            actions::finish,
        )
        TransferInStep.REJECTED -> FormScaffold(
            title = stringResource(R.string.transfer_in_rejected_title),
            body = stringResource(R.string.transfer_in_rejected_body),
            primaryLabel = stringResource(R.string.transfer_in_again),
            onPrimary = actions::again,
            secondaryLabel = stringResource(R.string.move_back),
            onSecondary = onLeave,
            modifier = Modifier.testTag("transfer_rejected"),
        ) {}
        TransferInStep.TIMED_OUT -> FormScaffold(
            title = stringResource(R.string.transfer_in_timeout_title),
            body = stringResource(R.string.transfer_in_timeout_body),
            primaryLabel = stringResource(R.string.transfer_in_again),
            onPrimary = actions::again,
            secondaryLabel = stringResource(R.string.move_back),
            onSecondary = onLeave,
            modifier = Modifier.testTag("transfer_timed_out"),
        ) {}
        TransferInStep.NOT_ANSWERED -> FormScaffold(
            title = stringResource(R.string.transfer_in_no_answer_title),
            body = stringResource(R.string.transfer_in_no_answer),
            primaryLabel = stringResource(R.string.transfer_in_scan_new),
            onPrimary = actions::again,
            secondaryLabel = stringResource(R.string.move_back),
            onSecondary = onLeave,
            modifier = Modifier.testTag("transfer_not_answered"),
        ) {}
    }
}

@Composable
private fun InputProblem(kind: FailureKind?) {
    kind ?: return
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(
        NoticeKind.WARNING,
        stringResource(R.string.recover_failed_title),
        stringResource(if (kind == FailureKind.INVITE_EXPIRED) R.string.transfer_in_expired_code else R.string.transfer_in_not_transfer),
        Modifier.testTag("code_refused"),
    )
}

/** The SAS, large, in a bordered box (§6.3); `sas` test tag. */
@Composable
private fun SasBox(sas: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, VettIdShape.card)
            .padding(vertical = Spacing.l)
            .testTag("sas"),
        contentAlignment = Alignment.Center,
    ) { SafetyCode(sas) }
}

@Composable
internal fun MoveLink(label: String, onClick: () -> Unit, tag: String) = Link(label, onClick, tag)
