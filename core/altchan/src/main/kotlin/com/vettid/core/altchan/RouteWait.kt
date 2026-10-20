package com.vettid.core.altchan

import com.vettid.core.attestation.manifest.Release
import kotlinx.coroutines.delay

/**
 * (ANDROID-PLAN 0.1.54 point 6; owner decision of 2026-10-20) For a short while after `update: moved` the member API can
 * still route the vault to the release it moved from, until the host records the move (VAULT-MESSAGING §11.10.5). The
 * app never sends that release the PIN (the rollback check of §11.10.6 is unchanged; only the member's "Return to
 * release N" does): when the route names the stored marker's `from`, number and PCR0 both, it fetches the route again
 * after [WAITS_S] (about a minute) and goes on only once it names another release. A route that still names `from`
 * after the last wait is [AltRefusedException.Reason.ROUTE_PENDING]. A route to any other release is returned as it is:
 * an older one that is not `from` then meets the rollback check, as before.
 */
object RouteWait {
    /** The waits before each fetch again, in seconds: 5, 10, 15, 15 and 15. */
    val WAITS_S: List<Long> = listOf(5L, 10L, 15L, 15L, 15L)

    private const val MS_PER_S = 1000L

    /** Whether [routed] is the release the unconfirmed move [from] left. */
    fun namesFrom(from: MovedFrom?, routed: Release): Boolean = from != null && routed.pcr0 == from.pcr0 && routed.number == from.number

    /**
     * [first], or the first route fetched again ([refetch]) that does not name [from]. [release] reads a route's release.
     * Throws [AltRefusedException] `ROUTE_PENDING` when every fetch names it; nothing is sent meanwhile.
     */
    suspend fun <T> awayFrom(from: MovedFrom?, first: T, release: (T) -> Release, refetch: suspend () -> T): T {
        var routed = first
        if (!namesFrom(from, release(routed))) return routed
        for (s in WAITS_S) {
            delay(s * MS_PER_S)
            routed = refetch()
            if (!namesFrom(from, release(routed))) return routed
        }
        throw AltRefusedException(AltRefusedException.Reason.ROUTE_PENDING)
    }
}
