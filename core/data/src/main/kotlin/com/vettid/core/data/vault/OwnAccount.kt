package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.NameRequest

/**
 * The member's own account as the open vault reported it (ANDROID-PLAN §4 "The member's own profile"): the snapshot of
 * `account.get` (the names, the full email address, the membership) and the latest name request. **Memory only**: never
 * in the account record, a file, DataStore or `SavedStateHandle`, and forgotten ([forget]) whenever the vault is not
 * open (a lock, the hold or the due state, a sign-out, a reset, a wipe, a deletion). It comes back from the vault after
 * the next unlock (or the passed check).
 *
 * Every write is dropped when a [forget] came after the [mark] read before the request it answers was sent, or when the
 * vault is not open at the moment of the write: an answer on its way when the vault locks never brings the names back.
 * [publish] runs under the same lock, so that what the app shows is never computed from a value a [forget] has already
 * taken away.
 */
internal class OwnAccount {
    private val lock = Any()

    @Volatile
    private var generation = 0

    @Volatile
    var snapshot: AccountSnapshot? = null
        private set

    @Volatile
    var nameRequest: NameRequest? = null
        private set

    /** Read before a request whose answer is to be kept ([keep], [request]). */
    fun mark(): Int = generation

    /**
     * Keeps [u] (`account.get`'s, [accountUpdate]) while no [forget] came after [mark] and [open] still says the vault is
     * open; then [also], under the lock. True when kept.
     */
    fun keep(mark: Int, u: AccountUpdate, open: () -> Boolean, also: () -> Unit = {}): Boolean = synchronized(lock) {
        if (mark != generation || !open()) return false
        snapshot = u.snapshot
        nameRequest = u.nameRequest
        also()
        true
    }

    /** A name request the vault stored (`account.name.set`), under the same conditions as [keep]. */
    fun request(mark: Int, r: NameRequest, open: () -> Boolean, also: () -> Unit = {}): Boolean = synchronized(lock) {
        if (mark != generation || !open()) return false
        nameRequest = r
        also()
        true
    }

    /** The vault is not open: the snapshot and the name request go; every answer still on its way is dropped. */
    fun forget(also: () -> Unit = {}) {
        synchronized(lock) {
            generation++
            snapshot = null
            nameRequest = null
            also()
        }
    }

    /** [f] of the snapshot and the name request as they are now, under the lock (what the app shows). */
    fun <T> publish(f: (AccountSnapshot?, NameRequest?) -> T): T = synchronized(lock) { f(snapshot, nameRequest) }
}

/**
 * Whether the vault is open for the own profile: [phase] unlocked and the owner check ([check]) neither held nor due
 * (§3.6.3; none read yet counts as open). Otherwise the member's names, full email and photo are not kept.
 */
internal fun isOwnProfileOpen(phase: AppPhase, check: OwnerCheckView?): Boolean =
    phase == AppPhase.Unlocked && (check == null || check.state == OwnerCheckState.OK)
