package com.vettid.app.core.security

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

private const val TAG = "UnlockRateLimiter"

/**
 * Per-process rate limiter for credential.identity-unlock and the
 * other password-gated operations that share its UTK pool.
 *
 * The vault already enforces single-use UTKs on AEAD success, but the
 * pool is large enough (a few thousand entries at steady state) that
 * a stolen-but-locked phone gives an attacker meaningful runway for
 * online password guessing. This limiter adds:
 *
 *   - Exponential backoff after each consecutive failure (200ms doubling
 *     to 30s) to soak up wall-clock time.
 *   - A hard ceiling (DEFAULT_HARD_CAP) after which we refuse to
 *     forward the request at all and require the user to relaunch the
 *     app. Process restart resets the counter — paired with a hard
 *     wipe (signOutAndWipe) for repeat offenders.
 *
 * SECURITY (android-auth-H2). Lives outside CredentialStore so the
 * counter survives across CredentialStore re-instantiation but resets
 * on app process death.
 */
@Singleton
class UnlockRateLimiter @Inject constructor() {

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var lastFailureAtMs: Long = 0L

    /**
     * Call before issuing the unlock request. Suspends for the
     * computed backoff window, and returns false if the hard cap has
     * been hit (caller should refuse the operation).
     */
    suspend fun beforeAttempt(): Boolean {
        val failures = consecutiveFailures
        if (failures >= DEFAULT_HARD_CAP) {
            Log.w(TAG, "Unlock attempts hit hard cap ($failures); refusing")
            return false
        }
        if (failures > 0) {
            val delayMs = backoffMs(failures)
            Log.i(TAG, "Throttling unlock attempt $failures (delay=${delayMs}ms)")
            delay(delayMs)
        }
        return true
    }

    /** Call when the unlock attempt succeeded. */
    fun recordSuccess() {
        consecutiveFailures = 0
        lastFailureAtMs = 0L
    }

    /**
     * Call when the unlock attempt failed (wrong password, vault
     * rejection, etc.). Increments the failure counter.
     */
    fun recordFailure() {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(Int.MAX_VALUE / 2)
        lastFailureAtMs = System.currentTimeMillis()
    }

    /** Snapshot for UI ("X attempts remaining"). */
    fun remainingAttempts(): Int = (DEFAULT_HARD_CAP - consecutiveFailures).coerceAtLeast(0)

    fun isLocked(): Boolean = consecutiveFailures >= DEFAULT_HARD_CAP

    private fun backoffMs(failures: Int): Long {
        // 200ms * 2^(failures-1), capped at 30s.
        val exp = (failures - 1).coerceAtMost(8)
        val ms = 200L shl exp
        return ms.coerceAtMost(30_000L)
    }

    companion object {
        const val DEFAULT_HARD_CAP = 10
    }
}
