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

    // --- Release L (VAULT-MESSAGING 0.29.0 §10.2 "Locks"): a notice that names the unlock it ends ---

    private val a = "a".repeat(32)
    private val b = "b".repeat(32)

    private fun named(ts: Instant, id: String?) = VaultMessage(
        Inner(id = Ulid.new(), type = "vault.locking", ts = ts, body = (if (id == null) "{}" else "{\"unlock_id\":\"$id\"}").toByteArray()),
    )

    @Test
    fun aNamedNoticeIsJudgedByItsNameWhenThisPhoneHoldsTheCurrentOne() {
        var current: String? = b
        val n = LockNotices(opened = t0) { current }
        assertTrue("another opening's lock, however late it says it is", n.stale(named(t0.plusSeconds(60), a)))
        assertFalse("this opening's lock, even with an earlier ts", n.stale(named(t0.minusSeconds(1), b)))
        // A notice without a name (a release before L) and a name of another form: by the time, as before L.
        assertTrue(n.stale(named(t0, null)))
        assertFalse(n.stale(named(t0.plusMillis(1), null)))
        assertFalse(n.stale(named(t0.plusMillis(1), "")))
        // No current name (a new process before its first status read): by the time.
        current = null
        assertFalse(n.stale(named(t0.plusSeconds(60), a)))
        assertTrue(n.stale(named(t0.minusSeconds(1), b)))
    }

    @Test
    fun onlyANoticeNamingTheBoundUnlockConfirmsABoundLock() {
        val n = LockNotices(opened = t0) { b }
        assertTrue(n.confirms(named(t0.minusSeconds(1), b), bound = b))
        assertFalse("another opening's lock says nothing of this one", n.confirms(named(t0.plusSeconds(60), a), bound = b))
        // An unbound lock, or a notice without a name: from after the last unlock here, as before L.
        assertTrue(n.confirms(named(t0.plusSeconds(1), a), bound = null))
        assertFalse(n.confirms(named(t0, null), bound = b))
        assertTrue(n.confirms(named(t0.plusSeconds(1), null), bound = b))
        assertFalse("not a vault.locking", n.confirms(msg("vault.held", t0.plusSeconds(1)), bound = null))
    }

    private fun named(id: String, ts: Instant) =
        VaultMessage(Inner(id = Ulid.new(), type = "vault.locking", ts = ts, body = """{"unlock_id":"$id"}""".toByteArray()))

    /**
     * Review of the iOS mirror, defect 2(a): held after "opened again", a `vault.locking` naming the dead opening (or
     * none, or while the dead one is not known) does not confirm; only a newer name does.
     */
    @Test
    fun whileHeldOnlyANewerNameConfirms() {
        val dead = "00112233445566778899aabbccddeeff"
        val newer = "ffeeddccbbaa99887766554433221100"
        val n = LockNotices(opened = t0)
        assertFalse(n.confirms(named(dead, t0.plusSeconds(1)), null, held = true, dead = dead))
        assertFalse(n.confirms(msg("vault.locking", t0.plusSeconds(1)), null, held = true, dead = dead))
        val later = named(newer, t0.plusSeconds(1))
        assertFalse("the dead one is not known (a new process)", n.confirms(later, null, held = true, dead = null))
        assertTrue(n.confirms(named(newer, t0.plusSeconds(1)), null, held = true, dead = dead))
        // Not held: the rules of a pending lock, unchanged.
        assertTrue(n.confirms(named(dead, t0.plusSeconds(1)), dead))
    }
}
