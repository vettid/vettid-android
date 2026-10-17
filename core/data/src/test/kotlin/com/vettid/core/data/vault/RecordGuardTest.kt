package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account record against a wipe on another thread: the steps of the race are taken one by one, in the order that
 * would otherwise bring the old record back or put an old write into the fresh one.
 */
class RecordGuardTest {
    private data class Record(val vault: String = "", val rotateOffered: Boolean = false)

    /** The account record as the vault manager keeps it: in memory, and on the phone (null: erased). */
    private class Fixture {
        val lock = Any()
        val guard = RecordGuard(lock)
        var local = Record(vault = "old")
        var disk: Record? = local

        /** As saveLocal: under the lock, nothing while a wipe runs. */
        fun save(r: Record) = synchronized(lock) {
            if (guard.wiping) return@synchronized
            local = r
            disk = r
        }

        fun erase() {
            disk = null
        }
    }

    @Test
    fun aWriteBetweenTheEraseAndTheFreshRecordIsDropped() {
        val f = Fixture()
        val old = f.local
        f.guard.beginWipe()
        f.erase()
        // An update of the old record lands now, before the fresh record is in memory.
        f.save(old.copy(rotateOffered = true))
        assertNull(f.guard.write { f.save(old) })
        assertNull(f.disk)
        f.guard.endWipe { f.local = Record() }
        assertNull(f.disk)
        assertEquals(Record(), f.local)
    }

    @Test
    fun aWriteForTheRecordBeforeAWipeIsDroppedAfterIt() {
        val f = Fixture()
        val since = f.guard.generation
        f.guard.beginWipe()
        f.erase()
        f.guard.endWipe { f.local = Record() }
        // The check passed before the wipe; the write comes after it.
        assertNull(f.guard.write(since) { f.save(f.local.copy(rotateOffered = true)) })
        assertNull(f.disk)
        assertFalse(f.local.rotateOffered)
    }

    @Test
    fun theFreshRecordIsWrittenAfterTheWipe() {
        val f = Fixture()
        val since = f.guard.generation
        assertTrue(f.guard.write(since) { f.save(f.local.copy(rotateOffered = true)) } != null)
        assertEquals(Record(vault = "old", rotateOffered = true), f.disk)
        f.guard.beginWipe()
        f.erase()
        f.guard.endWipe { f.local = Record() }
        // A setup code redeemed (or a recovery claimed) after the wipe: a record of its own, written.
        assertTrue(f.guard.write { f.save(Record(vault = "new")) } != null)
        assertEquals(Record(vault = "new"), f.disk)
        assertTrue(f.guard.write(f.guard.generation) { f.save(f.local.copy(rotateOffered = true)) } != null)
        assertEquals(Record(vault = "new", rotateOffered = true), f.disk)
    }
}
