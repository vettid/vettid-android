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
    /** (Release R) The offer to rotate the vault's keys is open, until a rotation succeeds or it is put away. */
    val rotateOffered: Boolean = false,
    /**
     * (0.34.0, §11.10.6, §11.10.9) This phone has seen `recovery_r` for its vault (a verified descriptor, a transfer
     * bundle, or a request of release R's shape that succeeded): it never takes an earlier path for it again.
     */
    val recoveryRSeen: Boolean = false,
    /** (0.34.0) The capabilities of the vault's instance this phone last verified (`recovery_offered` for the status). */
    val capabilities: List<String> = emptyList(),
) {
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
    }

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

    /** The inner `ts` of an unlock that opened the vault ([LockNotices]), for the record that unlock started with. */
    fun opened(since: Int, atMs: Long): Boolean = update(since) { it.copy(lastOpenedAtMs = maxOf(it.lastOpenedAtMs ?: 0, atMs)) }

    /** What `account.get` brought ([accountUpdate]), for the record the request was sent for. */
    fun refreshed(since: Int, u: AccountUpdate): Boolean = update(since) {
        it.copy(snapshot = u.snapshot, emailHint = u.snapshot?.emailHint ?: it.emailHint, nameRequest = u.nameRequest)
    }

    fun beginWipe() = store.beginWipe()

    /** The end of the wipe ([RecordStore.endWipe]): no offer, whether or not it completed. */
    fun endWipe(complete: Boolean) = store.endWipe(complete) { rotateOfferFlow.value = false }

    private fun LocalAccount.withLockOf(cur: LocalAccount) =
        copy(lockAskedAtMs = cur.lockAskedAtMs, relayLockUntilMs = cur.relayLockUntilMs, lockFileInUse = cur.lockFileInUse)
}
