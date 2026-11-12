package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The order of `wallet.list` reads and of changes made here: a read begun before a later read, or before a change, never puts
 * its older list back when it ends; a change made while the first list loads is not hidden by it.
 */
class WalletReadOrderTest {
    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it == Network.SIGNET || it == Network.TESTNET4 },
        { Instant.parse("2026-10-31T20:00:00Z") },
    )

    private fun wallet(id: String = "01WALLETHELD00000000000001") = WalletFixtures.wallet(id = id).also { ops.wallets[id] = it }

    /** Runs [change] while a list read begun before it is held, then lets that read end with its older list. */
    private suspend fun duringAnOlderRead(m: WalletsManager, change: suspend () -> Unit) {
        val gate = CompletableDeferred<Unit>()
        ops.slowRead = gate
        val older = CoroutineScope(Dispatchers.Default).launch { runCatching { m.refresh() } }
        try {
            ops.slowReadStarted.await()
            change()
        } finally {
            gate.complete(Unit)
        }
        older.join()
    }

    @Test
    fun aLimitLoweredHereStays() = runBlocking {
        val w = wallet()
        val m = manager()
        m.open()
        duringAnOlderRead(m) { m.lowerCaps(w.id, 100_000, null) }
        assertEquals(100_000L, m.wallets.value.single().perTxSats)
    }

    @Test
    fun aWalletDeletedHereStaysDeleted() = runBlocking {
        val w = wallet()
        val m = manager()
        m.open()
        duringAnOlderRead(m) { m.delete(w.id, "pw".toCharArray()) }
        assertTrue(m.wallets.value.none { it.id == w.id })
    }

    @Test
    fun aWalletCreatedHereStays() = runBlocking {
        wallet()
        val m = manager()
        m.open()
        var made: String? = null
        duringAnOlderRead(m) { made = m.create("New", Network.TESTNET4, "pw".toCharArray(), null).wallet.id }
        assertTrue(m.wallets.value.any { it.id == made })
    }

    /** A wallet created while the first list is still loading is not hidden by that list: the list is read once more. */
    @Test
    fun aWalletCreatedWhileTheFirstListLoadsIsNotHiddenByIt() = runBlocking {
        wallet()
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        ops.slowRead = gate
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        val made: String
        try {
            ops.slowReadStarted.await()
            made = m.create("New", Network.TESTNET4, "pw".toCharArray(), null).wallet.id
        } finally {
            gate.complete(Unit)
        }
        opening.join()
        assertTrue("the wallet made while the first list loaded", m.wallets.value.any { it.id == made })
        assertEquals(2, m.wallets.value.size)
    }

    /** An older read answered `unsupported_type` does not hide the wallets a newer read showed. */
    @Test
    fun anOlderUnsupportedAnswerDoesNotOverrideANewerList() = runBlocking {
        wallet()
        val m = manager()
        m.open()
        ops.slowReadRefuses = true
        duringAnOlderRead(m) { m.refresh() }
        assertEquals(WalletAvailability.AVAILABLE, m.availability.value)
    }
}
