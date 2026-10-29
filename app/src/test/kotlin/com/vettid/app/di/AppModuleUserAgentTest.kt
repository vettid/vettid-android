package com.vettid.app.di

import com.vettid.core.relay.NetworkGate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The client the app really builds ([AppModule.http]) sends `User-Agent: VettID-Android` (VAULT-MESSAGING §13.6). */
class AppModuleUserAgentTest {
    private lateinit var server: MockWebServer
    private val expected = listOf("VettID-Android")

    private fun prod(): OkHttpClient = AppModule.http(ConnectionPool(), NetworkGate.ALWAYS)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun uaOf(c: OkHttpClient, extra: (Request.Builder) -> Unit = {}): List<String> {
        server.enqueue(MockResponse.Builder().code(200).build())
        c.newCall(Request.Builder().url(server.url("/")).also(extra).build()).execute().close()
        return server.takeRequest(5, TimeUnit.SECONDS)!!.headers.values("User-Agent")
    }

    @Test
    fun `plain request`() = assertEquals(expected, uaOf(prod()))

    @Test
    fun `a preset value is replaced`() = assertEquals(expected, uaOf(prod()) { it.header("User-Agent", "okhttp/5.5.0") })

    @Test
    fun `derived client`() =
        assertEquals(expected, uaOf(prod().newBuilder().readTimeout(5, TimeUnit.SECONDS).followRedirects(false).build()))

    @Test
    fun `websocket handshake`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val ws = prod().newWebSocket(Request.Builder().url(server.url("/ws")).build(), object : WebSocketListener() {})
        val ua = server.takeRequest(5, TimeUnit.SECONDS)!!.headers.values("User-Agent")
        ws.cancel()
        assertEquals(expected, ua)
    }

    @Test
    fun `redirect follow-up`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/second").toString()).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        prod().newCall(Request.Builder().url(server.url("/first")).build()).execute().close()
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/second", second.url.encodedPath)
        assertEquals(expected, first.headers.values("User-Agent"))
        assertEquals(expected, second.headers.values("User-Agent"))
    }

    @Test
    fun `the production client carries the tunnel rewrite`() {
        val resp = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.test/").method("CONNECT", null).header("User-Agent", "okhttp/5.5.0").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(407).message("Preemptive Authenticate")
            .header("Proxy-Authenticate", "OkHttp-Preemptive").build()
        assertEquals(expected, prod().proxyAuthenticator.authenticate(null, resp)!!.headers.values("User-Agent"))
    }
}
