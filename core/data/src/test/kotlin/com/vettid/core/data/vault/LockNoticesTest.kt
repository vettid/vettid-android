package com.vettid.core.data.vault

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Staging S8 canary (2026-10-10): after a release update, and after an unlock of a vault that was already open, the
 * app showed "Unlock your vault" although the vault was open, because the `vault.locking` of the lock that came
 * before the unlock arrived after it.
 */
class LockNoticesTest {
    private val t0 = Instant.parse("2026-10-10T14:09:35.120Z")
    private val notices = LockNotices()

    private fun msg(type: String, ts: Instant) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = "{}".toByteArray()))

    @Test
    fun everyNoticeCountsBeforeTheFirstUnlock() {
        assertFalse(notices.stale(msg("vault.locking", t0)))
    }

    @Test
    fun aNoticeFromBeforeTheUnlockIsStale() {
        // The update's first step locked release N at 14:09:30; release N+1 opened the vault at 14:09:35.
        notices.opened(t0)
        assertTrue(notices.stale(msg("vault.locking", t0.minusSeconds(5))))
        // The lock an unlock of a running vault makes comes just before its result, or in the same millisecond.
        assertTrue(notices.stale(msg("vault.locking", t0.minusMillis(680))))
        assertTrue(notices.stale(msg("vault.locking", t0)))
    }

    @Test
    fun aLaterLockStillLocks() {
        notices.opened(t0)
        assertFalse(notices.stale(msg("vault.locking", t0.plusMillis(1))))
        assertFalse(notices.stale(msg("vault.locking", t0.plusSeconds(54))))
    }

    /**
     * Review L9 of #127: the last unlock's time is kept on the phone, so a new process still knows it. A notice the
     * vault sent before that unlock and that was never collected is stale after the restart too, and is no evidence
     * for a lock asked for since; one from after it is.
     */
    @Test
    fun aNewProcessKnowsTheLastUnlockAndOnlyALaterNoticeIsEvidenceForALock() {
        val restarted = LockNotices(opened = t0)
        val old = msg("vault.locking", t0.minusSeconds(3600))
        assertTrue(restarted.stale(old))
        assertFalse(restarted.sinceLastOpened(old))
        val later = msg("vault.locking", t0.plusSeconds(1))
        assertFalse(restarted.stale(later))
        assertTrue(restarted.sinceLastOpened(later))
        assertFalse("only the vault's lock notice", restarted.sinceLastOpened(msg("feed.event", t0.plusSeconds(1))))
        // Without a known unlock time a notice cannot be placed: it confirms nothing (the status read does).
        assertFalse(notices.sinceLastOpened(later))
    }

    @Test
    fun onlyLockNoticesAreFiltered() {
        notices.opened(t0)
        assertFalse(notices.stale(msg("vault.held", t0.minusSeconds(5))))
        assertFalse(notices.stale(msg("sync.event", t0.minusSeconds(5))))
    }

    @Test
    fun theLatestUnlockCounts() {
        notices.opened(t0)
        notices.opened(null) // a result without a ts changes nothing
        notices.opened(t0.minusSeconds(60)) // never back
        assertTrue(notices.stale(msg("vault.locking", t0.minusSeconds(1))))
        notices.opened(t0.plusSeconds(60))
        assertTrue(notices.stale(msg("vault.locking", t0.plusSeconds(30))))
        assertFalse(notices.stale(msg("vault.locking", t0.plusSeconds(61))))
    }
}
