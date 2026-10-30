package com.vettid.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Review of #204 (2): a file that stays after `clear()` is said, not swallowed (it would be read back at the next unlock). */
class KeystoreFileStoreClearTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun aMissingFileIsCleared() {
        KeystoreFileStore(java.io.File(dir.root, "none.bin"), "t").clear()
    }

    @Test
    fun aFileIsDeleted() {
        val f = dir.newFile("a.bin")
        KeystoreFileStore(f, "t").clear()
        assertFalse(f.exists())
    }

    @Test
    fun aFileThatCannotBeDeletedIsThrown() {
        // A directory with something in it cannot be deleted as a file.
        val f = dir.newFolder("stuck.bin")
        java.io.File(f, "inner").writeText("x")
        assertThrows(IOException::class.java) { KeystoreFileStore(f, "t").clear() }
    }
}
