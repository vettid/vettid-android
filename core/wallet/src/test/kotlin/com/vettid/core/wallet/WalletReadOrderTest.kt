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

    /** The first list read again after a change fails: the first list stays shown and open() still sends a pending limit. */
    @Test
    fun aFailedSecondReadStillLetsOpenSendThePendingLimit() = runBlocking {
        val w = wallet()
        val pending = """"pending_caps":{"${w.id}":{"per_tx_sats":100000}}"""
        flagsFile.bytes = """{"version":1,"backed_up":["${w.id}"],"testnet":{},"note_shown":[],$pending}""".encodeToByteArray()
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        ops.slowRead = gate
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        try {
            ops.slowReadStarted.await()
            m.create("New", Network.TESTNET4, "pw".toCharArray(), null)
            ops.listFails = true // the second read is refused
        } finally {
            gate.complete(Unit)
        }
        opening.join()
        assertEquals("both reads were made", 2, ops.calls.count { it == "list" })
        assertTrue("the pending limit was sent", ops.calls.any { it.startsWith("caps.set:100000") })
        assertTrue(m.wallets.value.any { it.id == w.id })
    }

    /** No change made here: the first list is read once. */
    @Test
    fun withNoChangeTheFirstListIsReadOnce() = runBlocking {
        wallet()
        val m = manager()
        m.open()
        assertEquals(1, ops.calls.count { it == "list" })
    }

    /** A change after a first read that failed: the read begun after it shows it, and is not read again. */
    @Test
    fun aReadBegunAfterTheChangeIsNotReadAgain() = runBlocking {
        wallet()
        val m = manager()
        ops.listFails = true
        runCatching { m.open() }
        ops.listFails = false
        val made = m.create("New", Network.TESTNET4, "pw".toCharArray(), null).wallet.id
        val before = ops.calls.count { it == "list" }
        m.refresh()
        assertEquals("one read, no second", before + 1, ops.calls.count { it == "list" })
        assertTrue(m.wallets.value.any { it.id == made })
    }

    /**
     * The first read held while a wallet is created, the second read held while that wallet's limit is lowered: the
     * created wallet is shown at the end (the second change makes the second read stale too, and one more read follows).
     */
    @Test
    fun aChangeDuringTheSecondReadDoesNotHideTheWalletCreatedDuringTheFirst() = runBlocking {
        val other = wallet()
        val m = manager()
        val first = CompletableDeferred<Unit>()
        ops.slowRead = first
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        ops.slowReadStarted.await()
        val made = m.create("New", Network.TESTNET4, "pw".toCharArray(), null).wallet.id
        val second = CompletableDeferred<Unit>()
        ops.listGate = second
        first.complete(Unit)
        // The second read waits at the gate; a change made here meanwhile.
        // Bounded: without the second read (the rule broken) this fails rather than hangs.
        kotlinx.coroutines.withTimeout(5_000) { while (ops.calls.count { it == "list" } < 2) kotlinx.coroutines.delay(5) }
        m.lowerCaps(made, 100_000, null)
        ops.listGate = null
        second.complete(Unit)
        opening.join()
        assertTrue("the wallet made during the first read is shown", m.wallets.value.any { it.id == made })
        assertTrue(m.wallets.value.any { it.id == other.id })
        assertEquals(100_000L, m.wallets.value.single { it.id == made }.perTxSats)
    }

    /** The stale first list is not shown: from its creation on, the new wallet never leaves the list. */
    @Test
    fun noFlickerOfTheWalletCreatedWhileTheFirstListLoads() = runBlocking {
        wallet()
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        ops.slowRead = gate
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        ops.slowReadStarted.await()
        val made = m.create("New", Network.TESTNET4, "pw".toCharArray(), null).wallet.id
        val shown = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch { m.wallets.collect { l -> shown += l.map { it.id } } }
        gate.complete(Unit)
        opening.join()
        watcher.cancel()
        assertTrue("every list shown after the creation has the new wallet", shown.all { made in it })
    }

    /** The second read fails: the first list is shown after all (the new wallet hidden until Try again, fail secure). */
    @Test
    fun aFailedSecondReadShowsTheFirstList() = runBlocking {
        val w = wallet()
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        ops.slowRead = gate
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        ops.slowReadStarted.await()
        m.create("New", Network.TESTNET4, "pw".toCharArray(), null)
        ops.listFails = true
        gate.complete(Unit)
        opening.join()
        assertEquals(WalletAvailability.AVAILABLE, m.availability.value)
        assertEquals("the first list, as read", listOf(w.id), m.wallets.value.map { it.id })
    }
}
