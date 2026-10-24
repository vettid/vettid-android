package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * What the phone keeps, durably, about the member's lock.
 *
 * - [pendingSinceMs]: a lock was asked for and the vault has not confirmed it. Written **before anything else
 *   happens** for the lock (before the hooks that run ahead of it, before the first attempt) and removed only when
 *   the lock is confirmed or an unlock made after it succeeded: a process that dies in between starts locked and
 *   goes on trying.
 * - [relayLockUntilMs]: a `vault.lock` was sent over the relay, got no answer, and may be acted on by the vault
 *   until then (its `exp`, plus a margin). It outlives the confirmation of the lock: the request may still be
 *   waiting in the mailbox of the vault, which collects again at the next unlock. No unlock is sent before it.
 *   (Release L) Only for a lock not bound to its unlock: a bound one that arrives late is answered `stale_lock`.
 * - [unlockId] (VAULT-MESSAGING 0.29.0 §10.2 "Locks", release L): the unlock the pending lock ends, from the vault's
 *   own word (the enroll, unlock or recovery-open result, or `vault.status`). With it the lock is **bound**: every
 *   attempt names it, over the relay and through the member API, and a process started later goes on with the same
 *   bound lock. Null: a vault before L, or a phone that did not hold the value; the lock is then made as before L.
 * - [held] (release L): the last lock ended because the vault was opened again since ([LockNotice.OpenedAgain]): the
 *   phone stays locked, also in a later process, until an unlock on this phone succeeds (or the vault is no longer
 *   this phone's).
 */
data class LockRecord(
    val pendingSinceMs: Long? = null,
    val relayLockUntilMs: Long? = null,
    val unlockId: String? = null,
    val held: Boolean = false,
)

/** Where [LockRecord] is kept: with the app's durable account record. */
interface LockRecordStore {
    fun load(): LockRecord

    /** Writes [record]. Must be durable when it returns. */
    fun save(record: LockRecord)
}

/** What the member is told about the lock they asked for. */
sealed interface LockNotice {
    /** Nothing to say: no lock is waiting, or it was confirmed without ever having been in doubt. */
    data object None : LockNotice

    /**
     * The lock is not confirmed. This phone is locked all the same and keeps trying.
     *
     * [attempts] is how many tries have failed. At 0 nothing has failed yet (the first try is running): the member
     * is told that the vault is being locked, without a warning. From 1 on the vault could not be told and may
     * still be open ([inDoubt]). (A vault has no time after which it locks by itself: it stays open until it is
     * told, or until the service restarts it. So there is no "at the latest by" to show.)
     */
    data class Unconfirmed(val askedAt: Instant, val attempts: Int) : LockNotice {
        /** At least one try failed: the vault may still be open. */
        val inDoubt: Boolean get() = attempts > 0
    }

    /** The vault has now confirmed a lock that was in doubt: said once, until dismissed or the next unlock. */
    data object ConfirmedLate : LockNotice

    /**
     * (Release L) The vault said that the unlock this lock was for is over: it was opened again since (`stale_lock`,
     * or the slot's `stale`), and the status did not say it is locked now. The lock is finished and not repeated
     * (VAULT-MESSAGING §10.2 "Locks"), but the vault is **not** known to be locked: it may be open. This phone stays
     * locked until the member unlocks it here ([LockKeeper.holdsPhone]). Said until dismissed or the next unlock.
     */
    data object OpenedAgain : LockNotice
}

/** What one try to lock the vault found out. */
enum class LockOutcome {
    /** The vault is known to be locked: the lock is confirmed. */
    LOCKED,

    /** (Release L) The vault runs under a newer unlock than the one the lock was for: the lock is over, not confirmed. */
    OPENED_AGAIN,

    /** Nothing that decides: the lock stays pending and is tried again. */
    UNCONFIRMED,
}

/** One try to lock the vault. */
fun interface LockAttempt {
    /**
     * Tells the vault to lock and says what is **known** now. [first] is the attempt made at once when the member
     * asked; later ones are retries. [unlockId]: the unlock the lock is bound to ([LockRecord.unlockId]), null for a
     * lock made as before release L. Never throws for an unreachable vault: that is [LockOutcome.UNCONFIRMED].
     */
    suspend fun tryLock(first: Boolean, unlockId: String?): LockOutcome
}

/**
 * The member's lock, made to **fail secure** (owner decision 2026-10-12): the phone is locked from the moment the
 * member asks, in every outcome; the vault is *known* locked only on evidence; until then the lock is pending,
 * durably, and is tried again until it is confirmed.
 *
 * - [begin] makes the lock pending, durably, and is called before anything else is done for it. [ask] then makes
 *   the first attempt and starts the retries if it did not confirm. It returns whether the lock is confirmed; a
 *   caller that is cancelled only stops waiting.
 * - [resume] is for a process start: a record found means the phone starts locked and the retries start at once.
 * - [confirmed] is for evidence from elsewhere (the vault's own `vault.locking`, a status read).
 * - [unlocking] is the only way to unlock while this class is in use. Lock attempts and unlocks never overlap, and
 *   after an unlock that succeeded no attempt of a lock asked for before it is ever made: the record is removed,
 *   the retries are cancelled, and every attempt checks, inside the same mutual exclusion, that it still belongs
 *   to the lock that is pending. An unlock that fails leaves the pending lock as it was, and so does one that
 *   succeeds after a **newer** lock was asked for (asked while the unlock was on its way): that lock stays
 *   pending and its attempt follows at once.
 * - An unlock first waits out an unanswered relay lock ([relayLockSent]), pending or not.
 *
 * Retries: [backoff] after each failed attempt, and at once on [retryNow] (the member's "Try again"), which is
 * honoured once in [retryNowEvery] at most.
 *
 * **A record that cannot be saved never stops a lock.** The lock is pending in this process first, then sent and
 * retried; the save follows, and its failure is only reported ([onSaveFailed]). The relay lock's time is kept in
 * memory as well, beside the record. What is lost with a failed save is only the lock's survival of a process death.
 *
 * **Retries live in this process.** When the process dies they stop until something starts the app again (the
 * member, the on-phone service, a push wake, the boot receiver, and (release L, owner decision 2026-10-23) the
 * app's pending-lock job, which Android runs while a lock is pending whether or not the app was killed); [resume]
 * then goes on, bound to the same unlock as before ([LockRecord.unlockId]).
 */
@Suppress("TooManyFunctions") // one small function per thing that can happen to a lock
class LockKeeper(
    private val scope: CoroutineScope,
    private val store: LockRecordStore,
    private val attempt: LockAttempt,
    private val now: () -> Instant = Instant::now,
    private val backoff: (attempts: Int) -> Duration = ::defaultBackoff,
    private val retryNowEvery: Duration = RETRY_NOW_EVERY,
    private val onSaveFailed: (Exception) -> Unit = {},
) {
    private val gate = Mutex()
    private val noticeFlow = MutableStateFlow<LockNotice>(LockNotice.None)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var retries: Job? = null
    private var kickedAt: Instant? = null

    /** The number of the lock that is pending; changed by every [begin], every confirmation and every superseding unlock. */
    @Volatile
    private var epoch = 0L

    /** An unanswered relay lock's time ([relayLockSent]), kept here too: a record that could not be saved still holds an unlock. */
    @Volatile
    private var relayUntilMs: Long? = null

    /** How many locks were asked for: an unlock supersedes only those asked for before it began. */
    @Volatile
    private var asks = 0L

    /** The unlock the pending lock is bound to ([LockRecord.unlockId]); kept here too, beside the record. */
    @Volatile
    private var boundTo: String? = null

    /** (Release L) The lock ended as [LockNotice.OpenedAgain]: this phone stays locked until an unlock here. */
    @Volatile
    private var held = false

    /** What to tell the member; [LockNotice.Unconfirmed] exactly while a lock is pending. */
    val notice: StateFlow<LockNotice> = noticeFlow.asStateFlow()

    /** A lock was asked for and is not confirmed: the vault must be treated as locked, and may be open. */
    val pending: Boolean get() = noticeFlow.value is LockNotice.Unconfirmed

    /**
     * This phone is to stay locked whatever the vault's status says: a lock is [pending], or (release L) one ended
     * because the vault was opened again since ([LockNotice.OpenedAgain]; until an unlock on this phone succeeds,
     * whether or not the member dismissed the notice). In memory: a new process decides from the status, as after
     * any start.
     */
    val holdsPhone: Boolean get() = pending || held

    /** The unlock the pending lock is bound to; null without a pending lock, or for one made as before release L. */
    val boundUnlockId: String? get() = if (pending) boundTo else null

    /**
     * At a process start: a lock that was pending when the process ended is pending now, and is tried again. It is
     * shown as in doubt from the start: it was not confirmed for as long as the process was gone.
     */
    fun resume() {
        val r = store.load()
        // (Release L) A lock that ended as "opened again": this phone is still locked, whatever the status says, and
        // the member is told again (the screen's card, and the notification an earlier process posted stays).
        if (r.held) {
            held = true
            if (!pending) noticeFlow.value = LockNotice.OpenedAgain
        }
        val since = r.pendingSinceMs ?: return
        synchronized(this) {
            if (pending) return
            epoch++
            asks++
            boundTo = r.unlockId
            noticeFlow.value = LockNotice.Unconfirmed(Instant.ofEpochMilli(since), 1)
            startRetries(epoch, atOnce = true)
        }
    }

    /**
     * The member asks for a lock: pending from here on, in this process and then durably, before anything is sent and
     * before anything else is done for the lock. Returns the lock's number, for [ask]. A save that fails is reported and
     * changes nothing else: the lock is sent and retried all the same. [unlockId] (release L): the vault's current
     * unlock as this phone knows it, to which the lock is bound; null makes the lock as before L.
     */
    fun begin(unlockId: String? = null): Long = synchronized(this) {
        retries?.cancel()
        retries = null
        val t = now()
        boundTo = unlockId
        noticeFlow.value = LockNotice.Unconfirmed(t, 0)
        asks++
        val mine = ++epoch
        write { it.copy(pendingSinceMs = t.toEpochMilli(), unlockId = unlockId) }
        mine
    }

    /**
     * The first attempt of the lock [mine] that [begin] made pending (without one: begins now). Returns true if
     * the vault is known to be locked after it; otherwise the lock stays pending and is tried again.
     */
    suspend fun ask(mine: Long = begin()): Boolean =
        // In the keeper's scope: the caller may go away, the attempt and what follows it do not.
        scope.async {
            val over = tryOnce(mine, first = true)
            if (!over) synchronized(this@LockKeeper) { if (epoch == mine && pending) startRetries(mine, atOnce = false) }
            // Over because the vault was opened again since: not locked.
            over && !held
        }.await()

    /** (Release L) The last lock ended as [LockNotice.OpenedAgain], and no unlock here followed: not known locked. */
    val openedAgain: Boolean get() = held

    /** Evidence from elsewhere that the vault is locked (its `vault.locking`, a status read): a pending lock is confirmed. */
    fun confirmed() {
        synchronized(this) { if (pending) confirm(late = (noticeFlow.value as LockNotice.Unconfirmed).inDoubt) }
    }

    /**
     * Tries again now (the member's "Try again"). Nothing happens without a pending lock, and nothing more than
     * once in [retryNowEvery]: the retries go on at their own pace in between. Returns whether a try was started.
     */
    fun retryNow(): Boolean = synchronized(this) {
        val t = now()
        val last = kickedAt
        if (!pending || (last != null && Duration.between(last, t) < retryNowEvery)) return false
        kickedAt = t
        kick.trySend(Unit)
        true
    }

    /**
     * A `vault.lock` goes out over the relay now and may be acted on for [validFor]: recorded durably **before**
     * it is sent, with [RELAY_MARGIN] added, so that no unlock is sent while it could still reach the vault.
     */
    fun relayLockSent(validFor: Duration) {
        synchronized(this) {
            val until = now().plus(validFor).plus(RELAY_MARGIN).toEpochMilli()
            relayUntilMs = until
            write { it.copy(relayLockUntilMs = until) }
        }
    }

    /** The vault answered the relay lock: it has acted on it, nothing of it is on its way any more. */
    fun relayLockAnswered() {
        synchronized(this) {
            relayUntilMs = null
            write { it.copy(relayLockUntilMs = null) }
        }
    }

    /**
     * The vault is no longer this phone's (it was erased, the vault deleted, the device forgotten): there is nothing
     * left to lock from here. The pending lock, its record and its notice go.
     */
    fun abandon() {
        synchronized(this) {
            epoch++
            retries?.cancel()
            retries = null
            boundTo = null
            held = false
            relayUntilMs = null // nothing of this phone's is on its way to a vault it still holds
            write { if (it != LockRecord()) LockRecord() else null }
            noticeFlow.value = LockNotice.None
        }
    }

    /**
     * The member has read [LockNotice.ConfirmedLate] or [LockNotice.OpenedAgain]. The phone stays locked after the
     * second all the same ([holdsPhone]): only an unlock here ends that.
     */
    fun dismissConfirmed() {
        synchronized(this) {
            val n = noticeFlow.value
            if (n == LockNotice.ConfirmedLate || n == LockNotice.OpenedAgain) noticeFlow.value = LockNotice.None
        }
    }

    /**
     * Runs an unlock. No lock attempt runs beside it. Before it, an unanswered relay lock is waited out, whether
     * or not a lock is pending (a lock confirmed another way leaves its relay request behind). If [opened] says the
     * unlock succeeded, a lock asked for before it began is over (the member opened the vault again, later than
     * they locked it) and none of its attempts will ever be made.
     */
    suspend fun <T> unlocking(
        opened: (T) -> Boolean,
        unlockIdOf: (T) -> String? = { null },
        unlock: suspend () -> T,
    ): T = gate.withLock {
        val before = asks
        settle().takeIf { it > 0 }?.let { delay(it) }
        val result = unlock()
        if (opened(result)) {
            synchronized(this) {
                // A lock asked for while this unlock was on its way is newer than it: it stays, and is tried next.
                if (asks == before) {
                    epoch++
                    retries?.cancel()
                    retries = null
                    boundTo = null
                    held = false
                    relayUntilMs = null
                    write { if (it != LockRecord()) LockRecord() else null }
                    noticeFlow.value = LockNotice.None
                } else if (pending) {
                    // (Release L) The newer lock names the opening this unlock made, never the one it ended (a lock
                    // bound to that one would be answered stale_lock and leave this opening open).
                    val id = unlockIdOf(result)
                    boundTo = id
                    held = false
                    write { it.copy(unlockId = id, held = false) }
                }
            }
        }
        result
    }

    /** How long an unanswered relay lock may still be acted on (never more than [SETTLE_MAX], whatever the clock says). */
    private fun settle(): Long {
        val stored = try {
            store.load().relayLockUntilMs
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            onSaveFailed(e)
            null
        }
        val until = listOfNotNull(stored, relayUntilMs).maxOrNull() ?: return 0
        return (until - now().toEpochMilli()).coerceIn(0, SETTLE_MAX.toMillis())
    }

    /**
     * One attempt of lock [mine], unless it is no longer the pending one. True when the lock is over: confirmed, or
     * (release L) ended because the vault was opened again since ([LockNotice.OpenedAgain]).
     */
    private suspend fun tryOnce(mine: Long, first: Boolean): Boolean = gate.withLock {
        val bound = synchronized(this) {
            if (epoch != mine || !pending) return@withLock !pending
            boundTo
        }
        val outcome = try {
            attempt.tryLock(first, bound)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            LockOutcome.UNCONFIRMED
        }
        synchronized(this) {
            if (epoch != mine || !pending) return@withLock !pending
            val n = noticeFlow.value as LockNotice.Unconfirmed
            when (outcome) {
                // Confirmed before anything failed: the member was never told otherwise, so there is nothing to take back.
                LockOutcome.LOCKED -> confirm(late = n.inDoubt)
                LockOutcome.OPENED_AGAIN -> end(LockNotice.OpenedAgain)
                LockOutcome.UNCONFIRMED -> noticeFlow.value = n.copy(attempts = n.attempts + 1)
            }
        }
        outcome != LockOutcome.UNCONFIRMED
    }

    private fun confirm(late: Boolean) = end(if (late) LockNotice.ConfirmedLate else LockNotice.None)

    /** The pending lock is over with [notice]; nothing of it is tried again. */
    private fun end(notice: LockNotice) {
        epoch++
        retries?.cancel()
        retries = null
        boundTo = null
        held = notice == LockNotice.OpenedAgain
        // The relay lock's time stays: its request may still be waiting for the vault. Held is kept on the phone too.
        write { it.copy(pendingSinceMs = null, unlockId = null, held = held) }
        noticeFlow.value = notice
    }

    /** Saves [f] of the record ([f] null: nothing to save); a failure is reported, never thrown: the lock goes on. */
    @Suppress("TooGenericExceptionCaught") // a disk, a rename, a full phone: whatever it is, the lock is not stopped
    private fun write(f: (LockRecord) -> LockRecord?) {
        try {
            f(store.load())?.let { store.save(it) }
        } catch (e: Exception) {
            onSaveFailed(e)
        }
    }

    /** Tries until confirmed or replaced: [atOnce] (a process start) or after the first backoff (a failed first attempt). */
    private fun startRetries(mine: Long, atOnce: Boolean) {
        retries?.cancel()
        retries = scope.launch {
            var n = 0
            var wait = !atOnce
            while (true) {
                if (wait) {
                    // The backoff, cut short by a kick.
                    withTimeoutOrNull(backoff(n).toMillis()) { kick.receive() }
                }
                wait = true
                if (tryOnce(mine, first = false)) return@launch
                if (epoch != mine) return@launch
                n++
            }
        }
    }

    companion object {
        /** Added to a relay lock's validity before an unlock may follow it: the clocks of phone and vault differ. */
        val RELAY_MARGIN: Duration = Duration.ofSeconds(10)

        /** The longest an unlock waits for an unanswered relay lock (a clock set back must not hold it longer). */
        val SETTLE_MAX: Duration = Duration.ofSeconds(60)

        /** The member's "Try again" starts a try once in this time at most. */
        val RETRY_NOW_EVERY: Duration = Duration.ofSeconds(10)

        private val QUICK = listOf(1L, 2L, 5L, 10L)
        private const val SLOW_S = 30L
        private const val SLOW_TIMES = 4
        private const val STEADY_S = 60L
        private const val JITTER = 0.2

        /** The wait before retry number [attempts], without jitter: 1, 2, 5, 10 s, four times 30 s, then every 60 s. */
        fun backoffBase(attempts: Int): Duration = Duration.ofSeconds(
            when {
                attempts < QUICK.size -> QUICK[attempts]
                attempts < QUICK.size + SLOW_TIMES -> SLOW_S
                else -> STEADY_S
            },
        )

        /**
         * [backoffBase], each wait up to a fifth longer so that phones do not retry in step. Every retry is one
         * `POST /api/vault/lock`, which the member API allows 30 times in 15 minutes per member: this makes at most
         * 21 in the first 15 minutes and 15 in every 15 minutes after.
         */
        fun defaultBackoff(attempts: Int): Duration {
            val base = backoffBase(attempts).toMillis()
            return Duration.ofMillis(base + (base * JITTER * Math.random()).toLong())
        }
    }
}
