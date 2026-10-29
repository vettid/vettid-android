package com.vettid.core.relay

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class FixedUserAgentTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun userAgentOf(c: OkHttpClient, extra: (Request.Builder) -> Unit = {}): List<String> {
        server.enqueue(MockResponse.Builder().code(200).build())
        c.newCall(Request.Builder().url(server.url("/")).also(extra).build()).execute().close()
        return server.takeRequest(5, TimeUnit.SECONDS)!!.headers.values("User-Agent")
    }

    private val shared get() = OkHttpClient.Builder().addInterceptor(TransportRetry(NetworkGate.ALWAYS)).fixedUserAgent().build()

    @Test
    fun `the shared client sends exactly VettID-Android`() {
        assertEquals(listOf("VettID-Android"), userAgentOf(shared))
    }

    @Test
    fun `a value set on the request is replaced`() {
        assertEquals(listOf("VettID-Android"), userAgentOf(shared) { it.header("User-Agent", "okhttp/5.5.0") })
    }

    @Test
    fun `derived clients keep it`() {
        val derived = shared.newBuilder().readTimeout(70, TimeUnit.SECONDS).followRedirects(false).build()
        assertEquals(listOf("VettID-Android"), userAgentOf(derived))
    }

    @Test
    fun `the websocket handshake carries it`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val ws = shared.newWebSocket(Request.Builder().url(server.url("/ws")).build(), object : okhttp3.WebSocketListener() {})
        val ua = server.takeRequest(5, TimeUnit.SECONDS)!!.headers.values("User-Agent")
        ws.cancel()
        assertEquals(listOf("VettID-Android"), ua)
    }

    private fun preemptive(challenge: String) = okhttp3.Response.Builder()
        .request(Request.Builder().url("https://example.test/").method("CONNECT", null).header("User-Agent", "okhttp/5.5.0").build())
        .protocol(okhttp3.Protocol.HTTP_1_1).code(407).message("Preemptive Authenticate")
        .header("Proxy-Authenticate", challenge).build()

    @Test
    fun `the preemptive proxy CONNECT carries it`() {
        val a = fixedUserAgentTunnel(Authenticator.NONE)
        val r = a.authenticate(null, preemptive("OkHttp-Preemptive"))!!
        assertEquals(listOf("VettID-Android"), r.headers.values("User-Agent"))
        assertEquals("CONNECT", r.method)
    }

    @Test
    fun `a real proxy challenge goes to the kept authenticator`() {
        val kept = Authenticator { _, resp -> resp.request.newBuilder().header("Proxy-Authorization", "x").build() }
        val r = fixedUserAgentTunnel(kept).authenticate(null, preemptive("Basic realm=\"p\""))!!
        assertEquals("x", r.header("Proxy-Authorization"))
        assertEquals(null, fixedUserAgentTunnel(Authenticator.NONE).authenticate(null, preemptive("Basic realm=\"p\"")))
    }

    @Test
    fun `the client carries the tunnel rewrite`() {
        val a = shared.proxyAuthenticator
        assertEquals(listOf("VettID-Android"), a.authenticate(null, preemptive("OkHttp-Preemptive"))!!.headers.values("User-Agent"))
    }
}
