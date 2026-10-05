package com.vettid.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.policy.labelRes
import com.vettid.core.data.policy.messageArg
import com.vettid.core.data.policy.messageRes
import com.vettid.core.data.vault.EnrollStep
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.RookLogo
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.StepList
import com.vettid.core.ui.components.StrengthMeter
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/** What the onboarding screens can ask for (the ViewModel; a no-op in previews and the screen catalog). */
@Suppress("TooManyFunctions")
interface OnboardingActions {
    fun start()
    fun startRecovery()
    fun startTransfer()
    fun recover()
    fun transfer()
    fun leaveMove()
    fun newVaultAfterMove()
    fun acknowledgeReplaced()
    fun setEmail(v: String)
    fun submitEmail()
    fun resendLink()
    fun setLinkInput(v: String)
    fun submitLink()
    fun confirmSignIn()
    fun back(): Boolean
    fun setAccountPin(v: String)
    fun submitAccountPin()
    fun checkAgain()
    fun useAnotherAccount()
    fun enrollAnyway()
    fun setPin(v: String)
    fun submitPin()
    fun setPinConfirm(v: String)
    fun submitPinConfirm()
    fun setPassword(v: String)
    fun setPasswordConfirm(v: String)
    fun submitPassword()
    fun setBackup(on: Boolean)
    fun acknowledgeBackupOff(ack: Boolean)
    fun submitBackup()
    fun run()
    fun editAfterFailure()
    fun finish()
}

/** The onboarding flow, driven by [OnboardingViewModel]. [onOpenAccountSite] opens account.vettid.org. */
@Composable
fun OnboardingFlow(onOpenAccountSite: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingContent(state, viewModel, onOpenAccountSite)
}

private val BACKABLE = setOf(
    OnboardingStep.EMAIL, OnboardingStep.CHECK_EMAIL, OnboardingStep.CONFIRM_SIGN_IN, OnboardingStep.ACCOUNT_PIN,
    OnboardingStep.PIN_CONFIRM, OnboardingStep.BACKUP,
)

/** One onboarding step for [state] (stateless). */
@Suppress("CyclomaticComplexMethod")
@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    actions: OnboardingActions,
    onOpenAccountSite: () -> Unit,
    recover: @Composable (onLeave: () -> Unit, onNewVault: () -> Unit, onOpenAccountSite: () -> Unit) -> Unit =
        { l, n, o -> RecoverFlow(l, n, o) },
    transferIn: @Composable (onLeave: () -> Unit) -> Unit = { l -> TransferInFlow(l) },
) {
    val canBack = state.step in BACKABLE || (state.step == OnboardingStep.PASSWORD && !state.credentialOnly)
    BackHandler(enabled = canBack) { actions.back() }
    val back: (() -> Unit)? = if (canBack) ({ actions.back(); Unit }) else null
    when (state.step) {
        OnboardingStep.WELCOME -> WelcomeScreen(actions::start, actions::startTransfer, actions::startRecovery)
        OnboardingStep.EMAIL -> EmailScreen(state, actions, back)
        OnboardingStep.CHECK_EMAIL -> CheckEmailScreen(state, actions, back)
        OnboardingStep.CONFIRM_SIGN_IN -> ConfirmSignInScreen(state, actions, back)
        OnboardingStep.ACCOUNT_PIN -> AccountPinScreen(state, actions, back)
        OnboardingStep.TERMS -> TermsScreen(state, actions, onOpenAccountSite)
        OnboardingStep.VAULT_ELSEWHERE -> VaultElsewhereScreen(state, actions, onOpenAccountSite)
        OnboardingStep.PIN_CREATE -> PinCreateScreen(state, actions)
        OnboardingStep.PIN_CONFIRM -> PinConfirmScreen(state, actions, back)
        OnboardingStep.PASSWORD -> PasswordScreen(state, actions, back)
        OnboardingStep.BACKUP -> BackupScreen(state, actions, back)
        OnboardingStep.PROGRESS -> ProgressScreen(state, actions)
        OnboardingStep.DONE -> DoneScreen(actions)
        OnboardingStep.RECOVER -> recover(actions::leaveMove, actions::newVaultAfterMove, onOpenAccountSite)
        OnboardingStep.TRANSFER_IN -> transferIn(actions::leaveMove)
        OnboardingStep.REPLACED ->
            ReplacedScreen(state.replacedReason, actions::acknowledgeReplaced, actions::useAnotherAccount, state.busy)
    }
}

@Composable
private fun ErrorText(state: OnboardingUiState, override: FailureKind? = null, overrideText: String? = null) {
    val kind = state.error ?: return
    val text = if (override != null && kind == override && overrideText != null) overrideText else stringResource(kind.messageRes())
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(NoticeKind.URGENT, stringResource(R.string.onboarding_progress_failed), text, Modifier.testTag("error"))
}

@Composable
private fun DevHint(state: OnboardingUiState) {
    val hint = state.devHint ?: return
    Spacer(Modifier.height(Spacing.l))
    NoticeCard(NoticeKind.INFO, stringResource(R.string.onboarding_dev_title), hint)
}

@Composable
fun WelcomeScreen(onStart: () -> Unit, onTransfer: () -> Unit, onRecover: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
        primaryLabel = stringResource(R.string.onboarding_welcome_start),
        onPrimary = onStart,
        secondaryLabel = stringResource(R.string.onboarding_welcome_transfer),
        onSecondary = onTransfer,
        header = {
            Spacer(Modifier.height(Spacing.xxl))
            RookLogo(height = 88.dp)
            Spacer(Modifier.height(Spacing.xl))
        },
    ) {
        Point(Icons.Outlined.Key, stringResource(R.string.onboarding_welcome_point_keys))
        Spacer(Modifier.height(Spacing.l))
        Point(Icons.Outlined.VerifiedUser, stringResource(R.string.onboarding_welcome_point_member))
        Spacer(Modifier.height(Spacing.l))
        MoveLink(stringResource(R.string.onboarding_welcome_recover), onRecover, "welcome_recover")
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.l))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun EmailScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_email_title),
        body = stringResource(R.string.onboarding_email_body),
        primaryLabel = stringResource(R.string.onboarding_email_send),
        onPrimary = actions::submitEmail,
        primaryEnabled = state.email.isNotBlank(),
        busy = state.busy,
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = state.email,
            onValueChange = actions::setEmail,
            label = { Text(stringResource(R.string.onboarding_email_label)) },
            singleLine = true,
            isError = state.emailInvalid,
            supportingText = if (state.emailInvalid) ({ Text(stringResource(R.string.onboarding_email_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { actions.submitEmail() }),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        ErrorText(state)
        DevHint(state)
    }
}

@Composable
fun CheckEmailScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_check_title),
        body = stringResource(R.string.onboarding_check_body, state.email),
        primaryLabel = stringResource(R.string.onboarding_check_continue),
        onPrimary = actions::submitLink,
        primaryEnabled = state.linkInput.isNotBlank(),
        busy = state.busy,
        secondaryLabel = stringResource(R.string.onboarding_check_resend),
        onSecondary = actions::resendLink,
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = state.linkInput,
            onValueChange = actions::setLinkInput,
            label = { Text(stringResource(R.string.onboarding_check_paste_label)) },
            singleLine = true,
            isError = state.linkInvalid,
            supportingText = if (state.linkInvalid) ({ Text(stringResource(R.string.onboarding_check_paste_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { actions.submitLink() }),
            modifier = Modifier.fillMaxWidth().testTag("link"),
        )
        ErrorText(state, FailureKind.UNAUTHORIZED, stringResource(R.string.onboarding_link_expired))
        DevHint(state)
    }
}

@Composable
fun ConfirmSignInScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_confirm_title),
        body = stringResource(R.string.onboarding_confirm_body, state.email),
        primaryLabel = stringResource(R.string.onboarding_confirm_sign_in),
        onPrimary = actions::confirmSignIn,
        busy = state.busy,
        secondaryLabel = stringResource(R.string.onboarding_confirm_cancel),
        onSecondary = { actions.back() },
        onBack = onBack,
    ) {
        ErrorText(state, FailureKind.UNAUTHORIZED, stringResource(R.string.onboarding_link_expired))
    }
}

@Composable
fun AccountPinScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_account_pin_title),
        body = stringResource(R.string.onboarding_account_pin_body),
        primaryLabel = stringResource(R.string.onboarding_continue),
        onPrimary = actions::submitAccountPin,
        primaryEnabled = state.accountPin.length >= 4,
        busy = state.busy,
        onBack = onBack,
    ) {
        SecretField(
            value = state.accountPin,
            onValueChange = actions::setAccountPin,
            label = stringResource(R.string.onboarding_account_pin_label),
            isPin = true,
            onImeAction = actions::submitAccountPin,
            modifier = Modifier.testTag("account_pin"),
        )
        ErrorText(state, FailureKind.UNAUTHORIZED, stringResource(R.string.onboarding_account_pin_wrong))
    }
}

@Composable
fun TermsScreen(state: OnboardingUiState, actions: OnboardingActions, onOpenAccountSite: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.onboarding_terms_title),
        body = stringResource(
            if (state.termsUpdated) R.string.onboarding_terms_body_updated else R.string.onboarding_terms_body_registered,
        ),
        primaryLabel = stringResource(R.string.onboarding_terms_open),
        onPrimary = onOpenAccountSite,
        secondaryLabel = stringResource(R.string.onboarding_terms_check),
        onSecondary = actions::checkAgain,
        busy = state.busy,
    ) {
        ErrorText(state)
        Spacer(Modifier.height(Spacing.l))
        TextLink(stringResource(R.string.onboarding_other_account), actions::useAnotherAccount)
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
        Text(label, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun VaultElsewhereScreen(state: OnboardingUiState, actions: OnboardingActions, onOpenAccountSite: () -> Unit) {
    FormScaffold(
        title = stringResource(R.string.onboarding_elsewhere_title),
        body = stringResource(R.string.onboarding_elsewhere_body),
        primaryLabel = stringResource(R.string.onboarding_elsewhere_transfer),
        onPrimary = actions::transfer,
        secondaryLabel = stringResource(R.string.onboarding_elsewhere_recover),
        onSecondary = actions::recover,
        busy = state.busy,
    ) {
        NoticeCard(
            NoticeKind.INFO,
            stringResource(R.string.onboarding_elsewhere_transfer_title),
            stringResource(R.string.onboarding_elsewhere_transfer_body),
            modifier = Modifier.testTag("elsewhere_transfer"),
        )
        Spacer(Modifier.height(Spacing.m))
        NoticeCard(
            NoticeKind.WARNING,
            stringResource(R.string.onboarding_elsewhere_recovery_title),
            stringResource(R.string.onboarding_elsewhere_recovery_body),
            modifier = Modifier.testTag("elsewhere_recovery"),
            actions = {
                androidx.compose.material3.TextButton(onClick = onOpenAccountSite) {
                    Text(stringResource(R.string.onboarding_elsewhere_open))
                }
            },
        )
        Spacer(Modifier.height(Spacing.l))
        TextLink(stringResource(R.string.onboarding_elsewhere_anyway), actions::enrollAnyway)
        TextLink(stringResource(R.string.onboarding_other_account), actions::useAnotherAccount)
    }
}

/** The message for a PIN that breaks [PinPolicy]. */
@Composable
fun pinProblemText(p: PinPolicy.Problem): String = p.messageArg()?.let { stringResource(p.messageRes(), it) }
    ?: stringResource(p.messageRes())

@Composable
fun PinCreateScreen(state: OnboardingUiState, actions: OnboardingActions) {
    FormScaffold(
        title = stringResource(R.string.onboarding_pin_title),
        body = stringResource(R.string.onboarding_pin_body),
        primaryLabel = stringResource(R.string.onboarding_continue),
        onPrimary = actions::submitPin,
        primaryEnabled = state.pin.isNotEmpty(),
    ) {
        SecretField(
            value = state.pin,
            onValueChange = actions::setPin,
            label = stringResource(R.string.onboarding_pin_label),
            isPin = true,
            error = state.pinProblem?.let { pinProblemText(it) },
            onImeAction = actions::submitPin,
            modifier = Modifier.testTag("pin"),
        )
        ErrorText(state)
    }
}

@Composable
fun PinConfirmScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_pin_confirm_title),
        body = stringResource(R.string.onboarding_pin_confirm_body),
        primaryLabel = stringResource(R.string.onboarding_continue),
        onPrimary = actions::submitPinConfirm,
        primaryEnabled = state.pinConfirm.isNotEmpty(),
        onBack = onBack,
    ) {
        SecretField(
            value = state.pinConfirm,
            onValueChange = actions::setPinConfirm,
            label = stringResource(R.string.onboarding_pin_confirm_label),
            isPin = true,
            error = if (state.pinMismatch) stringResource(R.string.onboarding_pin_mismatch) else null,
            onImeAction = actions::submitPinConfirm,
            modifier = Modifier.testTag("pin_confirm"),
        )
    }
}

/** The label of a strength level. */
@Composable
fun strengthLabel(s: PasswordPolicy.Strength): String = stringResource(s.labelRes())

/** The message for a password that breaks [PasswordPolicy]. */
@Composable
fun passwordProblemText(p: PasswordPolicy.Problem): String = stringResource(p.messageRes())

/**
 * A new credential password with its confirmation and the strength meter
 * (onboarding; the credential feature has its own copy for password changes).
 */
@Composable
fun NewPasswordFields(
    password: String,
    confirm: String,
    strength: PasswordPolicy.Strength,
    problem: PasswordPolicy.Problem?,
    mismatch: Boolean,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDone: () -> Unit,
) {
    SecretField(
        value = password,
        onValueChange = onPassword,
        label = stringResource(R.string.onboarding_password_label),
        error = problem?.let { passwordProblemText(it) },
        imeAction = ImeAction.Next,
        modifier = Modifier.testTag("password"),
    )
    Spacer(Modifier.height(Spacing.s))
    StrengthMeter(level = strength.ordinal, label = strengthLabel(strength))
    Spacer(Modifier.height(Spacing.l))
    SecretField(
        value = confirm,
        onValueChange = onConfirm,
        label = stringResource(R.string.onboarding_password_confirm_label),
        error = if (mismatch) stringResource(R.string.onboarding_password_mismatch) else null,
        onImeAction = onDone,
        modifier = Modifier.testTag("password_confirm"),
    )
}

@Composable
fun PasswordScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_password_title),
        body = stringResource(R.string.onboarding_password_body),
        primaryLabel = stringResource(R.string.onboarding_continue),
        onPrimary = actions::submitPassword,
        primaryEnabled = state.password.isNotEmpty() && state.passwordConfirm.isNotEmpty(),
        onBack = onBack,
    ) {
        NewPasswordFields(
            state.password, state.passwordConfirm, state.passwordStrength, state.passwordProblem, state.passwordMismatch,
            actions::setPassword, actions::setPasswordConfirm, actions::submitPassword,
        )
        ErrorText(state)
    }
}

@Composable
private fun ChoiceCard(selected: Boolean, title: String, body: String, onSelect: () -> Unit, tag: String) {
    Surface(
        shape = VettIdShape.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.xs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The backup choice cards and, when off, the warning with its acknowledgement (§3.5.6). Shared with Credential. */
@Composable
fun BackupChoice(backup: Boolean, acknowledged: Boolean, onBackup: (Boolean) -> Unit, onAcknowledge: (Boolean) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        ChoiceCard(
            backup,
            stringResource(R.string.onboarding_backup_on),
            stringResource(R.string.onboarding_backup_on_body),
            { onBackup(true) },
            "backup_on",
        )
        ChoiceCard(
            !backup,
            stringResource(R.string.onboarding_backup_off),
            stringResource(R.string.onboarding_backup_off_body),
            { onBackup(false) },
            "backup_off",
        )
    }
    if (!backup) {
        Spacer(Modifier.height(Spacing.l))
        NoticeCard(
            NoticeKind.URGENT,
            stringResource(R.string.onboarding_backup_off_warning_title),
            stringResource(R.string.onboarding_backup_off_warning_body),
        )
        Spacer(Modifier.height(Spacing.s))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = onAcknowledge)
                .testTag("backup_off_ack"),
        ) {
            Checkbox(checked = acknowledged, onCheckedChange = null)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.onboarding_backup_off_ack), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun BackupScreen(state: OnboardingUiState, actions: OnboardingActions, onBack: (() -> Unit)?) {
    FormScaffold(
        title = stringResource(R.string.onboarding_backup_title),
        body = stringResource(R.string.onboarding_backup_body),
        primaryLabel = stringResource(R.string.onboarding_backup_submit),
        onPrimary = actions::submitBackup,
        primaryEnabled = state.canContinueBackup,
        onBack = onBack,
    ) {
        BackupChoice(state.backup, state.backupOffAcknowledged, actions::setBackup, actions::acknowledgeBackupOff)
    }
}

@Composable
private fun stepLabel(s: EnrollStep): String = stringResource(
    when (s) {
        EnrollStep.ENROLL -> R.string.onboarding_step_enroll
        EnrollStep.WAIT_FOR_VAULT -> R.string.onboarding_step_wait
        EnrollStep.HANDSHAKE -> R.string.onboarding_step_handshake
        EnrollStep.CREATE_CREDENTIAL -> R.string.onboarding_step_credential
        EnrollStep.BACKUP -> R.string.onboarding_step_backup
        EnrollStep.CONFIRM -> R.string.onboarding_step_confirm
    },
)

@Composable
fun ProgressScreen(state: OnboardingUiState, actions: OnboardingActions) {
    val failed = state.progressFailed || (state.error != null && !state.busy)
    FormScaffold(
        title = stringResource(R.string.onboarding_progress_title),
        body = stringResource(R.string.onboarding_progress_body),
        primaryLabel = if (failed) stringResource(R.string.onboarding_progress_retry) else null,
        onPrimary = actions::run,
        secondaryLabel = if (failed) stringResource(R.string.onboarding_progress_edit) else null,
        onSecondary = actions::editAfterFailure,
    ) {
        StepList(state.progress.map { (s, st) -> stepLabel(s) to st }, Modifier.testTag("progress"))
        ErrorText(state)
    }
}

@Composable
fun DoneScreen(actions: OnboardingActions) {
    FormScaffold(
        title = stringResource(R.string.onboarding_done_title),
        body = stringResource(R.string.onboarding_done_body),
        primaryLabel = stringResource(R.string.onboarding_done_go),
        onPrimary = actions::finish,
        header = {
            Spacer(Modifier.height(Spacing.xl))
            RookLogo(height = 72.dp)
            Spacer(Modifier.height(Spacing.l))
        },
    ) {
        NoticeCard(NoticeKind.INFO, stringResource(R.string.onboarding_done_next_title), stringResource(R.string.onboarding_done_next_body))
    }
}
