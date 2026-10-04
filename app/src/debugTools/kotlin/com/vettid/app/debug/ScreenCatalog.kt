// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber", "TooManyFunctions")

package com.vettid.app.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vettid.app.ui.AccountSheet
import com.vettid.app.ui.LocalThemeController
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AttestationInfo
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.vault.EnrollStep
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.RecoveryView
import com.vettid.core.data.vault.ReleaseInfoView
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.StepState
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.feature.credential.AlarmActions
import com.vettid.feature.credential.AlarmContent
import com.vettid.feature.credential.AlarmStep
import com.vettid.feature.credential.AlarmUiState
import com.vettid.feature.credential.BackupOffDialog
import com.vettid.feature.credential.ChangePasswordContent
import com.vettid.feature.credential.ChangePasswordUiState
import com.vettid.feature.credential.CredentialActions
import com.vettid.feature.credential.CredentialContent
import com.vettid.feature.credential.CredentialUiState
import com.vettid.feature.credential.RotateContent
import com.vettid.feature.credential.RotateUiState
import com.vettid.feature.onboarding.AppLockScreen
import com.vettid.feature.onboarding.OnboardingActions
import com.vettid.feature.onboarding.OnboardingContent
import com.vettid.feature.onboarding.OnboardingStep
import com.vettid.feature.onboarding.OnboardingUiState
import com.vettid.feature.onboarding.UnlockActions
import com.vettid.feature.onboarding.UnlockContent
import com.vettid.feature.onboarding.UnlockMessage
import com.vettid.feature.onboarding.UnlockUiState
import com.vettid.feature.settings.AttestationContent
import com.vettid.feature.settings.ChangePinContent
import com.vettid.feature.settings.ChangePinUiState
import com.vettid.feature.settings.DeleteVaultActions
import com.vettid.feature.settings.DeleteVaultContent
import com.vettid.feature.settings.DeleteVaultUiState
import com.vettid.feature.settings.LoadState
import com.vettid.feature.settings.RecoveryContent
import com.vettid.feature.settings.RecoveryUiState
import com.vettid.feature.settings.SettingsActions
import com.vettid.feature.settings.SettingsContent
import com.vettid.feature.settings.SettingsUiState
import com.vettid.feature.settings.VaultStatusContent
import java.time.Instant

/** No-op actions for the catalog. */
private object NoOnboarding : OnboardingActions {
    override fun start() = Unit
    override fun setEmail(v: String) = Unit
    override fun submitEmail() = Unit
    override fun resendLink() = Unit
    override fun setLinkInput(v: String) = Unit
    override fun submitLink() = Unit
    override fun confirmSignIn() = Unit
    override fun back(): Boolean = false
    override fun setAccountPin(v: String) = Unit
    override fun submitAccountPin() = Unit
    override fun checkAgain() = Unit
    override fun useAnotherAccount() = Unit
    override fun enrollAnyway() = Unit
    override fun setPin(v: String) = Unit
    override fun submitPin() = Unit
    override fun setPinConfirm(v: String) = Unit
    override fun submitPinConfirm() = Unit
    override fun setPassword(v: String) = Unit
    override fun setPasswordConfirm(v: String) = Unit
    override fun submitPassword() = Unit
    override fun setBackup(on: Boolean) = Unit
    override fun acknowledgeBackupOff(ack: Boolean) = Unit
    override fun submitBackup() = Unit
    override fun run() = Unit
    override fun editAfterFailure() = Unit
    override fun finish() = Unit
}

private object NoUnlock : UnlockActions {
    override fun retryPreflight() = Unit
    override fun acknowledgeUpdate() = Unit
    override fun setApproveOffer(approve: Boolean) = Unit
    override fun setPin(v: String) = Unit
    override fun submit() = Unit
    override fun cancelRecoveryAndUnlock() = Unit
    override fun signOut() = Unit
}

/**
 * DEBUG ONLY. Every A3 screen with sample state, for screenshots without a
 * vault: `--es vettid.start screen:<name>` (names below). Sample values are
 * made up; nothing here talks to a vault.
 */
object ScreenCatalog {
    private const val EMAIL = "sam@example.org"
    private fun release(n: Long, status: String = "active") =
        ReleaseView(n, "%02d".format(n).repeat(48), status, null, "https://vettid.org/security/releases/$n")

    private val onboarding = OnboardingUiState(email = EMAIL)
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private val credential = CredentialStatus(true, 7, "Jk4m2Qx9TzA1", "2026-10-04T14:12:00Z", null, true, 300, 3)
    private val alarm = CredentialAlarm("01JABCDEF0123456789ABCDEFG", CredentialAlarm.STATE_FROZEN, "2026-10-04T14:20:00Z", "other")

    @Composable
    private fun Ob(state: OnboardingUiState) = OnboardingContent(state, NoOnboarding) {}

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "onboarding.welcome" to { Ob(onboarding) },
        "onboarding.email" to { Ob(onboarding.copy(step = OnboardingStep.EMAIL)) },
        "onboarding.check_email" to { Ob(onboarding.copy(step = OnboardingStep.CHECK_EMAIL)) },
        "onboarding.confirm" to { Ob(onboarding.copy(step = OnboardingStep.CONFIRM_SIGN_IN)) },
        "onboarding.account_pin" to { Ob(onboarding.copy(step = OnboardingStep.ACCOUNT_PIN, accountPin = "12")) },
        "onboarding.terms" to { Ob(onboarding.copy(step = OnboardingStep.TERMS)) },
        "onboarding.elsewhere" to { Ob(onboarding.copy(step = OnboardingStep.VAULT_ELSEWHERE)) },
        "onboarding.pin" to { Ob(onboarding.copy(step = OnboardingStep.PIN_CREATE, pin = "1234")) },
        "onboarding.pin_problem" to {
            Ob(onboarding.copy(step = OnboardingStep.PIN_CREATE, pin = "123456", pinProblem = com.vettid.core.data.policy.PinPolicy.Problem.SEQUENCE))
        },
        "onboarding.pin_confirm" to { Ob(onboarding.copy(step = OnboardingStep.PIN_CONFIRM, pinConfirm = "97531", pinMismatch = true)) },
        "onboarding.password" to {
            Ob(
                onboarding.copy(
                    step = OnboardingStep.PASSWORD, password = "correct horse battery", passwordConfirm = "correct horse battery",
                    passwordStrength = PasswordPolicy.Strength.GOOD,
                ),
            )
        },
        "onboarding.backup" to { Ob(onboarding.copy(step = OnboardingStep.BACKUP)) },
        "onboarding.backup_off" to { Ob(onboarding.copy(step = OnboardingStep.BACKUP, backup = false)) },
        "onboarding.progress" to {
            Ob(
                onboarding.copy(
                    step = OnboardingStep.PROGRESS, busy = true,
                    progress = EnrollStep.entries.mapIndexed { i, s -> s to if (i < 3) StepState.DONE else if (i == 3) StepState.ACTIVE else StepState.PENDING },
                ),
            )
        },
        "onboarding.progress_failed" to {
            Ob(
                onboarding.copy(
                    step = OnboardingStep.PROGRESS, error = FailureKind.ATTESTATION,
                    progress = EnrollStep.entries.mapIndexed { i, s -> s to if (i == 0) StepState.FAILED else StepState.PENDING },
                ),
            )
        },
        "onboarding.done" to { Ob(onboarding.copy(step = OnboardingStep.DONE)) },
        "unlock" to { UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), pin = "1234"), NoUnlock) },
        "unlock.updated" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(4, "deprecated"), 3, true, false, release(5))), NoUnlock)
        },
        "unlock.rollback" to { UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(2), 3, false, true, null)), NoUnlock) },
        "unlock.backoff" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), message = UnlockMessage.BadPin, waitSeconds = 75),
                NoUnlock,
            )
        },
        "unlock.recovery" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), recoveryPending = true), NoUnlock)
        },
        "unlock.ended" to { UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflightError = FailureKind.RELEASE_ENDED), NoUnlock) },
        "app_lock" to { AppLockScreen(onUnlock = {}) },
        "credential" to { CredentialContent(CredentialUiState(loading = false, status = credential, windowUntil = Instant.now().plusSeconds(240)), chrome, CredentialActions()) },
        "credential.alarm_banner" to {
            Column(Modifier.fillMaxSize()) {
                UrgentBanner(stringResource(com.vettid.feature.credential.R.string.credential_alarm_banner), stringResource(com.vettid.feature.credential.R.string.credential_alarm_review), {})
                CredentialContent(CredentialUiState(loading = false, status = credential, alarm = alarm), chrome, CredentialActions())
            }
        },
        "credential.window_dialog" to {
            CredentialContent(CredentialUiState(loading = false, status = credential, windowDialog = true, windowPassword = "secret"), chrome, CredentialActions())
        },
        "credential.backup_off" to {
            CredentialContent(CredentialUiState(loading = false, status = credential), chrome, CredentialActions())
            BackupOffDialog({}, {})
        },
        "credential.password" to {
            ChangePasswordContent(ChangePasswordUiState(current = "old password!", password = "a new long passphrase", strength = PasswordPolicy.Strength.STRONG), {}, {}, {}, {}, {})
        },
        "credential.rotate" to { RotateContent(RotateUiState(), {}, {}, {}) },
        "credential.alarm" to { AlarmContent(AlarmUiState(alarm = alarm), AlarmActions()) },
        "credential.alarm_rotate" to {
            AlarmContent(AlarmUiState(alarm = alarm.copy(state = CredentialAlarm.STATE_ROTATION_REQUIRED), step = AlarmStep.ROTATE, notMine = true), AlarmActions())
        },
        "settings" to {
            SettingsContent(
                SettingsUiState(
                    account = AccountInfo(EMAIL, "Sam", "Rivera"),
                    preferences = AppPreferences(ThemePreference.SYSTEM, true, AppLockTimeout.FIVE_MINUTES), appLockOn = true,
                ),
                SettingsActions(),
            )
        },
        "settings.status" to {
            VaultStatusContent(
                LoadState(
                    false,
                    VaultOverview(
                        "3f9c2a7be41d4c0e9b8a6f5d2c1e0a9b", "unlocked", ReleaseInfoView(3, "deprecated", "2027-01-15T00:00:00Z", 4, "update_available"),
                        3, null, false, 1, 4,
                    ),
                ),
                {}, {},
            )
        },
        "settings.pin" to { ChangePinContent(ChangePinUiState(current = "975310", pin = "1111"), {}, {}, {}, {}, {}) },
        "settings.recovery" to {
            RecoveryContent(
                RecoveryUiState(loading = false, recovery = RecoveryView("01JABCDEF0123456789ABCDEFG", "pending", "2026-10-05T14:00:00Z", "2026-10-06T14:00:00Z")),
                {}, {}, {},
            )
        },
        "settings.attestation" to {
            AttestationContent(LoadState(false, AttestationInfo("devStack", true, "STRONG_BOX", true, 400, "SelfSigned", true, "4e8e e8f7 1c2d 3e4f", 3, "0303 0303 0303 0303")), {})
        },
        "settings.delete" to { DeleteVaultContent(DeleteVaultUiState(phrase = "delete my vault", pin = "975310", password = "pw", acknowledged = true), DeleteVaultActions()) },
        "settings.delete_confirm" to {
            DeleteVaultContent(DeleteVaultUiState(phrase = "delete my vault", pin = "975310", password = "pw", acknowledged = true, confirming = true), DeleteVaultActions())
        },
        "gallery" to { GalleryScreen(themeMode = LocalThemeController.current.mode, onThemeModeChange = {}, onBack = {}) },
        "account_sheet" to { AccountSheet(name = "Sam Rivera", detail = EMAIL, onDismiss = {}, onLockVault = {}, onSignOut = {}) },
    )
}
