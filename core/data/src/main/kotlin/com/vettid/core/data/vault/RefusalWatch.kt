package com.vettid.core.data.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A phone replaced while it was offline, whose vault the new phone already
 * unlocked, sees the vault `unlocked` and opens the app, never the unlock
 * screen, so it never meets the unreadable unlock result that offers "Erase
 * VettID from this phone" (owner decision, 2026-10-05). What it meets instead
 * is the relay refusing its deposits to the vault (`token_revoked`: the vault
 * denylisted its relay key, §7.4).
 *
 * This watch turns [THRESHOLD] such refusals in a row, with no message from
 * the vault in between, into the unlock screen ([route]: the phase becomes
 * `Locked`) with [suspected] set, where the member's PIN asks the enclave
 * itself: an unreadable result then offers the erase, a sealed answer clears
 * the suspicion. The relay's refusals are not authenticated, so this never
 * wipes anything and holds no reference to the wipe: only an authenticated
 * `device.unlinked` or a sealed `unknown_device` does ([HolderPolicy]). A
 * working phone with a passing token error (a revoked token the vault
 * replaces, a refusal followed by any message from the vault) stays where it is.
 */
class RefusalWatch(private val threshold: Int = THRESHOLD, private val route: () -> Unit) {
    private val flag = MutableStateFlow(false)

    /** The vault refused this phone [threshold] times in a row while it was open; cleared by any sign that it knows it. */
    val suspected: StateFlow<Boolean> = flag.asStateFlow()

    /**
     * The device's count of consecutive refusals ([com.vettid.core.vault.VaultDevice.vaultRefusals]) changed, in
     * [phase]. Routes once, from the open app only, when the count reaches [threshold]; a count back at 0 (the
     * vault spoke to this phone) clears the suspicion. Returns whether it routed.
     */
    fun onRefusals(count: Int, phase: AppPhase): Boolean {
        if (count == 0) flag.value = false
        val routes = count >= threshold && !flag.value && phase == AppPhase.Unlocked
        if (routes) {
            flag.value = true
            route()
        }
        return routes
    }

    /** A sealed unlock result (any): the enclave knows this phone. */
    fun clear() {
        flag.value = false
    }

    companion object {
        /** Refused deposits in a row (each a separate request: the app retries none of them, §8.6). */
        const val THRESHOLD = 3
    }
}
