package com.vettid.core.data.lock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Allow screenshots" (owner decision 2026-10-11, A6 audit S1). The whole app is kept out of screenshots, screen
 * recordings and the recent-apps preview; this is the member's way out for a while, from Settings → App, after a
 * confirmation. Kept in memory only: it is never on at app start, never written to disk and never sent to the vault.
 * It turns itself off when the vault locks and [LIFETIME_MS] after it was turned on, whichever comes first.
 *
 * [onByDefault] is for the development builds (debug, devStack), whose screens are screenshotted all the time (the
 * screen catalog): there the allowance is on from the start and nothing turns it off but the switch.
 *
 * The typed recovery code's screen stays protected whatever this says (it holds the window secure itself).
 */
class ScreenshotAllowance(
    private val onByDefault: Boolean,
    private val scope: CoroutineScope,
    /** A clock that also runs while the phone sleeps (`SystemClock.elapsedRealtime`), in milliseconds. */
    private val now: () -> Long,
) {
    private val flow = MutableStateFlow(onByDefault)

    /** Screenshots, screen recording and the recent-apps preview may show the app. */
    val allowed: StateFlow<Boolean> = flow.asStateFlow()

    /** True in the builds where the allowance does not end by itself (shown by the Settings row). */
    val staysOn: Boolean get() = onByDefault

    private var until: Long? = null
    private var timer: Job? = null

    /** The member confirmed: on, for at most an hour. */
    fun allow() {
        flow.value = true
        if (onByDefault) return
        until = now() + LIFETIME_MS
        timer?.cancel()
        timer = scope.launch {
            delay(LIFETIME_MS)
            check()
        }
    }

    /** The member turned it off. */
    fun disallow() {
        timer?.cancel()
        timer = null
        until = null
        flow.value = false
    }

    /** The vault is no longer open (locked, signed out, unreachable): the allowance ends. */
    fun vaultClosed() {
        if (!onByDefault) disallow()
    }

    /**
     * Ends an allowance whose hour is over. Called by the timer and on every return to the app: a timer does not
     * run while the phone sleeps, the clock does.
     */
    fun check() {
        val end = until ?: return
        if (now() >= end) disallow()
    }

    companion object {
        const val LIFETIME_MS = 60 * 60 * 1000L
    }
}
