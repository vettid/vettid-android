package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backed-up flag per wallet and the legacy testnet label (ANDROID-PLAN §4 "Local state"), in the encrypted settings file. */
class WalletFlagsTest {
    private class MemoryFile : WalletFlagsFile {
        var bytes: ByteArray? = null
        var failLoad = false
        var saves = 0

        override fun load(): ByteArray? {
            check(!failLoad) { "cannot decrypt" }
            return bytes
        }

        override fun save(bytes: ByteArray) {
            saves++
            this.bytes = bytes.copyOf()
        }
    }

    private val file = MemoryFile()

    @Test
    fun backedUpIsKeptPerWalletAndForgotten() = runBlocking {
        val f = WalletFlags(file)
        assertFalse(f.backedUp("W1"))
        assertTrue(f.setBackedUp("W1"))
        assertTrue(WalletFlags(file).backedUp("W1"))
        assertFalse(f.backedUp("W2"))
        assertTrue(f.forget("W1"))
        assertFalse(f.backedUp("W1"))
        assertEquals("""{"version":1,"backed_up":[],"testnet":{}}""", file.bytes!!.decodeToString())
    }

    @Test
    fun aLegacyTestnetWalletsChainIsKept() = runBlocking {
        val f = WalletFlags(file)
        assertNull(f.testnetLabel("W1"))
        assertTrue(f.setTestnetLabel("W1", Network.TESTNET4))
        assertEquals(Network.TESTNET4, WalletFlags(file).testnetLabel("W1"))
        assertTrue(runCatching { runBlocking { f.setTestnetLabel("W1", Network.SIGNET) } }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun anUnreadableFileReadsAsNotBackedUpAndIsReplaced() = runBlocking {
        file.bytes = "garbage".encodeToByteArray()
        val f = WalletFlags(file)
        assertFalse(f.backedUp("W1"))
        assertTrue(f.setBackedUp("W2"))
        assertTrue(f.backedUp("W2"))
        file.failLoad = true
        assertFalse(f.backedUp("W2"))
    }

    @Test
    fun aWriteThatAWipeOvertookIsRefused() = runBlocking {
        var generation = 0L
        val f = WalletFlags(object : WalletFlagsFile {
            override fun load(): ByteArray? {
                generation++ // the wipe begins while the flag is being set
                return file.load()
            }

            override fun save(bytes: ByteArray) = file.save(bytes)
        }) { generation }
        assertFalse(f.setBackedUp("W1"))
        assertEquals(0, file.saves)
    }

    @Test
    fun nothingIsWrittenWhenNothingChanges() = runBlocking {
        val f = WalletFlags(file)
        assertTrue(f.forget("W1"))
        assertEquals(0, file.saves)
    }
}
