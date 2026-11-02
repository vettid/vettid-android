// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainSummary
import com.vettid.core.chain.ChainTx
import com.vettid.core.chain.ChainTxInput
import com.vettid.core.chain.ChainTxOutput
import com.vettid.core.wallet.ChainOpen
import com.vettid.core.wallet.ChainSource
import com.vettid.core.wallet.HistoryAccess
import com.vettid.core.wallet.TransactionsResult
import com.vettid.core.wallet.TxDirection
import com.vettid.core.wallet.TxStatus
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletPayee
import com.vettid.core.wallet.WalletTx
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** A chain server for the Transactions screens: transactions by address, a tip. */
internal class TxServer : HistoryAccess {
    val byAddress = mutableMapOf<String, List<ChainTx>>()
    var tip = 1_000L
    var fails: ChainError? = null

    override suspend fun transactions(address: String): List<ChainTx> {
        fails?.let { throw it }
        return byAddress[address].orEmpty()
    }

    override suspend fun transaction(txid: String): ChainTx? {
        fails?.let { throw it }
        return byAddress.values.flatten().firstOrNull { it.txid == txid }
    }

    override suspend fun tipHeight(): Long = tip.also { fails?.let { e -> throw e } }

    fun source(): ChainSource = ChainSource {
        ChainOpen.Ready("mempool.space", null, this) { address ->
            val n = byAddress[address]?.size ?: 0
            if (n > 0) ChainSummary(1_000, 0, n.toLong(), n.toLong()) else ChainSummary(0, 0, 0, 0)
        }
    }
}

/** The Transactions list and one transaction as the member's view models drive them (W2-3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransactionsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val server = TxServer()
    private val id = "01WALLET0000000000000000AA"
    private val ours = derive("p2tr", 0)
    private val payee = "tb1qpayee0000000000000000000000000000000"
    private val txA = "%064x".format(0xa1L)
    private val txB = "%064x".format(0xb2L)

    private fun received(txid: String, sats: Long = 50_000) = ChainTx(
        txid, true, 100, 1_730_000_000, null,
        listOf(ChainTxInput("%064x".format(1L), 0, "tb1qstranger", sats + 1_000)),
        listOf(ChainTxOutput("5120aa", ours, sats)),
    )

    private suspend fun TestScope.opened(note: Boolean = true, chain: ChainSource = server.source()): com.vettid.core.wallet.WalletsManager {
        val flags = WalletFlags(MemoryFlags())
        flags.setBackedUp(id)
        if (note) flags.setPrivacyNoteShown(id)
        return manager(backgroundScope, ops, flags, chain).also { it.open() }
    }

    private fun list(m: com.vettid.core.wallet.WalletsManager) = TransactionsViewModel(SavedStateHandle(mapOf("walletId" to id)), m)

    private fun one(m: com.vettid.core.wallet.WalletsManager, txid: String, walletId: String? = id) =
        TransactionViewModel(SavedStateHandle(buildMap { put("txid", txid); walletId?.let { put("walletId", it) } }), m)

    @Test
    fun theListShowsWhatTheServerHas() = runTest {
        ops.wallet(id)
        server.byAddress[ours] = listOf(received(txA))
        val vm = list(opened())
        eventually { vm.state.value.list != null }
        val items = vm.state.value.list!!.items
        assertEquals(1, items.size)
        assertEquals(TxDirection.RECEIVED, items[0].direction)
        assertEquals(50_000L, items[0].amountSats)
        assertEquals(TxStatus.Confirmed(901), items[0].status)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.problem)
        assertEquals("Savings", vm.state.value.name)
    }

    @Test
    fun aWalletWithNothingIsEmptyNotAnError() = runTest {
        ops.wallet(id)
        val vm = list(opened())
        eventually { vm.state.value.list != null }
        assertTrue(vm.state.value.list!!.items.isEmpty())
        assertNull(vm.state.value.problem)
    }

    @Test
    fun aServerThatFailsIsShownAndTryAgainReadsAgain() = runTest {
        ops.wallet(id)
        server.byAddress[ours] = listOf(received(txA))
        server.fails = ChainError.Timeout()
        val vm = list(opened())
        eventually { vm.state.value.problem != null }
        assertTrue(vm.state.value.problem is TransactionsResult.ServerFailed)
        assertNull(vm.state.value.list)
        server.fails = null
        vm.load()
        eventually { vm.state.value.list != null }
        assertNull(vm.state.value.problem)
    }

    @Test
    fun aMissingUnreadableOrWrongNetworkServerIsTypedLikeTheDetails() = runTest {
        ops.wallet(id)
        var open: ChainOpen = ChainOpen.NoServer(ChainServer.NotSet)
        val vm = list(opened(chain = { open }))
        eventually { vm.state.value.problem != null }
        assertEquals(TransactionsResult.NoServer(ChainServer.NotSet), vm.state.value.problem)
        open = ChainOpen.NoServer(ChainServer.Unreadable)
        vm.load()
        eventually { vm.state.value.problem == TransactionsResult.NoServer(ChainServer.Unreadable) }
        open = ChainOpen.WrongNetwork(com.vettid.core.chain.Network.SIGNET)
        vm.load()
        eventually { vm.state.value.problem == TransactionsResult.ServerWrongNetwork(com.vettid.core.chain.Network.SIGNET) }
    }

    @Test
    fun nothingIsAskedOfTheServerBeforeThePrivacyNote() = runTest {
        ops.wallet(id)
        val vm = list(opened(note = false))
        eventually { vm.state.value.problem != null }
        assertEquals(TransactionsResult.NoteNeeded, vm.state.value.problem)
    }

    @Test
    fun aVaultThatFailsIsShown() = runTest {
        ops.wallet(id)
        ops.historyFails = true
        val vm = list(opened())
        eventually { vm.state.value.problem != null }
        assertTrue(vm.state.value.problem is TransactionsResult.VaultFailed)
    }

    @Test
    fun theScreenLeavesWhenTheVaultCloses() = runTest {
        ops.wallet(id)
        val m = opened()
        val vm = list(m)
        eventually { vm.state.value.list != null }
        m.clear()
        eventually { vm.state.value.closed }
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun oneTransactionShowsItsFiguresAndTheServerThatSaidThem() = runTest {
        ops.wallet(id)
        server.byAddress[ours] = listOf(received(txA))
        val vm = one(opened(), txA)
        eventually { vm.state.value.tx != null }
        val s = vm.state.value
        assertEquals(TxDirection.RECEIVED, s.tx!!.direction)
        assertEquals("mempool.space", s.host)
    }

    @Test
    fun aTxidAloneFindsTheWalletFromTheVaultsHistory() = runTest {
        ops.wallet(id)
        ops.history = listOf(WalletTx(txB, Instant.now(), 5_000, 0, 300, listOf(WalletPayee(payee, 5_000)), null, null))
        // Unknown to the server, signed just now: not seen yet.
        val vm = one(opened(), txB.uppercase(), walletId = null)
        eventually { vm.state.value.tx != null }
        assertEquals(TxDirection.SENT, vm.state.value.tx!!.direction)
        assertEquals(TxStatus.NotSeen, vm.state.value.tx!!.status)
        assertEquals("Savings", vm.state.value.name)
    }

    @Test
    fun aTxidInNoWalletIsNotFoundAndAFailingVaultIsShown() = runTest {
        ops.wallet(id)
        val vm = one(opened(), txB, walletId = null)
        eventually { vm.state.value.notFound }
        assertNull(vm.state.value.tx)
        assertFalse(vm.state.value.loading)
        ops.historyFails = true
        vm.load()
        eventually { vm.state.value.problem != null }
        assertTrue(vm.state.value.problem is TransactionsResult.VaultFailed)
    }

    @Test
    fun aTransactionNotInTheListIsNotFoundNotInvented() = runTest {
        ops.wallet(id)
        server.byAddress[ours] = listOf(received(txA))
        val vm = one(opened(), txB)
        eventually { vm.state.value.notFound }
        assertNull(vm.state.value.tx)
        assertNotNull(vm.state.value.host)
    }

    @Test
    fun theTransactionScreenLeavesWhenTheVaultCloses() = runTest {
        ops.wallet(id)
        server.byAddress[ours] = listOf(received(txA))
        val m = opened()
        val vm = one(m, txA)
        eventually { vm.state.value.tx != null }
        m.clear()
        eventually { vm.state.value.closed }
    }

    @Test
    fun aRefreshDuringTheFirstLoadDoesNotStartAnother() = runTest {
        ops.wallet(id)
        server.byAddress[ours] = listOf(received(txA))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var opens = 0
        val vm = list(opened(chain = { n ->
            opens++
            gate.await()
            server.source().open(n)
        }))
        eventually { opens == 1 }
        vm.load()
        vm.load()
        gate.complete(Unit)
        eventually { vm.state.value.list != null }
        assertEquals(1, opens)
    }

    @Test
    fun aTapOnSomethingThatIsNoTxidAsksNobody() = runTest {
        ops.wallet(id)
        val vm = one(opened(), "not-a-txid", walletId = null)
        eventually { vm.state.value.notFound }
        assertTrue(ops.calls.none { it == "history" })
    }
}
