package com.vettid.core.data.vault

import com.vettid.core.altchan.Slot
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** What the vault client may be used for while a lock is pending. Everything else is refused ([LockGuard.admit]). */
enum class LockTimeUse {
    /** The lock itself. */
    LOCK,

    /** A `call.end`: the hang-up of a call the lock ends, so that the other member is not left listening. */
    CALL_END,
}

/** The three ways the app reaches the vault for a lock. None of them throws for a vault that cannot be reached. */
interface LockPorts {
    /** How long the vault may act on a relay lock after it was sent (its `exp`). */
    val relayValidFor: Duration

    /**
     * Sends `vault.lock` over the relay, once: with [unlockId] a bound lock (release L, `vault.lock {unlock_id}`),
     * without the lock of a vault before L.
     */
    suspend fun relayLock(unlockId: String?): RelayLock

    /** Asks the member API to have the vault locked ([unlockId]: bound, release L), and waits a bounded time for its answer. */
    suspend fun memberLock(unlockId: String?): MemberLock

    /** The member API's status of the vault: true "unlocked", false anything else or no vault, null not readable. */
    suspend fun unlocked(): Boolean?
}

/** The vault's own answer to `vault.lock` over the relay. */
enum class RelayLock {
    /** `{}`: the vault locks (after the batch). */
    LOCKED,

    /** (Release L) `stale_lock`: the unlock named is not the vault's current one; nothing was done. */
    STALE,

    /** No answer in time, or an error that says nothing of the lock. */
    NO_ANSWER,
}

/**
 * What the member API said to a lock request. **For a lock made as before release L none of these is proof that the
 * vault locked.** For a bound lock (release L) the slot's [Answered.code] is the host's word, which the app takes
 * (VAULT-LOCK L6): `locked` and `not_running` confirm, `stale` ends the lock.
 */
sealed interface MemberLock {
    /**
     * The request's slot was answered, and whether as "done". A release before L reports "done" also when its lock
     * failed, and with no [code]; from release L "done" carries `locked`, `not_running` or `stale`.
     */
    data class Answered(val done: Boolean, val code: String? = null) : MemberLock

    /** Still queued when the wait was over, or not sent at all (no network). */
    data object NoAnswer : MemberLock

    /** `429`: no lock is to be asked of the member API again before [retryAfter] has passed. */
    data class Limited(val retryAfter: Duration) : MemberLock
}

/**
 * The rules of a pending lock, in one place and behind [LockPorts], so that they can be tested:
 *
 * 1. **What confirms a lock** ([attempt]): the vault's own answer to the relay lock, or a status that does not say
 *    "unlocked". For a lock made as before release L the member API's answer to a lock request never does, whatever
 *    it says. For a lock bound to its unlock (release L, VAULT-MESSAGING §10.2 "Locks", §11.5) also the slot's
 *    `locked` and `not_running`; and the vault's `stale_lock` or the slot's `stale` end the lock without confirming
 *    it (the vault was opened again since), unless a status read then says it is locked after all.
 * 2. **While a lock is pending this phone is locked whatever the status says** ([lockedWhatever]).
 * 3. **While a lock is pending nothing is sent to the vault** but the lock and a pending `call.end` ([admits]).
 * 4. **While a lock is pending nothing the vault sends is taken into the app's lists** ([takesEvents]).
 */
class LockGuard(
    private val ports: LockPorts,
    private val now: () -> Instant = Instant::now,
    newKeeper: (LockAttempt) -> LockKeeper,
) {
    @Volatile
    private var noMemberLockBefore: Instant = Instant.MIN

    /** The lock's state, record and retries. */
    val keeper: LockKeeper = newKeeper { first, unlockId -> attempt(first, unlockId) }
    private val lock: LockKeeper get() = keeper

    val pending: Boolean get() = lock.pending

    /**
     * One try, for a lock bound to [unlockId] (release L) or, with null, made as before L (vettid-android #127):
     *
     * - **Before L** (and for a phone that does not hold the value of a vault that announced one, VAULT-LOCK Q3): the
     *   relay lock is sent by the first attempt alone (a request that expires, never from the outbox: a retry must
     *   not leave lock requests on their way that could reach a vault opened again), and is recorded first so that
     *   the next unlock waits it out. Every attempt asks the member API, unbound, unless it said `429` and its time
     *   is not over, and then reads the status, which decides. The member API's answer never confirms.
     * - **Bound:** the relay lock names the unlock and is sent by every attempt (a late copy is answered
     *   `stale_lock`, so nothing is recorded and no unlock waits for it; §8.6); the member API's lock names it too,
     *   and its slot's code is read.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one return per piece of evidence
    suspend fun attempt(first: Boolean, unlockId: String? = null): LockOutcome {
        val bound = unlockId != null
        if (bound || first) {
            if (!bound) lock.relayLockSent(ports.relayValidFor)
            when (ports.relayLock(unlockId)) {
                RelayLock.LOCKED -> {
                    if (!bound) lock.relayLockAnswered()
                    return LockOutcome.LOCKED
                }
                // Only a bound lock can be stale; to an unbound one the answer is not the vault's word on it.
                RelayLock.STALE -> if (bound) return afterStale()
                RelayLock.NO_ANSWER -> Unit
            }
        }
        var reads = 1
        if (!now().isBefore(noMemberLockBefore)) {
            when (val a = ports.memberLock(unlockId)) {
                is MemberLock.Limited -> noMemberLockBefore = now().plus(a.retryAfter.coerceAtLeast(LIMITED_AT_LEAST))
                is MemberLock.Answered -> {
                    if (bound && a.done && a.code in CONFIRMING_CODES) return LockOutcome.LOCKED
                    if (bound && a.done && a.code == Slot.CODE_STALE) return afterStale()
                    // An answered request without a code that decides: the status may take a moment to follow.
                    reads = STATUS_READS_AFTER_ANSWER
                }
                MemberLock.NoAnswer -> Unit
            }
        }
        repeat(reads) { n ->
            if (n > 0) delay(STATUS_READ_EVERY.toMillis())
            if (ports.unlocked() == false) return LockOutcome.LOCKED
        }
        return LockOutcome.UNCONFIRMED
    }

    /**
     * The unlock the lock was for is over (the vault was opened again since): the lock is finished, and the status
     * says what is true now. Not "unlocked" (locked since, or no vault running): confirmed. Otherwise it is not known
     * to be locked and the member is told so ([LockNotice.OpenedAgain]).
     */
    private suspend fun afterStale(): LockOutcome =
        if (ports.unlocked() == false) LockOutcome.LOCKED else LockOutcome.OPENED_AGAIN

    /**
     * For the phase: true when this phone is locked **whatever the status says** (a lock is pending, or one ended
     * because the vault was opened again since, [LockKeeper.holdsPhone]). A status that does not say "unlocked"
     * ([unlocked] false) confirms a pending lock on the way; one that could not be read (null) confirms nothing.
     */
    fun lockedWhatever(unlocked: Boolean?): Boolean {
        if (!lock.holdsPhone) return false
        if (unlocked == false) lock.confirmed()
        return true
    }

    /**
     * Whether the vault client may be used: for an operation on the open vault ([use] null) only while this phone is
     * not held locked ([LockKeeper.holdsPhone]); for a [LockTimeUse] always.
     */
    fun admits(use: LockTimeUse? = null): Boolean = use != null || !lock.holdsPhone

    /** Whether what the vault sends is taken into the app's lists: not while this phone is held locked. */
    fun takesEvents(): Boolean = !lock.holdsPhone

    companion object {
        /** After the member API answered a lock request: the status is read this often, [STATUS_READ_EVERY] apart. */
        const val STATUS_READS_AFTER_ANSWER = 3
        val STATUS_READ_EVERY: Duration = Duration.ofSeconds(1)

        /** After a `429` without a usable `retry_after`. */
        val LIMITED_AT_LEAST: Duration = Duration.ofSeconds(60)

        /** (Release L) A bound lock's slot codes that confirm it: the host says the vault is locked, or was not running. */
        val CONFIRMING_CODES = setOf(Slot.CODE_LOCKED, Slot.CODE_NOT_RUNNING)
    }
}
