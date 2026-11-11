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
    fun aRetryAfterOverTheCapGivesUpAtOnce() = runBlocking<Unit> {
        server.enqueue(json(429, """{"code":"rate_limited","message":"slow","retry_after":3600}"""))
        val e = assertThrows(RelayException::class.java) { runBlocking { client.deposit("m", "t", ByteArray(1)) } }
        assertEquals(RelayException.RATE_LIMITED, e.code)
        assertEquals(3600, e.retryAfterSeconds)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aRetryAfterHeaderOverTheCapGivesUpAtOnce() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "86400").body("busy").build())
        val e = assertThrows(RelayException::class.java) { runBlocking { client.ack("x") } }
        assertEquals(86400, e.retryAfterSeconds)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aRetryAfterAtTheCapIsWaited() = runBlocking<Unit> {
        server.enqueue(json(429, """{"code":"rate_limited","message":"slow","retry_after":${RelayClient.MAX_RETRY_AFTER_S}}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        client.ack("x")
        assertTrue(sleeps.single() in RelayClient.MAX_RETRY_AFTER_S * 1000L until RelayClient.MAX_RETRY_AFTER_S * 1000L + 250L)
    }

    private fun bounded(callMs: Long, attempts: Int = 1, blobMs: Long = RelayClient.BLOB_CALL_TIMEOUT_MS) = RelayClient(
        server.url("/").toString(), key, OkHttpClient(), maxAttempts = attempts, sleep = { sleeps.add(it) },
        limits = RelayClient.CallLimits(callMs = callMs, blobMs = blobMs),
    )

    @Test
    fun aBlobUploadThatTimedOutIsNotSentAgain() = runBlocking<Unit> {
        repeat(3) { server.enqueue(json(201, """{"blob_id":"b1"}""").newBuilder().headersDelay(2, TimeUnit.SECONDS).build()) }
        val c = bounded(callMs = 60_000, attempts = 3, blobMs = 300)
        assertThrows(java.io.IOException::class.java) { runBlocking { c.putBlob("mbx", "tok", ByteArray(100)) } }
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aBlobUploadAnswered5xxIsNotSentAgainButA429Is() = runBlocking<Unit> {
        server.enqueue(json(503, """{"code":"internal","message":""}"""))
        val e = assertThrows(RelayException::class.java) { runBlocking { client.putBlob("mbx", "tok", ByteArray(100)) } }
        assertEquals(503, e.status)
        assertEquals(1, server.requestCount)
        server.enqueue(json(429, """{"code":"rate_limited","message":"","retry_after":1}"""))
        server.enqueue(json(201, """{"blob_id":"b2"}"""))
        assertEquals("b2", client.putBlob("mbx", "tok", ByteArray(100)).id)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aLongPollIsNotCutByTheCallTimeout() = runBlocking<Unit> {
        val body = """{"messages":[{"msg_id":"01L","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"${"A".repeat(4096)}"}]}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).throttleBody(1024, 200, TimeUnit.MILLISECONDS).build())
        val msgs = bounded(callMs = 300).collect(wait = Duration.ZERO)
        assertEquals("01L", msgs.single().msgId)
    }

    /** (#336 review, item 1) The relay answered: not a refusal (a collect's batch may be leased), a lost attempt. */
    @Test
    fun anAnswerOverItsBoundIsALostAttempt() = runBlocking<Unit> {
        server.enqueue(json(200, """{"msg_id":"${"m".repeat((1 shl 20) + 1)}"}"""))
        assertThrows(RelayClient.AnswerTooLarge::class.java) { runBlocking { bounded(callMs = 60_000).deposit("m", "t", ByteArray(1)) } }
        assertEquals(1, server.requestCount)
        var lost = 0
        server.enqueue(json(200, """{"messages":[],"x":"${"a".repeat(40 shl 20)}"}"""))
        server.enqueue(json(200, """{"messages":[]}"""))
        assertTrue(client.collect(wait = Duration.ZERO, onLostAttempt = { lost++ }).isEmpty())
        assertEquals(1, lost)
    }

    /** (#336 re-check) register()'s answer is read with the bound too. */
    @Test
    fun aRegisterAnswerOverItsBoundIsALostAttempt() = runBlocking<Unit> {
        server.enqueue(json(201, """{"mailbox_id":"${"m".repeat((1 shl 20) + 1)}"}"""))
        assertThrows(RelayClient.AnswerTooLarge::class.java) { runBlocking { bounded(callMs = 60_000).register() } }
    }

    /** (#336 re-check) A negative `Retry-After` header is no wait either. */
    @Test
    fun aNegativeRetryAfterHeaderIsNoWait() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "-7").body("busy").build())
        val e = assertThrows(RelayException::class.java) { runBlocking { bounded(callMs = 60_000).ack("x") } }
        assertEquals(0, e.retryAfterSeconds)
    }

    /** A collect's batch over the 1 MiB of other answers is read (a 1 MiB bound on a collect would fail this). */
    @Test
    fun aLargeCollectIsRead() = runBlocking<Unit> {
        val payload = "A".repeat(256 * 1024)
        val one = """{"msg_id":"01L","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"$payload"}"""
        server.enqueue(json(200, """{"messages":[${List(8) { one }.joinToString(",")}]}"""))
        assertEquals(8, client.collect(wait = Duration.ZERO).size)
    }

    @Test
    fun aNegativeRetryAfterIsNoWait() = runBlocking<Unit> {
        server.enqueue(json(429, """{"code":"rate_limited","message":"slow","retry_after":-5}"""))
        val e = assertThrows(RelayException::class.java) { runBlocking { bounded(callMs = 60_000).ack("x") } }
        assertEquals(0, e.retryAfterSeconds)
        server.enqueue(json(429, """{"code":"rate_limited","message":"slow","retry_after":-5}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        client.ack("x")
        assertTrue(sleeps.single() < 1000L)
    }

    /**
     * (GC #6847, the review of 4de9019c, High 1) A request with a whole deadline: its retries and their waits never run past
     * it. A retry whose wait would leave no time for the attempt is not made, and the last refusal is thrown at once.
     */
    @Test
    fun aDeadlineBoundsTheRetriesAndTheirWaits() = runBlocking<Unit> {
        var now = 0L
        val timed = RelayClient(server.url("/").toString(), key, OkHttpClient(), sleep = { sleeps.add(it); now += it }, elapsedMs = { now })
        // A retry_after of 2 s with 1.5 s left: not waited for.
        server.enqueue(json(429, """{"code":"rate_limited","message":"","retry_after":2}"""))
        val e = assertThrows(RelayException::class.java) {
            runBlocking { timed.collect(java.time.Duration.ZERO, deadline = java.time.Duration.ofMillis(1_500)) }
        }
        assertEquals(RelayException.RATE_LIMITED, e.code)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
        // With room for it: retried, and answered.
        server.enqueue(json(429, """{"code":"rate_limited","message":"","retry_after":2}"""))
        server.enqueue(json(200, """{"messages":[]}"""))
        assertEquals(0, timed.collect(java.time.Duration.ZERO, deadline = java.time.Duration.ofSeconds(10)).size)
        assertEquals(3, server.requestCount)
        assertEquals(1, sleeps.size)
        // (The re-check of f97eb7e0, Low 1) A retry left with under 3 s for its attempt is not made: the refusal is the answer.
        server.enqueue(json(429, """{"code":"rate_limited","message":""}"""))
        val late = assertThrows(RelayException::class.java) {
            runBlocking { timed.collect(java.time.Duration.ZERO, deadline = java.time.Duration.ofMillis(RelayClient.MIN_ATTEMPT_MS)) }
        }
        assertEquals(RelayException.RATE_LIMITED, late.code)
        assertEquals(4, server.requestCount)
        assertEquals(1, sleeps.size)
    }

    /** A client whose clock moves only by its sleeps (plus [overslept] each), and whose jitter is 0. */
    private fun timedClient(overslept: Long = 0): Pair<RelayClient, () -> Long> {
        var now = 0L
        val noJitter = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        val c = RelayClient(
            server.url("/").toString(), key, OkHttpClient(), sleep = { sleeps.add(it); now += it + overslept }, random = noJitter,
            elapsedMs = { now },
        )
        return c to { now }
    }

    private fun refusedOnce(retryAfterS: Int = 1) {
        server.enqueue(json(429, """{"code":"rate_limited","message":"","retry_after":$retryAfterS}"""))
    }

    /**
     * (#345's blind reviews, item 4) The floor's boundary: a retry_after of 1 s leaves the retry max(wait, 3 s) to the
     * millisecond. One more is enough, exactly the floor is not; a long poll's own wait is the floor when longer.
     */
    @Test
    fun theRetryFloorToTheMillisecond() = runBlocking<Unit> {
        val (c, _) = timedClient()
        val floor = RelayClient.MIN_ATTEMPT_MS
        for ((deadline, retried) in listOf(1_000 + floor + 1 to true, 1_000 + floor to false, 1_000 + floor - 1 to false)) {
            val before = server.requestCount
            refusedOnce()
            if (retried) server.enqueue(json(200, """{"messages":[]}"""))
            val r = runCatching { c.collect(java.time.Duration.ZERO, deadline = java.time.Duration.ofMillis(deadline)) }
            assertEquals("$deadline", retried, r.isSuccess)
            assertEquals("$deadline", if (retried) 2 else 1, server.requestCount - before)
        }
        // A long poll's wait (5 s, over the floor) is the least its retry needs.
        val w = 5_000L
        for ((deadline, retried) in listOf(1_000 + w + 1 to true, 1_000 + w to false)) {
            val before = server.requestCount
            refusedOnce()
            if (retried) server.enqueue(json(200, """{"messages":[]}"""))
            val r = runCatching { c.collect(java.time.Duration.ofMillis(w), deadline = java.time.Duration.ofMillis(deadline)) }
            assertEquals("poll $deadline", retried, r.isSuccess)
            assertEquals("poll $deadline", if (retried) 2 else 1, server.requestCount - before)
        }
    }

    /** (#345, item 3) A sleep that ran long (a frozen process): the retry is not made with too little left. */
    @Test
    fun anOversleptRetryIsNotMade() = runBlocking<Unit> {
        val (c, _) = timedClient(overslept = 5_000)
        refusedOnce()
        val e = assertThrows(RelayException::class.java) {
            runBlocking { c.collect(java.time.Duration.ZERO, deadline = java.time.Duration.ofSeconds(8)) }
        }
        assertEquals(RelayException.RATE_LIMITED, e.code)
        assertEquals("slept, then saw the time gone", 1, sleeps.size)
        assertEquals(1, server.requestCount)
    }

    /** (#336 review, item 3; RELAY-PROTOCOL §7.1) A 429 `quota_exceeded` is never retried unchanged. */
    @Test
    fun aQuotaRefusalIsNotRetried() = runBlocking<Unit> {
        server.enqueue(json(429, """{"code":"quota_exceeded","message":"","quota":"token","retry_after":1}"""))
        val e = assertThrows(RelayException::class.java) { runBlocking { client.deposit("m", "t", ByteArray(1)) } }
        assertEquals(RelayException.QUOTA_EXCEEDED, e.code)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
        // (GC #6847, #336's Opus L3) Not retryable, so no caller keeps it to resend unchanged; what it used up is read.
        assertFalse(e.retryable)
        assertEquals(RelayException.QUOTA_TOKEN, e.quota)
        assertTrue("an ordinary 429 still is", RelayException(429, RelayException.RATE_LIMITED, "").retryable)
        assertNull("only a quota refusal carries it", RelayException(429, RelayException.RATE_LIMITED, "").quota)
    }

    /**
     * (#336 review, item 2) A sent blob upload answered `replay_detected` (OkHttp may have resent it on a failed
     * connection, and the relay stored the first) is not signed afresh and sent again; another request is.
     */
    @Test
    fun aSentBlobUploadAnsweredReplayDetectedIsNotSentAgain() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"replay_detected","message":""}"""))
        val e = assertThrows(RelayException::class.java) { runBlocking { client.putBlob("mbx", "tok", ByteArray(100)) } }
        assertEquals(RelayException.REPLAY_DETECTED, e.code)
        assertEquals(1, server.requestCount)
        server.enqueue(json(401, """{"code":"replay_detected","message":""}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        client.ack("x")
        assertEquals(3, server.requestCount)
    }

    /** (#336 review, item 4) A long-poll given its own call timeout ends a trickling body at it. */
    @Test
    fun aLongPollWithACallTimeoutEndsATricklingBody() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(65536)).throttleBody(1024, 100, TimeUnit.MILLISECONDS).build())
        val t0 = System.nanoTime()
        assertThrows(java.io.IOException::class.java) {
            runBlocking { bounded(callMs = 60_000).collect(wait = Duration.ZERO, callTimeout = Duration.ofMillis(500)) }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("ended after $ms ms", ms < 3000)
    }

    @Test
    fun aClaimWhoseBodyTricklesEndsAtTheCallTimeout() = runBlocking<Unit> {
        // 64 KiB at 1 KiB per 100 ms: about 6 s, the read timeout never trips (a byte keeps coming).
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(65536)).throttleBody(1024, 100, TimeUnit.MILLISECONDS).build())
        val c = bounded(callMs = 500)
        val t0 = System.nanoTime()
        assertThrows(java.io.IOException::class.java) { runBlocking { c.getClaim("c1") } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("ended after $ms ms", ms < 3000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aDepositWhoseAnswerNeverComesEndsAtTheCallTimeoutPerAttempt() = runBlocking<Unit> {
        repeat(3) { server.enqueue(json(200, """{"msg_id":"m1"}""").newBuilder().headersDelay(2, TimeUnit.SECONDS).build()) }
        val c = bounded(callMs = 300, attempts = 3)
        val t0 = System.nanoTime()
        assertThrows(java.io.IOException::class.java) { runBlocking { c.deposit("m", "t", ByteArray(1)) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("ended after $ms ms", ms < 5000)
        assertEquals(2, sleeps.size)
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
    fun aBlobLargerThanTheBoundIsRefused() = runBlocking<Unit> {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(okio.Buffer().write(ByteArray(100))).build())
        val e = assertThrows(RelayException::class.java) { runBlocking { client.getBlob("b1", maxBytes = 50) } }
        assertEquals(RelayException.PAYLOAD_TOO_LARGE, e.code)
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(okio.Buffer().write(ByteArray(50))).build())
        assertEquals(50, client.getBlob("b2", maxBytes = 50).size)
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

    /**
     * The collector says when a collect request is out and when it ended (a push wake waits for a request that has
     * been out long enough to have found the mailbox empty): around every round, also a failed one, and not during
     * the backoff after it.
     */
    @Test
    fun theCollectorSaysWhenARequestIsOut() = runBlocking<Unit> {
        server.enqueue(json(503, """{"code":"internal","message":""}"""))
        server.enqueue(json(200, """{"messages":[{"msg_id":"01S","deposited_at":"x","sender":"${VectorsTest.SENDER_PUB}","payload":"AQ=="}]}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(404, """{"code":"mailbox_unknown","message":""}"""))
        val log = mutableListOf<String>()
        val c = RelayClient(server.url("/").toString(), key, OkHttpClient(), maxAttempts = 1)
        val collector = MailboxCollector(c, sleep = { log.add("backoff") }, onRequest = { out -> log.add(if (out) "out" else "ended") })
        assertThrows(RelayException::class.java) { runBlocking { collector.run { log.add("handle ${it.msgId}") } } }
        assertEquals(listOf("out", "ended", "backoff", "out", "handle 01S", "ended", "out", "ended"), log)
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
