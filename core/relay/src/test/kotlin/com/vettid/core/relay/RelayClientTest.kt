// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.relay

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.json.StrictJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The relay client against a local HTTP server that checks every signature as the relay does. */
class RelayClientTest {
    private lateinit var server: MockWebServer
    private val key = Ed25519PrivateKey.generate()
    private val sleeps = mutableListOf<Long>()
    private lateinit var client: RelayClient

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        client = RelayClient(server.url("/").toString(), key, OkHttpClient(), sleep = { sleeps.add(it) })
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build()

    /** Verifies the request signature as the relay does (§4.1) and returns the timestamp. */
    private fun verifySigned(r: RecordedRequest): String {
        val pub = Base64s.decodeStd(r.headers[RelayAuth.HEADER_KEY]!!)
        assertArrayEquals(key.publicKey, pub)
        val ts = r.headers[RelayAuth.HEADER_TIMESTAMP]!!
        val sig = Base64s.decodeStd(r.headers[RelayAuth.HEADER_SIG]!!)
        val body = r.body?.toByteArray() ?: ByteArray(0)
        val d = RelayAuth.digest(r.method, r.url.encodedPath, ts, RelayAuth.bodyHash(body))
        assertTrue("signature", Ed25519.verifyRaw(pub, d, sig))
        val t = Instant.parse(ts)
        assertTrue(Duration.between(t, Instant.now()).abs() < Duration.ofSeconds(90))
        return ts
    }

    @Test
    fun registerDepositCollectAck() = runBlocking<Unit> {
        server.enqueue(
            json(
                201,
                """{"mailbox_id":"${client.mailboxId}","limits":{"max_payload_bytes":262144,"message_ttl_seconds":1209600,""" +
                    """"visibility_timeout_seconds":60,"max_token_lifetime_seconds":34560000,"open_token_max_lifetime_seconds":604800,""" +
                    """"max_claim_bytes":16384,"claim_ttl_seconds":604800,"max_blob_bytes":8388608,"blob_ttl_seconds":604800}}""",
            ),
        )
        val reg = client.register()
        assertTrue(reg.created)
        assertEquals(8388608L, reg.limits.maxBlobBytes)
        val r1 = server.takeRequest()
        verifySigned(r1)
        assertEquals("""{"pubkey":"${client.publicKeyB64}"}""", r1.body!!.utf8())

        server.enqueue(json(201, """{"msg_id":"01JXAMPLE0000000000000000A"}"""))
        assertEquals("01JXAMPLE0000000000000000A", client.deposit("gr2q7gf5lh6pzfdnurnkvputhp", "tok", byteArrayOf(1, 2, 3)))
        val r2 = server.takeRequest()
        verifySigned(r2)
        assertEquals("VettID-Deposit tok", r2.headers["Authorization"])
        assertEquals("/v1/mailbox/gr2q7gf5lh6pzfdnurnkvputhp", r2.url.encodedPath)
        assertEquals("""{"payload":"AQID"}""", r2.body!!.utf8())

        server.enqueue(
            json(
                200,
                """{"messages":[{"msg_id":"01A","deposited_at":"2026-10-04T00:00:00Z","sender":"${VectorsTest.SENDER_PUB}","jti":"j1","payload":"AQID"},""" +
                    """{"msg_id":"01B","deposited_at":"2026-10-04T00:00:01Z","sender":"${VectorsTest.SENDER_PUB}","payload":""}]}""",
            ),
        )
        val msgs = client.collect(Duration.ofSeconds(25), 32)
        assertEquals(2, msgs.size)
        assertEquals("j1", msgs[0].jti)
        assertNull(msgs[1].jti) // pre-0.4 relays may omit jti
        assertArrayEquals(byteArrayOf(1, 2, 3), msgs[0].payload())
        assertArrayEquals(VectorsTest.key(VectorsTest.SENDER_SEED).publicKey, msgs[0].senderKey())
        val r3 = server.takeRequest()
        verifySigned(r3)
        assertEquals("wait=25&max=32", r3.url.encodedQuery)

        server.enqueue(MockResponse.Builder().code(204).build())
        client.ack("01A")
        val r4 = server.takeRequest()
        verifySigned(r4)
        assertEquals("DELETE", r4.method)
        assertEquals("/v1/mailbox/01A", r4.url.encodedPath)
    }

    @Test
    fun retriesFiveHundredsResigningEachAttempt() = runBlocking<Unit> {
        server.enqueue(json(503, """{"code":"internal","message":"x"}"""))
        server.enqueue(json(201, """{"msg_id":"01M"}"""))
        assertEquals("01M", client.deposit("m", "t", ByteArray(1)))
        val a = server.takeRequest()
        val b = server.takeRequest()
        verifySigned(a)
        verifySigned(b)
        assertEquals(1, sleeps.size)
        assertTrue(sleeps[0] in 250L..500L)
    }

    @Test
    fun honoursRetryAfter() = runBlocking<Unit> {
        server.enqueue(json(429, """{"code":"rate_limited","message":"slow","retry_after":3}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        client.ack("x")
        assertTrue(sleeps.single() in 3000L until 3250L)
    }

    @Test
    fun clientErrorsAreNotRetried() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"token_expired","message":"expired"}"""))
        val e = assertThrows(RelayException::class.java) { runBlocking { client.deposit("m", "t", ByteArray(1)) } }
        assertEquals(RelayException.TOKEN_EXPIRED, e.code)
        assertEquals(401, e.status)
        assertFalse(e.retryable)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun errorWithoutCodeBody() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(404).body("not here").build())
        val e = assertThrows(RelayException::class.java) { runBlocking { client.ack("x") } }
        assertEquals("http_404", e.code)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runBlocking<Unit> {
        repeat(3) { server.enqueue(json(500, """{"code":"internal","message":""}""")) }
        val e = assertThrows(RelayException::class.java) { runBlocking { client.ack("x") } }
        assertEquals("internal", e.code)
        assertEquals(3, server.requestCount)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun claimGetIsUnsignedAndNeverRetried() = runBlocking<Unit> {
        server.enqueue(json(500, """{"code":"internal","message":""}"""))
        assertThrows(RelayException::class.java) { runBlocking { client.getClaim("abc") } }
        assertEquals(1, server.requestCount)
        val r = server.takeRequest()
        assertNull(r.headers[RelayAuth.HEADER_SIG])
        assertEquals("/v1/claim/abc", r.url.encodedPath)
    }

    @Test
    fun claimTtlTravelsInTheSignedPath() = runBlocking<Unit> {
        server.enqueue(json(201, """{"claim_id":"c1","expires_at":"2026-10-04T00:15:00Z"}"""))
        val ref = client.putClaim(byteArrayOf(9), Duration.ofSeconds(3600))
        assertEquals("c1", ref.id)
        assertEquals(Instant.parse("2026-10-04T00:15:00Z"), ref.expiresAt)
        val r = server.takeRequest()
        verifySigned(r)
        assertEquals("/v1/claim/ttl/3600", r.url.encodedPath)
        assertEquals("application/octet-stream", r.headers["Content-Type"])
    }

    @Test
    fun blobsDenylistRotateAndDelete() = runBlocking<Unit> {
        server.enqueue(json(201, """{"blob_id":"b1","expires_at":"2026-10-11T00:00:00Z"}"""))
        assertEquals("b1", client.putBlob("mbx", "tok", ByteArray(100)).id)
        verifySigned(server.takeRequest())
        server.enqueue(MockResponse.Builder().code(200).body("raw").build())
        assertEquals("raw", String(client.getBlob("b1")))
        verifySigned(server.takeRequest())
        server.enqueue(MockResponse.Builder().code(204).build())
        client.revoke(Revocation.jti("j1"), Revocation.sub(VectorsTest.SENDER_PUB))
        val dr = server.takeRequest()
        verifySigned(dr)
        assertEquals("""{"revoke":[{"kind":"jti","value":"j1"},{"kind":"sub","value":"${VectorsTest.SENDER_PUB}"}]}""", dr.body!!.utf8())
        val next = Ed25519PrivateKey.generate()
        server.enqueue(json(200, """{"mailbox_id":"newbox"}"""))
        assertEquals("newbox", client.rotate(next))
        val rr = server.takeRequest()
        verifySigned(rr)
        val o = StrictJson.parseObject(rr.body!!.toByteArray())
        assertTrue(Ed25519.verifyRaw(next.publicKey, client.mailboxId.toByteArray(), o.base64("new_key_proof")))
        server.enqueue(MockResponse.Builder().code(204).build())
        client.deleteMailbox()
        val del = server.takeRequest()
        verifySigned(del)
        assertEquals("DELETE", del.method)
        assertEquals("/v1/mailbox", del.url.encodedPath)
        assertEquals(0L, del.bodySize)
    }

    @Test
    fun twoRequestsInOneSecondCarryDifferentSignatures() = runBlocking<Unit> {
        repeat(2) { server.enqueue(json(200, """{"messages":[]}""")) }
        client.collect(Duration.ZERO)
        Thread.sleep(3)
        client.collect(Duration.ZERO)
        val a = server.takeRequest()
        val b = server.takeRequest()
        assertNotEquals(a.headers[RelayAuth.HEADER_SIG], b.headers[RelayAuth.HEADER_SIG])
    }

    @Test
    fun webSocketCollectAndAck() = runBlocking<Unit> {
        val acks = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"msg_id":"01W","deposited_at":"2026-10-04T00:00:00Z","sender":"${VectorsTest.SENDER_PUB}","jti":"j","payload":"AQ=="}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        acks.add(text)
                        webSocket.close(4404, "mailbox_unknown")
                    }
                },
            ).build(),
        )
        val got = mutableListOf<String>()
        val collector = MailboxCollector(client, MailboxCollector.Mode.WEBSOCKET, sleep = { })
        val e = assertThrows(RelayException::class.java) { runBlocking { collector.run { got.add(it.msgId) } } }
        assertEquals(RelayException.MAILBOX_UNKNOWN, e.code)
        assertEquals(listOf("01W"), got)
        assertEquals("""{"ack":"01W"}""", acks.poll(5, TimeUnit.SECONDS))
        verifySigned(server.takeRequest())
    }

    @Test
    fun longPollCollectorHandlesThenAcks() = runBlocking<Unit> {
        server.enqueue(json(200, """{"messages":[{"msg_id":"01P","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"AQ=="}]}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        val order = mutableListOf<String>()
        val n = MailboxCollector(client).pollOnce { order.add("handle ${it.msgId}") }
        assertEquals(1, n)
        assertEquals("GET", server.takeRequest().method)
        val ack = server.takeRequest()
        assertEquals("DELETE", ack.method)
        assertEquals(listOf("handle 01P"), order)
    }

    @Test
    fun collectorStopsOnTerminalErrorsAndBacksOffOnTransient() = runBlocking<Unit> {
        server.enqueue(json(503, """{"code":"internal","message":""}"""))
        server.enqueue(json(503, """{"code":"internal","message":""}"""))
        server.enqueue(json(503, """{"code":"internal","message":""}"""))
        server.enqueue(json(404, """{"code":"mailbox_unknown","message":"gone"}"""))
        val waits = mutableListOf<Long>()
        val c = RelayClient(server.url("/").toString(), key, OkHttpClient(), maxAttempts = 1)
        val e = assertThrows(RelayException::class.java) { runBlocking { MailboxCollector(c, sleep = { waits.add(it) }).run { } } }
        assertEquals(RelayException.MAILBOX_UNKNOWN, e.code)
        assertEquals(3, waits.size)
        assertTrue(waits.all { it <= 30_000 })
    }

    /** A stale or replayed signature (§4.1) is retried, signed afresh; the relay applied nothing. */
    @Test
    fun aStaleSignatureIsRetriedWithAFreshOne() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"timestamp_stale","message":"stale"}"""))
        server.enqueue(json(401, """{"code":"replay_detected","message":""}"""))
        server.enqueue(json(201, """{"msg_id":"01M"}"""))
        assertEquals("01M", client.deposit("mbx", "tok", ByteArray(4)))
        val sigs = (1..3).map { server.takeRequest().also { r -> verifySigned(r) }.headers[RelayAuth.HEADER_SIG] }
        assertEquals(3, sigs.toSet().size)
        assertTrue(RelayException(401, RelayException.TIMESTAMP_STALE, "").retryable)
        assertTrue(RelayException(401, RelayException.REPLAY_DETECTED, "").staleSignature)
        assertFalse(RelayException(401, RelayException.SIGNATURE_INVALID, "").retryable)
        assertFalse(RelayException(403, RelayException.TOKEN_REVOKED, "").retryable)
    }

    /** The collector backs off and goes on after `timestamp_stale` (a request signed before the phone froze). */
    @Test
    fun theCollectorGoesOnAfterAStaleSignature() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"timestamp_stale","message":""}"""))
        server.enqueue(json(200, """{"messages":[{"msg_id":"01S","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"AQ=="}]}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(404, """{"code":"mailbox_unknown","message":""}"""))
        val waits = mutableListOf<Long>()
        val got = mutableListOf<String>()
        val c = RelayClient(server.url("/").toString(), key, OkHttpClient(), maxAttempts = 1)
        val e = assertThrows(RelayException::class.java) { runBlocking { MailboxCollector(c, sleep = { waits.add(it) }).run { got.add(it.msgId) } } }
        assertEquals(RelayException.MAILBOX_UNKNOWN, e.code)
        assertEquals(listOf("01S"), got)
        assertEquals(1, waits.size)
    }

    /** A message the handler leaves for redelivery ([LeaveUnacked]) is not acked; the next one is. */
    @Test
    fun aMessageLeftForRedeliveryIsNotAcked() = runBlocking<Unit> {
        server.enqueue(
            json(
                200,
                """{"messages":[{"msg_id":"01E","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"AQ=="},""" +
                    """{"msg_id":"01F","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"AQ=="}]}""",
            ),
        )
        server.enqueue(MockResponse.Builder().code(204).build())
        val n = MailboxCollector(client).pollOnce { if (it.msgId == "01E") throw LeaveUnacked("early") }
        assertEquals(2, n)
        assertEquals("GET", server.takeRequest().method)
        val ack = server.takeRequest()
        assertEquals("/v1/mailbox/01F", ack.url.encodedPath)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}
