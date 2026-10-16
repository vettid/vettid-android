package com.vettid.core.data.vault

import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.TypedRecoveryCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** No transfer kept ([VaultRepository.transferKeptSince]'s default). */
val NO_KEPT_TRANSFER: StateFlow<Instant?> = MutableStateFlow(null)

/** No move to finish ([VaultRepository.finishMovePending]'s default). */
val NO_MOVE_TO_FINISH: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * The answer to `vault.recovery.open` (§11.14.6): [code] `ok` or one of the result codes (`wrong`, `wrong_again`,
 * `attempt_lost`, `quiet`, `wait`, `busy`, `retry`, `stored_mismatch`, `exhausted`, `closed`, `not_armed`,
 * `counter_unavailable`, …); [used] of 10 attempts when the counter answered; [retryAt] with `quiet`, `wait`, and a
 * wrong or lost attempt that began a wait.
 */
data class RecoveryOpenOutcome(
    val code: String,
    val used: Int? = null,
    val retryAt: Instant? = null,
    /** The open before this one was taken by the member API and got no answer (§11.14.6 "A right entry…"). */
    val afterUnanswered: Boolean = false,
    /**
     * The vault had already answered `ok` and made this phone its app, but the phone could not finish opening it: [code]
     * is then the answer of this phone's own unlock (`bad_pin`, `backoff`, …), and nothing of the recovery was used.
     */
    val opened: Boolean = false,
) {
    val ok: Boolean get() = code == "ok"
    val left: Int? get() = used?.let { (ATTEMPTS - it).coerceAtLeast(0) }

    companion object {
        const val ATTEMPTS = 10
    }
}

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

    /** The app came back to the foreground: whatever stopped while it was in the background is checked again. */
    fun onForeground() {}

    /**
     * Redeems the portal's setup code with this phone's app key (§11.12.1) and returns the account's masked email
     * (`email_hint`) for the member to confirm before a PIN is asked for. [FailureKind.SETUP_CODE_INVALID] for any
     * refusal of the code itself.
     */
    suspend fun redeemSetupCode(code: SetupCodeInput): String

    /** "That is not my account" after a redeem, or a pending key that expired: back to the welcome screen. */
    suspend fun forgetSetupCode()

    /**
     * The member confirmed "Start over" on [FailureKind.NO_VAULT] (A6 audit N6): asks the member API once more and,
     * only if it again says explicitly that the account has no vault, forgets this phone's keys for it; the welcome
     * screen follows. Any other answer is the new phase ([refresh]).
     */
    suspend fun forgetMissingVault() {}

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

/** [VaultRepository.pendingDeletion] of a repository that does not follow it. */
private val NO_DELETION: StateFlow<DeletionView?> = MutableStateFlow(null)
private val NO_LOCK_NOTICE: StateFlow<LockNotice> = MutableStateFlow(LockNotice.None)

/** The vault on this device: enrollment, unlock and lock, status, PIN, deletion, recovery. */
@Suppress("TooManyFunctions")
interface VaultRepository {
    /**
     * The open app was sent to the unlock screen because the relay kept refusing this phone's messages to the
     * vault (`token_revoked`, [RefusalWatch]): the vault may no longer know it. Not proof: the PIN asks the
     * enclave. Cleared by any sealed unlock result or message from the vault.
     */
    val refusedByVault: StateFlow<Boolean>

    /**
     * What to tell the member about the lock they asked for ([LockNotice]): that the vault could not be told and
     * may still be open (this phone is locked all the same and keeps trying), or that it has now confirmed.
     */
    val lockNotice: StateFlow<LockNotice> get() = NO_LOCK_NOTICE

    /** Tries the pending lock again now. */
    fun retryLock() = Unit

    /** The member has read that the vault confirmed a lock that was in doubt. */
    fun dismissLockNotice() = Unit

    /** Enrolls a vault with [pin] (§11.3) and runs the first handshake. */
    suspend fun enroll(pin: String, onStep: (EnrollStep) -> Unit)

    /**
     * A start-over requested on the account portal (VAULT-MESSAGING 0.16.0 §11.11.9, `deletion` in `GET
     * /api/vault/status`): null when none is pending.
     */
    val pendingDeletion: StateFlow<DeletionView?> get() = NO_DELETION

    /** Cancels the pending start-over (`POST /api/vault/deletion/cancel`, signed by the app key). True when cancelled. */
    suspend fun cancelDeletion(): Boolean = false

    /** Creates the Protean Credential (§3.5.5), sets the backup (§3.5.6) and confirms the vault. */
    suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit)

    /** (Release R, VAULT-MESSAGING 0.33.0 §11.14.9) The same, with the PIN, which the vault verifies and arms recovery with. */
    suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit, pin: String?) =
        createCredential(password, backup, onStep)

    /**
     * (Release R) `pin.change` with the password and the phone's next secret (§11.14.9). Refused for a vault without a
     * phone secret ([changePin] of two arguments is the earlier operation).
     */
    suspend fun changePin(pin: String, newPin: String, password: String): Unit = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /** (Release R) `vault.secret.renew`: a new phone secret with the PIN unchanged (Settings → Security). */
    suspend fun renewPhoneSecret(pin: String, password: String): Unit = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /** (Release R) Whether this phone holds a phone secret for its vault (a vault of release R). */
    val usesPhoneSecret: Boolean get() = false

    /**
     * (Release R, §11.14.10) When this phone approved a transfer it still keeps the keys for (until the new phone's
     * first unlock, at most 7 days); null otherwise. The shell shows "Finish the move on your new phone".
     */
    val transferKeptSince: StateFlow<Instant?> get() = NO_KEPT_TRANSFER

    /**
     * (Release R, §11.14.10) This new phone received a transfer and has not yet finished the move (an unlock from
     * storage with its own secret): the app asks again at every start until it is done.
     */
    val finishMovePending: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    /**
     * (Release R, §11.14.10) This old phone opened the vault with its own keys after approving a transfer ("The move
     * didn't finish"): it is the vault's phone again. Told once, until [acknowledgeMovedBack].
     */
    val movedBack: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    fun acknowledgeMovedBack() {}

    /**
     * (Release R, owner decision 2026-10-16) At the end of the 7 days after approving a transfer, the vault confirmed
     * that this phone is still its app: the move did not happen and the phone keeps its keys. Told once.
     */
    val moveNotDone: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    fun acknowledgeMoveNotDone() {}

    /** Leaves onboarding for the app. */
    suspend fun finishSetup()

    /** The release check before the PIN (§11.10.6). */
    suspend fun preflight(): PreflightInfo

    /** Unlocks (§11.4), optionally approving [approve] (§11.10.3) or cancelling a recovery (§11.11.4). */
    suspend fun unlock(pin: String, approve: ReleaseView? = null, cancelRecovery: Boolean = false): UnlockAttempt

    /**
     * Unlocks a vault past its owner-check deadline (VAULT-MESSAGING §3.6.5): the PIN unlocks; if `vault.status`
     * then says held or due, the check follows with [pin] and [password] ([OwnerCheckRepository.unlockCheckOutcome]).
     */
    suspend fun unlockWithCheck(
        pin: String,
        password: String,
        approve: ReleaseView? = null,
        cancelRecovery: Boolean = false,
    ): UnlockAttempt =
        unlock(pin, approve, cancelRecovery)

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

    /** (Release R, §11.14.9) With the PIN, which the vault verifies; the change closes the recovery generation. */
    suspend fun changePassword(password: String, newPassword: String, pin: String?) = changePassword(password, newPassword)

    suspend fun rotate(password: String)

    /**
     * A new credential (`credential.reset`, VAULT-MESSAGING 0.15.2 §3.5.5): the PIN, the current password and the
     * new one. Every critical item is destroyed. Refused while the vault is held; restarts the owner-check clock.
     */
    suspend fun newCredential(pin: String, password: String, newPassword: String)

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

    /**
     * The same for a typed code (VAULT-MESSAGING 0.25.0 §11.11.10), whose check characters already matched this
     * app's member API origin: claims by its locator, takes the vault and the recovery from the answer, and then
     * registers exactly as [registerRecovery] does for the scanned QR of that recovery.
     */
    suspend fun registerTypedRecovery(typed: TypedRecoveryCode): RecoveryRegistration

    /** The release check before the PIN (§11.10.6), as for an unlock. */
    suspend fun recoveryPreflight(): PreflightInfo

    /** Unlocks with the PIN (§11.11.5 step 1) and runs the first handshake (step 2). */
    suspend fun recoveryUnlock(pin: String, approve: ReleaseView? = null): UnlockAttempt

    /**
     * Whether the vault keeps a copy of the credential, from this phone's recovery unlock (`credential_backup`,
     * 0.10.6, §11.11.5 step 1): true, the password recovers it; false, the vault cannot be recovered (0.16.0: no
     * recovery with the backup off; a vault of 0.16.0 refuses such an unlock with `no_backup` already); null, the
     * vault did not say: ask.
     */
    suspend fun recoveryCredentialBackup(): Boolean?

    /**
     * Forgets this phone's recovery registration and device state, so that a new recovery code, a setup code or a
     * transfer starts afresh: when the vault cannot be recovered (`no_backup`, VAULT-MESSAGING 0.16.0), and when the
     * member starts over from the PIN or password step (A6 audit E1). The recovery itself is not cancelled by this:
     * the app has no such call (§11.11.4).
     */
    suspend fun abandonRecovery() {}

    /** `credential.recover` with the password (§11.11.5 step 3). */
    suspend fun recoverCredential(password: String): RecoverOutcome


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
     * [FailureKind.NO_RESPONSE] after 10 minutes. True when the vault gave this phone the member's
     * `user_guid` (VAULT-MESSAGING 0.17.0 §6.7.1), which its unlocks need; false from a vault release
     * before 0.17.0: the transfer is complete, but this phone cannot unlock the vault once it locks.
     */
    suspend fun awaitTransferIn(): Boolean

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
    suspend fun transferApprove(transferId: String, pin: String, password: String, signer: String? = null): Boolean

    suspend fun transferReject(transferId: String)

    /**
     * (Release R, VAULT-MESSAGING 0.33.0 §11.14.6) Opens the registered recovery with the PIN and the password together
     * and this phone's unlock secret. On `ok` the phone becomes the vault's app (its first handshake and the
     * credential follow) and the app opens; every other answer is the table of ANDROID-PLAN 0.1.40.
     */
    suspend fun recoveryOpen(pin: String, password: String): RecoveryOpenOutcome = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /**
     * (Release R) The vault answered `ok` to this phone's open, which has not finished opening it yet (the handshake or
     * the credential did not complete, or the app stopped): [recoveryOpen] then finishes that recovery with the PIN and
     * sends no new open, and [abandonRecovery] is refused ([CODE_RECOVERY_OPENED]).
     */
    suspend fun recoveryOpened(): Boolean = false

    /** (Release R) The attempts used of the registered recovery as last reported (also after a restart); null before. */
    val recoveryAttemptsUsed: Int? get() = null

    /** (Release R) After an `ok` [recoveryOpen] the member has read: the app opens. */
    suspend fun finishRecoveryOpen() {}

    companion object {
        /** (Release R) [abandonRecovery] after the vault made this phone its app: never; the phone key stays. */
        const val CODE_RECOVERY_OPENED = "recovery_opened"

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
