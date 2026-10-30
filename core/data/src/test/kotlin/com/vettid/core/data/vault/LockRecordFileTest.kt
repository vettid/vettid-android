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
        // Held, and since confirmed locked (second recheck of #190, D3).
        LockRecordFile(file(), legacy = { LockRecord() }).save(LockRecord(held = true, heldConfirmed = true))
        assertEquals("2 - - - 2", file().readText().trim())
        assertEquals(LockRecord(held = true, heldConfirmed = true), LockRecordFile(file(), legacy = { LockRecord() }).load())
    }

    /** A bound record that is not of the form says a lock is pending (fail secure). */
    @Test
    fun aMalformedBoundRecordSaysALockIsPending() {
        for (text in listOf("2 7 - xyz 0", "2 - - ${"A".repeat(32)} 0", "2 7 - - 3", "2 7 - -", "3 7 - - 0")) {
            file().writeText(text)
            assertEquals(text, LockRecord(pendingSinceMs = 11), LockRecordFile(file(), legacy = { LockRecord() }, now = { 11 }).load())
        }
    }

    // --- sealed under the Keystore file key (2026-10-27 audit) ---

    /** A stand-in for the Keystore seal: the format byte, then the bytes reversed (not plain text, not the plain form). */
    private class FakeSealer(var failToOpen: Boolean = false) : LockRecordFile.Sealer {
        override fun seal(plain: ByteArray) = byteArrayOf(0x01) + plain.reversedArray()

        override fun open(blob: ByteArray): ByteArray {
            if (failToOpen) throw com.vettid.core.keystore.KeystoreException("cannot decrypt")
            return blob.copyOfRange(1, blob.size).reversedArray()
        }
    }

    @Test
    fun aSealedRecordIsNotPlainTextOnDiskAndReadsBack() {
        val f = LockRecordFile(file(), FakeSealer(), legacy = { LockRecord() })
        f.save(LockRecord(pendingSinceMs = 7, relayLockUntilMs = 9))
        assertFalse("not the plain text", file().readText().contains("7 9"))
        assertEquals(0x01.toByte(), file().readBytes()[0])
        assertEquals(LockRecord(7, 9), LockRecordFile(file(), FakeSealer(), legacy = { LockRecord() }).load())
    }

    @Test
    fun aPlainRecordOfAnEarlierBuildIsReadOnceAndSealedAgain() {
        file().writeText("1 7 9\n")
        val f = LockRecordFile(file(), FakeSealer(), legacy = { LockRecord() })
        assertEquals(LockRecord(7, 9), f.load())
        assertEquals("sealed by the read", 0x01.toByte(), file().readBytes()[0])
        assertEquals(LockRecord(7, 9), LockRecordFile(file(), FakeSealer(), legacy = { LockRecord() }).load())
    }

    /** The Keystore cannot open it: a lock is pending, as for a record not understood (fail secure). */
    @Test
    fun aSealedRecordTheKeystoreCannotOpenSaysALockIsPending() {
        LockRecordFile(file(), FakeSealer(), legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 7))
        assertEquals(
            LockRecord(pendingSinceMs = 21),
            LockRecordFile(file(), FakeSealer(failToOpen = true), legacy = { LockRecord() }, now = { 21 }).load(),
        )
    }

    @Test
    fun aSealerThatCannotSealRefusesTheSaveWithAnIoError() {
        val broken = object : LockRecordFile.Sealer {
            override fun seal(plain: ByteArray): ByteArray = throw com.vettid.core.keystore.KeystoreException("no key")

            override fun open(blob: ByteArray) = blob
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            LockRecordFile(file(), broken, legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 7))
        }
    }

    /** A RuntimeException from the Keystore (a ProviderException, say) is a pending lock at load and an IOException at save. */
    @Test
    fun aRuntimeFailureOfTheKeystoreIsAPendingLockNotACrash() {
        val ok = FakeSealer()
        LockRecordFile(file(), ok, legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 7))
        val boom = object : LockRecordFile.Sealer {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.ProviderException("no alias")

            override fun open(blob: ByteArray): ByteArray = throw java.security.ProviderException("no alias")
        }
        assertEquals(LockRecord(pendingSinceMs = 5), LockRecordFile(file(), boom, legacy = { LockRecord() }, now = { 5 }).load())
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            LockRecordFile(file(), boom, legacy = { LockRecord() }).save(LockRecord(pendingSinceMs = 7))
        }
    }

    /** Review of #204 (3): a read-modify-write must not read the fail-secure placeholder as the record. */
    @Test
    fun aStrictLoadThrowsWhereThePlainLoadSaysALockIsPending() {
        file().writeText("garbage that is not a record")
        val f = LockRecordFile(file(), legacy = { LockRecord() }, now = { 5 })
        assertEquals(LockRecord(pendingSinceMs = 5), f.load())
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { f.loadStrict() }
        val ok = LockRecordFile(dir.newFile("ok"), legacy = { LockRecord() })
        ok.save(LockRecord(pendingSinceMs = 7))
        assertEquals(LockRecord(pendingSinceMs = 7), ok.loadStrict())
    }
}
