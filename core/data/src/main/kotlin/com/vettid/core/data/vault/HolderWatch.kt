package com.vettid.core.data.vault

import com.vettid.core.altchan.UnlockResult
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What may tell the app that this phone no longer holds the vault, and which
 * of it is proof (owner decision, 2026-10-05: a replaced phone erases itself).
 * A wipe destroys the only local copy of the credential, so only messages the
 * app has authenticated count.
 */
sealed interface HolderSignal {
    /** A vault message the device opened under its session with the vault (§5, §10.3), from the vault's relay key. */
    data class VaultEvent(val type: String, val reason: String?) : HolderSignal

    /**
     * The enclave's `vault.unlock.result` (§11.4), opened with this device's KEM key: sealed to it and
     * answering this request's id, which travelled only inside the request sealed to the attested enclave.
     */
    data class SealedUnlockResult(val ok: Boolean, val code: String?) : HolderSignal

    /**
     * Everything else: network errors, timeouts, HTTP statuses of the member API or the relay (relay token
     * errors included), unreadable or unparseable results, vault error responses. Never proof.
     */
    data class Failure(val kind: FailureKind, val code: String?) : HolderSignal
}

/** Which [HolderSignal]s prove that this phone was replaced. */
object HolderPolicy {
    const val DEVICE_UNLINKED = "device.unlinked"
    const val REASON_TRANSFERRED = "transferred"
    const val REASON_REPLACED = "replaced"
    const val CODE_UNKNOWN_DEVICE = "unknown_device"

    /**
     * - `device.unlinked{reason: "transferred"}`: the vault completed a direct transfer (§6.7.1 step 4). The
     *   vault removes the old app only in the flush that makes the new app, whose handshake is complete, the
     *   holder; vettid-vault sends it after the approval's `{}` (`afterRespond`), so the `{}` alone is not proof.
     * - `device.unlinked{reason: "replaced"}`: a recovery completed on another phone (§11.11.5 step 3): the
     *   new app, registered, unlocked and paired, recovered or reset the credential and replaced this one.
     * - a sealed unlock result `unknown_device` (§11.4). (A spec-conforming enclave answers an unknown device
     *   with random bytes instead, which the app cannot read: that is a [HolderSignal.Failure].)
     *
     * Other `device.unlinked` reasons (`vault_deleted`, none) are not a replacement.
     */
    fun proves(s: HolderSignal): Boolean = when (s) {
        is HolderSignal.VaultEvent -> s.type == DEVICE_UNLINKED && (s.reason == REASON_TRANSFERRED || s.reason == REASON_REPLACED)
        is HolderSignal.SealedUnlockResult -> !s.ok && s.code == CODE_UNKNOWN_DEVICE
        is HolderSignal.Failure -> false
    }

    fun of(m: VaultMessage): HolderSignal.VaultEvent = HolderSignal.VaultEvent(m.type, VaultJson.str(m.body, "reason"))

    fun of(r: UnlockResult): HolderSignal.SealedUnlockResult = HolderSignal.SealedUnlockResult(r.ok, r.code)

    fun of(e: VaultFailure): HolderSignal.Failure = HolderSignal.Failure(e.kind, e.code)
}

/**
 * Runs [wipe] once per proof ([HolderPolicy]) and lets a direct transfer's
 * approval wait for the vault's confirmation. The VaultManager feeds it every
 * vault event, every sealed unlock result and every failure.
 */
class HolderWatch(private val scope: CoroutineScope, private val wipe: suspend () -> Unit) {
    private val lock = Mutex()
    private val wipes = MutableStateFlow(0)

    /** How many wipes this process ran. */
    val count: Int get() = wipes.value

    /**
     * A vault event (§10.3). The wipe runs in its own coroutine: the event observer that delivers this is
     * cancelled by the wipe. Returns whether the event proves a replacement.
     */
    fun onVaultEvent(m: VaultMessage): Boolean {
        val proof = HolderPolicy.proves(HolderPolicy.of(m))
        if (proof) {
            val seen = wipes.value
            scope.launch { wipeOnce(seen) }
        }
        return proof
    }

    /** A sealed unlock result of this phone (§11.4); wipes before returning when it proves a replacement. */
    suspend fun onSealedUnlockResult(r: UnlockResult): Boolean {
        val proof = HolderPolicy.proves(HolderPolicy.of(r))
        if (proof) wipeNow()
        return proof
    }

    /** A failure: never proof (documented here so that every path goes through the policy). */
    fun onFailure(e: VaultFailure): Boolean = HolderPolicy.proves(HolderPolicy.of(e))

    /**
     * The old phone's approval of a direct transfer (§6.7.1 step 3). [approve] is the `device.transfer.approve`
     * request; its `{}` does not prove completion, so this then waits up to [waitMs] for the vault's
     * `device.unlinked{transferred}` (which wipes). True when the wipe happened. A phone that misses it wipes
     * when the notice arrives later (the mailbox keeps it, relay TTL).
     */
    suspend fun approveTransfer(waitMs: Long, approve: suspend () -> Unit): Boolean {
        val before = wipes.value
        approve()
        return withTimeoutOrNull(waitMs) { wipes.first { it > before } } != null
    }

    /** Wipes now (or waits for the wipe that already runs). */
    suspend fun wipeNow() = wipeOnce(wipes.value)

    /** One wipe per proof received before it: a proof that arrived while a wipe ran waits for it and does nothing more. */
    private suspend fun wipeOnce(seen: Int) {
        lock.withLock {
            if (wipes.value != seen) return
            wipe()
            wipes.value = seen + 1
        }
    }
}
