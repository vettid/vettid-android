package com.vettid.core.relay

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The first request after Doze (VettID "cannot connect" until "Try again"): transport failures are retried before
 * they reach the screen, HTTP statuses never are, and a request that may have reached the server is resent only
 * when that is safe.
 */
class TransportRetryTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()

    /** Fails the first [failures] lookups as a phone whose network is still blocked after Doze does. */
    private inner class WakingDns(private val failures: Int = 0) : Dns {
        val lookups = AtomicInteger()

        override fun lookup(hostname: String): List<InetAddress> {
            if (lookups.incrementAndGet() <= failures) throw UnknownHostException("Unable to resolve host \"$hostname\"")
            return listOf(server.socketAddress.address)
        }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun client(dns: Dns = WakingDns(), gate: NetworkGate = NetworkGate.ALWAYS) = OkHttpClient.Builder()
        .addInterceptor(TransportRetry(gate, sleep = { sleeps.add(it) }))
        .retryOnConnectionFailure(false) // OkHttp's own recovery off: only TransportRetry acts
        .dns(dns)
        .build()

    /** A host name for the server, so the [Dns] is consulted. */
    private fun url(path: String = "/") = server.url(path).newBuilder().host("localhost").build()

    private fun ok(body: String = """{"ok":true}""") = MockResponse.Builder().code(200).body(body).build()

    /** The server reads the request, then drops the connection without an answer (a connection that died in Doze). */
    private fun dropped() = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

    private fun get(c: OkHttpClient) = c.newCall(Request.Builder().url(url()).build()).execute().use { it.code to it.body.string() }

    private fun post(c: OkHttpClient) =
        c.newCall(Request.Builder().url(url()).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { it.code }

    @Test
    fun aGetWhoseConnectionDroppedIsRetriedOnceAndSucceeds() {
        server.enqueue(dropped())
        server.enqueue(ok())
        assertEquals(200 to """{"ok":true}""", get(client()))
        assertEquals(2, server.requestCount)
        assertEquals(TransportRetry.DEFAULT_BACKOFF_MS.first(), sleeps.sum())
    }

    @Test
    fun aLookupThatFailsWhileTheNetworkWakesIsRetriedForAnyMethod() {
        val dns = WakingDns(failures = 1)
        server.enqueue(ok())
        assertEquals(200, post(client(dns)))
        assertEquals(1, server.requestCount)
        assertEquals(2, dns.lookups.get())
    }

    @Test
    fun aPersistentFailureSurfacesAfterTheRetries() {
        val dns = WakingDns(failures = Int.MAX_VALUE)
        assertThrows(UnknownHostException::class.java) { get(client(dns)) }
        assertEquals(1 + TransportRetry.DEFAULT_RETRIES, dns.lookups.get())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPostThatMayHaveReachedTheServerIsNotResent() {
        server.enqueue(dropped())
        server.enqueue(ok())
        assertThrows(IOException::class.java) { post(client()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anHttpErrorIsNeverRetried() {
        server.enqueue(MockResponse.Builder().code(403).body("""{"code":"token_revoked"}""").build())
        assertEquals(403 to """{"code":"token_revoked"}""", get(client()))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        assertEquals(401, post(client()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun itWaitsForAUsableNetworkBeforeTheFirstAttempt() {
        val checks = AtomicInteger()
        val gate = NetworkGate { checks.incrementAndGet() > 3 } // blocked for three looks, then lifted
        server.enqueue(ok())
        assertEquals(200, get(client(gate = gate)).first)
        assertEquals(listOf(100L, 100L, 100L), sleeps)
    }

    @Test
    fun aNetworkThatNeverComesStillGetsItsAttemptsWithinTheBudget() {
        server.enqueue(ok())
        assertEquals(200, get(client(gate = { false })).first)
        assertEquals(TransportRetry.DEFAULT_NETWORK_WAIT_MS, sleeps.sum())
    }

    @Test
    fun theDecision() {
        val p = TransportRetry.Policy.entries
        // nothing went out: anything but NEVER
        assertTrue(TransportRetry.retryable(UnknownHostException(), TransportRetry.Policy.UNSENT_ONLY, sent = false))
        assertTrue(TransportRetry.retryable(SocketTimeoutException("connect timed out"), TransportRetry.Policy.IDEMPOTENT, sent = false))
        assertFalse(TransportRetry.retryable(UnknownHostException(), TransportRetry.Policy.NEVER, sent = false))
        // sent: only an idempotent request, and not after a read timeout
        assertTrue(TransportRetry.retryable(IOException("unexpected end of stream"), TransportRetry.Policy.IDEMPOTENT, sent = true))
        assertFalse(TransportRetry.retryable(IOException("unexpected end of stream"), TransportRetry.Policy.UNSENT_ONLY, sent = true))
        assertFalse(TransportRetry.retryable(SocketTimeoutException("timeout"), TransportRetry.Policy.IDEMPOTENT, sent = true))
        // never: a certificate that does not match, a protocol error
        p.forEach { assertFalse(TransportRetry.retryable(javax.net.ssl.SSLPeerUnverifiedException("pin"), it, sent = false)) }
        p.forEach { assertFalse(TransportRetry.retryable(java.net.ProtocolException("bad"), it, sent = false)) }
        assertEquals(TransportRetry.Policy.IDEMPOTENT, TransportRetry.policyOf(Request.Builder().url(url()).build()))
        assertEquals(TransportRetry.Policy.UNSENT_ONLY, TransportRetry.policyOf(Request.Builder().url(url()).delete().build()))
    }

    // --- the relay client: signed requests are accepted once (RELAY-PROTOCOL §4.1) ---

    private fun relay(dns: Dns = WakingDns()) = RelayClient(url().toString(), Ed25519PrivateKey.generate(), client(dns), maxAttempts = 1)

    @Test
    fun aSignedRelayRequestIsResentByTheInterceptorOnlyIfItNeverWentOut() = runBlocking<Unit> {
        // dropped after the relay read it: the interceptor does not resend the same signature (the relay's own loop
        // re-signs, here switched off with maxAttempts = 1)
        server.enqueue(dropped())
        assertThrows(IOException::class.java) { runBlocking { relay().ack("01J0000000000000000000000A") } }
        assertEquals(1, server.requestCount)
        // a lookup that failed: nothing went out, so the same request is sent once the network is up
        server.enqueue(MockResponse.Builder().code(204).build())
        relay(WakingDns(failures = 1)).ack("01J0000000000000000000000A")
        assertEquals(2, server.requestCount)
    }

    /** A clock that the interceptor's waits move forward, as time passes for a phone frozen in the background. */
    private class FrozenPhoneClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    /**
     * Pixel 10, 2026-10-07: the app's network is blocked ~5 s after it leaves the screen and the process freezes
     * soon after; a relay request signed before the wait reached the relay minutes later (`timestamp_stale`). The
     * interceptor signs each attempt right before it goes out, after the wait.
     */
    @Test
    fun aSignedRelayRequestIsSignedAgainAfterWaitingForTheNetwork() = runBlocking<Unit> {
        val start = Instant.parse("2026-10-07T10:00:00Z")
        val clock = FrozenPhoneClock(start)
        val checks = AtomicInteger()
        val gate = NetworkGate { checks.incrementAndGet() > 3 } // blocked for three looks
        val http = OkHttpClient.Builder()
            .addInterceptor(TransportRetry(gate, sleep = { clock.now = clock.now.plusSeconds(60) })) // each look: a minute frozen
            .dns(WakingDns())
            .build()
        val key = Ed25519PrivateKey.generate()
        server.enqueue(MockResponse.Builder().code(204).build())
        RelayClient(url().toString(), key, http, clock = clock, maxAttempts = 1).ack("01J0000000000000000000000A")
        val r = server.takeRequest()
        val ts = r.headers[RelayAuth.HEADER_TIMESTAMP]!!
        assertEquals(RelayAuth.timestamp(start.plusSeconds(180)), ts) // signed after the wait, not before it
        val d = RelayAuth.digest("DELETE", r.url.encodedPath, ts, RelayAuth.bodyHash(null))
        assertTrue(Ed25519.verifyRaw(key.publicKey, d, Base64s.decodeStd(r.headers[RelayAuth.HEADER_SIG]!!)))
    }

    @Test
    fun theSignerReplacesOnlyTheSignatureHeaders() {
        val req = Request.Builder().url(url()).header("Authorization", "VettID-Deposit t").header(RelayAuth.HEADER_SIG, "old")
            .tag(RequestSigner::class.java, RequestSigner { mapOf(RelayAuth.HEADER_SIG to "new") }).build()
        val again = RequestSigner.resign(req)
        assertEquals("new", again.header(RelayAuth.HEADER_SIG))
        assertEquals("VettID-Deposit t", again.header("Authorization"))
        val plain = Request.Builder().url(url()).build()
        assertTrue(RequestSigner.resign(plain) === plain)
    }

    @Test
    fun aRelayClaimFetchIsNeverRetried() {
        val dns = WakingDns(failures = 1)
        assertThrows(UnknownHostException::class.java) { runBlocking { relay(dns).getClaim("c1") } }
        assertEquals(1, dns.lookups.get())
    }

    @Test
    fun aRevokedTokenIsTheRelaysAnswerNotRetried() {
        server.enqueue(MockResponse.Builder().code(403).body("""{"code":"token_revoked","message":"revoked"}""").build())
        val e = assertThrows(RelayException::class.java) { runBlocking { relay().deposit("mbx", "tok", ByteArray(4)) } }
        assertEquals(RelayException.TOKEN_REVOKED, e.code)
        assertEquals(1, server.requestCount)
    }
}
