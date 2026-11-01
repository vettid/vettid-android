package com.vettid.core.data.vault

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [UnlockNoticeHold] (#244 review B3): an unlock of a running vault locks it first and says so (§11.4). That notice,
 * relayed before the unlock's result, is judged after it: stale then, it ends nothing. A later notice still locks.
 */
class UnlockNoticeHoldTest {
    private val before = Instant.parse("2026-11-01T08:00:00Z")
    private val unlockAt = Instant.parse("2026-11-01T09:00:00Z")

    private fun msg(type: String, ts: Instant) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = ts, body = "{}".toByteArray()))

    /** As VaultManager: held during the unlock, then taken (the stale check, then the lock). */
    private class Manager(opened: Instant) {
        val notices = LockNotices(opened = opened)
        val hold = UnlockNoticeHold()
        var locks = 0

        fun take(m: VaultMessage) {
            if (!notices.stale(m)) locks++
        }

        fun receive(m: VaultMessage) {
            if (!hold.hold(m) { take(m) }) take(m)
        }

        fun end(t: UnlockNoticeHold.Ticket) = runBlocking { t.end().forEach { it() } }
    }

    @Test
    fun theUnlocksOwnNoticeBeforeItsResultEndsNothing() {
        val m = Manager(opened = before)
        val t = m.hold.begin()
        // The vault locks before it opens (§11.4): the notice is after the previous opening, so not stale yet.
        m.receive(msg(LockNotices.TYPE, unlockAt.minusSeconds(1)))
        assertEquals(0, m.locks)
        // The result: the vault open as of its ts; the notice judged now is from before it.
        m.notices.opened(unlockAt)
        m.end(t)
        assertEquals(0, m.locks)
    }

    @Test
    fun withoutTheHoldTheUnlocksOwnNoticeWouldLock() {
        val m = Manager(opened = before)
        m.receive(msg(LockNotices.TYPE, unlockAt.minusSeconds(1)))
        assertEquals(1, m.locks)
    }

    @Test
    fun aLaterNoticeStillLocks() {
        val m = Manager(opened = before)
        val t = m.hold.begin()
        m.receive(msg(LockNotices.TYPE, unlockAt.plusSeconds(1)))
        m.notices.opened(unlockAt)
        m.end(t)
        assertEquals(1, m.locks)
    }

    @Test
    fun aFailedUnlockTakesWhatItHeld() {
        val m = Manager(opened = before)
        val t = m.hold.begin()
        m.receive(msg(LockNotices.TYPE, unlockAt))
        // No result: the opening time stays the previous one, and the notice is judged against it.
        m.end(t)
        assertEquals(1, m.locks)
    }

    @Test
    fun otherEventsAndNoUnlockAreNotHeld() {
        val h = UnlockNoticeHold()
        assertFalse(h.hold(msg(LockNotices.TYPE, unlockAt)) {})
        h.begin()
        assertFalse(h.hold(msg("vault.held", unlockAt)) {})
        assertTrue(h.hold(msg(LockNotices.TYPE, unlockAt)) {})
    }

    @Test
    fun heldUntilTheLastUnlockEndsAndTakenOnce() {
        val h = UnlockNoticeHold()
        val a = h.begin()
        val b = h.begin()
        assertTrue(h.hold(msg(LockNotices.TYPE, unlockAt)) {})
        assertEquals(0, a.end().size)
        assertEquals(0, a.end().size)
        assertEquals(1, b.end().size)
        assertEquals(0, b.end().size)
        // Over: not held any more.
        assertFalse(h.hold(msg(LockNotices.TYPE, unlockAt)) {})
    }
}
