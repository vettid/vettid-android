package com.vettid.core.altchan

import com.vettid.core.relay.TransportRetry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A6 audit N4 and N6, at the member API client.
 *
 * N4: a signed request carries a timestamp and a single-use nonce (MEMBER-API "App request signing": a nonce seen
 * twice within 600 s is `401`, and a `401` has no detail). A GET re-sent after a dropped connection used to carry the
 * same header, was answered `401`, and the unlock screen read that as "this phone was replaced" and offered to erase
 * it. Every attempt is now signed anew, a `401` is asked once more with a new signature, and a `401` that the phone's
 * clock explains is reported as that.
 *
 * N6: only the API's explicit `"vault": null` is "no vault"; an empty or malformed status is a failed read.
 */
class MemberApiFreshSignatureTest {
    private lateinit var server: MockWebServer
    private val key = MemberApiTest.SoftAppKey()
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private val status = """{"vault":{"vault_id":"v1","state":"locked"},"service":"available"}"""

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun api(http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()) =
        MemberApiClient(server.url("/").toString(), "", http, MemberAuth.AppKey(key) { "v1" }, sleep = { }, now = { now.toEpochMilli() })

    private fun resp(code: Int, body: String, date: Instant? = now): MockResponse = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", "application/json")
        .apply { if (date != null) addHeader("Date", HTTP_DATE.format(date)) }
        .build()

    private fun unauthorized(date: Instant? = now) = resp(401, """{"error":"unauthorized","message":"unauthorized"}""", date)

    /** The `nonce` and `ts` of the next request the server received. */
    private fun signature(): Pair<String, String> {
        val h = server.takeRequest().headers[AppRequestSigning.HEADER]!!
        val f = h.split("; ").associate { it.substringBefore('=') to it.substringAfter('=') }
        return f["nonce"]!! to f["sig"]!!
    }

    @Test
    fun aRequestReSentAfterADroppedConnectionIsSignedAnew() = runBlocking {
        // The app's client: the transport retry re-sends a GET whose connection dropped after the request went out.
        val http = OkHttpClient.Builder().addInterceptor(TransportRetry(sleep = {})).retryOnConnectionFailure(false).build()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(resp(200, status))
        assertEquals("v1", api(http).vaultStatusAnswer().vault?.vaultId)
        assertEquals(2, server.requestCount)
        val (nonce1, sig1) = signature()
        val (nonce2, sig2) = signature()
        assertNotEquals("the API has seen the first nonce: the retry carries another", nonce1, nonce2)
        assertNotEquals(sig1, sig2)
    }

    @Test
    fun aSingle401IsAskedOnceMoreWithANewSignatureAndIsNotReported() = runBlocking {
        server.enqueue(unauthorized())
        server.enqueue(resp(200, status))
        assertEquals("v1", api().vaultStatusAnswer().vault?.vaultId)
        val (nonce1, _) = signature()
        val (nonce2, _) = signature()
        assertNotEquals(nonce1, nonce2)
    }

    @Test
    fun a401ToTheFreshlySignedRequestIsUnauthorized() {
        server.enqueue(unauthorized())
        server.enqueue(unauthorized())
        val e = assertThrows(MemberApiException::class.java) { runBlocking { api().vaultStatusAnswer() } }
        assertEquals(401, e.status)
        assertEquals(MemberApiException.UNAUTHORIZED, e.code)
        assertEquals("asked twice, no more", 2, server.requestCount)
    }

    @Test
    fun a401WhileThePhonesClockIsOffIsReportedAsTheClock() {
        listOf(now.plusSeconds(600), now.minusSeconds(3600)).forEach { serverTime ->
            server.enqueue(unauthorized(serverTime))
            server.enqueue(unauthorized(serverTime))
            val e = assertThrows(MemberApiException::class.java) { runBlocking { api().vaultStatusAnswer() } }
            assertEquals(401, e.status)
            assertEquals(MemberApiException.CLOCK_SKEW, e.code)
        }
        // Within the API's window, or without a Date to compare with, it stays the API's answer.
        listOf(now.plusSeconds(120), null).forEach { serverTime ->
            server.enqueue(unauthorized(serverTime))
            server.enqueue(unauthorized(serverTime))
            val e = assertThrows(MemberApiException::class.java) { runBlocking { api().vaultStatusAnswer() } }
            assertEquals(MemberApiException.UNAUTHORIZED, e.code)
        }
    }

    @Test
    fun otherRefusalsAreNotAskedAgain() {
        server.enqueue(resp(403, """{"error":"forbidden","message":"no"}"""))
        assertThrows(MemberApiException::class.java) { runBlocking { api().vaultStatusAnswer() } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun onlyAnExplicitNullIsNoVault() = runBlocking {
        server.enqueue(resp(200, """{"vault":null,"service":"available"}"""))
        assertNull(api().vaultStatusAnswer().vault)
        // An empty body, a body without `vault`, and anything that is neither a vault nor null: a failed read.
        listOf("", "{}", """{"service":"available"}""", """{"vault":"none"}""", """{"vault":[]}""", """{"vault":{}}""", "<html></html>")
            .forEach { body ->
                server.enqueue(resp(200, body))
                assertThrows(body, IOException::class.java) { runBlocking { api().vaultStatusAnswer() } }
            }
    }

    private companion object {
        val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
    }
}
