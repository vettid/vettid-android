// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainSummary
import com.vettid.core.chain.ChainTx
import com.vettid.core.chain.ChainTxInput
import com.vettid.core.chain.ChainTxOutput
import com.vettid.core.chain.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The Transactions list (W2-3; ANDROID-PLAN §4 "Transactions"): the merge by `txid`, the amounts, the status, and the manager around it. */
class WalletTransactionsTest {
    private val now = Instant.parse("2026-10-31T20:00:00Z")
    private val ours = "tb1qours000000000000000000000000000000000"
    private val change = "tb1qchange000000000000000000000000000000"
    private val payee = "tb1qpayee0000000000000000000000000000000"
    private val stranger = "tb1qstranger0000000000000000000000000000"
    private val own = setOf(ours, change)

    private fun id(n: Int) = "%064x".format(n.toLong() + 0xa000)

    private fun inp(address: String, sats: Long) = ChainTxInput(id(999), 0, address, sats)

    private fun out(address: String?, sats: Long) = ChainTxOutput("0014aa", address, sats)

    private fun chainTx(n: Int, ins: List<ChainTxInput>, outs: List<ChainTxOutput>, fee: Long? = 500, height: Long? = 100, time: Long = 1_730_000_000) =
        ChainTx(id(n), height != null, height, height?.let { time }, fee, ins, outs)

    private fun signed(n: Int, sending: Long, changeSats: Long, fee: Long, at: Instant?, vararg payees: WalletPayee) =
        WalletTx(id(n), at, sending, changeSats, fee, payees.toList(), null, null)

    private fun merge(chain: List<ChainTx>, history: List<WalletTx> = emptyList(), tip: Long? = 105) =
        TransactionMerge.merge(own, chain.associateBy { it.txid }, history, tip, now)

    @Test
    fun aSentPaymentInBothSourcesIsOneRowWithTheVaultsFiguresAndTheServersStatus() {
        val tx = chainTx(1, listOf(inp(ours, 100_000)), listOf(out(payee, 60_000), out(change, 39_000)), fee = 1_000)
        val vault = signed(1, 60_000, 39_000, 1_000, now.minusSeconds(3600), WalletPayee(payee, 60_000))
        val rows = merge(listOf(tx), listOf(vault))
        val r = rows.single()
        assertEquals(TxDirection.SENT, r.direction)
        assertEquals(60_000L, r.amountSats)
        assertEquals(-61_000L, r.netSats)
        assertEquals(1_000L, r.feeSats)
        assertEquals(TxStatus.Confirmed(6), r.status)
        assertEquals(listOf(WalletPayee(payee, 60_000)), r.payees)
        assertEquals(now.minusSeconds(3600), r.signedAt)
        assertEquals(Instant.ofEpochSecond(1_730_000_000), r.blockTime)
    }

    @Test
    fun aReceivedPaymentIsWhatTheWalletGotAndHasNoFee() {
        val tx = chainTx(2, listOf(inp(stranger, 80_000)), listOf(out(ours, 50_000), out(stranger, 29_000)), height = null)
        val r = merge(listOf(tx)).single()
        assertEquals(TxDirection.RECEIVED, r.direction)
        assertEquals(50_000L, r.amountSats)
        assertEquals(50_000L, r.netSats)
        assertNull(r.feeSats)
        assertEquals(TxStatus.Waiting, r.status)
        assertTrue(r.payees.isEmpty())
    }

    @Test
    fun aTransferToOneselfIsNeitherSentNorReceived() {
        val tx = chainTx(3, listOf(inp(ours, 20_000)), listOf(out(change, 19_500)), fee = 500)
        val r = merge(listOf(tx)).single()
        assertEquals(TxDirection.SELF, r.direction)
        assertEquals(19_500L, r.amountSats)
        // Only the fee left the wallet.
        assertEquals(-500L, r.netSats)
        assertEquals(500L, r.feeSats)
        assertTrue(r.payees.isEmpty())
    }

    @Test
    fun theFeeIsInputsLessOutputsWhenTheServerGivesNone() {
        val tx = chainTx(4, listOf(inp(ours, 10_000)), listOf(out(payee, 9_000)), fee = null)
        assertEquals(1_000L, merge(listOf(tx)).single().feeSats)
    }

    @Test
    fun anOpReturnOutputIsNotAPayee() {
        val tx = chainTx(5, listOf(inp(ours, 10_000)), listOf(out(null, 0), out(change, 9_500)), fee = 500)
        assertEquals(TxDirection.SELF, merge(listOf(tx)).single().direction)
    }

    @Test
    fun aSignedPaymentTheServerDoesNotKnowIsNotBroadcastAfter24HoursAndWaitingBefore() {
        val old = signed(6, 5_000, 1_000, 300, now.minusSeconds(25 * 3600), WalletPayee(payee, 5_000))
        val recent = signed(7, 6_000, 1_000, 300, now.minusSeconds(23 * 3600), WalletPayee(payee, 6_000))
        val noTime = signed(8, 7_000, 0, 300, null, WalletPayee(payee, 7_000))
        val exactly = signed(9, 8_000, 0, 300, now.minusSeconds(24 * 3600), WalletPayee(payee, 8_000))
        val rows = merge(emptyList(), listOf(old, recent, noTime, exactly)).associateBy { it.txid }
        assertEquals(TxStatus.NotBroadcast, rows.getValue(id(6)).status)
        // Under 24 hours (or no time at all, as recentSends reads it): not seen yet, not "Not broadcast".
        assertEquals(TxStatus.NotSeen, rows.getValue(id(7)).status)
        assertEquals(TxStatus.NotSeen, rows.getValue(id(8)).status)
        // Exactly 24 hours is outside recentSends' window (`isAfter(cutoff)`), so it is past it here too.
        assertEquals(TxStatus.NotBroadcast, rows.getValue(id(9)).status)
        val r = rows.getValue(id(6))
        assertEquals(TxDirection.SENT, r.direction)
        assertEquals(5_000L, r.amountSats)
        assertEquals(-5_300L, r.netSats)
        assertEquals(300L, r.feeSats)
    }

    @Test
    fun aVaultPaymentToOwnAddressesIsASelfTransfer() {
        val h = signed(10, 4_000, 500, 200, now.minusSeconds(60), WalletPayee(ours.uppercase(), 4_000))
        val r = merge(emptyList(), listOf(h)).single()
        assertEquals(TxDirection.SELF, r.direction)
        assertEquals(-200L, r.netSats)
    }

    @Test
    fun confirmationsCountTheBlockOfTheTransactionFromTheTip() {
        assertEquals(1L, TransactionMerge.confirmations(100, 100))
        assertEquals(6L, TransactionMerge.confirmations(100, 105))
        // A tip older than the block (the server moved on between the calls) is still one confirmation, never zero or negative.
        assertEquals(1L, TransactionMerge.confirmations(100, 98))
        assertEquals(1L, TransactionMerge.confirmations(null, 98))
        assertEquals(1L, TransactionMerge.confirmations(100, null))
    }

    @Test
    fun newestFirstNotInABlockBeforeConfirmed() {
        val a = chainTx(11, listOf(inp(stranger, 9)), listOf(out(ours, 5)), height = 90)
        val b = chainTx(12, listOf(inp(stranger, 9)), listOf(out(ours, 5)), height = 100)
        val c = chainTx(13, listOf(inp(stranger, 9)), listOf(out(ours, 5)), height = null)
        val h = signed(14, 1, 0, 1, now.minusSeconds(100), WalletPayee(payee, 1))
        // Not in a block first (the entry with no block time by its signing time: unknown-time first, then the later), then by height.
        assertEquals(listOf(id(13), id(14), id(12), id(11)), merge(listOf(a, b, c), listOf(h)).map { it.txid })
        assertEquals(listOf(id(12), id(11)), merge(listOf(a, b)).map { it.txid })
    }

    @Test
    fun theVaultsFiguresWinForAPaymentItSignedWhateverTheServerSays() {
        // The server shows a payment of its own as a receipt of a million sats with no fee; the vault signed 60,000 to a payee.
        val lie = chainTx(20, listOf(inp(stranger, 2_000_000)), listOf(out(ours, 1_000_000), out(stranger, 999_000)), fee = 1_000, height = 100)
        val vault = signed(20, 60_000, 39_000, 1_000, now.minusSeconds(60), WalletPayee(payee, 60_000))
        val r = merge(listOf(lie), listOf(vault)).single()
        assertEquals(TxDirection.SENT, r.direction)
        assertEquals(60_000L, r.amountSats)
        assertEquals(1_000L, r.feeSats)
        assertEquals(-61_000L, r.netSats)
        assertEquals(listOf(WalletPayee(payee, 60_000)), r.payees)
        // Only where it stands comes from the server.
        assertEquals(TxStatus.Confirmed(6), r.status)
        assertEquals(100L, r.blockHeight)
    }

    @Test
    fun aKeptUnsentPaymentIsNotBroadcastAtOnceAndOneUnderADayIsNotSeen() {
        val h = signed(21, 5_000, 0, 300, now.minusSeconds(60), WalletPayee(payee, 5_000))
        assertEquals(TxStatus.NotBroadcast, TransactionMerge.merge(own, emptyMap(), listOf(h), null, now, setOf(id(21))).single().status)
        assertEquals(TxStatus.NotSeen, TransactionMerge.merge(own, emptyMap(), listOf(h), null, now).single().status)
    }

    @Test
    fun aTransactionThatTouchesNoOwnAddressIsDropped() {
        val tx = chainTx(22, listOf(inp(stranger, 9_000)), listOf(out(payee, 8_000)))
        assertTrue(merge(listOf(tx)).isEmpty())
    }

    @Test
    fun ownAndForeignInputsGiveTheNetAmountAndNoFee() {
        // 30,000 of the wallet's and 70,000 of another's go to a payee (90,000) and 9,000 back to the wallet.
        val tx = chainTx(23, listOf(inp(ours, 30_000), inp(stranger, 70_000)), listOf(out(payee, 90_000), out(change, 9_000)), fee = 1_000)
        val r = merge(listOf(tx)).single()
        assertEquals(TxDirection.SENT, r.direction)
        assertEquals(21_000L, r.amountSats)
        assertEquals(-21_000L, r.netSats)
        assertNull(r.feeSats)
        assertTrue(r.payees.isEmpty())
    }

    @Test
    fun theVaultsOwnFiguresThatOverflowAreAVaultFailureNotTheServers() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        ops.history = listOf(signed(1, Long.MAX_VALUE, 0, 5, now, WalletPayee(payee, Long.MAX_VALUE)))
        val m = manager()
        m.open()
        assertTrue(m.transactions(w.id) is TransactionsResult.VaultFailed)
        val e = runCatching { merge(emptyList(), listOf(signed(1, Long.MAX_VALUE, 0, 5, now, WalletPayee(payee, Long.MAX_VALUE)))) }.exceptionOrNull()
        assertTrue(e is WalletWireException)
    }

    @Test
    fun anAtThatDoesNotReadIsAMalformedHistoryEntryButNoAtIsNull() {
        fun entry(at: String) = com.vettid.core.vault.VaultJson.parseObject(
            """{"transactions":[{"txid":"${id(1)}",$at"sending_sats":1,"change_sats":0,"fee_sats":1,"payees":[]}]}""".encodeToByteArray(),
        )
        assertNull(WalletWire.history(entry("")).single().at)
        assertNull(WalletWire.history(entry("\"at\":null,")).single().at)
        assertEquals(now, WalletWire.history(entry("\"at\":\"$now\",")).single().at)
        assertTrue(runCatching { WalletWire.history(entry("\"at\":\"yesterday\",")) }.exceptionOrNull() is WalletWireException)
        assertTrue(runCatching { WalletWire.history(entry("\"at\":5,")) }.exceptionOrNull() is WalletWireException)
    }

    @Test
    fun aServerListingASignedTxidAsAPureReceiptStillShowsSent() {
        val lie = chainTx(25, listOf(inp(stranger, 70_000)), listOf(out(ours, 70_000)), height = null)
        val vault = signed(25, 60_000, 0, 1_000, now.minusSeconds(60), WalletPayee(payee, 60_000))
        val r = merge(listOf(lie), listOf(vault)).single()
        assertEquals(TxDirection.SENT, r.direction)
        assertEquals(60_000L, r.amountSats)
        assertEquals(TxStatus.Waiting, r.status)
    }

    @Test
    fun aMixedTransactionWithNoNetChangeIsSelfAndOneThatGainsIsReceived() {
        val flat = chainTx(26, listOf(inp(ours, 10_000), inp(stranger, 5_000)), listOf(out(ours, 10_000), out(payee, 5_000)), fee = 0)
        assertEquals(TxDirection.SELF, merge(listOf(flat)).single().direction)
        val gain = chainTx(27, listOf(inp(ours, 10_000), inp(stranger, 5_000)), listOf(out(ours, 13_000), out(payee, 2_000)), fee = 0)
        val r = merge(listOf(gain)).single()
        assertEquals(TxDirection.RECEIVED, r.direction)
        assertEquals(3_000L, r.amountSats)
        assertNull(r.feeSats)
    }

    @Test
    fun anOwnInputWithoutAValueIsMalformedNotZero() {
        val tx = chainTx(28, listOf(ChainTxInput(id(999), 0, ours, null)), listOf(out(payee, 1_000)))
        assertTrue(runCatching { merge(listOf(tx)) }.exceptionOrNull() is ChainError.Malformed)
    }

    @Test
    fun anOutputTextThatIsNotAnAddressOfTheNetworkIsNoPayee() {
        val tx = chainTx(29, listOf(inp(ours, 10_000)), listOf(out("<script>", 4_000), out(payee, 5_000), out(change, 500)), fee = 500)
        val rows = TransactionMerge.merge(own, mapOf(tx.txid to tx), emptyList(), 105, now) { it == payee }
        assertEquals(listOf(WalletPayee(payee, 5_000)), rows.single().payees)
        // The amount still counts everything that left.
        assertEquals(9_000L, rows.single().amountSats)
        val none = TransactionMerge.merge(own, mapOf(tx.txid to tx), emptyList(), 105, now) { false }
        assertTrue(none.single().payees.isEmpty())
    }

    @Test
    fun sumsThatOverflowAreMalformed() {
        val tx = chainTx(24, listOf(inp(stranger, 1)), listOf(out(ours, Long.MAX_VALUE), out(ours, Long.MAX_VALUE)))
        val e = runCatching { merge(listOf(tx)) }.exceptionOrNull()
        assertTrue(e is ChainError.Malformed)
    }

    // --- the manager ---

    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }
    private val receive = AddressChain("p2tr", false)
    private val changeChain = AddressChain("p2tr", true)

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it in setOf(Network.SIGNET, Network.TESTNET4) },
        { now },
    )

    private fun withWallet(): VaultWallet {
        val w = WalletFixtures.wallet(nextReceive = 1)
        ops.wallets[w.id] = w
        ops.issued[w.id to receive] = mutableListOf(WalletFixtures.address(receive, 0))
        runBlocking { WalletFlags(flagsFile).setPrivacyNoteShown(w.id) }
        ops.vaultClock = now
        return w
    }

    private fun addr(chain: AddressChain, i: Int) = WalletFixtures.address(chain, i).address

    private fun fundedAt(chain: AddressChain, i: Int) {
        this.chain.history[addr(chain, i)] = ChainSummary(1_000, 0, 1, 1)
    }

    @Test
    fun theListMergesTheServersAddressesWithTheVaultsHistory() = runBlocking {
        val w = withWallet()
        val a0 = addr(receive, 0)
        val c0 = addr(changeChain, 0)
        fundedAt(receive, 0)
        fundedAt(changeChain, 0)
        val sent = chainTx(1, listOf(inp(a0, 100_000)), listOf(out(payee, 60_000), out(c0, 39_000)), fee = 1_000, height = 100)
        val received = chainTx(2, listOf(inp(stranger, 80_000)), listOf(out(a0, 50_000)), height = null)
        val self = chainTx(3, listOf(inp(a0, 20_000)), listOf(out(c0, 19_500)), fee = 500, height = 101)
        // The sent payment is under both addresses; the list holds it once.
        chain.txs.byAddress[a0] = listOf(received, sent, self)
        chain.txs.byAddress[c0] = listOf(sent, self)
        chain.txs.tip = 105
        ops.history = listOf(
            signed(1, 60_000, 39_000, 1_000, now.minusSeconds(900), WalletPayee(payee, 60_000)),
            signed(4, 5_000, 0, 300, now.minusSeconds(30 * 3600), WalletPayee(payee, 5_000)),
            signed(5, 6_000, 0, 300, now.minusSeconds(3600), WalletPayee(payee, 6_000)),
        )
        val m = manager()
        m.open()
        val done = m.transactions(w.id) as TransactionsResult.Done
        val rows = done.transactions.items.associateBy { it.txid }
        assertEquals(5, rows.size)
        assertEquals(TxDirection.SENT, rows.getValue(id(1)).direction)
        assertEquals(TxStatus.Confirmed(6), rows.getValue(id(1)).status)
        assertEquals(TxDirection.RECEIVED, rows.getValue(id(2)).direction)
        assertEquals(TxStatus.Waiting, rows.getValue(id(2)).status)
        assertEquals(TxDirection.SELF, rows.getValue(id(3)).direction)
        assertEquals(TxStatus.Confirmed(5), rows.getValue(id(3)).status)
        assertEquals(TxStatus.NotBroadcast, rows.getValue(id(4)).status)
        assertEquals(TxStatus.NotSeen, rows.getValue(id(5)).status)
        assertEquals("mempool.space", done.transactions.host)
        // The vault's history was asked for 200, and the two it signed that no list shows were asked of the server by themselves.
        assertTrue("history:200" in ops.calls)
        assertEquals(2, chain.txs.asked.count { it == "tx" })
        assertEquals(done.transactions, m.transactionsKnown.value[w.id])
    }

    @Test
    fun aPaymentTheListsMissButTheServerKnowsIsNotCalledNotBroadcast() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        // Esplora lists only the newest 50 unconfirmed of an address: the server still has this one.
        chain.txs.byTxid[id(4)] = chainTx(4, listOf(inp(addr(receive, 0), 9_000)), listOf(out(payee, 8_000)), fee = 1_000, height = null)
        ops.history = listOf(signed(4, 8_000, 0, 1_000, now.minusSeconds(30 * 3600), WalletPayee(payee, 8_000)))
        val m = manager()
        m.open()
        val r = (m.transactions(w.id) as TransactionsResult.Done).transactions.items.single()
        assertEquals(TxStatus.Waiting, r.status)
    }

    @Test
    fun theWalkIsTheLookupsAndNothingIsAskedBeforeTheNoteOrTheCheck() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        val m = manager()
        m.open()
        m.transactions(w.id)
        // The same addresses the look-up asks about: both accounts, both chains, up to the gap past the last one with history.
        val walked = chain.asked.toSet()
        val m2 = manager()
        chain.asked.clear()
        m2.open()
        m2.lookup(w.id)
        assertEquals(chain.asked.toSet(), walked)
        // No note, no look-up.
        val bare = WalletFixtures.wallet(id = "01WALLET0000000000000000BB", nextReceive = 1)
        ops.wallets[bare.id] = bare
        ops.issued[bare.id to receive] = mutableListOf(WalletFixtures.address(receive, 0))
        val m3 = manager()
        m3.open()
        chain.txs.asked.clear()
        assertEquals(TransactionsResult.NoteNeeded, m3.transactions(bare.id))
        assertTrue(chain.txs.asked.isEmpty())
    }

    @Test
    fun theServerStatesAreTypedLikeTheDetails() = runBlocking {
        val w = withWallet()
        val c = FakeChain.noServer(ChainServer.Unreadable)
        val m1 = WalletsManager(CoroutineScope(SupervisorJob()), { ops }, { n -> c.open(n) }, WalletFlags(flagsFile), { true }, { now })
        m1.open()
        assertEquals(TransactionsResult.NoServer(ChainServer.Unreadable), m1.transactions(w.id))
        val wrong = FakeChain(ChainOpen.WrongNetwork(Network.SIGNET))
        val m2 = WalletsManager(CoroutineScope(SupervisorJob()), { ops }, { n -> wrong.open(n) }, WalletFlags(flagsFile), { true }, { now })
        m2.open()
        assertEquals(TransactionsResult.ServerWrongNetwork(Network.SIGNET), m2.transactions(w.id))
    }

    @Test
    fun aMalformedOrFailingServerAnswerFailsTheListAndAMalformedHistoryToo() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        val m = manager()
        m.open()
        chain.txs.fails = ChainError.Malformed()
        assertTrue(m.transactions(w.id) is TransactionsResult.ServerFailed)
        chain.txs.fails = ChainError.Timeout()
        assertTrue(m.transactions(w.id) is TransactionsResult.ServerFailed)
        chain.txs.fails = null
        ops.historyFails = true
        assertTrue(m.transactions(w.id) is TransactionsResult.VaultFailed)
        ops.historyFails = false
        ops.historyThrows = WalletWireException("transactions")
        assertTrue(m.transactions(w.id) is TransactionsResult.VaultFailed)
    }

    @Test
    fun aTransactionIsFoundInTheHistoryOfItsWallet() = runBlocking {
        val w = withWallet()
        ops.history = listOf(signed(1, 1, 0, 1, now, WalletPayee(payee, 1)))
        val m = manager()
        m.open()
        assertEquals(w.id, m.walletOfTransaction(id(1).uppercase()))
        assertNull(m.walletOfTransaction(id(2)))
    }

    @Test
    fun theListIsForgottenAtLockAndHoldAndWipe() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        val m = manager()
        m.open()
        assertTrue(m.transactions(w.id) is TransactionsResult.Done)
        assertTrue(m.transactionsKnown.value.containsKey(w.id))
        m.clear()
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun aClearDuringTheListEndsItClearedAndKeepsNothing() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        val m = manager()
        m.open()
        chain.onOpen = { m.clear() }
        assertEquals(TransactionsResult.Cleared, m.transactions(w.id))
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun aDeletedWalletsListGoes() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        val m = manager()
        m.open()
        m.transactions(w.id)
        m.onEvent("sync.event", syncEvent("wallet.deleted", w.id))
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun aHistoryEntryThatIsNoTxidIsDropped() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        ops.history = listOf(signed(1, 1, 0, 1, now, WalletPayee(payee, 1)).copy(txid = "not-a-txid"))
        val m = manager()
        m.open()
        assertTrue((m.transactions(w.id) as TransactionsResult.Done).transactions.items.isEmpty())
        assertEquals(0, chain.txs.asked.count { it == "tx" })
    }

    @Test
    fun aTransactionIsFoundAmongSeveralWalletsAndOnlyOnceTheListIsRead() = runBlocking {
        val w1 = withWallet()
        val w2 = WalletFixtures.wallet(id = "01WALLET0000000000000000BB", nextReceive = 1)
        ops.wallets[w2.id] = w2
        ops.history = listOf(signed(1, 1, 0, 1, now, WalletPayee(payee, 1)))
        val m = manager()
        // The unlock is still reading the wallets when the notification asks: the answer waits for the list and is right.
        val gate = CompletableDeferred<Unit>()
        ops.listGate = gate
        val opening = async(Dispatchers.Default) { m.open() }
        delay(100)
        val finding = async(Dispatchers.Default) { m.walletOfTransaction(id(1)) }
        delay(100)
        gate.complete(Unit)
        opening.await()
        assertEquals(w1.id, finding.await())
        assertNull(m.walletOfTransaction(id(2)))
        // A list that cannot be read is a failure, not "none".
        ops.listGate = null
        ops.listFails = true
        val m2 = manager()
        runCatching { m2.open() }
        assertTrue(runCatching { m2.walletOfTransaction(id(1)) }.exceptionOrNull() is com.vettid.core.data.vault.VaultFailure)
    }

    @Test
    fun aClearDuringTheListsTheSignedPaymentsOrBeforePublishEndsClearedAndStopsAsking() = runBlocking {
        val w = withWallet()
        for (i in 0..5) fundedAt(receive, i)
        ops.issued[w.id to receive] = (0..5).map { WalletFixtures.address(receive, it) }.toMutableList()
        ops.wallets[w.id] = w.copy(accounts = w.accounts.map { if (it.type == "p2tr") it.copy(nextReceive = 6) else it })
        val m = manager()
        m.open()
        // During the lists: the second batch is never asked.
        chain.txs.onTransactions = { m.clear() }
        assertEquals(TransactionsResult.Cleared, m.transactions(w.id))
        assertEquals(5, chain.txs.asked.count { it == "txs" })
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun aClearDuringThePerTxidAsksAndBeforePublishKeepsNothing() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        ops.history = listOf(
            signed(1, 1, 0, 1, now, WalletPayee(payee, 1)),
            signed(2, 1, 0, 1, now, WalletPayee(payee, 1)),
        )
        val m = manager()
        m.open()
        chain.txs.onTransaction = { m.clear() }
        assertEquals(TransactionsResult.Cleared, m.transactions(w.id))
        assertEquals(1, chain.txs.asked.count { it == "tx" })
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun aClearAfterTheLastServerCallDropsTheList() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        val a0 = addr(receive, 0)
        chain.txs.byAddress[a0] = listOf(chainTx(1, listOf(inp(stranger, 9)), listOf(out(a0, 5)), height = 100))
        val m = manager()
        m.open()
        chain.txs.onTip = { m.clear() }
        assertEquals(TransactionsResult.Cleared, m.transactions(w.id))
        assertTrue(m.transactionsKnown.value.isEmpty())
    }

    @Test
    fun aVaultPaymentToAnOwnAddressTheWalkDerivedButNothingFundedIsASelfTransfer() = runBlocking {
        val w = withWallet()
        fundedAt(receive, 0)
        // Change index 3 is derived by the walk (up to the gap) though nothing is on it.
        ops.history = listOf(signed(1, 5_000, 0, 200, now.minusSeconds(60), WalletPayee(addr(changeChain, 3), 5_000)))
        val m = manager()
        m.open()
        val r = (m.transactions(w.id) as TransactionsResult.Done).transactions.items.single()
        assertEquals(TxDirection.SELF, r.direction)
    }

    @Test
    fun theConfirmedCopyOfATransactionUnderTwoAddressesIsKept() = runBlocking {
        val w = withWallet()
        val a0 = addr(receive, 0)
        val c0 = addr(changeChain, 0)
        fundedAt(receive, 0)
        fundedAt(changeChain, 0)
        val unconfirmed = chainTx(1, listOf(inp(stranger, 9)), listOf(out(a0, 5)), height = null)
        val confirmed = chainTx(1, listOf(inp(stranger, 9)), listOf(out(a0, 5)), height = 100)
        chain.txs.byAddress[a0] = listOf(confirmed)
        chain.txs.byAddress[c0] = listOf(unconfirmed)
        val m = manager()
        m.open()
        val r = (m.transactions(w.id) as TransactionsResult.Done).transactions.items.single()
        assertTrue(r.status is TxStatus.Confirmed)
    }
}
