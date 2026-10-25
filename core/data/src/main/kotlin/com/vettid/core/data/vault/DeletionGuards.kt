package com.vettid.core.data.vault

import com.vettid.core.altchan.MemberApiException
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage

/**
 * (ANDROID-PLAN 0.1.78 §4 "A vault being deleted", 5) The decisions of a 410 without the vault's word, as small classes that
 * [VaultManager] only constructs and gives callbacks, so that tests drive the logic and no call site holds any.
 */

/** The event observer while a 410 stands: the vault's own word erases, replacement proofs go to the holder, the rest is dropped. */
internal class DeletionEvents(
    private val apiStanding: () -> Boolean,
    private val onErase: () -> Unit,
    private val onHolder: (VaultMessage) -> Unit,
) {
    /** True when the event was taken here; false: no 410 stands and the ordinary handling applies. */
    fun take(m: VaultMessage): Boolean {
        val action = VaultDeletion.eventAction(m.type, VaultJson.str(m.body, "reason"), apiStanding()) ?: return false
        when (action) {
            VaultDeletion.EventAction.ERASE -> onErase()
            VaultDeletion.EventAction.HOLDER -> onHolder(m)
            VaultDeletion.EventAction.DROP -> Unit
        }
        return true
    }
}

/**
 * One read of the status for the watch: a `410` is [DeletionCheck.GONE] (erases) only with the vault's word, so not while a 410
 * stands alone ([apiStanding]); a status that lists the vault again then removes the marker ([onListedWhileApi]).
 */
internal class ResolvedRead(
    private val apiStanding: () -> Boolean,
    private val source: suspend () -> DeletionCheck,
    private val onListedWhileApi: () -> Unit,
) {
    suspend fun read(): DeletionCheck {
        val api = apiStanding()
        val r = VaultDeletion.resolve(source(), vaultWord = !api)
        if (api && VaultDeletion.clearsApiMarker(r)) onListedWhileApi()
        return r
    }
}

/** Whether a failed request starts the 410-only state, and whether it locks the vault first. */
internal object ApiDeletionStart {
    class Plan(val code: String, val lock: Boolean)

    /** Null: this failure starts nothing (not a definite 410, a state already held, a phone with no paired vault). */
    fun plan(f: VaultFailure, phase: AppPhase, deleting: Boolean): Plan? {
        if (!VaultDeletion.isGone410(f) || !VaultDeletion.api410Applies(phase, deleting)) return null
        val code = (f.cause as MemberApiException).code
        return Plan(code, VaultDeletion.locksOnApiDeleted(phase))
    }
}

/** What may open a session while "being deleted", and what the lock of a standing 410 may open. */
internal class SessionGuard(private val deleting: () -> Boolean, private val apiStanding: () -> Boolean) {
    /** Throws unless a session may be opened: never while deleting, except the mailbox of a standing 410. */
    fun check(mailboxOnly: Boolean) {
        if (!VaultDeletion.allowsSession(deleting(), mailboxOnly, apiStanding())) {
            throw VaultFailure(FailureKind.NOT_SUPPORTED, VaultManager.CODE_DELETING)
        }
    }

    /** The lock's session is the mailbox one while a 410 stands. */
    fun lockMailboxOnly(): Boolean = apiStanding()
}
