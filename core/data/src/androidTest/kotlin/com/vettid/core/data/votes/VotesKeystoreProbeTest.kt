package com.vettid.core.data.votes

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.SeedWrapKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #322/#324 device check: how the real AndroidKeyStore (KeyMint) reports a damaged kept votes file, and that both votes
 * stores read it as corrupt (empty, replaced by the next save), not as [StoreUnavailable]. Uses a test-only key alias,
 * deleted after each test. What the phone threw is logged with tag [TAG] (class names only) and is in every assertion.
 */
@RunWith(AndroidJUnit4::class)
class VotesKeystoreProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun cleanup() {
        AndroidKeys.delete(ALIAS)
        files.forEach { it.delete() }
    }

    private fun sealedFile(purpose: String): Pair<File, KeystoreFileStore> {
        val f = File(context.noBackupFilesDir, "probe-$purpose.bin").also { it.delete(); files += it }
        return f to KeystoreFileStore(f, purpose, key = { SeedWrapKey.getOrCreate(ALIAS) })
    }

    private fun File.flip(index: Int) {
        val b = readBytes()
        b[index] = (b[index].toInt() xor 1).toByte()
        writeBytes(b)
    }

    /** The class names of [e] and its causes down to the root. */
    private fun chain(e: Throwable): String =
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CAUSES).joinToString(" <- ") { it.javaClass.name }

    /**
     * Reads [raw] (the file store itself) to see what the phone throws, logs it, and checks it is classed corrupt; then
     * that [load] (the votes store) reads empty and does not throw.
     */
    private fun assertCorrupt(what: String, raw: KeystoreFileStore, load: () -> List<*>) {
        val thrown = runCatching { raw.load() }.exceptionOrNull()
        assertNotNull("$what: the damaged file was read without an error", thrown)
        val seen = chain(thrown!!)
        Log.i(TAG, "$what: $seen; isCorrupt=${KeystoreFileStore.isCorrupt(thrown)}")
        assertTrue("$what: classed unavailable, the phone threw $seen", KeystoreFileStore.isCorrupt(thrown))
        val read = runCatching { load() }
        assertTrue(
            "$what: the store threw ${read.exceptionOrNull()?.let(::chain)}, the phone threw $seen",
            read.isSuccess && read.getOrThrow().isEmpty(),
        )
    }

    @Test
    fun aDamagedAttemptsFileReadsAsCorruptAndIsRepaired() {
        val (f, raw) = sealedFile("vote-attempts")
        val store = KeystoreResultsAttemptStore(raw)
        store.save(listOf(ResultsAttempt(key = "conn/a", atMs = 1)))
        // A byte of the sealed part (past the format byte and the 12-byte IV): the AES-GCM tag fails.
        f.flip(SEALED_FROM + (f.length().toInt() - SEALED_FROM) / 2)
        assertCorrupt("vote-attempts, sealed byte", raw, store::load)
        val next = listOf(ResultsAttempt(key = "conn/b", atMs = 2))
        store.save(next)
        assertEquals("vote-attempts: the next save repairs the file", next, store.load())
        // The format byte: the malformed path.
        f.flip(0)
        assertCorrupt("vote-attempts, header byte", raw, store::load)
    }

    @Test
    fun aDamagedRecheckFileReadsAsCorruptAndIsRepaired() {
        val (f, raw) = sealedFile("vote-rechecks")
        val store = KeystoreRecheckStampStore(raw)
        store.save(listOf(RecheckStamp("conn/a", 1)))
        f.flip(SEALED_FROM + (f.length().toInt() - SEALED_FROM) / 2)
        assertCorrupt("vote-rechecks, sealed byte", raw, store::load)
        val next = listOf(RecheckStamp("conn/b", 2))
        store.save(next)
        assertEquals("vote-rechecks: the next save repairs the file", next, store.load())
        f.flip(0)
        assertCorrupt("vote-rechecks, header byte", raw, store::load)
    }

    private companion object {
        const val TAG = "VotesKeystoreProbe"

        /** A test-only key, never the app's ([KeystoreFileStore.ALIAS]). */
        const val ALIAS = "vettid.test.votes.v1"

        /** The format byte and the 12-byte IV come first; the AES-GCM ciphertext and tag after. */
        const val SEALED_FROM = 13
        const val MAX_CAUSES = 8
    }
}
