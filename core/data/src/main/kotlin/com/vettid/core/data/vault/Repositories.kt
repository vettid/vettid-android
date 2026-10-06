package com.vettid.core.data.vault

import com.vettid.core.altchan.RecoveryCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * The member's account on this device: the phase the app is in, the setup code that starts a vault here
 * (VAULT-MESSAGING 0.15.0 §11.12: the app never signs in), and the account as the vault reports it (§11.13).
 * Every failure is a [VaultFailure].
 */
interface AccountRepository {
    val phase: StateFlow<AppPhase>

    /** The member's account from the vault's snapshot (read-only), or only its masked email before one arrived. */
    val account: StateFlow<AccountInfo?>

    /** A development hint for the setup screens (devStack builds only). */
    val devHint: String?

    /**
     * This build's member API origin (`https://account.vettid.org`): setup and recovery QR codes name the portal
     * that made them in `api`, which must equal it exactly (an identifier, never contacted).
     */
    val apiOrigin: String

    /**
     * The operator paused the vault service (MEMBER-API 1.2.0): `GET /api/vault/status` said `service: "paused"`,
     * or a vault route answered `503 vault_unavailable` with it. For a non-blocking banner only; cleared when
     * `status` says the service is available again.
     */
    val servicePaused: StateFlow<Boolean> get() = NEVER_PAUSED

    /** Re-reads the vault's state and sets [phase]. */
    suspend fun refresh()

    /**
     * Redeems the portal's setup code with this phone's app key (§11.12.1) and returns the account's masked email
     * (`email_hint`) for the member to confirm before a PIN is asked for. [FailureKind.SETUP_CODE_INVALID] for any
     * refusal of the code itself.
     */
    suspend fun redeemSetupCode(code: SetupCodeInput): String

    /** "That is not my account" after a redeem, or a pending key that expired: back to the welcome screen. */
    suspend fun forgetSetupCode()

    /** Re-reads the account snapshot from the vault (`account.get`); quiet on failure. */
    suspend fun refreshAccount() {}

    /**
     * The member's "Erase VettID from this phone" (owner decision, 2026-10-05), offered only where the vault
     * did not recognise this phone at unlock: the same crash-safe wipe as a replaced phone's (`LocalWipe`),
     * with the same best-effort, bounded relay cleanup. The erase itself needs no network. Returns once the phone
     * is as freshly installed ([phase] [AppPhase.SignedOut]); it runs to the end even if the caller is cancelled.
     */
    suspend fun eraseThisPhone()
}

/** [AccountRepository.servicePaused] of a repository that does not follow the service switch. */
private val NEVER_PAUSED: StateFlow<Boolean> = MutableStateFlow(false)

/** The vault on this device: enrollment, unlock and lock, status, PIN, deletion, recovery. */
@Suppress("TooManyFunctions")
interface VaultRepository {
    /**
     * The open app was sent to the unlock screen because the relay kept refusing this phone's messages to the
     * vault (`token_revoked`, [RefusalWatch]): the vault may no longer know it. Not proof: the PIN asks the
     * enclave. Cleared by any sealed unlock result or message from the vault.
     */
    val refusedByVault: StateFlow<Boolean>

    /** Enrolls a vault with [pin] (§11.3) and runs the first handshake. */
    suspend fun enroll(pin: String, onStep: (EnrollStep) -> Unit)

    /** Creates the Protean Credential (§3.5.5), sets the backup (§3.5.6) and confirms the vault. */
    suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit)

    /** Leaves onboarding for the app. */
    suspend fun finishSetup()

    /** The release check before the PIN (§11.10.6). */
    suspend fun preflight(): PreflightInfo

    /** Unlocks (§11.4), optionally approving [approve] (§11.10.3) or cancelling a recovery (§11.11.4). */
    suspend fun unlock(pin: String, approve: ReleaseView? = null, cancelRecovery: Boolean = false): UnlockAttempt

    suspend fun lock()

    suspend fun overview(): VaultOverview

    /** `pin.change` (§10.6). */
    suspend fun changePin(pin: String, newPin: String)

    /** `vault.delete` (§12.5): irreversible. */
    suspend fun deleteVault(pin: String, password: String)

    /** A recovery in progress, from `GET /api/vault/status` (it is cancelled on the account portal, or by an unlock). */
    suspend fun recovery(): RecoveryView?

    suspend fun attestationInfo(): AttestationInfo
}

/** The Protean Credential (§3.5): status, unlock window, password, rotation, backup, clone alarms. */
interface CredentialRepository {
    /** The open clone alarm, if any (§3.5.9). */
    val alarm: StateFlow<CredentialAlarm?>

    /** Until when the unlock window is open (§3.5.3), as this app opened it. */
    val unlockWindow: StateFlow<Instant?>

    suspend fun status(): CredentialStatus

    suspend fun openUnlockWindow(password: String)

    suspend fun closeUnlockWindow()

    suspend fun changePassword(password: String, newPassword: String)

    suspend fun rotate(password: String)

    suspend fun setBackup(on: Boolean)

    suspend fun setUnlockTtl(seconds: Int)

    /** Answers the alarm: [mine] = "that was me" (§3.5.9). Either way a rotation follows. */
    suspend fun confirmAlarm(mine: Boolean)
}

/**
 * Moving the vault to another phone (VAULT-MESSAGING §11.11 recovery, §6.7.1
 * direct transfer), on both phones. Every failure is a [VaultFailure].
 */
@Suppress("TooManyFunctions")
interface MoveRepository {
    // --- recovery, on the new phone (§11.11) ---

    /** Where a recovery on this phone stands. */
    suspend fun recoveryStage(): RecoveryStage

    /**
     * Claims the recovery of the portal's QR with this phone's app key (§11.11.7, 0.15.0), then registers this
     * phone with its code (§11.11.3), attested.
     */
    suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration

    /** The release check before the PIN (§11.10.6), as for an unlock. */
    suspend fun recoveryPreflight(): PreflightInfo

    /** Unlocks with the PIN (§11.11.5 step 1) and runs the first handshake (step 2). */
    suspend fun recoveryUnlock(pin: String, approve: ReleaseView? = null): UnlockAttempt

    /**
     * Whether the vault keeps a copy of the credential, from this phone's recovery unlock (`credential_backup`,
     * 0.10.6, §11.11.5 step 1): true, the password recovers it; false, only a new credential or deleting the vault
     * remain (step 4), so the password is not asked for; null, the vault did not say (older than 0.10.6): ask.
     */
    suspend fun recoveryCredentialBackup(): Boolean?

    /** `credential.recover` with the password (§11.11.5 step 3). */
    suspend fun recoverCredential(password: String): RecoverOutcome

    /** Backup off: a new credential under [password]; the old one and every critical item are destroyed (§11.11.5 step 4). */
    suspend fun resetCredential(password: String)

    /** Backup off: deletes the vault with the PIN alone (§11.11.5 step 4, §12.5). Irreversible. */
    suspend fun deleteRecoveredVault(pin: String)

    // --- direct transfer, the new phone (§6.7.1) ---

    /**
     * Starts the transfer from the old phone's code ([code]: the scanned QR or a
     * pasted link) and returns the SAS once the handshake checked out. Without the
     * vault's `hs.resp` within [HS_RESP_WAIT_MS], drops the transfer and fails with
     * [FailureKind.NO_RESPONSE] and [CODE_HS_UNANSWERED].
     */
    suspend fun transferIn(code: String): String

    /**
     * Waits for the old phone's approval, then takes the credential over (`credential.get`,
     * `credential.ack`, `credential.utk.get`). [FailureKind.REJECTED] when the owner rejected it;
     * [FailureKind.NO_RESPONSE] after 10 minutes.
     */
    suspend fun awaitTransferIn()

    /** Drops a transfer this phone started and has not completed. */
    suspend fun abandonTransferIn()

    // --- direct transfer, the old phone (§6.7.1) ---

    /** `device.transfer.create`. */
    suspend fun transferCreate(): TransferOfferView

    /** Waits until [until] for the new phone's `device.transfer.pending`; null when the code expired first. */
    suspend fun awaitTransferPending(transferId: String, until: Instant): TransferPendingView?

    /**
     * Approves with the PIN and the password (§6.7.1 step 3), then waits a little for the vault's
     * `device.unlinked{transferred}`, which erases this phone (the app is then SignedOut, as freshly
     * installed). True when that happened; false when the notice has not arrived yet (it wipes when it does).
     */
    suspend fun transferApprove(transferId: String, pin: String, password: String): Boolean

    suspend fun transferReject(transferId: String)

    companion object {
        /**
         * How long the new phone waits for the vault's `hs.resp` after its transfer `hs.init` (§6.7.1 step 2,
         * 0.10.6, a SHOULD): the vault answers at once, but never a dropped `hs.init` (a spent or expired code,
         * a failed attestation).
         */
        const val HS_RESP_WAIT_MS = 60_000L

        /** [transferIn]'s [FailureKind.NO_RESPONSE] code when no `hs.resp` came within [HS_RESP_WAIT_MS]. */
        const val CODE_HS_UNANSWERED = "hs_unanswered"
    }
}
