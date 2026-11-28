package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * (Step 4, owner decision a, #19767) [silent]: [SILENT_AFTER] probes in a row found neither the vault answering nor it
 * locked, while the member API answered (a vault the service still counts as running that does not answer); a probe that
 * could ask nobody, or that ended with the app no longer in front, counts for nothing. Anything heard from the vault ([heard]), a
 * probe that reached it or found it locked, and each opening ([reset]) start the count again; a probe that began before
 * one of those counts for nothing. Whether the notice shows (the vault open, no call) is the caller's.
 */
internal class VaultGoneWatch(
    private val scope: CoroutineScope,
    /** Whether the vault is open on this phone and the app is in front. */
    private val watching: () -> Boolean,
    /**
     * The probe: what it found ([VaultProbe.LOCKED] applied there), or null when it could ask nobody (the member API's read
     * failed too, or a lock found could not be applied): that counts for nothing and resets nothing.
     */
    private val probe: suspend () -> VaultProbe?,
    /** Monotonic milliseconds. */
    private val elapsedMs: () -> Long,
    /** Request types whose lack of an answer starts no probe. */
    private val ignored: Set<String> = emptySet(),
) {
    private val probing = AtomicBoolean(false)
    private val lastProbeMs = AtomicLong(Long.MIN_VALUE)

    /** Moved by [heard] and [reset]: a probe's result counts only under the mark it began with. */
    private var mark = 0L

    @Volatile
    private var unanswered = 0
    private val silentFlow = MutableStateFlow(false)

    /** [SILENT_AFTER] probes in a row went unanswered, and nothing was heard from the vault since. */
    val silent: StateFlow<Boolean> = silentFlow.asStateFlow()

    /** A request of [type] got no answer. */
    fun onNoAnswer(type: String) {
        if (type in ignored || !watching()) return
        // The mark with the slot: a message from here on voids this probe's result.
        val at = claim() ?: return
        scope.launch {
            try {
                if (watching()) {
                    val found = probe()
                    // A probe that ended with the app no longer in front (or the vault no longer open) says nothing new.
                    if (found != null && (found != VaultProbe.NO_ANSWER || watching())) counted(at, found)
                }
            } finally {
                probing.set(false)
            }
        }
    }

    /**
     * A message from the vault arrived: it is there. Called for every message, so it does nothing while nothing is counted
     * and no probe runs (one running then counts for nothing).
     */
    fun heard() {
        // [probing] first: a probe's count is written before its slot is let go, so one that ended meanwhile is seen.
        if (!probing.get() && unanswered == 0 && !silentFlow.value) return
        reset()
    }

    /** Starts the count again (an opening, a message from the vault). */
    @Synchronized
    fun reset() {
        mark++
        unanswered = 0
        silentFlow.value = false
    }

    @Synchronized
    private fun counted(at: Long, found: VaultProbe) {
        if (at != mark) return // heard from, or opened again, while it ran
        if (found == VaultProbe.NO_ANSWER) {
            unanswered++
            if (unanswered >= SILENT_AFTER) silentFlow.value = true
        } else {
            mark++
            unanswered = 0
            silentFlow.value = false
        }
    }

    /**
     * Takes the probe's slot: none running and the last at least [PROBE_EVERY_MS] ago, checked and taken at once; the
     * mark it is taken under, or null.
     */
    @Synchronized
    private fun claim(): Long? {
        val now = elapsedMs()
        val last = lastProbeMs.get()
        val due = last == Long.MIN_VALUE || now - last >= PROBE_EVERY_MS
        // One at a time; the probe's own unanswered status read lands here.
        val claimed = due && probing.compareAndSet(false, true)
        if (claimed) lastProbeMs.set(now)
        return if (claimed) mark else null
    }

    companion object {
        /** The least time between two probes. */
        const val PROBE_EVERY_MS = 60_000L

        /** (Owner decision a, #19767) Unanswered probes in a row before "Your vault isn't answering". */
        const val SILENT_AFTER = 2
    }
}
