package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.AccountView
import com.vettid.core.vault.NameRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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

    /** What [keep] kept: the snapshot [before] it and the [update]. */
    data class Kept(val before: AccountSnapshot?, val update: AccountUpdate)

    /** Read before a request whose answer is to be kept ([keep], [request]). */
    fun mark(): Int = generation

    /**
     * Keeps what is new in `account.get`'s [v] ([accountUpdate], against the snapshot and the request held now, under the
     * lock: an older answer never replaces a newer one) while no [forget] came after [mark] and [open] still says the
     * vault is open; then [also], under the lock. Null when nothing was kept.
     */
    fun keep(mark: Int, v: AccountView, open: () -> Boolean, also: () -> Unit = {}): Kept? = synchronized(lock) {
        if (mark != generation || !open()) return null
        val before = snapshot
        val u = accountUpdate(before, nameRequest, v) ?: return null
        snapshot = u.snapshot
        nameRequest = u.nameRequest
        also()
        Kept(before, u)
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

/** What the vault manager does with the own profile when the phase or the owner check moves (ANDROID-PLAN §4). */
internal enum class OwnProfileStep {
    /** Forget the own account and profile (the vault is not open). */
    FORGET,

    /** Read them again from the vault (it opened again). */
    READ,

    NONE,
    ;

    companion object {
        /** The phase moved to [phase]: unlocked reads, starting does nothing, every other phase forgets. */
        fun onPhase(phase: AppPhase): OwnProfileStep = when (phase) {
            AppPhase.Unlocked -> READ
            AppPhase.Starting -> NONE
            else -> FORGET
        }

        /** The owner check moved from [wasHeld] to [held] (held or due) during [phase]. */
        fun onCheck(wasHeld: Boolean, held: Boolean, phase: AppPhase): OwnProfileStep = when {
            held && !wasHeld -> FORGET
            !held && wasHeld && phase == AppPhase.Unlocked -> READ
            else -> NONE
        }

        /** Held or due (§3.6.3). */
        fun held(v: OwnerCheckView?): Boolean = v != null && v.state != OwnerCheckState.OK
    }
}

/** The account record's keys an older build kept the own account under ([LocalAccount]). */
private val LEGACY_OWN_ACCOUNT_KEYS = setOf("snapshot", "nameRequest")

/**
 * Whether the account record's JSON [raw] still holds the own account of an older build (the snapshot or the name
 * request): the vault manager then writes the record again without it at the start ([AccountRecord.rewrite]).
 */
internal fun holdsLegacyOwnAccount(raw: String, json: Json): Boolean = try {
    json.parseToJsonElement(raw).jsonObject.keys.any { it in LEGACY_OWN_ACCOUNT_KEYS }
} catch (_: IllegalArgumentException) {
    false
}
