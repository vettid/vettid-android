package com.vettid.core.data.lock

import android.hardware.biometrics.BiometricPrompt

/** How an app-lock prompt ended. */
enum class PromptOutcome {
    SUCCEEDED,

    /** The system cancelled it (the app went to the background, another prompt took over). */
    SYSTEM_CANCELED,

    /** The member cancelled it, or it failed (lockout, no hardware): the lock screen's "Unlock" asks again. */
    DISMISSED,
    ;

    companion object {
        /** A BiometricPrompt error code as an outcome. */
        fun ofError(code: Int): PromptOutcome = if (code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) SYSTEM_CANCELED else DISMISSED
    }
}

/**
 * One app-lock prompt at a time (owner report 2026-10-09: after a return to the app a second prompt followed the
 * first, and the phone PIN typed into the first reached the screen under the lock). Process-wide (it lives in
 * [AppLock]), so a recreated activity sees a prompt the old one started:
 * - [tryStart] says whether a prompt may start; never while one is in flight;
 * - an automatic prompt (the lock showing, a return to the foreground) never follows a prompt that ended without
 *   success during the same lock: the member presses "Unlock" (no loop of prompts);
 * - [ownerGone]: the activity that showed it was destroyed, so its callback will not come.
 */
class UnlockPromptGate {
    private var inFlight = false
    private var owner: Any? = null
    private var endedWithoutSuccess = false

    val prompting: Boolean @Synchronized get() = inFlight

    @Synchronized
    fun tryStart(auto: Boolean, owner: Any): Boolean {
        if (inFlight || (auto && endedWithoutSuccess)) return false
        inFlight = true
        this.owner = owner
        return true
    }

    @Synchronized
    fun finished(outcome: PromptOutcome) {
        inFlight = false
        owner = null
        endedWithoutSuccess = outcome != PromptOutcome.SUCCEEDED
    }

    /** True when [owner] had the prompt in flight (it is then over). */
    @Synchronized
    fun ownerGone(owner: Any): Boolean {
        if (this.owner !== owner) return false
        inFlight = false
        this.owner = null
        return true
    }

    /** A new lock (start, timeout): its first prompt is automatic again. */
    @Synchronized
    fun newLock() {
        endedWithoutSuccess = false
    }
}
