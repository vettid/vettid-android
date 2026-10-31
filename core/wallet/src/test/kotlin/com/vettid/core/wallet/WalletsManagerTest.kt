// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainSummary
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** The wallets in memory (ANDROID-PLAN §4 "Wallet", W1c): list, create and back up, receive, and the look-up with its checks. */
class WalletsManagerTest {
    private val ops = FakeWalletOps()
    private var chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }
    private val now = Instant.parse("2026-10-31T20:00:00Z")
    private val offered = setOf(Network.SIGNET, Network.TESTNET4)

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it in offered },
        { now },
    )

    private val receive = AddressChain("p2tr", false)

    /** The vector wallet with [issuedReceive] receive addresses issued on its receiving account. */
    private fun withWallet(issuedReceive: Int = 1, network: String = "testnet4"): VaultWallet {
        val w = WalletFixtures.wallet(network = network, nextReceive = issuedReceive)
        ops.wallets[w.id] = w
        ops.issued[w.id to receive] = (0 until issuedReceive).map { WalletFixtures.address(receive, it) }.toMutableList()
        return w
    }

    private fun funded(chain: AddressChain, index: Int, sats: Long, unconfirmed: Long = 0) {
        this.chain.history[WalletFixtures.address(chain, index).address] = ChainSummary(sats, unconfirmed, 1, 1)
    }

    @Test
    fun theListMakesTheWalletsAvailable() = runBlocking {
        withWallet()
        val m = manager()
        assertEquals(WalletAvailability.UNKNOWN, m.availability.value)
        m.refresh()
        assertEquals(WalletAvailability.AVAILABLE, m.availability.value)
        assertEquals(listOf("Savings"), m.wallets.value.map { it.name })
    }

    @Test
    fun aVaultWithoutWalletsIsAskedOnce() = runBlocking {
        ops.unsupported = true
        val m = manager()
        val e = runCatching { m.refresh() }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        assertEquals(WalletAvailability.UNSUPPORTED, m.availability.value)
        m.refresh()
        assertEquals(1, ops.calls.count { it == "list" })
        // A new unlock asks again.
        m.clear()
        assertEquals(WalletAvailability.UNKNOWN, m.availability.value)
    }

    @Test
    fun aLookupSumsBothAccountsAndChainsAndReportsTheFundedAddresses() = runBlocking {
        val w = withWallet()
        funded(receive, 0, 50_000)
        // Past the vault's next index and inside the gap: found, and the scan goes 20 past it.
        funded(receive, 15, 20_000, unconfirmed = 1_000)
        funded(AddressChain("p2wpkh", true), 3, 7_000)
        val m = manager()
        m.refresh()
        val r = m.lookup(w.id) as LookupResult.Done
        assertEquals(77_000L, r.lookup.confirmedSats)
        assertEquals(1_000L, r.lookup.unconfirmedSats)
        assertEquals("mempool.space", r.lookup.host)
        assertEquals(now, r.lookup.at)
        assertEquals(3, r.lookup.funded.size)
        // The receive chain was read to index 15 + 20.
        assertTrue(chain.asked.contains(WalletFixtures.address(receive, 35).address))
        assertFalse(chain.asked.contains(WalletFixtures.address(receive, 36).address))
        // The funded addresses go to the vault once, in one request.
        assertEquals(listOf(r.lookup.funded.sorted()), ops.marked)
        assertEquals(r.lookup, m.lookups.value[w.id])
    }

    @Test
    fun moreThan256FundedAddressesAreReportedInChunks() = runBlocking {
        val w = withWallet()
        repeat(300) { funded(receive, it, 1) }
        val m = manager()
        m.refresh()
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertEquals(listOf(256, 44), ops.marked.map { it.size })
    }

    @Test
    fun aMismatchStopsTheLookupBeforeTheServerIsAsked() = runBlocking {
        val w = withWallet(issuedReceive = 2)
        // The vault lists, at index 1, an address the app does not derive there.
        ops.lie = { a -> if (a.index == 1 && !a.change && a.type == "p2tr") a.copy(address = WalletFixtures.address(receive, 7).address) else a }
        val m = manager()
        m.refresh()
        assertEquals(LookupResult.AddressMismatch, m.lookup(w.id))
        assertTrue(chain.asked.isEmpty())
        assertTrue(ops.marked.isEmpty())
        assertNull(m.lookups.value[w.id])
    }

    @Test
    fun theReceivingChainHasAnAddressBeforeTheFirstCheck() = runBlocking {
        val w = withWallet(issuedReceive = 0)
        val m = manager()
        m.refresh()
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertEquals(1, ops.calls.count { it == "new" })
    }

    @Test
    fun theCheckRunsOncePerSession() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.refresh()
        m.lookup(w.id)
        val lists = ops.calls.count { it.startsWith("list:") }
        m.lookup(w.id)
        assertEquals(lists, ops.calls.count { it.startsWith("list:") })
        // A new session (after a lock) checks again.
        m.clear()
        m.refresh()
        m.lookup(w.id)
        assertTrue(ops.calls.count { it.startsWith("list:") } > lists)
    }

    @Test
    fun eachServerStateIsItsOwnResult() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.refresh()
        chain = FakeChain.noServer(ChainServer.NotSet)
        assertEquals(LookupResult.NoServer(ChainServer.NotSet), m.lookup(w.id))
        chain = FakeChain.noServer(ChainServer.Unreadable)
        assertEquals(LookupResult.NoServer(ChainServer.Unreadable), m.lookup(w.id))
        chain = FakeChain(ChainOpen.WrongNetwork(Network.TESTNET))
        assertEquals(LookupResult.ServerWrongNetwork(Network.TESTNET), m.lookup(w.id))
        chain = FakeChain(ChainOpen.Unchecked(ChainError.Unreachable()))
        assertTrue(m.lookup(w.id) is LookupResult.ServerFailed)
        // A server that fails mid-scan.
        chain = FakeChain(ChainOpen.Ready("node.example") { throw ChainError.Timeout() })
        assertTrue((m.lookup(w.id) as LookupResult.ServerFailed).error is ChainError.Timeout)
        assertTrue(ops.marked.isEmpty())
    }

    @Test
    fun aLegacyTestnetWalletAsksItsChain() = runBlocking {
        val w = withWallet(network = "testnet")
        val m = manager()
        m.refresh()
        assertEquals(LookupResult.NeedsNetwork, m.lookup(w.id))
        // testnet3: the staging app does not connect to it.
        assertTrue(m.setTestNetwork(w.id, Network.TESTNET))
        assertEquals(LookupResult.NetworkNotOffered, m.lookup(w.id))
        assertTrue(m.setTestNetwork(w.id, Network.TESTNET4))
        assertTrue(m.lookup(w.id) is LookupResult.Done)
    }

    @Test
    fun receiveGivesTheNewestUnusedAddressAndANewOneOnlyWhenItIsUsed() = runBlocking {
        val w = withWallet(issuedReceive = 3)
        val m = manager()
        m.refresh()
        assertEquals(2, m.receiveAddress(w.id).index)
        assertEquals(0, ops.calls.count { it == "new" })
        // Funded, as the last look-up saw it: a new one.
        funded(receive, 2, 1_000)
        m.lookup(w.id)
        assertEquals(3, m.receiveAddress(w.id).index)
        // Marked used by the vault: a new one too.
        ops.issued.getValue(w.id to receive)[3] = WalletFixtures.address(receive, 3, used = true)
        assertEquals(4, m.receiveAddress(w.id).index)
        // "New address".
        assertEquals(5, m.newAddress(w.id).index)
    }

    @Test
    fun createRevealsWithTheSamePasswordAndLeavesTheWalletNotBackedUp() = runBlocking {
        val m = manager()
        val c = m.create("Savings", Network.TESTNET4, "pw")
        assertEquals(listOf("create:testnet4", "reveal"), ops.calls)
        assertEquals(24, c.phrase!!.wordCount)
        assertEquals(listOf(c.wallet), m.wallets.value)
        assertFalse(m.backedUp(c.wallet.id))
        assertTrue(m.confirmBackup(c.wallet.id))
        assertTrue(m.backedUp(c.wallet.id))
        c.phrase.wipe()
        assertTrue(c.phrase.words().isEmpty())
    }

    @Test
    fun aRevealThatFailsKeepsTheWallet() = runBlocking {
        ops.revealFails = true
        val m = manager()
        val c = m.create("Savings", Network.SIGNET, "pw")
        assertNull(c.phrase)
        assertNotNull(c.revealFailure)
        assertEquals(1, m.wallets.value.size)
        assertFalse(m.backedUp(c.wallet.id))
    }

    @Test
    fun walletsAreCreatedOnlyOnTheOfferedTestNetworks() = runBlocking {
        for (n in listOf(Network.MAINNET, Network.TESTNET, Network.REGTEST)) {
            try {
                manager().create("x", n, "pw")
                fail("$n")
            } catch (_: IllegalArgumentException) {
                // refused before anything is sent
            }
        }
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun deleteForgetsTheWalletAndItsFlag() = runBlocking {
        val m = manager()
        val c = m.create("Savings", Network.TESTNET4, "pw")
        m.confirmBackup(c.wallet.id)
        m.delete(c.wallet.id, "pw")
        assertTrue(m.wallets.value.isEmpty())
        assertFalse(m.backedUp(c.wallet.id))
    }

    @Test
    fun aWalletDeletedElsewhereGoesFromTheList() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.refresh()
        m.lookup(w.id)
        ops.wallets.clear()
        m.onEvent("sync.event", syncEvent("wallet.deleted", w.id))
        assertTrue(m.wallets.value.isEmpty())
        assertNull(m.lookups.value[w.id])
    }

    @Test
    fun aLookupThatFinishesAfterALockIsDropped() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.refresh()
        chain.gate = CompletableDeferred()
        val running = async(Dispatchers.Default) { m.lookup(w.id) }
        delay(200)
        m.clear()
        chain.gate!!.complete(Unit)
        assertFalse(running.await() is LookupResult.Done)
        assertTrue(m.lookups.value.isEmpty())
        assertTrue(ops.marked.isEmpty())
    }
}
