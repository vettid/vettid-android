package com.vettid.core.altchan

import com.vettid.core.relay.TransportRetry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The phase read at app start (`GET /api/vault/status`) over the app's client: a connection that died while the phone
 * dozed is retried instead of showing "VettID cannot connect"; an answer of the API keeps its handling.
 */
class MemberApiRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MemberApiClient

    private val status = """{"vault":{"vault_id":"v1","state":"locked"},"service":"available"}"""

    private fun resp(code: Int, body: String) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun dropped() = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder().addInterceptor(TransportRetry(sleep = {})).retryOnConnectionFailure(false).build()
        api = MemberApiClient(server.url("/").toString(), "", http, MemberAuth.Bearer("guid-1"))
    }

    @After
    fun stop() = server.close()

    @Test
    fun statusRecoversFromADroppedConnection() = runBlocking {
        server.enqueue(dropped())
        server.enqueue(resp(200, status))
        assertEquals("v1", api.vaultStatusAnswer().vault?.vaultId)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun statusFailsAfterTheRetriesWhenTheServiceStaysUnreachable() {
        repeat(1 + TransportRetry.DEFAULT_RETRIES) { server.enqueue(dropped()) }
        assertThrows(IOException::class.java) { runBlocking { api.vaultStatusAnswer() } }
        assertEquals(1 + TransportRetry.DEFAULT_RETRIES, server.requestCount)
    }

    @Test
    fun anApiRefusalIsNotRetried() {
        server.enqueue(resp(403, """{"code":"forbidden","message":"no"}"""))
        val e = assertThrows(MemberApiException::class.java) { runBlocking { api.vaultStatusAnswer() } }
        assertEquals(403, e.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aSealedPostThatMayHaveArrivedIsNotResent() {
        // POST /api/vault/lock carries a request_id the API takes once (409 duplicate_request): never resent blindly
        server.enqueue(dropped())
        assertThrows(IOException::class.java) { runBlocking { api.lock("vault-1", "01J0000000000000000000000A") } }
        assertEquals(1, server.requestCount)
    }
}
