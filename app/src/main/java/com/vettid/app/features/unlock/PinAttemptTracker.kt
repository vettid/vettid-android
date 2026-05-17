package com.vettid.app.features.unlock

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SECURITY (#97): client-side rate-limit on PIN unlock attempts.
 *
 * The enclave does coarse rate-limiting at the NATS layer, but a
 * device-local guard prevents:
 *   • a stolen device from being brute-forced via UI-automation
 *     bypassing the typing UX,
 *   • repeated wrong-PIN attempts from racing the network round-trip.
 *
 * Backoff schedule (ascending) — counters survive process restart by
 * design via SharedPreferences:
 *   • attempts 1-4: no delay
 *   • attempt 5:   30s lockout
 *   • attempt 10:  5min lockout
 *   • attempt 15:  30min lockout
 *   • attempt 20+: 60min lockout (caps here)
 *
 * Successful unlock zeroes both counter and lockout.
 */
@Singleton
class PinAttemptTracker @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Milliseconds until unlock attempts are allowed again; 0 if unlocked. */
    fun remainingLockoutMs(now: Long = System.currentTimeMillis()): Long {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL_MS, 0L)
        return (lockedUntil - now).coerceAtLeast(0L)
    }

    /** True when the user must wait before trying another PIN. */
    fun isLockedOut(now: Long = System.currentTimeMillis()): Boolean =
        remainingLockoutMs(now) > 0L

    /** Total failed attempts since the last successful unlock. */
    fun failedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    /**
     * Record a failed PIN attempt and apply the matching lockout (if any).
     * Returns the lockout duration applied in milliseconds (0 = no lockout
     * yet, more attempts allowed).
     */
    fun recordFailure(now: Long = System.currentTimeMillis()): Long {
        val attempts = failedAttempts() + 1
        val lockoutMs = lockoutForAttempts(attempts)
        prefs.edit().apply {
            putInt(KEY_FAILED_ATTEMPTS, attempts)
            if (lockoutMs > 0) {
                putLong(KEY_LOCKED_UNTIL_MS, now + lockoutMs)
            } else {
                remove(KEY_LOCKED_UNTIL_MS)
            }
        }.apply()
        return lockoutMs
    }

    /** Reset all tracking after a successful unlock. */
    fun reset() {
        prefs.edit().apply {
            remove(KEY_FAILED_ATTEMPTS)
            remove(KEY_LOCKED_UNTIL_MS)
        }.apply()
    }

    private fun lockoutForAttempts(attempts: Int): Long = when {
        attempts >= 20 -> 60L * 60_000L
        attempts >= 15 -> 30L * 60_000L
        attempts >= 10 -> 5L * 60_000L
        attempts >= 5 -> 30_000L
        else -> 0L
    }

    companion object {
        private const val PREFS_NAME = "vettid_pin_attempts"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKED_UNTIL_MS = "locked_until_ms"
    }
}
