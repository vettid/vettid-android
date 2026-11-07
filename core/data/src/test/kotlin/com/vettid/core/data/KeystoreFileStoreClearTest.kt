package com.vettid.core.data

import com.vettid.core.keystore.KeystoreException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Review of #204 (2): a file that stays after `clear()` is said, not swallowed (it would be read back at the next unlock).
 * And #322: which read failures [KeystoreFileStore.isCorrupt] counts as the data at fault.
 */
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

    @Test
    fun onlyAMalformedFileOrAFailedTagIsCorrupt() {
        // #322 (review): a block-size or key failure is the Keystore or its key, not the data: unavailable, not corrupt.
        assertFalse(KeystoreFileStore.isCorrupt(KeystoreException("cannot decrypt", javax.crypto.IllegalBlockSizeException())))
        assertFalse(KeystoreFileStore.isCorrupt(KeystoreException("cannot decrypt", java.security.InvalidKeyException())))
        assertFalse(KeystoreFileStore.isCorrupt(java.security.ProviderException()))
        assertTrue(KeystoreFileStore.isCorrupt(KeystoreException("cannot decrypt", javax.crypto.AEADBadTagException())))
        // Through the store: a key the Keystore refuses (InvalidKeyException at init) is not corrupt; another key's tag is.
        val f = dir.newFile("k.bin")
        val good = KeystoreFileStore(f, "t") { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 1 }, "AES") }
        good.save("x".toByteArray())
        val refused = runCatching { KeystoreFileStore(f, "t") { throw java.security.InvalidKeyException("not authenticated") }.load() }
        assertFalse(KeystoreFileStore.isCorrupt(refused.exceptionOrNull()!!))
        val other = runCatching { KeystoreFileStore(f, "t") { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 2 }, "AES") }.load() }
        assertTrue(KeystoreFileStore.isCorrupt(other.exceptionOrNull()!!))
        // Malformed: not this format.
        f.writeText("plain")
        assertTrue(KeystoreFileStore.isCorrupt(runCatching { good.load() }.exceptionOrNull()!!))
    }
}
