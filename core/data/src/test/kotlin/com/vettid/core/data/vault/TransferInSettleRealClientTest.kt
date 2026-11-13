package com.vettid.core.data.vault

import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.relay.RelayClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * (#345's blind reviews, item 4; GC #6847) The settlement window with the real relay client and its whole-request deadline
 * (`VaultDevice.settleCollect`'s bound, wait + the window's transport bound): two refusals at the final look, a 429 and a
 * 503, are the client's to retry within the bound, so the look answers and the transfer the vault paired is not dropped.
 */
class TransferInSettleRealClientTest {
    private lateinit var relay: MockWebServer
    private val final = AtomicBoolean(false)
    private val finals = AtomicInteger(0)

    @Before
    fun start() {
        relay = MockWebServer()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!final.get()) return MockResponse.Builder().code(200).body("""{"messages":[]}""").build()
                return when (finals.incrementAndGet()) {
                    1 -> MockResponse.Builder().code(429).body("""{"code":"rate_limited","message":""}""").build()
                    2 -> MockResponse.Builder().code(503).body("""{"code":"unavailable","message":""}""").build()
                    // the vault's device.paired, waiting in the mailbox
                    else -> MockResponse.Builder().code(200).body("""{"messages":[{"msg_id":"m1","sender":"","payload":"AA=="}]}""").build()
                }
            }
        }
        relay.start()
    }

    @After
    fun stop() = relay.close()

    @Test
    fun twoRefusalsAtTheFinalLookWithinTheClientsDeadlineDoNotDrop() = runBlocking<Unit> {
        val client = RelayClient(relay.url("/").toString(), Ed25519PrivateKey.generate(), OkHttpClient())
        val t0 = System.nanoTime()
        val nowMs = { (System.nanoTime() - t0) / 1_000_000 }
        val settle = TransferInResume.Settle(windowMs = 600, pauseMs = 100, transportMs = 10_000, now = nowMs)
        var paired = false
        var drops = 0
        val collect: suspend (Long) -> Unit = { w ->
            if (w == 0L && nowMs() >= settle.windowMs) final.set(true)
            val got = client.collect(Duration.ofMillis(w), deadline = Duration.ofMillis(w + settle.transportMs))
            if (got.isNotEmpty()) paired = true
        }
        assertTrue(TransferInResume.atDeadline({ paired }, collect, { drops++ }, settle = settle, sentWait = { 0L }))
        assertEquals("nothing dropped", 0, drops)
        assertEquals("the final look's three attempts", 3, finals.get())
    }
}
