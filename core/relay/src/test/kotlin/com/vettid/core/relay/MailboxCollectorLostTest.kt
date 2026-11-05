package com.vettid.core.relay

import com.vettid.core.crypto.Ed25519PrivateKey
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

    private fun collector(): MailboxCollector {
        val client = RelayClient(server.url("/").toString(), Ed25519PrivateKey.generate(), OkHttpClient(), sleep = {})
        return MailboxCollector(client, wait = Duration.ZERO, sleep = {}, onLost = { lost++ })
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
}
