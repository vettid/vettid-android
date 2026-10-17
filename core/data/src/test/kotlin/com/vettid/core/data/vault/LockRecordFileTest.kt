package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A lock the member asked for is kept across a process death whatever the state of the account record. */
class LockRecordFileTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun file() = java.io.File(dir.root, "lock-record")

    @Test
    fun aLockIsKeptAcrossARestart() {
        LockRecordFile(file(), legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 7, relayLockUntilMs = 9))
        // A new process: a new instance reads it from the phone.
        assertEquals(LockRecord(7, 9), LockRecordFile(file(), legacy = { LockRecord() }).load())
    }

    @Test
    fun aLockIsKeptWhileTheAccountRecordCannotBeRead() {
        var disk: LocalAccount? = LocalAccount(userGuid = "unreadable on disk")
        val record = AccountRecord(LocalAccount(), readable = false, persist = { disk = it })
        val lockFile = LockRecordFile(file(), legacy = { LockRecord(record.value.lockAskedAtMs, record.value.relayLockUntilMs) })
        // The member locks: the account record takes no write in this state; the lock's own file does.
        assertFalse(record.update(record.recordGeneration) { it.copy(lockAskedAtMs = 7) })
        lockFile.save(LockRecord(pendingSinceMs = 7))
        assertEquals(LockRecord(pendingSinceMs = 7), LockRecordFile(file(), legacy = { LockRecord() }).load())
        assertEquals(LocalAccount(userGuid = "unreadable on disk"), disk)
    }

    @Test
    fun aLockIsKeptAfterAWipeThatDidNotFinish() {
        val record = AccountRecord(LocalAccount(userGuid = "u"), readable = true, persist = {})
        record.beginWipe()
        record.endWipe(complete = false)
        LockRecordFile(file(), legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 3))
        assertEquals(LockRecord(pendingSinceMs = 3), LockRecordFile(file(), legacy = { LockRecord() }).load())
    }

    @Test
    fun anOlderBuildsRecordIsReadUntilTheFileExists() {
        val f = LockRecordFile(file(), legacy = { LockRecord(pendingSinceMs = 5) })
        assertEquals(LockRecord(pendingSinceMs = 5), f.load())
        f.save(LockRecord())
        assertEquals(LockRecord(), f.load())
    }

    @Test
    fun aFileThatCannotBeUnderstoodSaysALockIsPending() {
        file().writeText("garbage")
        assertEquals(LockRecord(pendingSinceMs = 11), LockRecordFile(file(), legacy = { LockRecord() }, now = { 11 }).load())
    }

    @Test
    fun theFirstSaveMarksTheFileInUseOnce() {
        var migrated = false
        var marks = 0
        val f = LockRecordFile(file(), legacy = { LockRecord(pendingSinceMs = 5) }, migrated = { migrated }, onFirstSave = {
            marks++
            migrated = true
        })
        f.save(LockRecord(pendingSinceMs = 5))
        f.save(LockRecord())
        assertEquals(1, marks)
    }

    @Test
    fun aMissingFileOnceInUseSaysALockIsPending() {
        val f = LockRecordFile(file(), legacy = { LockRecord() }, migrated = { true }, now = { 13 })
        assertEquals(LockRecord(pendingSinceMs = 13), f.load())
    }

    @Test
    fun aMissingFileBeforeItIsInUseReadsTheOlderRecord() {
        val f = LockRecordFile(file(), legacy = { LockRecord(relayLockUntilMs = 4) }, migrated = { false })
        assertEquals(LockRecord(relayLockUntilMs = 4), f.load())
    }
}
