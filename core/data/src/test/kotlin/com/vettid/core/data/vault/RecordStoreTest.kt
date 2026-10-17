package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account record against a wipe or a reset on another thread: the steps of each race are taken one by one, in the
 * order that would otherwise bring the old record back or put an old write into the fresh one.
 */
class RecordStoreTest {
    private data class Record(val vault: String = "", val lastOpened: Long = 0, val rotateOffered: Boolean = false)

    /** The record in memory ([RecordStore.value]) and on the phone ([disk], null: erased), as the vault manager's. */
    private class Fixture {
        var disk: Record? = Record(vault = "old")
        val store = RecordStore(disk!!, empty = { Record() }, persist = { disk = it })

        fun erase() {
            disk = null
        }

        /** "Finish the move"'s offer, as the vault manager writes it. */
        fun offer(gen: Int) = store.update(generation = gen) { it.copy(rotateOffered = true) }

        /** The unlock's last-opened time, for the record the unlock started with. */
        fun opened(since: Int, at: Long) = store.update(since) { it.copy(lastOpened = maxOf(it.lastOpened, at)) }
    }

    @Test
    fun aWriteBetweenTheEraseAndTheFreshRecordIsDropped() {
        val f = Fixture()
        f.store.beginWipe()
        f.erase()
        // An update lands now, before the fresh record is in memory: it would write the old record back.
        assertFalse(f.store.update { it.copy(rotateOffered = true) })
        assertFalse(f.store.reset { Record(vault = "new") })
        assertNull(f.disk)
        f.store.endWipe()
        assertNull(f.disk)
        assertEquals(Record(), f.store.value)
    }

    @Test
    fun anOfferReadDuringAWipeIsDroppedAfterIt() {
        val f = Fixture()
        f.store.beginWipe()
        // The runner's lock and its check came after the wipe began; the offer comes after the wipe ended.
        val gen = f.store.generation
        f.erase()
        f.store.endWipe()
        assertFalse(f.offer(gen))
        assertNull(f.disk)
        assertFalse(f.store.value.rotateOffered)
    }

    @Test
    fun anUnlockStartedDuringAWipeWritesNothingAfterIt() {
        val f = Fixture()
        f.store.beginWipe()
        val since = f.store.recordGeneration
        f.erase()
        f.store.endWipe()
        // The unlock answers after the wipe: its last-opened time is for the erased record.
        assertFalse(f.opened(since, at = 42))
        assertNull(f.disk)
        assertEquals(Record(), f.store.value)
    }

    @Test
    fun aWriteForTheRecordBeforeAWipeIsDroppedAfterIt() {
        val f = Fixture()
        val since = f.store.recordGeneration
        f.store.beginWipe()
        f.erase()
        f.store.endWipe()
        assertFalse(f.opened(since, at = 42))
        assertNull(f.disk)
    }

    @Test
    fun aWriteForTheRecordBeforeAResetIsDroppedAfterIt() {
        val f = Fixture()
        val since = f.store.recordGeneration
        // Forgotten (a setup code, a missing or deleted vault, a recovery): a record of its own.
        assertTrue(f.store.reset { Record() })
        assertFalse(f.opened(since, at = 42))
        assertEquals(Record(), f.disk)
    }

    @Test
    fun anOfferAfterALockIsDropped() {
        val f = Fixture()
        val gen = f.store.generation
        f.store.bumpGeneration()
        assertFalse(f.offer(gen))
        assertEquals(Record(vault = "old"), f.disk)
    }

    @Test
    fun theRecordIsWrittenWhenNothingChanged() {
        val f = Fixture()
        assertTrue(f.offer(f.store.generation))
        assertTrue(f.opened(f.store.recordGeneration, at = 42))
        assertEquals(Record(vault = "old", lastOpened = 42, rotateOffered = true), f.disk)
        // Nothing to write: the record stays as it is.
        assertFalse(f.store.update { null })
        assertEquals(Record(vault = "old", lastOpened = 42, rotateOffered = true), f.disk)
    }

    @Test
    fun theFreshRecordIsWrittenAfterTheWipe() {
        val f = Fixture()
        f.store.beginWipe()
        f.erase()
        f.store.endWipe()
        // A setup code redeemed (or a recovery claimed) after the wipe: a record of its own, written.
        assertTrue(f.store.reset { Record(vault = "new") })
        assertEquals(Record(vault = "new"), f.disk)
        assertTrue(f.opened(f.store.recordGeneration, at = 7))
        assertTrue(f.offer(f.store.generation))
        assertEquals(Record(vault = "new", lastOpened = 7, rotateOffered = true), f.disk)
    }
}
