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
 * A lock the member asked for and the vault has not acknowledged, as it is kept on the phone: when it was asked.
 * Written before the first attempt and removed only when the lock is confirmed or a newer unlock replaced it, so a
 * process that dies in between starts locked and goes on trying.
 */
data class LockMarker(val askedAtMs: Long)

/** Where [LockMarker] is kept: with the app's durable account record. */
interface LockMarkerStore {
    fun load(): LockMarker?

    /** Writes [marker], or removes it (null). Must be durable when it returns. */
    fun save(marker: LockMarker?)
}

/** What the member is told about the lock they asked for. */
sealed interface LockNotice {
    /** Nothing to say: no lock is waiting, or it was confirmed without ever having been in doubt. */
    data object None : LockNotice

    /**
     * The vault could not be told to lock and may still be open. This phone is locked all the same and keeps trying.
     * (A vault has no time after which it locks by itself: it stays open until it is told, or until the service
     * restarts it. So there is no "at the latest by" to show.)
     */
    data class Unconfirmed(val askedAt: Instant, val attempts: Int) : LockNotice

    /** The vault has now confirmed a lock that was in doubt: said once, until dismissed or the next unlock. */
    data object ConfirmedLate : LockNotice
}

/** One try to lock the vault. */
fun interface LockAttempt {
    /**
     * Tells the vault to lock and says whether it is **known** to be locked now: it acknowledged, or its status says
     * so. [first] is the attempt made at once when the member asked; later ones are retries. Never throws for an
     * unreachable vault: that is `false`.
     */
    suspend fun tryLock(first: Boolean): Boolean
}

/**
 * The member's lock, made to **fail secure** (owner decision 2026-10-12): the phone is locked from the moment the
 * member asks, in every outcome; the vault is *known* locked only when it has acknowledged or its status says so;
 * until then the lock is pending, durably, and is tried again until it is confirmed.
 *
 * - [ask] writes the marker before the first attempt, makes that attempt, and starts the retries if it did not
 *   confirm. It returns whether the lock is confirmed; a caller that is cancelled only stops waiting.
 * - [resume] is for a process start: a marker found means the phone starts locked and the retries start at once.
 * - [confirmed] is for evidence from elsewhere (the vault's own `vault.locking`, a status read).
 * - [unlocking] is the only way to unlock while this class is in use. Lock attempts and unlocks never overlap, and
 *   after an unlock that succeeded no attempt of an older lock is ever made: the marker is removed, the retries
 *   are cancelled, and every attempt checks, inside the same mutual exclusion, that it still belongs to the lock
 *   that is pending. An unlock that fails leaves the pending lock as it was.
 *
 * Retries: [backoff] after each failed attempt (quick at first, then every 30 s, with jitter), and at once on
 * [retryNow] (the member's "Try again", the network coming back).
 */
class LockKeeper(
    private val scope: CoroutineScope,
    private val store: LockMarkerStore,
    private val attempt: LockAttempt,
    private val now: () -> Instant = Instant::now,
    private val backoff: (attempts: Int) -> Duration = ::defaultBackoff,
    /** Before an unlock: how long an unanswered lock request of this keeper may still be on its way to the vault. */
    private val settle: () -> Duration = { Duration.ZERO },
) {
    private val gate = Mutex()
    private val noticeFlow = MutableStateFlow<LockNotice>(LockNotice.None)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var retries: Job? = null

    /** The number of the lock that is pending; changed by every [ask] and by every unlock that succeeded. */
    @Volatile
    private var epoch = 0L

    /** What to tell the member; [LockNotice.Unconfirmed] exactly while a lock is pending. */
    val notice: StateFlow<LockNotice> = noticeFlow.asStateFlow()

    /** A lock was asked for and is not confirmed: the vault must be treated as locked, and may be open. */
    val pending: Boolean get() = noticeFlow.value is LockNotice.Unconfirmed

    /** At a process start: a lock that was pending when the process ended is pending now, and is tried again. */
    fun resume() {
        val m = store.load() ?: return
        synchronized(this) {
            if (pending) return
            epoch++
            noticeFlow.value = LockNotice.Unconfirmed(Instant.ofEpochMilli(m.askedAtMs), 0)
            startRetries(epoch, atOnce = true)
        }
    }

    /**
     * The member asks for a lock. The marker is durable before anything is sent. Returns true if the vault is known
     * to be locked after the first attempt; otherwise the lock is pending and is tried again.
     */
    suspend fun ask(): Boolean {
        val mine = synchronized(this) {
            retries?.cancel()
            store.save(LockMarker(now().toEpochMilli()))
            noticeFlow.value = LockNotice.Unconfirmed(now(), 0)
            ++epoch
        }
        // In the keeper's scope: the caller may go away, the attempt and what follows it do not.
        return scope.async {
            val ok = tryOnce(mine, first = true)
            if (!ok) synchronized(this@LockKeeper) { if (epoch == mine && pending) startRetries(mine, atOnce = false) }
            ok
        }.await()
    }

    /** Evidence from elsewhere that the vault is locked (its `vault.locking`, a status read): a pending lock is confirmed. */
    fun confirmed() {
        synchronized(this) { if (pending) confirm(late = true) }
    }

    /** Tries again now (the member's "Try again", the network coming back). Nothing happens without a pending lock. */
    fun retryNow() {
        kick.trySend(Unit)
    }

    /**
     * The vault is no longer this phone's (it was erased, the vault deleted, the device forgotten): there is nothing
     * left to lock from here. The pending lock, its marker and its notice go.
     */
    fun abandon() {
        synchronized(this) {
            epoch++
            retries?.cancel()
            retries = null
            if (store.load() != null) store.save(null)
            noticeFlow.value = LockNotice.None
        }
    }

    /** The member has read [LockNotice.ConfirmedLate]. */
    fun dismissConfirmed() {
        synchronized(this) { if (noticeFlow.value == LockNotice.ConfirmedLate) noticeFlow.value = LockNotice.None }
    }

    /**
     * Runs an unlock. No lock attempt runs beside it; if [opened] says it succeeded, any pending lock is over (the
     * member opened the vault again, later than they locked it) and none of its attempts will ever be made.
     */
    suspend fun <T> unlocking(opened: (T) -> Boolean, unlock: suspend () -> T): T = gate.withLock {
        // A lock request that got no answer may still reach the vault for a moment: not after the unlock.
        if (pending) settle().takeIf { !it.isNegative && !it.isZero }?.let { delay(it.toMillis()) }
        val result = unlock()
        if (opened(result)) {
            synchronized(this) {
                epoch++
                retries?.cancel()
                retries = null
                store.save(null)
                noticeFlow.value = LockNotice.None
            }
        }
        result
    }

    /** One attempt of lock [mine], unless it is no longer the pending one. True when the lock is confirmed. */
    private suspend fun tryOnce(mine: Long, first: Boolean): Boolean = gate.withLock {
        if (epoch != mine || !pending) return@withLock !pending
        val ok = try {
            attempt.tryLock(first)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            false
        }
        synchronized(this) {
            if (epoch != mine || !pending) return@withLock !pending
            if (ok) {
                // Confirmed by the first attempt: the member was never told otherwise, so there is nothing to take back.
                confirm(late = !first)
            } else {
                val n = noticeFlow.value as LockNotice.Unconfirmed
                noticeFlow.value = n.copy(attempts = n.attempts + 1)
            }
        }
        ok
    }

    private fun confirm(late: Boolean) {
        epoch++
        retries?.cancel()
        retries = null
        store.save(null)
        noticeFlow.value = if (late) LockNotice.ConfirmedLate else LockNotice.None
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
        private val STEPS = listOf(1L, 2L, 5L, 10L)
        private const val STEADY_S = 30L
        private const val JITTER = 0.2

        /** 1 s, 2 s, 5 s, 10 s, then every 30 s; each up to a fifth longer, so that phones do not retry in step. */
        fun defaultBackoff(attempts: Int): Duration {
            val base = STEPS.getOrElse(attempts) { STEADY_S } * MILLIS
            return Duration.ofMillis(base + (base * JITTER * Math.random()).toLong())
        }

        private const val MILLIS = 1_000L
    }
}
