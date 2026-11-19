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
     * The phase collector's feed clear for [p]: a soft one for Unreachable, which closes nothing. Any other clears all, unless
     * a newer opening was published before the collector reached [p] (a lock and an unlock faster than it): that opening
     * already reset what the feed keeps for it and its read is under way, and the close's own paths cleared the feed when it
     * happened (a lock, a wipe, a sign-out).
     */
    fun clearFeed(p: AppPhase) {
        if (p is AppPhase.Unreachable) feed.clear(keepHeld = true) else phase().unlessOpen { feed.clear() }
    }

    /**
     * The release check's clear for [p]: a waiting-asks replay kept for the opening goes with a close, not with Unreachable,
     * and not with a close reached after a newer opening was published (that opening dropped the old one in [onOpen], and
     * what it holds now is its own).
     */
    fun dropReplayOnClose(p: AppPhase) {
        if (p !is AppPhase.Unreachable) phase().unlessOpen { heldAskReplay.drop() }
    }

    /**
     * A check that passed while a status read fails (another device's): its replay is kept for the opening if it continues,
     * whose owed reads run it; a close or a new opening drops it.
     */
    fun holdForTheContinuedOpening(due: HeldAskReplay.Due) = heldAskReplay.hold(due)
}
