package com.vettid.core.testing

import com.vettid.core.altchan.SignInStatus
import com.vettid.core.data.account.SignInLink
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.AttestationInfo
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.vault.EnrollStep
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.RecoveryView
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.RecoverOutcome
import com.vettid.core.data.vault.RecoveryRegistration
import com.vettid.core.data.vault.RecoveryStage
import com.vettid.core.data.vault.RecoveryTarget
import com.vettid.core.data.vault.TransferOfferView
import com.vettid.core.data.vault.TransferPendingView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * TEST ONLY. An in-memory account, vault and credential for ViewModel tests:
 * every call is recorded in [calls]; [fail] makes the next calls of a name
 * throw; the unlock outcomes are scripted in [unlockResults].
 */
@Suppress("TooManyFunctions")
class FakeVault(initial: AppPhase = AppPhase.SignedOut) : AccountRepository, VaultRepository, CredentialRepository, MoveRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()
    val unlockResults = ArrayDeque<UnlockAttempt>()
    var signInStatus = SignInStatus.SIGNED_IN
    var phaseAfterSignIn: AppPhase = AppPhase.Setup(SetupStage.NEW_VAULT)
    var preflightInfo = PreflightInfo(release(3), 3, softwareUpdated = false, rollback = false, offer = null)
    var credential = CredentialStatus(
        true,
        2,
        "abc",
        "2026-10-04T12:00:00Z",
        null,
        backup = true,
        unlockTtlSeconds = 300,
        criticalItems = 0,
    )
    var overviewValue = VaultOverview("0123456789abcdef0123456789abcdef", "unlocked", null, 3, null, false, 1, 0)
    var recoveryValue: RecoveryView? = null
    var lastPin: String? = null
    var lastPassword: String? = null
    var lastBackup: Boolean? = null
    var lastApproved: ReleaseView? = null
    var lastCancelRecovery = false

    // --- recovery and transfer (MoveRepository) ---
    var recoveryStageValue = RecoveryStage.CODE
    var recoveryTargetValue = RecoveryTarget("0123456789abcdef0123456789abcdef", null)
    val registrations = ArrayDeque<RecoveryRegistration>()
    var lastRegistered: Triple<String, String, String>? = null
    val recoveryUnlockResults = ArrayDeque<UnlockAttempt>()
    var recoverOutcome = RecoverOutcome.RECOVERED
    var transferSas = "042817"
    var lastTransferCode: String? = null

    /** Completes (or fails) the new phone's wait for the old phone's approval. */
    var transferApproval = CompletableDeferred<Unit>()
    var offer = TransferOfferView("01JTRANSFER000000000000000", "{\"v\":2,\"t\":\"p\"}", "eyJ2Ijoy", Instant.parse("2026-10-05T12:10:00Z"))

    /** Completes the old phone's wait for `device.transfer.pending` (null: the code expired). */
    var pendingTransfer = CompletableDeferred<TransferPendingView?>()
    var lastTransferApproved: String? = null
    var lastTransferRejected: String? = null

    override val phase = MutableStateFlow(initial)
    override val account = MutableStateFlow<AccountInfo?>(null)
    override val pendingEmail = MutableStateFlow<String?>(null)
    override val alarm = MutableStateFlow<CredentialAlarm?>(null)
    override val unlockWindow = MutableStateFlow<Instant?>(null)
    override val devHint: String? = null
    override val signInHosts: Set<String> = setOf(SignInLink.HOST)

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    override suspend fun refresh() = call("refresh")

    override suspend fun startSignIn(email: String) {
        call("startSignIn")
        pendingEmail.value = email
    }

    override suspend fun verifySignIn(email: String, link: SignInLink): SignInStatus {
        call("verifySignIn")
        if (signInStatus == SignInStatus.SIGNED_IN) signedIn(email)
        return signInStatus
    }

    override suspend fun signInPin(pin: String): SignInStatus {
        call("signInPin")
        signedIn(pendingEmail.value ?: "")
        return SignInStatus.SIGNED_IN
    }

    private fun signedIn(email: String) {
        account.value = AccountInfo(email, "Test", "Member")
        phase.value = phaseAfterSignIn
    }

    override suspend fun signOut() {
        call("signOut")
        phase.value = AppPhase.SignedOut
    }

    override suspend fun enroll(pin: String, onStep: (EnrollStep) -> Unit) {
        call("enroll")
        lastPin = pin
        listOf(EnrollStep.ENROLL, EnrollStep.WAIT_FOR_VAULT, EnrollStep.HANDSHAKE).forEach(onStep)
    }

    override suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit) {
        call("createCredential")
        lastPassword = password
        lastBackup = backup
        listOf(EnrollStep.CREATE_CREDENTIAL, EnrollStep.BACKUP, EnrollStep.CONFIRM).forEach(onStep)
        phase.value = AppPhase.Setup(SetupStage.FINISHING)
    }

    override suspend fun finishSetup() {
        call("finishSetup")
        phase.value = AppPhase.Unlocked
    }

    override suspend fun preflight(): PreflightInfo {
        call("preflight")
        return preflightInfo
    }

    override suspend fun unlock(pin: String, approve: ReleaseView?, cancelRecovery: Boolean): UnlockAttempt {
        call("unlock")
        lastPin = pin
        lastApproved = approve
        lastCancelRecovery = cancelRecovery
        val r = unlockResults.removeFirstOrNull() ?: UnlockAttempt.Success
        if (r == UnlockAttempt.Success) phase.value = AppPhase.Unlocked
        return r
    }

    override suspend fun lock() {
        call("lock")
        phase.value = AppPhase.Locked
    }

    override suspend fun overview(): VaultOverview {
        call("overview")
        return overviewValue
    }

    override suspend fun changePin(pin: String, newPin: String) {
        call("changePin")
        lastPin = newPin
    }

    override suspend fun deleteVault(pin: String, password: String) {
        call("deleteVault")
        lastPin = pin
        lastPassword = password
        phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
    }

    override suspend fun recovery(): RecoveryView? {
        call("recovery")
        return recoveryValue
    }

    override suspend fun cancelRecovery(recoveryId: String) {
        call("cancelRecovery")
        recoveryValue = recoveryValue?.copy(state = "cancelled")
    }

    override suspend fun attestationInfo(): AttestationInfo {
        call("attestationInfo")
        return AttestationInfo("test", true, "STRONG_BOX", true, 300, "SelfSigned", true, "4e8e e8f7", 3, "aaaa bbbb")
    }

    override suspend fun status(): CredentialStatus {
        call("status")
        return credential.copy(alarm = alarm.value)
    }

    override suspend fun openUnlockWindow(password: String) {
        call("openUnlockWindow")
        lastPassword = password
        unlockWindow.value = Instant.parse("2026-10-04T12:05:00Z")
    }

    override suspend fun closeUnlockWindow() {
        call("closeUnlockWindow")
        unlockWindow.value = null
    }

    override suspend fun changePassword(password: String, newPassword: String) {
        call("changePassword")
        lastPassword = newPassword
    }

    override suspend fun rotate(password: String) {
        call("rotate")
        lastPassword = password
        alarm.value = null
    }

    override suspend fun setBackup(on: Boolean) {
        call("setBackup")
        lastBackup = on
        credential = credential.copy(backup = on)
    }

    override suspend fun setUnlockTtl(seconds: Int) {
        call("setUnlockTtl")
        credential = credential.copy(unlockTtlSeconds = seconds)
    }

    override suspend fun confirmAlarm(mine: Boolean) {
        call("confirmAlarm")
        alarm.value = alarm.value?.copy(state = CredentialAlarm.STATE_ROTATION_REQUIRED)
    }

    // --- MoveRepository ---

    override suspend fun recoveryStage(): RecoveryStage {
        call("recoveryStage")
        return recoveryStageValue
    }

    override suspend fun recoveryTarget(): RecoveryTarget {
        call("recoveryTarget")
        return recoveryTargetValue
    }

    override suspend fun registerRecovery(vaultId: String, recoveryId: String, code: String): RecoveryRegistration {
        call("registerRecovery")
        lastRegistered = Triple(vaultId, recoveryId, code)
        val r = registrations.removeFirstOrNull() ?: RecoveryRegistration.Registered
        if (r == RecoveryRegistration.Registered) phase.value = AppPhase.Setup(SetupStage.RECOVERING)
        return r
    }

    override suspend fun recoveryPreflight(): PreflightInfo {
        call("recoveryPreflight")
        return preflightInfo
    }

    override suspend fun recoveryUnlock(pin: String, approve: ReleaseView?): UnlockAttempt {
        call("recoveryUnlock")
        lastPin = pin
        lastApproved = approve
        return recoveryUnlockResults.removeFirstOrNull() ?: UnlockAttempt.Success
    }

    override suspend fun recoverCredential(password: String): RecoverOutcome {
        call("recoverCredential")
        lastPassword = password
        if (recoverOutcome == RecoverOutcome.RECOVERED) phase.value = AppPhase.Setup(SetupStage.FINISHING)
        return recoverOutcome
    }

    override suspend fun resetCredential(password: String) {
        call("resetCredential")
        lastPassword = password
        phase.value = AppPhase.Setup(SetupStage.FINISHING)
    }

    override suspend fun deleteRecoveredVault(pin: String) {
        call("deleteRecoveredVault")
        lastPin = pin
        phase.value = AppPhase.Setup(SetupStage.NEW_VAULT)
    }

    override suspend fun transferIn(code: String): String {
        call("transferIn")
        lastTransferCode = code
        return transferSas
    }

    override suspend fun awaitTransferIn() {
        call("awaitTransferIn")
        transferApproval.await()
        phase.value = AppPhase.Setup(SetupStage.FINISHING)
    }

    override suspend fun abandonTransferIn() = call("abandonTransferIn")

    override suspend fun transferCreate(): TransferOfferView {
        call("transferCreate")
        return offer
    }

    override suspend fun awaitTransferPending(transferId: String, until: Instant): TransferPendingView? {
        call("awaitTransferPending")
        return pendingTransfer.await()
    }

    /** Whether the vault's `device.unlinked{transferred}` follows an approval (and wipes this phone). */
    var transferConfirmed = true

    override suspend fun transferApprove(transferId: String, pin: String, password: String): Boolean {
        call("transferApprove")
        lastTransferApproved = transferId
        lastPin = pin
        lastPassword = password
        if (transferConfirmed) phase.value = AppPhase.SignedOut // the wipe: as freshly installed
        return transferConfirmed
    }

    override suspend fun transferReject(transferId: String) {
        call("transferReject")
        lastTransferRejected = transferId
    }

    companion object {
        fun release(n: Long, status: String = "active") =
            ReleaseView(n, n.toString().padStart(2, '0').repeat(48), status, null, "https://vettid.org/releases/$n")

        fun failure(kind: FailureKind, code: String? = null, retry: Long = 0) = VaultFailure(kind, code, retry)
    }
}
