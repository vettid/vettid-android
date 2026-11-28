package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * (ANDROID-PLAN §4, #19721 b′) A vault that went away without `vault.locking` (its process crashed or was killed) leaves the
 * app showing it open while every request ends unanswered. A request that got no answer although its deposit went out
 * ([com.vettid.core.vault.VaultDevice.onNoAnswer]) starts a probe ([probe]: the owner check's `vault.status`, then the member
 * API's state, which takes only `locked`, whose lock it applies as `vault.locking` would; a result read across a lock, an
 * unlock or a wipe is dropped there). The probe changes nothing, so one unanswered request is enough; at most one runs at a
 * time, at least [PROBE_EVERY_MS] apart, and none while the vault is not open on this phone or the app is not in front. The
 * probe's own requests are not counted.
 */
internal class VaultGoneWatch(
    private val scope: CoroutineScope,
    /** Whether the vault is open on this phone and the app is in front. */
    private val watching: () -> Boolean,
    /** The probe; true when it found the vault locked (and applied it). */
    private val probe: suspend () -> Boolean,
    /** Monotonic milliseconds. */
    private val elapsedMs: () -> Long,
    /** Request types whose lack of an answer starts no probe. */
    private val ignored: Set<String> = emptySet(),
) {
    private val probing = AtomicBoolean(false)
    private val lastProbeMs = AtomicLong(Long.MIN_VALUE)

    /** A request of [type] got no answer. */
    fun onNoAnswer(type: String) {
        if (type in ignored || !watching() || !claim()) return
        scope.launch {
            try {
                if (watching()) probe()
            } finally {
                probing.set(false)
            }
        }
    }

    /** Takes the probe's slot: none running and the last at least [PROBE_EVERY_MS] ago, checked and taken at once. */
    @Synchronized
    private fun claim(): Boolean {
        val now = elapsedMs()
        val last = lastProbeMs.get()
        val due = last == Long.MIN_VALUE || now - last >= PROBE_EVERY_MS
        // One at a time; the probe's own unanswered status read lands here.
        val claimed = due && probing.compareAndSet(false, true)
        if (claimed) lastProbeMs.set(now)
        return claimed
    }

    companion object {
        /** The least time between two probes. */
        const val PROBE_EVERY_MS = 60_000L
    }
}
