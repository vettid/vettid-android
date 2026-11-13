package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * (Show held as held, Android) The outbox is flushed once a mailbox's quota backoff ends, with the stop count of the moment
 * the backoff was set, not once the session ended. What goes is the device's lock gate's to decide (ANDROID-PLAN §4 "While
 * pending"): while a lock is pending a `call.end` goes and nothing else does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotaFlushTest {
    private var stops = 0L
    private var live = true
    private val flushed = mutableListOf<Long>()

    private fun kotlinx.coroutines.test.TestScope.flush() = QuotaFlush(backgroundScope, { live }, { seq -> flushed += seq })

    @Test
    fun theOutboxIsFlushedWhenTheBackoffEnds() = runTest {
        val f = flush()
        f.backoffSet(60_000, stops)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("not before the backoff has ended", 0, flushed.size)
        advanceTimeBy(QuotaFlush.MARGIN_MS + 1)
        runCurrent()
        assertEquals(1, flushed.size)
    }

    /** The flush is asked for under the stop count announced with the backoff, not one read later. */
    @Test
    fun theFlushTakesTheStopCountOfTheAnnouncement() = runTest {
        val f = flush()
        f.backoffSet(60_000, 0)
        stops = 1 // stopped while the backoff ran
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(listOf(0L), flushed)
    }

    @Test
    fun notOnceTheSessionEndedOrTheDeviceWasForgotten() = runTest {
        val f = flush()
        live = false
        f.backoffSet(60_000, stops)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(0, flushed.size)
    }

    @Test
    fun aLaterBackoffReplacesTheWait() = runTest {
        val f = flush()
        f.backoffSet(60_000, stops)
        advanceTimeBy(30_000)
        f.backoffSet(120_000, stops) // doubled: the first wait no longer counts
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(0, flushed.size)
        advanceTimeBy(81_000)
        runCurrent()
        assertEquals(1, flushed.size)
    }

    private class Store : LockRecordStore {
        var record = LockRecord()

        override fun load(): LockRecord = record

        override fun save(record: LockRecord) {
            this.record = record
        }
    }

    private class Ports : LockPorts {
        override val relayValidFor: Duration = Duration.ofSeconds(30)

        override suspend fun relayLock(unlockId: String?): RelayLock = RelayLock.NO_ANSWER

        override suspend fun memberLock(unlockId: String?): MemberLock = MemberLock.NoAnswer

        override suspend fun unlocked(): Boolean? = null
    }

    /**
     * As the manager wires it: a lock pending, a `call.end` waiting for the mailbox's quota behind a held entry. Once the backoff
     * ends the flush runs with no lock check of its own, and the device's gate (the manager's [LockGuard.sendGate]) lets the
     * `call.end` out and nothing else.
     */
    @Test
    fun withALockPendingTheCallEndGoesOnceTheBackoffEndsWithAFakeOutbox() = runTest {
        val guard = LockGuard(Ports(), { Instant.parse("2026-11-12T10:00:00Z") }) { attempt ->
            LockWiring.keeper(backgroundScope, Store(), attempt, {}) {}
        }
        guard.keeper.begin(null)
        val gate = guard.sendGate()
        val outbox = mutableListOf("relay.token.issued", "call.end", "device.features")
        val sent = mutableListOf<String>()
        val f = QuotaFlush(backgroundScope, { true }) { _ ->
            val going = outbox.filter { gate.admits(it) }
            sent += going
            outbox -= going.toSet()
        }
        f.backoffSet(60_000, 0)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(listOf("call.end"), sent)
        assertEquals(listOf("relay.token.issued", "device.features"), outbox)
    }

    /**
     * With a fake device whose flush deposits only under its current stop count, interleaved step by step: an announcement
     * taken before a stop but handled after it (the observer runs late) deposits nothing; the start's announcement of the rest,
     * under the new count, makes the flush go when the backoff ends; a stop with no start leaves nothing to go.
     */
    @Test
    fun aRestartDuringTheBackoffStillFlushesWithAFakeOutbox() = runTest {
        val deposits = mutableListOf<Long>()
        val f = QuotaFlush(backgroundScope, { live }) { seq -> if (seq == stops) deposits += seq }
        val announced = 0L // the refusal's announcement, at count 0
        stops++ // stop(), before the observer handles it
        f.backoffSet(60_000, announced)
        advanceTimeBy(20_000)
        f.backoffSet(40_000, stops) // start(): the rest, under its own count
        advanceTimeBy(40_000 + QuotaFlush.MARGIN_MS + 1)
        runCurrent()
        assertEquals("it goes once the backoff ends", listOf(1L), deposits)
        f.backoffSet(60_000, stops)
        stops++ // stop(), no start
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals("nothing more", listOf(1L), deposits)
        // Without the start's announcement, the late-handled one alone deposits nothing.
        val alone = mutableListOf<Long>()
        val g = QuotaFlush(backgroundScope, { live }) { seq -> if (seq == stops) alone += seq }
        g.backoffSet(60_000, stops - 1)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(emptyList<Long>(), alone)
    }
}
