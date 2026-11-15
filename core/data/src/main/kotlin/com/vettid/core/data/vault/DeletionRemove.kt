package com.vettid.core.data.vault

/**
 * (ANDROID-PLAN 0.1.54, 0.1.78) Whether the member's confirmed "Remove from this phone" wipes, decided once it holds the
 * deletion's ends (no erase or clear beside it).
 */
internal object DeletionRemove {
    /** A deletion as this process holds it: its own marker and the 410's (either may be null, not both for a deletion). */
    data class Seen(val own: DeletingMarker?, val api: DeletedApiMarker?) {
        val any: Boolean get() = own != null || api != null
    }

    /**
     * True to wipe: the deletion the member confirmed it for ([confirmed], read when Remove was called) is still the one up
     * ([now]), "being deleted" holds, and its screen is past the bound (the only time Remove is offered). False after the
     * vault's word erased (its notice stays), after a status listed the vault again, and for a new deletion that came up
     * behind it (its own bound first).
     */
    fun wipes(confirmed: Seen, now: Seen, deleting: Boolean, phase: AppPhase): Boolean =
        confirmed.any && confirmed == now && deleting && (phase as? AppPhase.Deleting)?.slow == true
}
