package com.vettid.core.relay

import com.vettid.core.crypto.Ed25519PrivateKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/**
 * What the collector counts as a request lost (it may have been handed messages that stay leased to a request that is gone):
 * every lost attempt, even when the client's own retry then succeeds; a 5xx; a 200 that does not parse. Not a 4xx that is
 * the relay's own answer.
 */
class MailboxCollectorLostTest {
    private lateinit var server: MockWebServer
    private var lost = 0

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private var retrySends = 0

    private fun collector(): MailboxCollector {
        val client = RelayClient(server.url("/").toString(), Ed25519PrivateKey.generate(), OkHttpClient(), sleep = {})
        return MailboxCollector(client, wait = Duration.ZERO, sleep = {}, onLost = { lost++ }, onRetrySent = { retrySends++ })
    }

    private fun empty() = MockResponse.Builder().code(200).body("""{"messages":[]}""").build()

    private fun dropped() = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

    private fun status(code: Int, body: String = """{"code":"x","message":""}""") = MockResponse.Builder().code(code).body(body).build()

    /** One lost response, then the retry's `[]`: the round succeeds, yet the lost attempt is counted. */
    @Test
    fun aLostAttemptIsCountedEvenWhenTheRetrySucceeds() = runBlocking<Unit> {
        server.enqueue(dropped())
        server.enqueue(empty())
        assertEquals(0, collector().pollOnce {})
        assertTrue("counted: $lost", lost >= 1)
    }

    /** A 502 from a proxy, then `[]`. */
    @Test
    fun aServerErrorIsCountedEvenWhenTheRetrySucceeds() = runBlocking<Unit> {
        server.enqueue(status(502, "bad gateway"))
        server.enqueue(empty())
        assertEquals(0, collector().pollOnce {})
        assertTrue("counted: $lost", lost >= 1)
    }

    /** (#336 review, item 1) A 200 over the client's bound: the batch may be leased, so it is counted, and retried. */
    @Test
    fun anAnswerOverTheBoundIsCountedAndRetried() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("""{"messages":[],"x":"${"a".repeat(40 shl 20)}"}""").build())
        server.enqueue(empty())
        assertEquals(0, collector().pollOnce {})
        assertTrue("counted: $lost", lost >= 1)
        assertEquals(2, server.requestCount)
    }

    /** A 4xx that is the relay's own answer handed nothing out. */
    @Test
    fun aClientErrorIsNotCounted() = runBlocking<Unit> {
        server.enqueue(status(403, """{"code":"token_revoked","message":""}"""))
        val r = runCatching { collector().run {} }
        assertTrue(r.exceptionOrNull() is RelayException)
        assertEquals(0, lost)
    }

    /** A 200 whose body does not parse: the batch may have been handed out. */
    @Test
    fun aBodyThatDoesNotParseIsCounted() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("not json").build())
        val r = runCatching { collector().run {} }
        assertTrue("ended with the parse failure: ${r.exceptionOrNull()}", r.isFailure)
        assertTrue("counted: $lost", lost >= 1)
    }

    /** Three lost attempts of one round are three cuts, not four: the last failed attempt is not counted a second time. */
    @Test
    fun theLastLostAttemptIsNotCountedTwice() = runBlocking<Unit> {
        repeat(3) { server.enqueue(dropped()) }
        server.enqueue(status(403, """{"code":"token_revoked","message":""}"""))
        val r = runCatching { collector().run {} }
        assertTrue(r.exceptionOrNull() is RelayException)
        assertEquals(3, lost)
    }

    /** A lost attempt, then a successful collect of a message whose ack is refused: the ack's loss is a new loss, counted too. */
    @Test
    fun aRefusedAckAfterALostAttemptIsCountedToo() = runBlocking<Unit> {
        server.enqueue(dropped())
        server.enqueue(MockResponse.Builder().code(200).body("""{"messages":[{"msg_id":"m1","payload":""}]}""").build())
        server.enqueue(status(403, """{"code":"token_revoked","message":""}"""))
        val r = runCatching { collector().run {} }
        assertTrue(r.exceptionOrNull() is RelayException)
        assertEquals(2, lost)
    }

    /** A retry inside the client's own retries is a send of its own: one lost attempt, one retry send. */
    @Test
    fun aRetryInsideTheClientIsASendOfItsOwn() = runBlocking<Unit> {
        server.enqueue(dropped())
        server.enqueue(empty())
        assertEquals(0, collector().pollOnce {})
        assertEquals(1, retrySends)
    }

    /**
     * A lost attempt, then the next attempt is parked at the relay (handed a message) and cut by a cancellation: the cut is
     * counted too, whatever was lost before in the same round (2 cuts).
     */
    @Test
    fun aCancellationAfterALostAttemptInTheSameRoundIsCounted() = runBlocking<Unit> {
        server.enqueue(dropped())
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"messages":[]}""").headersDelay(30, java.util.concurrent.TimeUnit.SECONDS).build(),
        )
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { collector().run {} }
        while (server.requestCount < 2) kotlinx.coroutines.delay(10)
        kotlinx.coroutines.delay(100)
        job.cancel()
        job.join()
        assertEquals(2, lost)
    }

    /**
     * (#307 review F1) A send is stamped when an attempt actually starts writing its request (OkHttp's requestHeadersStart),
     * every attempt: a lost response and its retry are two sends; a relay that cannot be reached (the connect is refused)
     * never saw a request, so nothing is stamped.
     */
    @Test
    fun aSendIsStampedWhenTheRequestIsWrittenEveryAttempt() = runBlocking<Unit> {
        var sent = 0
        server.enqueue(dropped())
        server.enqueue(empty())
        val client = RelayClient(server.url("/").toString(), Ed25519PrivateKey.generate(), OkHttpClient(), sleep = {})
        assertEquals(0, MailboxCollector(client, wait = Duration.ZERO, sleep = {}, onSent = { sent++ }).pollOnce {})
        assertEquals("the lost attempt and the retry", 2, sent)
        val gone = server.url("/").toString()
        server.close()
        sent = 0
        val unreachable = RelayClient(gone, Ed25519PrivateKey.generate(), OkHttpClient(), sleep = {})
        val collector = MailboxCollector(unreachable, wait = Duration.ZERO, sleep = {}, onSent = { sent++ })
        assertTrue(runCatching { collector.pollOnce {} }.isFailure)
        assertEquals("nothing was written", 0, sent)
    }
}
