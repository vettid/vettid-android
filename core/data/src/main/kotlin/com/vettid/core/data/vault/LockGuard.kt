package com.vettid.core.data.vault

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

    /** Sends `vault.lock` over the relay, once. True when the vault answered: it has locked. */
    suspend fun relayLock(): Boolean

    /** Asks the member API to have the vault locked, and waits a bounded time for its answer. */
    suspend fun memberLock(): MemberLock

    /** The member API's status of the vault: true "unlocked", false anything else or no vault, null not readable. */
    suspend fun unlocked(): Boolean?
}

/** What the member API said to a lock request. **None of these is proof that the vault locked.** */
sealed interface MemberLock {
    /** The request's slot was answered, and whether as "done". The enclave reports "done" also when its lock failed. */
    data class Answered(val done: Boolean) : MemberLock

    /** Still queued when the wait was over, or not sent at all (no network). */
    data object NoAnswer : MemberLock

    /** `429`: no lock is to be asked of the member API again before [retryAfter] has passed. */
    data class Limited(val retryAfter: Duration) : MemberLock
}

/**
 * The rules of a pending lock, in one place and behind [LockPorts], so that they can be tested:
 *
 * 1. **What confirms a lock** ([attempt]): the vault's own answer to the relay lock, or a status that does not say
 *    "unlocked". The member API's answer to a lock request never does, whatever it says.
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
    val keeper: LockKeeper = newKeeper { first -> attempt(first) }
    private val lock: LockKeeper get() = keeper

    val pending: Boolean get() = lock.pending

    /**
     * One try. The relay lock is sent by the first attempt alone (a request that expires, never from the outbox: a
     * retry must not leave lock requests on their way that could reach a vault opened again). Every attempt asks
     * the member API, unless it said `429` and its time is not over, and then reads the status, which decides.
     */
    @Suppress("ReturnCount") // one return per piece of evidence
    suspend fun attempt(first: Boolean): Boolean {
        if (first) {
            lock.relayLockSent(ports.relayValidFor)
            if (ports.relayLock()) {
                lock.relayLockAnswered()
                return true
            }
        }
        var reads = 1
        if (!now().isBefore(noMemberLockBefore)) {
            when (val a = ports.memberLock()) {
                is MemberLock.Limited -> noMemberLockBefore = now().plus(a.retryAfter.coerceAtLeast(LIMITED_AT_LEAST))
                // An answered request: the status may take a moment to follow.
                is MemberLock.Answered -> reads = STATUS_READS_AFTER_ANSWER
                MemberLock.NoAnswer -> Unit
            }
        }
        repeat(reads) { n ->
            if (n > 0) delay(STATUS_READ_EVERY.toMillis())
            if (ports.unlocked() == false) return true
        }
        return false
    }

    /**
     * For the phase: true when this phone is locked **whatever the status says** (a lock is pending). A status that
     * does not say "unlocked" ([unlocked] false) confirms the lock on the way; one that could not be read (null)
     * confirms nothing.
     */
    fun lockedWhatever(unlocked: Boolean?): Boolean {
        if (!lock.pending) return false
        if (unlocked == false) lock.confirmed()
        return true
    }

    /**
     * Whether the vault client may be used: for an operation on the open vault ([use] null) only while no lock is
     * pending; for a [LockTimeUse] always.
     */
    fun admits(use: LockTimeUse? = null): Boolean = use != null || !lock.pending

    /** Whether what the vault sends is taken into the app's lists: not while a lock is pending. */
    fun takesEvents(): Boolean = !lock.pending

    companion object {
        /** After the member API answered a lock request: the status is read this often, [STATUS_READ_EVERY] apart. */
        const val STATUS_READS_AFTER_ANSWER = 3
        val STATUS_READ_EVERY: Duration = Duration.ofSeconds(1)

        /** After a `429` without a usable `retry_after`. */
        val LIMITED_AT_LEAST: Duration = Duration.ofSeconds(60)
    }
}
