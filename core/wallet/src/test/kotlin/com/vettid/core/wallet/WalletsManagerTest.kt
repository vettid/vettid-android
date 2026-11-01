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
import kotlinx.coroutines.launch
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
        m.open()
        assertEquals(WalletAvailability.AVAILABLE, m.availability.value)
        assertEquals(listOf("Savings"), m.wallets.value.map { it.name })
    }

    @Test
    fun aVaultWithoutWalletsIsAskedOnce() = runBlocking {
        ops.unsupported = true
        val m = manager()
        val e = runCatching { m.open() }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        assertEquals(WalletAvailability.UNSUPPORTED, m.availability.value)
        m.open()
        assertEquals(1, ops.calls.count { it == "list" })
        // A new unlock asks again.
        m.clear()
        assertEquals(WalletAvailability.UNKNOWN, m.availability.value)
    }

    @Test
    fun aSecondLookupThatMarksNothingNewDoesNotReadTheWalletAgain() = runBlocking {
        val w = withWallet()
        funded(receive, 0, 50_000)
        val m = manager()
        m.open()
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        val gets = ops.gets
        // The same funded address again: the vault marks nothing, so its version did not move and nothing is read.
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertEquals(gets, ops.gets)
    }

    @Test
    fun aLookupSumsBothAccountsAndChainsAndReportsTheFundedAddresses() = runBlocking {
        val w = withWallet()
        funded(receive, 0, 50_000)
        // Past the vault's next index and inside the gap: found, and the scan goes 20 past it.
        funded(receive, 15, 20_000, unconfirmed = 1_000)
        funded(AddressChain("p2wpkh", true), 3, 7_000)
        val m = manager()
        m.open()
        val r = m.lookup(w.id) as LookupResult.Done
        assertEquals(77_000L, r.lookup.confirmedSats)
        assertEquals(1_000L, r.lookup.unconfirmedSats)
        assertEquals("mempool.space", r.lookup.host)
        assertEquals(now, r.lookup.at)
        assertEquals(3, r.lookup.funded.size)
        // The receive chain was read to index 15 + 20.
        assertTrue(chain.asked.contains(WalletFixtures.address(receive, 35).address))
        assertFalse(chain.asked.contains(WalletFixtures.address(receive, 36).address))
        // Only the funded address the vault issued (receive 0; it issued none past next_receive 1, and no change) is reported, once.
        assertEquals(listOf(listOf(WalletFixtures.address(receive, 0).address)), ops.marked)
        assertEquals(r.lookup, m.lookups.value[w.id])
    }

    @Test
    fun moreThan256FundedAddressesAreReportedInChunks() = runBlocking {
        // 300 issued and funded receive addresses.
        val w = withWallet(issuedReceive = 300)
        repeat(300) { funded(receive, it, 1) }
        val m = manager()
        m.open()
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertEquals(listOf(256, 44), ops.marked.map { it.size })
    }

    @Test
    fun aMismatchStopsTheLookupBeforeTheServerIsAsked() = runBlocking {
        val w = withWallet(issuedReceive = 2)
        // The vault lists, at index 1, an address the app does not derive there.
        ops.lie = { a -> if (a.index == 1 && !a.change && a.type == "p2tr") a.copy(address = WalletFixtures.address(receive, 7).address) else a }
        val m = manager()
        m.open()
        assertEquals(LookupResult.AddressMismatch, m.lookup(w.id))
        assertTrue(chain.asked.isEmpty())
        assertTrue(ops.marked.isEmpty())
        assertNull(m.lookups.value[w.id])
    }

    @Test
    fun theReceivingChainHasAnAddressBeforeTheFirstCheck() = runBlocking {
        val w = withWallet(issuedReceive = 0)
        val m = manager()
        m.open()
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertEquals(1, ops.calls.count { it == "new" })
    }

    @Test
    fun theCheckRunsOncePerSession() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
        m.lookup(w.id)
        val lists = ops.calls.count { it.startsWith("list:") }
        m.lookup(w.id)
        assertEquals(lists, ops.calls.count { it.startsWith("list:") })
        // A new session (after a lock) checks again.
        m.clear()
        m.open()
        m.lookup(w.id)
        assertTrue(ops.calls.count { it.startsWith("list:") } > lists)
    }

    @Test
    fun eachServerStateIsItsOwnResult() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
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
        m.open()
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
        m.open()
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
        m.open()
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
        m.open()
        chain.gate = CompletableDeferred()
        val running = async(Dispatchers.Default) { m.lookup(w.id) }
        delay(200)
        m.clear()
        chain.gate!!.complete(Unit)
        assertEquals(LookupResult.Cleared, running.await())
        assertTrue(m.lookups.value.isEmpty())
        assertTrue(ops.marked.isEmpty())
    }
    // ---- The clear against work in flight (the #235 review) ----

    @Test
    fun aListReadThatAClearOvertookIsNotPublished() = runBlocking {
        withWallet()
        val m = manager()
        ops.listGate = CompletableDeferred()
        val reading = async(Dispatchers.Default) { m.open() }
        delay(100)
        m.clear()
        ops.listGate!!.complete(Unit)
        reading.await()
        assertTrue(m.wallets.value.isEmpty())
        assertEquals(WalletAvailability.UNKNOWN, m.availability.value)
    }

    @Test
    fun anEventJustBeforeAClearReadsNothingAfterIt() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
        val reads = ops.calls.count { it == "list" }
        m.onEvent("sync.event", syncEvent("wallet.changed", w.id))
        m.clear()
        delay(WalletsManager.EVENT_DEBOUNCE_MS * 3)
        assertEquals(reads, ops.calls.count { it == "list" })
        assertTrue(m.wallets.value.isEmpty())
        // Closed: a refresh asks nothing either.
        m.refresh()
        assertEquals(reads, ops.calls.count { it == "list" })
    }

    @Test
    fun aCreateThatAClearOvertookKeepsNothingAndRevealsNothing() = runBlocking {
        lateinit var m: WalletsManager
        // The vault is locked while the create is answered.
        val gated = object : WalletOps by ops {
            override suspend fun create(name: String, network: String, password: String): VaultWallet =
                ops.create(name, network, password).also { m.clear() }
        }
        m = WalletsManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), { gated }, { n -> chain.open(n) }, WalletFlags(flagsFile), { it in offered }, { now })
        m.open()
        val c = m.create("Savings", Network.TESTNET4, "pw")
        assertNull(c.phrase)
        assertEquals(WalletsManager.CLEARED, c.revealFailure?.code)
        assertTrue(m.wallets.value.isEmpty())
        assertFalse("reveal" in ops.calls)
    }

    @Test
    fun aFundedGapAddressIsNotReported() = runBlocking {
        // The vault issued receive 0..1 (next_receive 2); the app finds funds at 0 and at 10, which the vault never issued.
        val w = withWallet(issuedReceive = 2)
        funded(receive, 0, 1_000)
        funded(receive, 10, 2_000)
        val m = manager()
        m.open()
        val r = m.lookup(w.id) as LookupResult.Done
        // Both count toward the balance and both are funded (Receive sees them) ...
        assertEquals(3_000L, r.lookup.confirmedSats)
        assertEquals(2, r.lookup.funded.size)
        // ... but only the vault's own address is reported.
        assertEquals(listOf(listOf(WalletFixtures.address(receive, 0).address)), ops.marked)
    }

    @Test
    fun aLookupStopsAskingTheServerOnceCleared() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
        var asked = 0
        chain = FakeChain(ChainOpen.Ready("node.example") { _ ->
            if (++asked == WalletsManager.BATCH) m.clear()
            ChainSummary(0, 0, 0, 0)
        })
        assertEquals(LookupResult.Cleared, m.lookup(w.id))
        // The batch in flight finished; no batch after it was asked.
        assertEquals(WalletsManager.BATCH, asked)
    }

    @Test
    fun theCheckIsPerWalletAndNetwork() = runBlocking {
        val w = withWallet(network = "testnet")
        // A build that offered both test chains: the label decides which one the wallet is checked and looked up on.
        val m = WalletsManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), { ops }, { n -> chain.open(n) }, WalletFlags(flagsFile), { it == Network.TESTNET4 || it == Network.TESTNET }, { now })
        m.open()
        m.setTestNetwork(w.id, Network.TESTNET4)
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        val lists = ops.calls.count { it.startsWith("list:") }
        // The member says the wallet is on the other test chain: the addresses are checked again for that one.
        m.setTestNetwork(w.id, Network.TESTNET)
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertTrue(ops.calls.count { it.startsWith("list:") } > lists)
    }

    @Test
    fun aRevealThatAClearOvertookWipesThePhraseAndFails() = runBlocking {
        lateinit var m: WalletsManager
        var revealed: RevealedPhrase? = null
        val gated = object : WalletOps by ops {
            override suspend fun revealPhrase(walletId: String, password: String): RevealedPhrase =
                ops.revealPhrase(walletId, password).also {
                    revealed = it
                    m.clear()
                }
        }
        m = WalletsManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), { gated }, { n -> chain.open(n) }, WalletFlags(flagsFile), { it in offered }, { now })
        val e = runCatching { m.revealPhrase("01WALLET0000000000000000AA", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.CLEARED, e.code)
        assertTrue(revealed!!.words().isEmpty())
    }

    @Test
    fun aCloseAlwaysFinishesItsHook() = runBlocking {
        val m = manager()
        var done = false
        val job = CoroutineScope(Dispatchers.Default).launch {
            m.close {
                delay(200)
                done = true
            }
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue(done)
    }

    @Test
    fun theReceivingAddressTypeIsSetAtTheCurrentVersionAndPublished() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
        m.setAddressType(w.id, "p2wpkh")
        assertEquals("update:${w.version}:p2wpkh", ops.calls.last())
        val now = m.wallets.value.single()
        assertEquals("p2wpkh", now.addressType)
        assertEquals(w.version + 1, now.version)
    }

    @Test
    fun aConflictOnTheAddressTypeReadsTheListAgainAndThrows() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
        ops.conflict = true
        // Changed on another device meanwhile.
        ops.wallets[w.id] = w.copy(version = w.version + 5, name = "Renamed")
        val e = runCatching { m.setAddressType(w.id, "p2wpkh") }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.CONFLICT, e.kind)
        // The retry with the fresh version conflicts again (the flag): refreshed once, asked twice, then thrown.
        assertEquals(2, ops.calls.count { it == "list" })
        assertEquals(2, ops.calls.count { it.startsWith("update") })
        assertEquals("Renamed", m.wallets.value.single().name)
        assertEquals("p2tr", m.wallets.value.single().addressType)
    }

    @Test
    fun aStaleVersionIsRefreshedAndTheChoiceAskedOnceMore() = runBlocking {
        val w = withWallet()
        val m = manager()
        m.open()
        // Changed elsewhere (an address used on another device): the app's version is stale.
        ops.wallets[w.id] = w.copy(version = w.version + 3)
        m.setAddressType(w.id, "p2wpkh")
        assertEquals(listOf("update:${w.version}:p2wpkh", "update:${w.version + 3}:p2wpkh"), ops.calls.filter { it.startsWith("update") })
        assertEquals("p2wpkh", m.wallets.value.single().addressType)
        assertEquals(w.version + 4, m.wallets.value.single().version)
    }

    @Test
    fun aLookupAndANewAddressLeaveTheVersionCurrentForTheAddressType() = runBlocking {
        val w = withWallet(issuedReceive = 0)
        funded(receive, 0, 50_000)
        val m = manager()
        m.open()
        // The first look-up issues the first address (the check) and reports the funded one used.
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertTrue(ops.marked.isNotEmpty())
        m.newAddress(w.id)
        m.setAddressType(w.id, "p2wpkh")
        // No conflict: the version was taken in after each of this phone's own changes.
        assertEquals(1, ops.calls.count { it.startsWith("update") })
        assertEquals("p2wpkh", m.wallets.value.single().addressType)
    }

    @Test
    fun anAddressJustIssuedIsReportedByTheSameLookup() = runBlocking {
        // The vault knows no address yet: the check issues index 0, which the server shows funded.
        val w = withWallet(issuedReceive = 0)
        funded(receive, 0, 70_000)
        val m = manager()
        m.open()
        assertTrue(m.lookup(w.id) is LookupResult.Done)
        assertEquals(listOf(WalletFixtures.address(receive, 0).address), ops.marked.flatten())
    }

    @Test
    fun aDeleteCancelledAfterTheVaultAnsweredStillForgetsTheWallet() = runBlocking {
        val w = withWallet()
        val file = flagsFile
        WalletFlags(file).setBackedUp(w.id)
        lateinit var job: kotlinx.coroutines.Job
        val gated = object : WalletOps by ops {
            override suspend fun delete(walletId: String, password: String) {
                ops.delete(walletId, password)
                job.cancel()
            }
        }
        val m = WalletsManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), { gated }, { n -> chain.open(n) }, WalletFlags(file), { it in offered }, { now })
        m.open()
        job = CoroutineScope(Dispatchers.Default).launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { m.delete(w.id, "pw") }
        job.start()
        job.join()
        assertTrue(m.wallets.value.isEmpty())
        assertFalse(WalletFlags(file).backedUp(w.id))
    }

    @Test
    fun theAddressTypeIsNotPublishedAfterAClear() = runBlocking {
        val w = withWallet()
        lateinit var m: WalletsManager
        val gated = object : WalletOps by ops {
            override suspend fun setAddressType(walletId: String, version: Long, type: String): Long =
                ops.setAddressType(walletId, version, type).also { m.clear() }
        }
        m = WalletsManager(CoroutineScope(SupervisorJob() + Dispatchers.Default), { gated }, { n -> chain.open(n) }, WalletFlags(flagsFile), { it in offered }, { now })
        m.open()
        m.setAddressType(w.id, "p2wpkh")
        assertTrue(m.wallets.value.isEmpty())
    }

    @Test
    fun thePrivacyNoteIsKeptAndBumpsTheFlagsSignal() = runBlocking {
        val w = withWallet()
        val m = manager()
        assertFalse(m.privacyNoteShown(w.id))
        val before = m.flagsChanged.value
        assertTrue(m.setPrivacyNoteShown(w.id))
        assertTrue(m.privacyNoteShown(w.id))
        assertEquals(before + 1, m.flagsChanged.value)
    }
}
