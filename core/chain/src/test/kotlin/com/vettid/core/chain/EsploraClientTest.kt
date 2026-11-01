// Recorded answers: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.chain

import com.vettid.core.chain.EsploraFixtures.ADDRESS
import com.vettid.core.chain.EsploraFixtures.OTHER_ADDRESS
import com.vettid.core.chain.EsploraFixtures.page
import com.vettid.core.chain.EsploraFixtures.tx
import com.vettid.core.chain.EsploraFixtures.txid
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The Esplora client (ANDROID-PLAN §4 "The chain source") against a recorded server: no network. */
class EsploraClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun client(limits: EsploraClient.Limits = EsploraClient.Limits()) =
        EsploraClient(server.url("/api"), OkHttpClient(), limits)

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private inline fun <reified E : ChainError> fails(block: () -> Unit): E {
        try {
            block()
        } catch (e: ChainError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}, got an answer")
        error("unreachable")
    }

    @Test
    fun theTipHeightAndABlockHash() = runBlocking {
        server.enqueue(ok("4321\n"))
        server.enqueue(ok(Genesis.of(Network.SIGNET)!!))
        val c = client()
        assertEquals(4321L, c.tipHeight())
        assertEquals(Genesis.of(Network.SIGNET), c.blockHash(0))
        assertEquals("/api/blocks/tip/height", server.takeRequest().url.encodedPath)
        assertEquals("/api/block-height/0", server.takeRequest().url.encodedPath)
        // A server that writes the hash in upper case says the same.
        server.enqueue(ok(Genesis.of(Network.SIGNET)!!.uppercase()))
        assertEquals(Genesis.of(Network.SIGNET), c.blockHash(0))
    }

    @Test
    fun theBalanceIsConfirmedAndUnconfirmed() = runBlocking {
        server.enqueue(ok(EsploraFixtures.SUMMARY))
        assertEquals(ChainSummary(100_000, -7_500, confirmedTxCount = 3, txCount = 5), client().summary(ChainTarget.Address(ADDRESS)))
        assertEquals("/api/address/$ADDRESS", server.takeRequest().url.encodedPath)
    }

    @Test
    fun totalsThatSpendMoreThanWasReceivedAreMalformed() {
        // Confirmed below zero, and unconfirmed spending more than the confirmed balance.
        for (body in listOf(EsploraFixtures.summary(1, confirmedSats = -1), EsploraFixtures.summary(1, confirmedSats = 1_000, unconfirmedSats = -1_001))) {
            server.enqueue(ok(body))
            fails<ChainError.Malformed> { runBlocking { client().summary(ChainTarget.Address(ADDRESS)) } }
        }
        server.enqueue(ok(EsploraFixtures.summary(1, confirmedSats = 1_000, unconfirmedSats = -1_000)))
        assertEquals(0L, runBlocking { client().summary(ChainTarget.Address(ADDRESS)) }.let { it.confirmedSats + it.unconfirmedSats })
    }

    @Test
    fun aScriptHashIsAskedForByItsHash() = runBlocking {
        server.enqueue(ok(EsploraFixtures.SUMMARY))
        client().summary(ChainTarget.ScriptHash(txid(5)))
        assertEquals("/api/scripthash/${txid(5)}", server.takeRequest().url.encodedPath)
    }

    @Test
    fun unspentOutputs() = runBlocking {
        server.enqueue(ok(EsploraFixtures.UTXO))
        val utxos = client().unspent(ChainTarget.Address(ADDRESS))
        assertEquals(
            listOf(ChainUtxo(txid(1), 0, 50_000, true, 200_001), ChainUtxo(txid(2), 3, 2_500, false, null)),
            utxos,
        )
        assertEquals("/api/address/$ADDRESS/utxo", server.takeRequest().url.encodedPath)
    }

    @Test
    fun transactionsFollowEsplorasPaging() = runBlocking {
        // The totals first (53 confirmed); the first page: 2 unconfirmed and 25 confirmed; then 25 confirmed; then 3, the last.
        server.enqueue(ok(EsploraFixtures.summary(53)))
        server.enqueue(ok(page(tx(1, false), tx(2, false), *(10 until 35).map { tx(it, true) }.toTypedArray())))
        server.enqueue(ok(page(*(35 until 60).map { tx(it, true) }.toTypedArray())))
        server.enqueue(ok(page(*(60 until 63).map { tx(it, true) }.toTypedArray())))
        val all = client().transactions(ChainTarget.Address(ADDRESS))
        assertEquals(2 + 25 + 25 + 3, all.size)
        assertEquals(listOf(txid(1), txid(2)), all.filterNot { it.confirmed }.map { it.txid })
        assertEquals("/api/address/$ADDRESS", server.takeRequest().url.encodedPath)
        assertEquals("/api/address/$ADDRESS/txs", server.takeRequest().url.encodedPath)
        assertEquals("/api/address/$ADDRESS/txs/chain/${txid(34)}", server.takeRequest().url.encodedPath)
        assertEquals("/api/address/$ADDRESS/txs/chain/${txid(59)}", server.takeRequest().url.encodedPath)
        assertEquals(4, server.requestCount)

        val t = all.first { it.txid == txid(10) }
        assertEquals(200_010L, t.blockHeight)
        assertEquals(10L, t.feeSats)
        assertEquals(ChainTxInput(txid(100_010), 1, OTHER_ADDRESS, 60_000), t.inputs.single())
        assertEquals(ADDRESS, t.outputs[0].address)
        assertEquals(50_000L, t.outputs[0].valueSats)
        assertNull(t.outputs[1].address)
    }

    @Test
    fun aShortFirstPageIsAll() = runBlocking {
        server.enqueue(ok(EsploraFixtures.summary(2)))
        server.enqueue(ok(page(tx(1, true), tx(2, true))))
        assertEquals(2, client().transactions(ChainTarget.Address(ADDRESS)).size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aShortPageCannotEndThePagingEarly() {
        // The totals count 30 confirmed transactions; the server lists 2 and stops.
        server.enqueue(ok(EsploraFixtures.summary(30)))
        server.enqueue(ok(page(tx(1, true), tx(2, true))))
        fails<ChainError.Malformed> { runBlocking { client().transactions(ChainTarget.Address(ADDRESS)) } }
    }

    @Test
    fun missingUnconfirmedTransactionsAreMalformed() {
        // The totals count 3 unconfirmed; the server lists 1.
        server.enqueue(ok(EsploraFixtures.summary(1, unconfirmedTxs = 3)))
        server.enqueue(ok(page(tx(1, false), tx(2, true))))
        fails<ChainError.Malformed> { runBlocking { client().transactions(ChainTarget.Address(ADDRESS)) } }
    }

    @Test
    fun aTransactionThatConfirmsWhileTheListIsReadIsNoMismatch() = runBlocking {
        // The totals saw 1 confirmed and 1 unconfirmed; by the time of the page both are confirmed.
        server.enqueue(ok(EsploraFixtures.summary(1, unconfirmedTxs = 1)))
        server.enqueue(ok(page(tx(1, true), tx(2, true))))
        assertEquals(2, client().transactions(ChainTarget.Address(ADDRESS)).size)
    }

    @Test
    fun moreThanFiftyUnconfirmedListsTheNewestFifty() = runBlocking {
        // Esplora lists at most 50 unconfirmed transactions: 60 in the totals, 50 on the page, is the whole answer.
        server.enqueue(ok(EsploraFixtures.summary(1, unconfirmedTxs = 60)))
        server.enqueue(ok(page(*(1..50).map { tx(it, false) }.toTypedArray(), tx(100, true))))
        assertEquals(51, client().transactions(ChainTarget.Address(ADDRESS)).size)
    }

    @Test
    fun theWholeListHasOneByteBudget() {
        server.enqueue(ok(EsploraFixtures.summary(60)))
        repeat(3) { p -> server.enqueue(ok(page(*(p * 25 until p * 25 + 25).map { tx(it, true) }.toTypedArray()))) }
        // A page is about 30 KB: the budget runs out on the second.
        val limits = EsploraClient.Limits(transactionsBytes = 40_000)
        fails<ChainError.TooLarge> { runBlocking { client(limits).transactions(ChainTarget.Address(ADDRESS)) } }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun theWholeListHasOneDeadline() {
        server.enqueue(ok(EsploraFixtures.summary(60)))
        repeat(3) { p ->
            val body = page(*(p * 25 until p * 25 + 25).map { tx(it, true) }.toTypedArray())
            server.enqueue(MockResponse.Builder().code(200).body(body).headersDelay(400, TimeUnit.MILLISECONDS).build())
        }
        // Each call is well inside its own limit; together they are not.
        val limits = EsploraClient.Limits(callTimeoutMs = 5_000, transactionsTimeoutMs = 700)
        fails<ChainError.Timeout> { runBlocking { client(limits).transactions(ChainTarget.Address(ADDRESS)) } }
    }

    @Test
    fun aCoinbaseInputHasNoPreviousOutput() = runBlocking {
        server.enqueue(ok(EsploraFixtures.summary(1)))
        server.enqueue(ok(page(EsploraFixtures.COINBASE_TX)))
        val input = client().transactions(ChainTarget.Address(ADDRESS)).single().inputs.single()
        assertEquals(ChainTxInput(null, null, null, null), input)
    }

    @Test
    fun aPageThatRepeatsATransactionStopsThePaging() {
        server.enqueue(ok(EsploraFixtures.summary(50)))
        server.enqueue(ok(page(*(10 until 35).map { tx(it, true) }.toTypedArray())))
        server.enqueue(ok(page(*(34 until 59).map { tx(it, true) }.toTypedArray())))
        fails<ChainError.Malformed> { runBlocking { client().transactions(ChainTarget.Address(ADDRESS)) } }
    }

    @Test
    fun moreTransactionsThanTheLimitAreRefused() {
        // Known from the totals: nothing is paged.
        server.enqueue(ok(EsploraFixtures.summary(75)))
        fails<ChainError.TooMany> { runBlocking { client(EsploraClient.Limits(maxTransactions = 60)).transactions(ChainTarget.Address(ADDRESS)) } }
        assertEquals(1, server.requestCount)
        // And from the pages, if the totals undercount.
        server.enqueue(ok(EsploraFixtures.summary(10)))
        repeat(3) { p -> server.enqueue(ok(page(*(p * 25 until p * 25 + 25).map { tx(it, true) }.toTypedArray()))) }
        fails<ChainError.TooMany> { runBlocking { client(EsploraClient.Limits(maxTransactions = 60)).transactions(ChainTarget.Address(ADDRESS)) } }
    }

    @Test
    fun aRawTransaction() = runBlocking {
        server.enqueue(ok(EsploraFixtures.RAW_HEX.uppercase()))
        assertArrayEquals(EsploraFixtures.RAW_TX, client().rawTransaction(EsploraFixtures.RAW_TXID.uppercase()))
        assertEquals("/api/tx/${EsploraFixtures.RAW_TXID}/hex", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aRawTransactionThatIsNotTheOneAskedForIsMalformed() {
        // Another transaction's bytes, and the right bytes with one more after them.
        for (body in listOf(EsploraFixtures.RAW_HEX, EsploraFixtures.RAW_HEX + "00")) {
            server.enqueue(ok(body))
            fails<ChainError.Malformed> { runBlocking { client().rawTransaction(if (body.length == EsploraFixtures.RAW_HEX.length) txid(3) else EsploraFixtures.RAW_TXID) } }
        }
    }

    @Test
    fun anUnknownTransactionIsNotFound() {
        server.enqueue(MockResponse.Builder().code(404).body("Transaction not found").build())
        fails<ChainError.NotFound> { runBlocking { client().rawTransaction(txid(3)) } }
    }

    @Test
    fun aKnownTransactionIsKnownAndAnUnknownOneIsNot() = runBlocking {
        val id = EsploraFixtures.RAW_TXID
        server.enqueue(ok("""{"txid":"${id.uppercase()}","status":{"confirmed":false}}"""))
        assertTrue(client().knowsTransaction(id))
        assertEquals("/api/tx/$id", server.takeRequest().url.encodedPath)
        server.enqueue(MockResponse.Builder().code(404).body("Transaction not found").build())
        assertFalse(client().knowsTransaction(id))
    }

    @Test
    fun theAnswerOfTheKnownCheckMustBeThatTransactionAndAServerFailureIsNotNo() {
        server.enqueue(ok("""{"txid":"${txid(3)}","status":{"confirmed":true}}"""))
        fails<ChainError.Malformed> { runBlocking { client().knowsTransaction(EsploraFixtures.RAW_TXID) } }
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())
        fails<ChainError.BadStatus> { runBlocking { client().knowsTransaction(EsploraFixtures.RAW_TXID) } }
    }

    @Test
    fun feeEstimates() = runBlocking {
        server.enqueue(ok(EsploraFixtures.FEES))
        val fees = client().feeEstimates()
        assertEquals(87.882, fees.getValue(1), 0.0)
        assertEquals(1.027, fees.getValue(144), 0.0)
        assertEquals(6, fees.size)
        assertEquals("/api/fee-estimates", server.takeRequest().url.encodedPath)
    }

    @Test
    fun badFeeEstimatesAreMalformed() {
        for (body in listOf("""{"1":-1}""", """{"x":2.0}""", """{"1":"2.0"}""", """{"1":10000.5}""", "[]", "not json")) {
            server.enqueue(ok(body))
            fails<ChainError.Malformed> { runBlocking { client().feeEstimates() } }
        }
        // The ceiling itself is an estimate.
        server.enqueue(ok("""{"1":10000}"""))
        assertEquals(10_000.0, runBlocking { client().feeEstimates() }.getValue(1), 0.0)
    }

    @Test
    fun aBroadcastThatSucceeds() = runBlocking {
        server.enqueue(ok(EsploraFixtures.RAW_TXID.uppercase()))
        val raw = EsploraFixtures.RAW_TX
        assertEquals(EsploraFixtures.RAW_TXID, client().broadcast(raw))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/tx", r.url.encodedPath)
        assertEquals(EsploraFixtures.RAW_HEX, r.body!!.utf8())
    }

    @Test
    fun aBroadcastAnsweredWithAnotherTxidIsMalformed() {
        server.enqueue(ok(txid(42)))
        fails<ChainError.Malformed> { runBlocking { client().broadcast(EsploraFixtures.RAW_TX) } }
    }

    @Test
    fun bytesThatAreNotATransactionAreNeverSent() {
        try {
            runBlocking { client().broadcast(byteArrayOf(1, 2, 3)) }
            fail("sent")
        } catch (_: IllegalArgumentException) {
            // refused before any request
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aBroadcastThatFailsCarriesItsKind() {
        val cases = mapOf(
            """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met, 110 < 141"}""" to BroadcastRefusal.FEE_TOO_LOW,
            """sendrawtransaction RPC error: {"code":-25,"message":"bad-txns-inputs-missingorspent"}""" to BroadcastRefusal.INPUTS_MISSING_OR_SPENT,
            """sendrawtransaction RPC error: {"code":-27,"message":"Transaction already in block chain"}""" to BroadcastRefusal.ALREADY_KNOWN,
            """sendrawtransaction RPC error: {"code":-27,"message":"Transaction outputs already in utxo set"}""" to BroadcastRefusal.ALREADY_KNOWN,
            """sendrawtransaction RPC error: {"code":-26,"message":"txn-mempool-conflict"}""" to BroadcastRefusal.CONFLICT,
            """sendrawtransaction RPC error: {"code":-26,"message":"dust"}""" to BroadcastRefusal.DUST,
            """sendrawtransaction RPC error: {"code":-26,"message":"mandatory-script-verify-flag-failed (Invalid Schnorr signature)"}""" to
                BroadcastRefusal.SCRIPT_FAILED,
            """sendrawtransaction RPC error: {"code":-22,"message":"TX decode failed"}""" to BroadcastRefusal.MALFORMED,
            """sendrawtransaction RPC error: {"code":-26,"message":"non-final"}""" to BroadcastRefusal.OTHER,
        )
        for ((text, kind) in cases) {
            server.enqueue(MockResponse.Builder().code(400).body(text).build())
            val e = fails<ChainError.BroadcastRefused> { runBlocking { client().broadcast(EsploraFixtures.RAW_TX) } }
            assertEquals(text, kind, e.reason)
            assertEquals(text, e.serverText)
            // The message is the kind only: the server's words stay out of logs and crash reports.
            assertFalse(e.message!!.contains("RPC"))
        }
    }

    @Test
    fun aBroadcastAnsweredWithAServerErrorIsABadStatus() {
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        assertEquals(502, fails<ChainError.BadStatus> { runBlocking { client().broadcast(EsploraFixtures.RAW_TX) } }.code)
    }

    @Test
    fun aRedirectIsRefusedAndNotFollowed() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere")).build())
        server.enqueue(ok("1"))
        fails<ChainError.Redirected> { runBlocking { client().tipHeight() } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBodyOverTheSizeLimitIsRefused() {
        val limits = EsploraClient.Limits(smallBytes = 100)
        server.enqueue(ok("1".repeat(101)))
        fails<ChainError.TooLarge> { runBlocking { client(limits).tipHeight() } }
        // The same without a declared length.
        server.enqueue(MockResponse.Builder().code(200).chunkedBody("1".repeat(500), 64).build())
        fails<ChainError.TooLarge> { runBlocking { client(limits).tipHeight() } }
    }

    @Test
    fun aSlowServerTimesOut() {
        server.enqueue(MockResponse.Builder().code(200).body("1").headersDelay(3, TimeUnit.SECONDS).build())
        fails<ChainError.Timeout> { runBlocking { client(EsploraClient.Limits(callTimeoutMs = 300)).tipHeight() } }
    }

    @Test
    fun aClosedServerIsUnreachable() {
        server.close()
        fails<ChainError.Unreachable> { runBlocking { client().tipHeight() } }
    }

    @Test
    fun malformedAnswersAreRefused() {
        val c = client()
        server.enqueue(ok("-5"))
        fails<ChainError.Malformed> { runBlocking { c.tipHeight() } }
        server.enqueue(ok("not-a-hash"))
        fails<ChainError.Malformed> { runBlocking { c.blockHash(0) } }
        server.enqueue(ok("""{"chain_stats":{}}"""))
        fails<ChainError.Malformed> { runBlocking { c.summary(ChainTarget.Address(ADDRESS)) } }
        server.enqueue(ok("""[{"txid":"${txid(1)}","vout":0,"status":{"confirmed":true},"value":-1}]"""))
        fails<ChainError.Malformed> { runBlocking { c.unspent(ChainTarget.Address(ADDRESS)) } }
        server.enqueue(ok("abc"))
        fails<ChainError.Malformed> { runBlocking { c.rawTransaction(txid(1)) } }
        server.enqueue(ok("not-a-txid"))
        fails<ChainError.Malformed> { runBlocking { c.broadcast(EsploraFixtures.RAW_TX) } }
    }

    @Test
    fun theRequestCarriesTheFixedUserAgentAndNothingOfThePhone() = runBlocking {
        server.enqueue(ok("1"))
        client().tipHeight()
        val r = server.takeRequest()
        assertEquals("VettID-Android", r.headers["User-Agent"])
        for (h in listOf("Cookie", "Authorization", "X-VettID-App", "Referer")) assertNull(h, r.headers[h])
        assertTrue(r.url.query == null)
    }

    @Test
    fun aTargetThatIsNotAnAddressOrHashIsNeverSent() {
        for (t in listOf(ChainTarget.Address("../tx"), ChainTarget.Address(""), ChainTarget.ScriptHash("zz"))) {
            try {
                runBlocking { client().summary(t) }
                fail("$t was sent")
            } catch (_: IllegalArgumentException) {
                // refused before any request
            }
        }
        assertEquals(0, server.requestCount)
    }
}
