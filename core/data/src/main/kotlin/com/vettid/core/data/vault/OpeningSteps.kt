package com.vettid.core.data.vault

import com.vettid.core.data.feed.FeedManager
import java.time.Instant

/**
 * (ANDROID-PLAN 0.1.134) What carries an opening of the vault across a status read that could not be made (Unreachable), and
 * what ends it: the steps [VaultManager] runs at an opening, at the phase collector's clears and when a passed check's
 * waiting-asks replay cannot run now. Kept apart so that they can be driven with a real feed and phase holder.
 */
internal class OpeningSteps(
    private val feed: FeedManager,
    private val heldAskReplay: HeldAskReplay,
    private val phase: () -> PhaseHolder,
) {
    /**
     * [PhaseHolder]'s `onOpen`: an opening that continues ([continued], and the feed's opening was not ended since) keeps what
     * it holds; any other is a new opening, which holds nothing of the one before (a kept replay is dropped). Returns the new
     * opening's number, for its mark on the vault's clock; null when the opening continued.
     */
    fun onOpen(continued: Boolean, now: Instant): Int? {
        if (continued && feed.openingContinues()) return null
        heldAskReplay.drop()
        return feed.vaultOpened(now)
    }

    /**
     * The phase collector's feed clear for [p]: a soft one for Unreachable, which closes nothing; any other clears all. Neither
     * when an opening was published before the collector reached [p] (a lock and an unlock, or a blip and its return, faster
     * than it): that opening already emptied the feed's list and reset what it keeps for itself ([FeedManager.vaultOpened]),
     * or continued the one the blip kept, and its read is under way.
     */
    fun clearFeed(p: AppPhase) {
        phase().unlessOpen { if (p is AppPhase.Unreachable) feed.clear(keepHeld = true) else feed.clear() }
    }

    /**
     * The release check's clear for [p]: a waiting-asks replay kept for the opening goes with a close, not with Unreachable,
     * and not with a close reached after a newer opening was published (that opening dropped the old one in [onOpen], and
     * what it holds now is its own).
     */
    fun dropReplayOnClose(p: AppPhase) {
        if (p !is AppPhase.Unreachable) phase().unlessOpen { heldAskReplay.drop() }
    }

    /** Where a passed check's waiting-asks replay goes ([placePassReplay]). */
    enum class PassReplay { HELD, LAUNCH, DROP }

    /**
     * A check that passed while a status read fails (another device's): its replay is kept for the opening if it continues,
     * whose owed reads run it; a close or a new opening drops it. Decided on the phase now, under the phase holder's lock:
     * still Unreachable, kept ([PassReplay.HELD]); open again since the caller looked, launched now ([PassReplay.LAUNCH]);
     * closed since, dropped, its window ended by the caller ([PassReplay.DROP]): nothing is read with the vault closed.
     */
    fun placePassReplay(due: HeldAskReplay.Due): PassReplay = phase().decide { p ->
        when {
            p is AppPhase.Unreachable -> PassReplay.HELD.also { heldAskReplay.hold(due) }
            p == AppPhase.Unlocked -> PassReplay.LAUNCH
            else -> PassReplay.DROP
        }
    }
}
