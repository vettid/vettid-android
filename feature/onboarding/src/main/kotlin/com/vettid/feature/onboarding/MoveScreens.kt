package com.vettid.feature.onboarding

import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.vault.CouldNotTell
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.CameraPermission
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.QrScanner
import com.vettid.core.ui.components.RookLogo
import com.vettid.core.ui.components.SafetyCode
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.NoKeyboardLearning
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.components.visibleSecretKeyboard
import com.vettid.core.ui.components.rememberCameraPermission
import com.vettid.core.ui.format.Durations
import com.vettid.core.ui.format.StagingTimeNoteIfShown
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.format.scaledDurationText
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
internal fun ScannerBox(
    onText: (String) -> Unit,
    camera: (@Composable (Modifier) -> Unit)?,
    permission: CameraPermission = rememberCameraPermission(),
) {
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
private fun Failure(kind: FailureKind?, text: String? = null, modifier: Modifier = Modifier, shownCode: String? = null) {
    if (kind == null && text == null) return
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(
        NoticeKind.URGENT,
        stringResource(R.string.recover_failed_title),
        text ?: failureText(kind!!.messageRes(), kind.vaultCode(shownCode)),
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

/**
 * One recovery step for [state] (stateless). [camera] replaces the camera preview (screen catalog); [secureTyping]
 * keeps the typed-entry screen out of screenshots and the recent-apps preview (off only in the screen catalog).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
@Composable
fun RecoverContent(
    state: RecoverUiState,
    actions: RecoverActions,
    onLeave: () -> Unit,
    onNewVault: () -> Unit,
    onOpenAccountSite: () -> Unit,
    camera: (@Composable (Modifier) -> Unit)? = null,
    secureTyping: Boolean = true,
    /** The release-R pane's view model; null: Hilt's (a test passes its own). */
    recoveryOpen: RecoveryOpenViewModel? = null,
) {
    val leaveable = state.step in setOf(RecoverStep.INTRO, RecoverStep.LOADING)
    val back: () -> Unit = { if (!actions.back() && leaveable) onLeave() }
    val canBack = leaveable || state.step == RecoverStep.SCAN || state.step == RecoverStep.TYPE
    BackHandler(enabled = canBack) { back() }
    val onBack: (() -> Unit)? = if (canBack) back else null
    if (state.confirmStartOver) {
        // A6 audit E1: what "Start over" does, said plainly. The app cannot cancel the recovery itself (§11.11.4).
        ConfirmDialog(
            title = stringResource(R.string.recover_start_over_title),
            text = stringResource(R.string.recover_start_over_body),
            confirmLabel = stringResource(R.string.recover_start_over),
            onConfirm = actions::confirmStartOver,
            onDismiss = actions::dismissStartOver,
            modifier = Modifier.testTag("recover_start_over_dialog"),
            destructive = true,
        )
    }
    when (state.step) {
        RecoverStep.LOADING, RecoverStep.INTRO -> RecoverIntro(state, actions, onBack, onOpenAccountSite)
        RecoverStep.SCAN -> RecoverScan(state, actions, onBack, camera)
        RecoverStep.TYPE -> RecoverTypeScreen(state, actions, onBack, secureTyping) {
            CodeRefusalNotice(state)
            ClaimFailure(state)
        }
        RecoverStep.REGISTERING -> FormScaffold(title = stringResource(R.string.recover_title), primaryLabel = null, onPrimary = {}) {
            Waiting(stringResource(R.string.recover_registering), Modifier.testTag("registering"))
            Text(
                stringResource(R.string.recover_registering_starting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("registering_starting"),
            )
        }
        // Release R (ANDROID-PLAN 0.1.40): the PIN and the password on one screen, checked before they are sent.
        RecoverStep.PIN -> if (state.phoneSecret) {
            val open: RecoveryOpenViewModel = recoveryOpen ?: hiltViewModel()
            val openState by open.uiState.collectAsStateWithLifecycle()
            // Back at the PIN (after a new code registered or was refused): read the rule again.
            LaunchedEffect(open) { open.refresh() }
            // The `ok` text is the recovered phone's last setup screen: once the vault opened, setup is finished.
            RecoveryOpenPane(
                openState, open, onStartOver = actions::askStartOver, onOpened = actions::finish, onNewCode = actions::useNewCode,
            )
        } else {
            RecoverPin(state, actions)
        }
        RecoverStep.PASSWORD -> FormScaffold(
            title = stringResource(R.string.recover_password_title),
            body = stringResource(R.string.recover_password_body),
            primaryLabel = stringResource(R.string.recover_password_submit),
            onPrimary = actions::submitPassword,
            primaryEnabled = state.password.isNotEmpty() && state.waitSeconds == 0L,
            busy = state.busy,
            secondaryLabel = stringResource(R.string.recover_start_over),
            onSecondary = actions::askStartOver,
            modifier = Modifier.testTag("recover_password_step"),
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
                state.error != null && state.error != FailureKind.BAD_PASSWORD -> Failure(state.error, shownCode = state.errorShown)
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
        RecoverStep.ENDED -> FormScaffold(
            title = stringResource(R.string.recover_ended_title),
            primaryLabel = endedPrimaryLabel(state),
            onPrimary = if (state.undecided) endedUndecidedAction(state, actions) else actions::askStartOver,
            busy = state.busy,
            secondaryLabel = if (state.undecided) null else stringResource(R.string.recover_ended_retry),
            onSecondary = actions::tryPinAgain,
            modifier = Modifier.testTag("recover_ended"),
        ) {
            if (state.undecided) EndedUndecided(state) else EndedPlain(state)
        }
        RecoverStep.DONE -> MovedScreen(
            body = stringResource(R.string.onboarding_moved_recovered_body),
            onGo = actions::finish,
        )
    }
}

/** (0.1.78) The ended step after an open whose answer was lost: the primary action depends on what status with K said. */
@Composable
private fun endedPrimaryLabel(state: RecoverUiState): String? = when {
    !state.undecided -> stringResource(R.string.recover_start_over)
    state.couldNotTell?.kind == CouldNotTell.Kind.START_NEW -> stringResource(R.string.recover_undecided_new_code)
    state.couldNotTell == null -> null
    else -> stringResource(R.string.recover_undecided_check_again)
}

private fun endedUndecidedAction(state: RecoverUiState, actions: RecoverActions): () -> Unit =
    if (state.couldNotTell?.kind == CouldNotTell.Kind.START_NEW) actions::useNewCode else actions::checkAgain

/** The text of the undecided ended step: the approved sentence once a new recovery may start, a draft while it is checked. */
@Composable
private fun EndedUndecided(state: RecoverUiState) {
    val text = when (state.couldNotTell?.kind) {
        CouldNotTell.Kind.START_NEW -> R.string.recover_could_not_tell
        CouldNotTell.Kind.WAIT -> R.string.recover_undecided_wait
        CouldNotTell.Kind.UNKNOWN -> R.string.recover_undecided_unknown
        else -> R.string.recover_undecided_checking
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("recover_undecided_body"),
    )
    if (state.error != null) Failure(state.error, shownCode = state.errorShown)
}

@Composable
private fun EndedPlain(state: RecoverUiState) {
    // The step is reached by itself (the PIN step's answer): announced, as the code's refusals are.
    Text(
        stringResource(R.string.recover_ended_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("recover_ended_body"),
    )
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(
        NoticeKind.INFO,
        stringResource(R.string.recover_ended_next_title),
        stringResource(R.string.recover_ended_next),
    )
    if (state.error != null) Failure(state.error, shownCode = state.errorShown)
}

private const val MIN_PIN = 6

/**
 * The scan step. **Type the code instead** (ANDROID-PLAN 0.1.33) is a text button under the camera, and the primary
 * action when the camera permission is refused or the phone has no camera.
 */
@Composable
private fun RecoverScan(state: RecoverUiState, actions: RecoverActions, onBack: (() -> Unit)?, camera: (@Composable (Modifier) -> Unit)?) {
    val permission = rememberCameraPermission()
    val hasCamera = camera != null || LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val canScan = hasCamera && (permission.granted || camera != null)
    val type = stringResource(R.string.recover_type)
    FormScaffold(
        title = stringResource(R.string.recover_scan_title),
        body = stringResource(R.string.recover_scan_body),
        primaryLabel = if (canScan) null else type,
        onPrimary = actions::type,
        onBack = onBack,
        secondaryLabel = if (canScan) type else null,
        onSecondary = actions::type,
    ) {
        if (hasCamera) {
            ScannerBox(actions::scanned, camera, permission)
        } else {
            NoticeCard(
                NoticeKind.INFO,
                stringResource(R.string.recover_scan_no_camera_title),
                stringResource(R.string.recover_scan_no_camera_body),
                Modifier.testTag("no_camera"),
            )
        }
        CodeRefusalNotice(state)
        ClaimFailure(state)
    }
}

/** A failure of the claim or the register; a `429` says how long to wait when the member API said so. */
@Composable
private fun ClaimFailure(state: RecoverUiState) {
    val wait = if (state.error == FailureKind.RATE_LIMITED && state.retrySeconds > 0) {
        stringResource(R.string.recover_rate_limited_wait, formatWait(state.retrySeconds))
    } else {
        null
    }
    Failure(state.error, wait, shownCode = state.errorShown)
}

@Composable
private fun RecoverIntro(state: RecoverUiState, actions: RecoverActions, onBack: (() -> Unit)?, onOpenAccountSite: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.recover_title),
        body = stringResource(R.string.recover_body, scaledDurationText(Durations.DAY)),
        primaryLabel = if (state.step == RecoverStep.LOADING) null else stringResource(R.string.recover_scan),
        onPrimary = actions::scan,
        busy = state.busy || state.step == RecoverStep.LOADING,
        // Under "Scan the code" (ANDROID-PLAN 0.1.33), as the setup code's screen has it.
        secondaryLabel = if (state.step == RecoverStep.LOADING) null else stringResource(R.string.recover_type),
        onSecondary = actions::type,
        onBack = onBack,
    ) {
        StagingTimeNoteIfShown()
        NoticeCard(NoticeKind.INFO, stringResource(R.string.recover_need_title), stringResource(R.string.recover_need_body))
        CodeRefusalNotice(state)
        ClaimFailure(state)
        Spacer(Modifier.height(Spacing.l))
        Link(stringResource(R.string.recover_open_site), onOpenAccountSite, "recover_open_site")
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
        CodeRefusal.OTHER_VAULT -> stringResource(R.string.recover_other_vault)
        CodeRefusal.NO_API -> stringResource(R.string.recover_scan_no_api)
        CodeRefusal.NOT_AVAILABLE -> stringResource(R.string.recover_not_available, scaledDurationText(Durations.DAY))
        CodeRefusal.NOT_OFFERED -> stringResource(R.string.recover_not_offered)
        CodeRefusal.TOO_MANY_ATTEMPTS -> stringResource(R.string.recover_too_many_attempts)
    }
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(
        if (r == CodeRefusal.BAD_CODE || r == CodeRefusal.NOT_A_CODE || r == CodeRefusal.RETRY) NoticeKind.WARNING else NoticeKind.URGENT,
        stringResource(R.string.recover_failed_title),
        text,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("code_refused"),
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
        // A registered recovery can be left (A6 audit E1): also when the release check fails or blocks the PIN.
        secondaryLabel = stringResource(R.string.recover_start_over),
        onSecondary = actions::askStartOver,
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
                    failureText(it.messageRes()),
                    Modifier.testTag("preflight_error"),
                )
            }
            if (p != null) PreflightNotices(p, true, {}, state.approveOffer, actions::setApproveOffer, state.notes)
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
            // A lock that came first (#245 review): the unlock screen's own words, not "Something went wrong".
            val lockFirst = state.error?.let { k ->
                when {
                    !openedThenLocked(k, state.errorCode) -> null
                    state.errorCode == MoveRepository.CODE_LOCK_PENDING -> stringResource(R.string.unlock_lock_pending)
                    else -> stringResource(R.string.unlock_not_opened)
                }
            }
            if (state.error != null) Failure(state.error, lockFirst, shownCode = state.errorShown)
        }
    }
}

/** "Your vault is connected to this phone now", after a recovery or a transfer: the vault itself never is on a phone. */
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
            RookLogo(height = 72.dp)
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
    BackHandler(enabled = state.step != TransferInStep.DONE && state.step != TransferInStep.FINISHING_FAILED) { back() }
    // (A6 audit N1) Cancelling once the code was shown: the old phone may already have approved the move.
    if (state.confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.transfer_in_cancel_title),
            text = stringResource(R.string.transfer_in_cancel_body),
            confirmLabel = stringResource(R.string.transfer_in_cancel_confirm),
            onConfirm = actions::confirmCancel,
            onDismiss = actions::dismissCancel,
            destructive = true,
            dismissLabel = stringResource(R.string.transfer_in_keep_waiting),
            modifier = Modifier.testTag("transfer_cancel_confirm"),
        )
    }
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
            Failure(
                state.error,
                if (state.error == FailureKind.NO_RESPONSE) stringResource(R.string.transfer_in_no_answer) else null,
                shownCode = state.errorShown,
            )
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
            Failure(
                state.error,
                if (state.error == FailureKind.NO_RESPONSE) stringResource(R.string.transfer_in_no_answer) else null,
                shownCode = state.errorShown,
            )
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
            NoKeyboardLearning {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = actions::setInput,
                    label = { Text(stringResource(R.string.transfer_in_paste_label)) },
                    minLines = 3,
                    isError = state.inputProblem != null,
                    // The transfer code is typed or pasted on a keyboard that does not suggest or learn it (A6 audit S2).
                    keyboardOptions = visibleSecretKeyboard(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.submitInput() }),
                    modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("transfer_code"),
                )
            }
            InputProblem(state.inputProblem)
            Failure(
                state.error,
                if (state.error == FailureKind.NO_RESPONSE) stringResource(R.string.transfer_in_no_answer) else null,
                shownCode = state.errorShown,
            )
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
            // (A6 audit N1) The screen stays on while the member approves on the old phone.
            KeepScreenOn()
            state.sas?.let { SasBox(it) }
            Spacer(Modifier.height(Spacing.l))
            Waiting(stringResource(R.string.transfer_in_waiting, formatWait(state.secondsLeft)), Modifier.testTag("waiting_old_phone"))
        }
        // Release R (§11.14.10): the new phone finishes the move with its own secret before anything else.
        TransferInStep.DONE -> if (state.phoneSecret) {
            val finish: FinishMoveViewModel = hiltViewModel()
            val finishState by finish.uiState.collectAsStateWithLifecycle()
            FinishMovePane(finishState, finish, actions::finish)
        } else {
            MovedScreen(
                stringResource(
                    if (state.canUnlockLater) {
                        R.string.onboarding_moved_transferred_body
                    } else {
                        R.string.onboarding_moved_transferred_no_guid_body
                    },
                ),
                actions::finish,
            )
        }
        TransferInStep.REJECTED -> FormScaffold(
            title = stringResource(R.string.transfer_in_rejected_title),
            body = stringResource(R.string.transfer_in_rejected_body),
            primaryLabel = stringResource(R.string.transfer_in_again),
            onPrimary = actions::again,
            secondaryLabel = stringResource(R.string.move_back),
            onSecondary = onLeave,
            modifier = Modifier.testTag("transfer_rejected"),
        ) {}
        // (A6 audit N1) The 11-minute deadline passed with no approval (after one collect): dropped; a new code.
        TransferInStep.TIMED_OUT -> FormScaffold(
            title = stringResource(R.string.transfer_in_timeout_title),
            body = stringResource(R.string.transfer_in_timeout_body),
            primaryLabel = stringResource(R.string.transfer_in_again),
            onPrimary = actions::again,
            secondaryLabel = stringResource(R.string.move_back),
            onSecondary = onLeave,
            modifier = Modifier.testTag("transfer_timed_out"),
        ) {}
        // (A6 audit N1) Approved, but the take-over failed: kept, tried again.
        TransferInStep.FINISHING_FAILED -> FormScaffold(
            title = stringResource(R.string.transfer_in_finishing_title),
            body = stringResource(R.string.transfer_in_finishing_body),
            primaryLabel = stringResource(R.string.transfer_in_again),
            onPrimary = actions::retryFinish,
            busy = state.busy,
            modifier = Modifier.testTag("transfer_finishing_failed"),
        ) {
            Failure(state.error, null, shownCode = state.errorShown)
        }
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

/** (A6 audit N1) Keeps the screen on while it is shown. */
@Composable
private fun KeepScreenOn() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
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
