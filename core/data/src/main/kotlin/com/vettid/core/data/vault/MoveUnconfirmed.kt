package com.vettid.core.data.vault

import com.vettid.core.altchan.AltState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * (ANDROID-PLAN 0.1.54 "The way back to release N after leaving or a restart") The stored, non-secret marker "a move is
 * unconfirmed; the previous release was N (PCR0)": written with `update: moved` in the same write that records release
 * N+1 ([AltState]), kept across leaving the update screen and a restart. [failed]: an unlock after the move failed to
 * reach or open the vault on N+1 for a reason that is not the member's ([MoveMarkers.setsFailed]); only then does the
 * unlock screen offer "Return to release N". Not cleared by time (point 5): only by what [MoveMarkers] lists.
 */
data class MoveUnconfirmed(
    val fromNumber: Long,
    val fromPcr0: String,
    val toNumber: Long,
    val toPcr0: String,
    val failed: Boolean,
) {
    /** Release N for the update's screens (only the number and the PCR0 are known here). */
    val from: ReleaseView get() = ReleaseView(fromNumber, fromPcr0, "", null, "")

    /** Release N+1 for the update's screens. */
    val to: ReleaseView get() = ReleaseView(toNumber, toPcr0, "", null, "")

    companion object {
        /** The marker [a] carries; null when no move is unconfirmed. */
        fun of(a: AltState): MoveUnconfirmed? =
            a.movedFrom?.let { MoveUnconfirmed(it.number, it.pcr0, a.releaseNumber, a.release, a.moveFailed) }
    }
}

/** No unconfirmed move ([VaultRepository.moveUnconfirmed]'s default). */
val NO_MOVE_UNCONFIRMED: StateFlow<MoveUnconfirmed?> = MutableStateFlow(null)

/**
 * (ANDROID-PLAN 0.1.54 points 2 and 4) What sets the marker's `failed`, and what clears the marker besides an unlock's
 * own result ([com.vettid.core.altchan.AltRequests.applyUnlock]: an unlock on N+1, `update: abandoned`, `refused`
 * `downgrade` on the way back, and the way back answered `wrong_release`).
 */
object MoveMarkers {
    /** The member API's `503 release_starting` (§11.10.5), past the API client's own waits. */
    private const val CODE_RELEASE_STARTING = "release_starting"

    /**
     * Sealed unlock results that are the member's or this phone's, never the new release failing: a wrong PIN, the
     * backoff, a pending recovery, the signer, this phone's keys or attestation, an unfinished transfer, a rollback of
     * the vault's state.
     */
    private val NOT_THE_RELEASE = setOf(
        "bad_pin", "backoff", "recovery_pending", "signer_refused", "device_secret", "transfer_incomplete", "attestation",
        "state_rollback",
    )

    /**
     * Whether an unlock after the move that failed with [kind] and [code] sets `failed` (point 2): N+1 did not start
     * (`release_starting` past its waits, `410 release_unavailable`), its instance did not verify, the unlock got no
     * answer, an enclave error ([sealed]: a result the enclave sealed to this phone), or the route still names N after
     * the retries (point 6). A wrong PIN, the backoff and a phone without a connection do not, nor does anything that
     * never reached the vault for another reason (a paused service, the clock, a change being decided).
     */
    fun setsFailed(kind: FailureKind, code: String?, sealed: Boolean): Boolean = when {
        sealed -> code !in NOT_THE_RELEASE
        kind == FailureKind.VAULT_UNAVAILABLE -> code == CODE_RELEASE_STARTING
        else -> kind in RELEASE_FAILURES
    }

    private val RELEASE_FAILURES = setOf(FailureKind.RELEASE_ENDED, FailureKind.ENCLAVE, FailureKind.NO_RESPONSE, FailureKind.ROUTE_PENDING)

    /**
     * Whether the `sync.event` kind `vault.release` naming [pcr0] and [number] confirms the move of [m]: it names N+1
     * (the vault sends it once it first runs under a new release, §11.10.6).
     */
    fun confirmedBy(m: MoveUnconfirmed?, pcr0: String?, number: Long?): Boolean =
        m != null && pcr0 == m.toPcr0 && number == m.toNumber
}
