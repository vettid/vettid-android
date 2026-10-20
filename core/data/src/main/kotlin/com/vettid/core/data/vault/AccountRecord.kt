package com.vettid.core.data.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

// --- the local account record (encrypted under a Keystore key; VaultManager reads and writes it) ---

/**
 * What this phone knows of its member without signing in: the `user_guid` and `vault_id` from the setup code's
 * redeem or the recovery's claim (§11.12.1, §11.11.7), the masked email to show, and the last account snapshot
 * the vault sent (§11.13). Fields of older builds (the signed-in member's email and names) are ignored.
 */
@Serializable
internal data class LocalAccount(
    val userGuid: String = "",
    val vaultId: String = "",
    val emailHint: String = "",
    val snapshot: com.vettid.core.vault.AccountSnapshot? = null,
    /** The latest name request (`account.get`'s `name_request`, VAULT-MESSAGING 0.18.0 §10.8). */
    val nameRequest: com.vettid.core.vault.NameRequest? = null,
    /** A direct transfer to this phone started (§6.7.1): it has a vault to come without a redeem. */
    val transferIn: Boolean = false,
    val setupComplete: Boolean = false,
    /**
     * Written by the build before the wipe (0af6ba1) when this phone stopped holding the vault; that build had
     * already forgotten the device keys. Read only to finish such a phone with a wipe.
     */
    val replaced: String? = null,
    /**
     * (Legacy: read only while no [LockRecordFile] exists.) A lock the member asked for that the vault has not confirmed
     * ([LockKeeper]): when it was asked (epoch ms). While it is set this phone is locked and keeps trying, across process
     * starts.
     */
    val lockAskedAtMs: Long? = null,
    /** (Legacy, as [lockAskedAtMs].) An unanswered relay `vault.lock` may be acted on by the vault until then. */
    val relayLockUntilMs: Long? = null,
    /**
     * The lock's record is kept in its own file ([LockRecordFile]) since its first save there: the legacy fields above
     * are cleared, and a missing file says a lock is pending. The phone's, not a vault's: kept across a [AccountRecord.reset].
     */
    val lockFileInUse: Boolean = false,
    /**
     * The last time a `vault.status` read reached the vault service from this phone (epoch ms, this phone's clock,
     * written at most hourly): after [StuckScreens.LONG_UNREACHABLE] without one, the root screen offers erase-or-recover.
     */
    val lastContactAtMs: Long? = null,
    /**
     * (STAGING-TIME-SCALE, S12) How long without contact before erase-or-recover is offered, set when [lastContactAtMs]
     * was written: [StuckScreens.LONG_UNREACHABLE] divided by the vault's time scale then. Null: 7 days (written before
     * the scale, or at scale 1).
     */
    val unreachableAfterMs: Long? = null,
    /**
     * The inner `ts` of the last successful unlock result (epoch ms, the enclave's clock): a `vault.locking`
     * from before it is stale also after a process start ([LockNotices]).
     */
    val lastOpenedAtMs: Long? = null,
    /** (Release R, §11.14.1) The phone key's vault id ("" for a transfer's new phone); with [phoneEpoch]. */
    val phoneKeyVault: String = "",
    /** (Release R) The unlock secret's epoch; 0: this vault has no phone secret (a vault before release R). */
    val phoneEpoch: Long = 0,
    /**
     * (Release R, §11.14.10) When this phone approved a transfer answered `{"unlink": "after_new_unlock"}` (epoch
     * ms): it keeps its keys until `device.unlinked{transferred}` or for 7 days, and says so at every start.
     */
    val transferApprovedAtMs: Long? = null,
    /**
     * (STAGING-TIME-SCALE, S10) How long this phone keeps its keys after [transferApprovedAtMs], set with it: 7 days
     * divided by the vault's time scale at the approval. Null: 7 days (approved before the scale was known).
     */
    val transferKeepMs: Long? = null,
    /**
     * (Release R, §11.14.6) A `vault.recovery.open` the member API took got no answer (the relay, a timeout, the
     * app stopped): the next answer may be that of a right entry that could not be completed. Cleared by the
     * next answer; a new registration starts without it.
     */
    val recoveryOpenUnanswered: Boolean = false,
    /** (Release R) The attempts used of the registered recovery, as the vault's counter last reported them. */
    val recoveryAttemptsUsed: Int? = null,
    /**
     * (Release R, §11.14.10) This new phone received a transfer and has not yet opened the vault from storage with
     * its own secret ("Finish the move"): asked again at every start until an unlock succeeds.
     */
    val finishMovePending: Boolean = false,
    /**
     * This phone received its vault by a direct transfer and has not yet left onboarding ([finishSetup]): the
     * last onboarding screen is the move's, not an enrollment's, also after a restart.
     */
    val movedIn: Boolean = false,
    /**
     * (Release R, §11.14.11) Since when (epoch ms, this phone's clock) every read of `vault.status` found the recovery
     * counter unavailable (or the recovery `unknown`); null after a read that did not. After 24 hours the status
     * screen says so with the urgency of a used attempt.
     */
    val recoveryUnknownSinceMs: Long? = null,
    /**
     * (STAGING-TIME-SCALE, S8) How long [recoveryUnknownSinceMs] runs before the notice is urgent, set when it started:
     * 24 hours divided by the vault's time scale then ([RecoveryUnknownClock.urgentMs]). Null: 24 hours (started before
     * the scale, or at scale 1).
     */
    val recoveryUnknownForMs: Long? = null,
    /** (Release R) The offer to rotate the vault's keys is open, until a rotation succeeds or it is put away. */
    val rotateOffered: Boolean = false,
    /**
     * (0.34.0, §11.10.6, §11.10.9) This phone has seen `recovery_r` for its vault (a verified descriptor, a transfer
     * bundle, or a request of release R's shape that succeeded): it never takes an earlier path for it again.
     */
    val recoveryRSeen: Boolean = false,
    /** (0.34.0) The capabilities of the vault's instance this phone last verified (`recovery_offered` for the status). */
    val capabilities: List<String> = emptyList(),
    /**
     * (VAULT-MESSAGING 0.43.0 §11.10.9, STAGING-TIME-SCALE §5.2) The time scale of the vault's instance this phone last
     * verified (a descriptor, or a transfer bundle); null until one was read, which counts as 1.
     */
    val timeScale: Long? = null,
    /**
     * (VAULT-MESSAGING 0.44.0 §11.14.9) A `pin.change` or `credential.password.change` that was sent and is not decided
     * yet: written before the send, cleared when an answer or the check decides it. No PIN, password or secret.
     */
    val unresolvedChange: UnresolvedChange? = null,
    /**
     * (VAULT-MESSAGING 0.45.0 §11.14.9 step 3) A PIN change's check ended without a decision: the PIN is uncertain (the
     * previous or the new one). The epoch of the current record it was sent from; until an unlock with the two steps
     * settles it, the owner check and every operation that carries the PIN are refused.
     */
    val uncertainPinFromEpoch: Long? = null,
    /**
     * (0.45.0 §11.14.9 step 5, N1) A `vault.secret.renew` stored before it was sent and not answered yet: its candidate
     * (the next epoch, with the PIN unchanged) may be the vault's. No PIN change is sent until it is decided
     * (`credential.version` once it is settled, or an unlock, which costs nothing: the PIN is unchanged).
     */
    val unconfirmedRenew: UnconfirmedRenew? = null,
    /** (0.45.0 step 7, N5) The decision of a lost answer not yet seen by the member: kept across a process death. */
    val changeDecision: SavedDecision? = null,
    /**
     * (ANDROID-PLAN 0.1.54) The non-secret marker `deleting {vault_id, since, until}`, stored when the vault sent
     * `device.unlinked{vault_deleted}`: a process that finds it starts in "being deleted", before it reads anything.
     * Deleted with the move to "no vault" and at a wipe.
     */
    val deleting: DeletingMarker? = null,
    /** (0.1.54) "Your vault was deleted" is to be said once on "no vault" (a record of its own after the deletion). */
    val deletedNotice: Boolean = false,
) {
    /** A renew may have applied without its answer ([unconfirmedRenew]). */
    val renewUnconfirmed: Boolean get() = unconfirmedRenew != null
    val phone: PhoneSecretState get() = PhoneSecretState(phoneKeyVault, phoneEpoch)

    /** Release R is known for this vault: the mark, or a phone secret for it. */
    val releaseRKnown: Boolean get() = recoveryRSeen || phone.inUse
    fun toInfo(): AccountInfo? = when {
        snapshot != null -> snapshot.toInfo(emailHint)
        emailHint.isNotEmpty() -> AccountInfo(emailHint)
        else -> null
    }?.copy(nameRequest = nameRequest?.let { NameRequestView.of(it) })
}

/**
 * The account record and the writes [VaultManager] makes to it, over a [RecordStore] (one lock, a wipe and a reset on
 * another thread, the record that could not be read, a wipe that did not finish). Every write keeps the legacy lock
 * fields of the record as they are: the lock's record has its own file now ([LockRecordFile]). The rotate offer's flow is kept
 * here, set under the same lock as the record, so that a wipe's or a reset's clearing of it is never undone by a write
 * that was on its way.
 */
@Suppress("TooManyFunctions") // one per kind of write the vault manager makes, each with its guard
internal class AccountRecord(
    initial: LocalAccount,
    readable: Boolean,
    persist: (LocalAccount) -> Unit,
    eraseFailed: Boolean = false,
) {
    private val store = RecordStore(initial, readable, empty = { LocalAccount() }, persist = persist, failed = eraseFailed)
    private val rotateOfferFlow = MutableStateFlow(initial.rotateOffered)

    /** (Release R) The offer to rotate the vault's keys after a transfer, a PIN change or a password change. */
    val rotateOffer: StateFlow<Boolean> = rotateOfferFlow.asStateFlow()

    val value: LocalAccount get() = store.value

    /** Bumped by every lock, reset and wipe ([RecordStore.generation]). */
    val generation: Int get() = store.generation

    /** Read before an operation whose writes belong to the record it started with ([update]'s `since`). */
    val recordGeneration: Int get() = store.recordGeneration

    fun bumpGeneration() = store.bumpGeneration()

    private val pinGen = java.util.concurrent.atomic.AtomicInteger()

    /**
     * (VAULT-MESSAGING §11.14.9 step 2; chosen, not in the spec) Moves with everything that may change which PIN the vault
     * expects: a PIN change's or a password change's marker stored, a change decided (applied, not applied or refused, or
     * ended with the PIN uncertain), a renew stored or decided, the uncertain PIN settled, the record reset or wiped, and
     * ([pinMoved]) a PIN change or renew beginning and the unlock's PIN step changing. A flow that unlocks with a PIN typed
     * on its own screen (the release update, "Finish the move") reads it when its keypad becomes usable and sends nothing
     * once it moved. In memory only, for the manager's life: a restart asks for the PIN again. Not moved by what leaves
     * the PIN as it is: an unlock's own confirmation of a candidate or of the current record (unless it settles an
     * uncertain PIN), the epoch-1 records of an enrollment, a transfer or a recovery, a lock, and the other writes here.
     */
    val pinGeneration: Int get() = pinGen.get()

    /** Something that may change which PIN the vault expects happened ([pinGeneration]). */
    fun pinMoved() {
        pinGen.incrementAndGet()
    }

    /** [ok], with [pinGeneration] moved when it is true. */
    private fun movedIf(ok: Boolean): Boolean {
        if (ok) pinMoved()
        return ok
    }

    /** A wipe runs, or the last one did not finish: no session is opened, nothing is written ([RecordStore.closed]). */
    val closed: Boolean get() = store.closed

    /** The last wipe did not finish, or one was left over at the start ([RecordStore.failed]). */
    val eraseFailed: Boolean get() = store.failed

    /**
     * A read-modify-write under the record's lock: [f] gets the record as it is now ([RecordStore.update]), so that two
     * writers of different members (the epoch, the move, the recovery clock) never lose each other's change. Dropped
     * after a wipe or a reset since [since] was read; [RecordStore.UNBOUND] only where the caller says why.
     */
    fun update(since: Int, f: (LocalAccount) -> LocalAccount?): Boolean = store.update(since) { cur -> f(cur)?.withLockOf(cur) }

    /** A record of its own ([a]: none, or a new setup's): no offer from before it, and the writes still on their way are dropped. */
    fun reset(a: LocalAccount): Boolean = store.reset { cur ->
        rotateOfferFlow.value = a.rotateOffered
        // Nothing of the lock's legacy fields goes into the new record; whether the lock's file is in use stays.
        a.copy(lockAskedAtMs = null, relayLockUntilMs = null, lockFileInUse = cur.lockFileInUse)
    }.also { pinMoved() }

    /**
     * The lock's record was saved in its own file for the first time: the legacy fields go and the file is in use from
     * here ([LocalAccount.lockFileInUse]). [RecordStore.UNBOUND]: the phone's state, not a vault's. Refused while the
     * record cannot be read or is shut; the next save of the lock's file tries again.
     */
    fun markLockFileInUse(): Boolean =
        store.update(RecordStore.UNBOUND) { it.copy(lockAskedAtMs = null, relayLockUntilMs = null, lockFileInUse = true) }

    /** The record read again from the phone ([readable]: it could be). */
    fun reload(a: LocalAccount, readable: Boolean) = store.reload(a, readable)

    /** The rotate offer [on] or put away, for the record of [since]; shown only when it is kept. */
    fun setRotateOffer(on: Boolean, since: Int): Boolean = store.update(since) { cur ->
        rotateOfferFlow.value = on // under the lock: a wipe's or a reset's clearing of it comes before or after, whole
        if (cur.rotateOffered == on) null else cur.copy(rotateOffered = on)
    }

    /**
     * The offer after "Finish the move" opened the vault: only while [generation] is still [gen] (no lock, no reset and
     * no wipe since the move's own lock), checked and written as one step. [RecordStore.UNBOUND] for the record: the
     * generation already moves with every reset and wipe.
     */
    fun offerAfterMove(gen: Int): Boolean = store.update(RecordStore.UNBOUND, generation = gen) { cur ->
        rotateOfferFlow.value = true
        if (cur.rotateOffered) null else cur.copy(rotateOffered = true)
    }

    /**
     * A `vault.status` read reached the vault service at [nowMs] ([LocalAccount.lastContactAtMs]), for the record of
     * [since]; the long-unreachable bound starts from it, divided by the vault's time scale now.
     */
    fun contacted(since: Int, nowMs: Long): Boolean = update(since) {
        val scale = it.timeScale ?: com.vettid.core.crypto.altchan.TimeScale.REAL
        val after = StuckScreens.unreachableAfterMs(scale)
        val span = it.unreachableAfterMs ?: StuckScreens.LONG_UNREACHABLE.toMillis()
        if (StuckScreens.writeContact(it.lastContactAtMs, nowMs, span) || span != after) {
            it.copy(lastContactAtMs = nowMs, unreachableAfterMs = after.takeIf { a -> a != StuckScreens.LONG_UNREACHABLE.toMillis() })
        } else {
            null
        }
    }

    /** The inner `ts` of an unlock that opened the vault ([LockNotices]), for the record that unlock started with. */
    fun opened(since: Int, atMs: Long): Boolean = update(since) { it.copy(lastOpenedAtMs = maxOf(it.lastOpenedAtMs ?: 0, atMs)) }

    /** What `account.get` brought ([accountUpdate]), for the record the request was sent for. */
    fun refreshed(since: Int, u: AccountUpdate): Boolean = update(since) {
        it.copy(snapshot = u.snapshot, emailHint = u.snapshot?.emailHint ?: it.emailHint, nameRequest = u.nameRequest)
    }

    // --- a lost answer to a PIN or password change (VAULT-MESSAGING 0.44.0 §11.14.9) ---

    /** What [storeChange] did. */
    enum class ChangeStored {
        /** Written: the change may be sent. */
        STORED,

        /** Another change is unresolved (checked and refused in the same step as the write): nothing written. */
        CONFLICT,

        /** The write was dropped or failed: nothing of it stays, and nothing may be sent. */
        FAILED,
    }

    /**
     * §11.14.9 step 1: [u] stored before the send, for the record of [since]. Refused, in the same step under the record's
     * lock, while another change is unresolved, so that two changes can never both be sent.
     */
    fun storeChange(since: Int, u: UnresolvedChange): ChangeStored {
        var conflict = false
        val before = store.value
        val ok = try {
            update(since) { cur ->
                val other = cur.unresolvedChange
                if (other != null && other.innerId != u.innerId) {
                    conflict = true
                    null
                } else {
                    cur.copy(unresolvedChange = u)
                }
            }
        } catch (_: com.vettid.core.keystore.KeystoreException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
        return when {
            conflict -> ChangeStored.CONFLICT
            ok -> ChangeStored.STORED.also { pinMoved() }
            else -> {
                // A persist that failed after the value was set in memory: nothing of it stays.
                if (store.value.unresolvedChange == u) store.reload(before, readable = true)
                ChangeStored.FAILED
            }
        }
    }

    /**
     * The change [m] applied (its answer, or the check): a PIN change's candidate becomes current (the next epoch, only
     * while the current record is still the one it was sent from) and the marker goes, as one write, with the offer to
     * rotate the vault's keys. [RecordStore.UNBOUND]: the change belongs to whatever record holds its marker; a reset or
     * wipe leaves none. False when nothing was written (no such marker, or the write failed).
     */
    fun confirmChange(m: UnresolvedChange): Boolean {
        val ok = try {
            update(RecordStore.UNBOUND) { cur ->
                if (cur.unresolvedChange?.innerId != m.innerId) return@update null
                val epoch = if (m.kind == ChangeKind.PIN && cur.phoneEpoch == m.fromEpoch) m.fromEpoch + 1 else cur.phoneEpoch
                cur.copy(phoneEpoch = epoch, unresolvedChange = null)
            }
        } catch (_: com.vettid.core.keystore.KeystoreException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
        if (ok) setRotateOffer(true, recordGeneration)
        return movedIf(ok)
    }

    /**
     * (0.45.0 step 3) The check of [m] ended without a decision: the marker goes and, after a PIN change, the PIN is
     * uncertain until a two-step unlock settles it ([settlePin]).
     */
    fun endChange(m: UnresolvedChange): Boolean = try {
        movedIf(
            update(RecordStore.UNBOUND) { cur ->
                if (cur.unresolvedChange?.innerId != m.innerId) return@update null
                val uncertain = if (m.kind == ChangeKind.PIN) m.fromEpoch else cur.uncertainPinFromEpoch
                cur.copy(unresolvedChange = null, uncertainPinFromEpoch = uncertain)
            },
        )
    } catch (_: com.vettid.core.keystore.KeystoreException) {
        false
    } catch (_: java.io.IOException) {
        false
    }

    /**
     * An unlock settled which PIN the vault holds: the previous one (`ok` with the current record) or the new one (the
     * candidate confirmed); with [epoch] the phone moves to that epoch (the candidate). The uncertain PIN and an
     * unconfirmed renew go (the vault holds the current record, or the candidate is now current); an unresolved change
     * stays (§11.14.9 step 5: `ok` does not decide it).
     */
    fun settlePin(since: Int, epoch: Long? = null): Boolean {
        var settled = false
        val ok = update(since) { cur ->
            val e = epoch ?: cur.phoneEpoch
            if (cur.uncertainPinFromEpoch == null && !cur.renewUnconfirmed && e == cur.phoneEpoch) {
                null
            } else {
                settled = cur.uncertainPinFromEpoch != null
                cur.copy(phoneEpoch = e, uncertainPinFromEpoch = null, unconfirmedRenew = null)
            }
        }
        // Only the uncertain PIN settled moves [pinGeneration]: an unlock's own confirmation of a candidate does not.
        return if (settled) movedIf(ok) else ok
    }

    /** (N1) The renew [r], stored before it is sent; false: not written, and nothing may be sent. */
    fun storeRenew(since: Int, r: UnconfirmedRenew): Boolean = try {
        movedIf(update(since) { it.copy(unconfirmedRenew = r) })
    } catch (_: com.vettid.core.keystore.KeystoreException) {
        false
    } catch (_: java.io.IOException) {
        false
    }

    /**
     * The renew [innerId] applied ([applied]: its answer, or `credential.version` one above the version it carried): the
     * phone moves to its candidate (only from the epoch it was sent from); or it did not apply. Either way it goes.
     */
    fun decideRenew(innerId: String, applied: Boolean): Boolean = movedIf(
        update(RecordStore.UNBOUND) { cur ->
            val r = cur.unconfirmedRenew?.takeIf { it.innerId == innerId } ?: return@update null
            val epoch = if (applied && cur.phoneEpoch == r.fromEpoch) r.fromEpoch + 1 else cur.phoneEpoch
            cur.copy(phoneEpoch = epoch, unconfirmedRenew = null)
        },
    )

    /** (N5) The decision the member has not seen yet ([d]), or none once seen. */
    fun saveDecision(d: SavedDecision?): Boolean = update(RecordStore.UNBOUND) { cur ->
        // The same decision again keeps whether it was notified (P4).
        if (cur.changeDecision == d || d != null && d.sameAs(cur.changeDecision)) null else cur.copy(changeDecision = d)
    }

    /** (P4) The kept decision was posted as a notification. */
    fun decisionNotified(): Boolean = update(RecordStore.UNBOUND) { cur ->
        val d = cur.changeDecision ?: return@update null
        if (d.notified) null else cur.copy(changeDecision = d.copy(notified = true))
    }

    /** The change [m] did not apply, was refused, or the check ended: its marker goes; the epoch stays. */
    fun clearChange(m: UnresolvedChange): Boolean = try {
        movedIf(
            update(RecordStore.UNBOUND) { cur ->
                if (cur.unresolvedChange?.innerId == m.innerId) cur.copy(unresolvedChange = null) else null
            },
        )
    } catch (_: com.vettid.core.keystore.KeystoreException) {
        false
    } catch (_: java.io.IOException) {
        false
    }

    fun beginWipe() = store.beginWipe().also { pinMoved() }

    /** The end of the wipe ([RecordStore.endWipe]): no offer, whether or not it completed. */
    fun endWipe(complete: Boolean) = store.endWipe(complete) { rotateOfferFlow.value = false }.also { pinMoved() }

    private fun LocalAccount.withLockOf(cur: LocalAccount) =
        copy(lockAskedAtMs = cur.lockAskedAtMs, relayLockUntilMs = cur.relayLockUntilMs, lockFileInUse = cur.lockFileInUse)
}
