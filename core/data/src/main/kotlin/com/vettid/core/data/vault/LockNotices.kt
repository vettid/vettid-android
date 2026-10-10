package com.vettid.core.data.vault

import com.vettid.core.vault.VaultMessage
import java.time.Instant

/**
 * Drops `vault.locking` notices from before the vault last opened (staging S8 canary, 2026-10-10).
 *
 * The notice is relayed and can arrive after the unlock that followed it: the relay connection is paused while the
 * vault is locked and comes back after the unlock, and an unlock of a vault that is already running locks it first
 * (§11.4: the enclave locks the running vault, then opens it in the same request). Taken as current, such a notice
 * sent the open app back to the unlock screen, where the next unlock locked and reopened the vault again.
 *
 * The comparison is on the enclave's clock: [opened] records the inner `ts` of the successful unlock result, which
 * the enclave sets after any lock that unlock made, and a notice whose own `ts` is not later than it is stale. A
 * notice that is later (the vault locked after this unlock) is kept, as is every notice before the first unlock
 * this process saw.
 */
class LockNotices {
    @Volatile
    private var openedAt: Instant? = null

    /** A successful unlock answered at [at] (the result's inner `ts`; null: unknown, nothing changes). */
    fun opened(at: Instant?) {
        if (at == null) return
        val prev = openedAt
        if (prev == null || at.isAfter(prev)) openedAt = at
    }

    /** Whether [m] is a `vault.locking` from before the vault last opened here. */
    fun stale(m: VaultMessage): Boolean {
        val at = openedAt
        return m.type == TYPE && at != null && !m.ts.isAfter(at)
    }

    companion object {
        const val TYPE = "vault.locking"
    }
}
