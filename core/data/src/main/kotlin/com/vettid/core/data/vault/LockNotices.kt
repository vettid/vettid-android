package com.vettid.core.data.vault

import com.vettid.core.altchan.UnlockIds
import com.vettid.core.vault.VaultJson
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
 *
 * (VAULT-MESSAGING 0.29.0 §10.2 "Locks", release L) A notice that names the unlock it ends (`unlock_id`) is judged by
 * that name instead, when this phone holds the vault's current one ([current]): another name is an older opening's
 * notice, and is dropped; the same name is this opening's. A notice without the name (a release before L), or one
 * that arrives while this phone holds no current name (a new process before its first status read), is judged by the
 * time as above.
 */
class LockNotices(opened: Instant? = null, private val current: () -> String? = { null }) {
    /** The last unlock's time; the one kept on the phone at a process start ([opened]), so that a restart forgets nothing. */
    @Volatile
    private var openedAt: Instant? = opened

    /** A successful unlock answered at [at] (the result's inner `ts`; null: unknown, nothing changes). */
    fun opened(at: Instant?) {
        if (at == null) return
        val prev = openedAt
        if (prev == null || at.isAfter(prev)) openedAt = at
    }

    /** Whether [m] is a `vault.locking` from before the vault last opened here (or, by its name, of another opening). */
    fun stale(m: VaultMessage): Boolean {
        if (m.type != TYPE) return false
        val named = named(m)
        val mine = current()
        val at = openedAt
        return if (named != null && mine != null) named != mine else at != null && !m.ts.isAfter(at)
    }

    /**
     * Whether [m] is evidence for the pending lock: a `vault.locking` naming the unlock that lock is bound to
     * ([bound], release L; [held]: after "opened again", see below), or, when either has no name, one from after the vault last opened here
     * ([sinceLastOpened]).
     */
    fun confirms(m: VaultMessage, bound: String?, held: Boolean = false, dead: String? = null): Boolean {
        if (m.type != TYPE) return false
        val named = named(m)
        // (Release L) Held after "opened again", with no lock pending: a notice naming the dead opening, or none, or
        // arriving when the dead one is not known, says nothing about the vault now. Only a newer name does (and a
        // status read, elsewhere).
        return when {
            held -> named != null && dead != null && named != dead
            named != null && bound != null -> named == bound
            else -> sinceLastOpened(m)
        }
    }

    private fun named(m: VaultMessage): String? = UnlockIds.of(VaultJson.str(m.body, "unlock_id"))

    /**
     * Whether [m] is a `vault.locking` from **after** the vault last opened here, with that time known: only such a
     * notice is evidence for a lock asked for since (an older one, left uncollected, says nothing about it).
     */
    fun sinceLastOpened(m: VaultMessage): Boolean {
        val at = openedAt
        return m.type == TYPE && at != null && m.ts.isAfter(at)
    }

    companion object {
        const val TYPE = "vault.locking"
    }
}
