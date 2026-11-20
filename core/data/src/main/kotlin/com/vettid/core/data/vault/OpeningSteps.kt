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
     * The phase collector's feed clear for [p], which it received in the feed's opening [openingSeen]: a soft one for
     * Unreachable, which closes nothing; any other clears all. Neither when the vault is open now (the blip's opening
     * continued) or a newer opening was published since (a lock and an unlock, or more, faster than the collector, whatever
     * the phase now): that opening already emptied the feed's list and reset what it keeps for itself
     * ([FeedManager.vaultOpened]), or continued the one the blip kept, and its read is under way.
     */
    fun clearFeed(p: AppPhase, openingSeen: Int) = phase().decide { now ->
        when {
            now == AppPhase.Unlocked || newerBlip(now, openingSeen) -> Unit
            // Soft only for a blip that continues an opening; a close now, or a blip after a close (no opening to carry on),
            // clears it all, whatever came between.
            p is AppPhase.Unreachable && keepsOpening(now) -> feed.clear(keepHeld = true)
            else -> feed.clear()
        }
    }

    /**
     * The release check's clear for [p]: a waiting-asks replay kept for the opening goes with a close, not with Unreachable,
     * and not with a close reached once open again or in a newer opening (that opening dropped the old one in [onOpen], and
     * what it holds now is its own).
     */
    fun dropReplayOnClose(p: AppPhase, openingSeen: Int) = phase().decide { now ->
        val blipKept = p is AppPhase.Unreachable && keepsOpening(now)
        if (now != AppPhase.Unlocked && !newerBlip(now, openingSeen) && !blipKept) heldAskReplay.drop()
    }

    /** A blip of an opening newer than [openingSeen]: a late step of the one before leaves it alone. Under the phase lock. */
    private fun newerBlip(now: AppPhase, openingSeen: Int) = now is AppPhase.Unreachable && feed.openingNumber != openingSeen

    /** [now] is a blip that continues an opening (not one right after a close, with no opening to carry on). Under the lock. */
    private fun keepsOpening(now: AppPhase) = now is AppPhase.Unreachable && phase().blipKeepsOpening

    /** Where a passed check's waiting-asks replay goes ([placePassReplay]). */
    enum class PassReplay { HELD, LAUNCH, DROP }

    /**
     * A check that passed while a status read fails (another device's), learnt in the feed's opening [opening]: its replay is
     * kept for that opening if it continues, whose owed reads run it; a close or a new opening drops it. Decided on the phase
     * now, under the phase holder's lock: still Unreachable in that opening, kept ([PassReplay.HELD]); that opening continued
     * since the caller looked, launched now ([PassReplay.LAUNCH]); closed since, or a new opening (never a replay at an
     * unlock), dropped, its window ended by the caller ([PassReplay.DROP]): nothing is read with the vault closed.
     */
    fun placePassReplay(due: HeldAskReplay.Due, opening: Int): PassReplay = phase().decide { p ->
        val same = feed.openingNumber == opening
        when {
            same && p is AppPhase.Unreachable && phase().blipKeepsOpening -> PassReplay.HELD.also { heldAskReplay.hold(due) }
            same && p == AppPhase.Unlocked -> PassReplay.LAUNCH
            else -> PassReplay.DROP
        }
    }
}
