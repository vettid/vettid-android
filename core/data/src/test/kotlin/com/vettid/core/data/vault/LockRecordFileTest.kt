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

    @Test
    fun aMissingFileWhileTheAccountRecordCannotBeReadSaysALockIsPending() {
        val f = LockRecordFile(file(), legacy = { LockRecord() }, migrated = { false }, recordUnreadable = { true }, now = { 17 })
        assertEquals(LockRecord(pendingSinceMs = 17), f.load())
    }

    @Test
    fun aFreshInstallHasNoLock() {
        val f = LockRecordFile(file(), legacy = { LockRecord() }, migrated = { false }, recordUnreadable = { false }, now = { 17 })
        assertEquals(LockRecord(), f.load())
    }

    /** (Release L) A bound lock's unlock is kept with it; a record without one keeps the first version's form. */
    @Test
    fun aBoundLocksUnlockIsKeptAcrossARestart() {
        val id = "0123456789abcdef0123456789abcdef"
        LockRecordFile(file(), legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 7, unlockId = id))
        assertEquals("2 7 - $id 0", file().readText().trim())
        assertEquals(LockRecord(7, null, id), LockRecordFile(file(), legacy = { LockRecord() }).load())
        LockRecordFile(file(), legacy = { LockRecord() }).save(LockRecord(relayLockUntilMs = 9))
        assertEquals("an older app reads it", "1 - 9", file().readText().trim())
        assertEquals(LockRecord(null, 9), LockRecordFile(file(), legacy = { LockRecord() }).load())
    }

    /** (Release L, review of #190 D1) "Held locked after the vault was opened again" survives a restart. */
    @Test
    fun theHeldMarkerIsKeptAcrossARestart() {
        LockRecordFile(file(), legacy = { LockRecord() }).save(LockRecord(relayLockUntilMs = 9, held = true))
        assertEquals("2 - 9 - 1", file().readText().trim())
        assertEquals(LockRecord(null, 9, null, held = true), LockRecordFile(file(), legacy = { LockRecord() }).load())
    }

    /** A bound record that is not of the form says a lock is pending (fail secure). */
    @Test
    fun aMalformedBoundRecordSaysALockIsPending() {
        for (text in listOf("2 7 - xyz 0", "2 - - ${"A".repeat(32)} 0", "2 7 - - 2", "2 7 - -", "3 7 - - 0")) {
            file().writeText(text)
            assertEquals(text, LockRecord(pendingSinceMs = 11), LockRecordFile(file(), legacy = { LockRecord() }, now = { 11 }).load())
        }
    }
}
