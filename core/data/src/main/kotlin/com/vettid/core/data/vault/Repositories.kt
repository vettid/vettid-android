package com.vettid.core.data.vault

import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.TypedRecoveryCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** No transfer kept ([VaultRepository.transferKeptSince]'s default). */
val NO_KEPT_TRANSFER: StateFlow<Instant?> = MutableStateFlow(null)

/** No failed "Finish the move" ([VaultRepository.finishMoveFailure]'s default). */
val NO_FINISH_MOVE_FAILURE: StateFlow<FailureKind?> = MutableStateFlow(null)

private const val KEEP_MS_PER_SECOND = 1_000L

/** No move to finish ([VaultRepository.finishMovePending]'s default). */
val NO_MOVE_TO_FINISH: StateFlow<Boolean> = MutableStateFlow(false)

/** No change being decided ([VaultRepository.changeCheck]'s default). */
val NO_CHANGE_CHECK: StateFlow<ChangeCheck?> = MutableStateFlow(null)

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
    /** (0.34.0) Whether THIS request used an attempt, as the vault says; null from a vault that does not say. */
    val attemptUsed: Boolean? = null,
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

    /**
     * (ANDROID-PLAN 0.1.54 "A vault being deleted") "Check again" on the "still finishing" screen: one read of `GET
     * /api/vault/status`; a tap while one runs waits for it. [DeletionCheck.GONE] has moved the app to "no vault".
     */
    suspend fun checkDeletion(): DeletionCheck = DeletionCheck.LISTED

    /** (0.1.54) "Your vault was deleted": said once on "no vault" after the deletion's state, until [acknowledgeDeletedNotice]. */
    val deletedNotice: StateFlow<Boolean> get() = NEVER_PAUSED

    fun acknowledgeDeletedNotice() {}
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

    /**
     * (ANDROID-PLAN 0.1.54) The stored marker of a release move that is not confirmed yet ([MoveUnconfirmed]); with
     * `failed`, the unlock screen offers "Return to release N".
     */
    val moveUnconfirmed: StateFlow<MoveUnconfirmed?> get() = NO_MOVE_UNCONFIRMED

    /**
     * (0.1.54 point 3) The unlock screen's "Return to release N", confirmed, with [pin]: the way back runs as on the update
     * screen ([ReleaseUpdateRepository.returnToPrevious]), whose screens show it. False when it cannot start.
     */
    fun returnToPreviousRelease(pin: String): Boolean = false

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

    /**
     * (VAULT-MESSAGING 0.44.0 §11.14.9) Where the decision of a PIN or password change whose answer was lost stands; null
     * when there is none. A decided state stays until [acknowledgeChangeCheck].
     */
    val changeCheck: StateFlow<ChangeCheck?> get() = NO_CHANGE_CHECK

    /** The member has read the decided state of [changeCheck]. */
    fun acknowledgeChangeCheck() {}

    /**
     * (ANDROID-PLAN 0.1.57 (1), 0.1.58 (3)) The vault answered `credential_required`: "This phone can't use your vault"
     * is shown while this is not null ([CredentialRequired]).
     */
    val credentialRequired: StateFlow<CredentialRequired.Refusal?> get() = CredentialRequired.state

    /** **Try again** on "This phone can't use your vault": the request that failed is sent again. */
    suspend fun retryCredentialRequired() = CredentialRequired.tryAgain()

    /** The screen goes (**Lock vault**, which also locks; or the vault no longer open). */
    fun clearCredentialRequired() = CredentialRequired.clear()

    /** (P4) The decided state kept on the phone was already posted as a notification. */
    fun changeDecisionNotified(): Boolean = false

    /** (P4) It has been posted now. */
    fun markChangeDecisionNotified() {}

    /**
     * (§11.14.9 step 5) A PIN change is unresolved: the unlock asks for the previous PIN, and for the new PIN only after
     * the vault showed that it holds the change ([UnlockAttempt.NewPinNeeded]).
     */
    val pinChangeUnresolved: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    /**
     * (§11.14.9 step 5) The previous PIN showed that the PIN change went through: the next [unlock] tries the PIN typed as
     * the NEW one. Kept through a failure that never reached the vault; cleared by any answer to that try.
     */
    val newPinAsked: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    /**
     * (0.45.0 §11.14.9 step 2) Why a flow that locks and unlocks with a PIN typed on its own screen ("Finish the move", the
     * release update) is held: the gate's text for an unresolved change or an uncertain PIN; null when it is not.
     */
    val changeHold: StateFlow<FailureKind?> get() = NO_FINISH_MOVE_FAILURE

    /** (Release R) `vault.secret.renew`: a new phone secret with the PIN unchanged (Settings → Security). */
    suspend fun renewPhoneSecret(pin: String, password: String): Unit = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /** (Release R) Whether this phone holds a phone secret for its vault (a vault of release R). */
    val usesPhoneSecret: Boolean get() = false

    /** This phone received its vault by a direct transfer and has not left onboarding yet. */
    val movedIn: Boolean get() = false

    /**
     * (0.34.0, §11.10.9) Whether the vault's release offers recovery: true for a release before R (its earlier
     * recovery), and from release R only when its capabilities list `recovery_offered`.
     */
    val recoveryOffered: Boolean get() = true

    /**
     * (Release R, §11.14.10) When this phone approved a transfer it still keeps the keys for (until the new phone's
     * first unlock, at most 7 days); null otherwise. The shell shows "Finish the move on your new phone".
     */
    val transferKeptSince: StateFlow<Instant?> get() = NO_KEPT_TRANSFER

    /**
     * (Release R, §11.14.10; owner decision 2026-10-23) The 7 days of a kept transfer are over but the vault has not
     * given a definite answer to the end-of-keep check (locked, offline, no answer, `token_revoked`): this phone keeps its
     * keys, says "Your vault has not confirmed the move to this phone…" instead of any erase offer, and checks again at
     * every start and return to the foreground.
     */
    val transferCheckWaiting: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    /** (STAGING-TIME-SCALE §5.2) The vault's time scale as this phone knows it ([TimeScaleSource]). */
    val timeScale: StateFlow<TimeScaleView> get() = TimeScaleSource.RealTime.timeScale

    /**
     * (STAGING-TIME-SCALE, S10) How long this phone keeps its keys after the approval, for the texts: 7 days, divided by
     * the vault's time scale when the approval started it.
     */
    val transferKeepSeconds: Long get() = KeptTransfer.KEEP_MS / KEEP_MS_PER_SECOND

    /**
     * (Release R, §11.14.10) This new phone received a transfer and has not yet finished the move (an unlock from
     * storage with its own secret): the app asks again at every start until it is done.
     */
    val finishMovePending: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    /**
     * (Release R, §11.14.10) "Finish the move": `vault.lock`, then `vault.unlock` with [pin] and this phone's own secret.
     * While it runs, [finishingMove] is true: the phone counts as locked (as after any lock) but the screen that asked
     * stays, so that the member sees the move finish instead of the unlock screen and the notice of a lock in doubt
     * that the vault, still in the middle of the move, may not answer at once.
     *
     * (VAULT-MESSAGING §11.14.9 step 2; chosen, not in the spec) [taken]: [pinGeneration] as the screen read it when its
     * keypad became usable (null: read now). Once it moved, nothing is sent: [UnlockAttempt.Failed] with
     * [FailureKind.CREDENTIAL_UNSETTLED] and `pin_taken_before_change`, and the PIN is asked for again.
     */
    suspend fun finishMove(pin: String, taken: Int? = null): UnlockAttempt {
        lock()
        return unlock(pin)
    }

    /**
     * (VAULT-MESSAGING §11.14.9 step 2; chosen, not in the spec) Which PIN the vault expects, as a generation that moves
     * with every change of it (a PIN or password change sent or decided, a renew, the uncertain PIN set or settled, the
     * unlock's PIN step, the records reset). A screen that unlocks with a PIN typed on it reads it when its keypad becomes
     * usable and hands it over with the PIN ([finishMove]).
     */
    val pinGeneration: Int get() = 0

    /** (Release R) True while [finishMove] runs, for at most a bounded time. */
    val finishingMove: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    /**
     * (Release R) Why the last [finishMove] did not open the vault (`transfer_incomplete`, a wrong PIN, a wait, the
     * network), for the unlock screen that follows it; null after a success or [acknowledgeFinishMoveFailure].
     */
    val finishMoveFailure: StateFlow<FailureKind?> get() = NO_FINISH_MOVE_FAILURE

    fun acknowledgeFinishMoveFailure() {}

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

    /**
     * (Release R, ANDROID-PLAN "Transfer") After a transfer, a PIN change or a password change the app offers to rotate
     * the vault's keys (`credential.rotate`), until a rotation or [dismissRotateOffer].
     */
    val rotateOffered: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    fun dismissRotateOffer() {}

    /**
     * (Release R, §11.14.10) This old phone removed its keys at the end of a kept transfer (the new phone finished the
     * move, or 7 days passed): "Your vault has moved to your new phone. VettID has removed its keys from this phone."
     * Told once, in this process, until [acknowledgeMovedAway].
     */
    val movedAway: StateFlow<Boolean> get() = NO_MOVE_TO_FINISH

    fun acknowledgeMovedAway() {}

    /** Leaves onboarding for the app. */
    suspend fun finishSetup()

    /** The release check before the PIN (§11.10.6). */
    suspend fun preflight(): PreflightInfo

    /** Unlocks (§11.4), optionally approving [approve] (§11.10.3) or cancelling a recovery (§11.11.4). */
    suspend fun unlock(pin: String, approve: ReleaseView? = null, cancelRecovery: Boolean = false): UnlockAttempt

    /**
     * (Release R, ANDROID-PLAN "Other screens") An unlock that cancels a pending recovery (`cancel_recovery`), followed
     * at once by the owner check with [pin] and [password], which records that the member is present. Before release R:
     * the unlock with `cancel_recovery` alone.
     */
    suspend fun unlockCancellingRecovery(pin: String, password: String, approve: ReleaseView? = null): UnlockAttempt =
        unlock(pin, approve, cancelRecovery = true)

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

    /**
     * (Release R, 0.34.0 §11.14.6 "A lost ok") The open's answer never arrived or could not be read: an unlock with the
     * PIN and this phone's secret; when its result carries the vault's bundle the recovery completed and is finished
     * here (handshake again, credential). No attempt is used.
     */
    suspend fun resumeLostOk(pin: String): RecoveryOpenOutcome = throw VaultFailure(FailureKind.NOT_SUPPORTED)

    /**
     * (Release R) After an `ok` [recoveryOpen] the member has read: the app opens (setup is finished: the `ok` text is a
     * recovered phone's last setup screen). Returns only once the app is open. Otherwise a [VaultFailure], and the app
     * stays closed: [CODE_LOCK_PENDING] when a lock asked for meanwhile is pending (the phase is then Locked),
     * [CODE_NOT_OPENED] when the app did not open for another reason.
     */
    suspend fun finishRecoveryOpen() {}

    companion object {
        /** (Release R) [abandonRecovery] after the vault made this phone its app: never; the phone key stays. */
        const val CODE_RECOVERY_OPENED = "recovery_opened"

        /** [finishRecoveryOpen]: a lock is pending, so the app did not open (as a release update's lock). */
        const val CODE_LOCK_PENDING = ReleaseUpdateManager.CODE_LOCK_PENDING

        /** [finishRecoveryOpen]: the app did not open (a lock or a wipe meanwhile, or setup could not be finished). */
        const val CODE_NOT_OPENED = "not_opened"

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
