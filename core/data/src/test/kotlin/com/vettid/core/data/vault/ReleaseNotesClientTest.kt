package com.vettid.core.data.vault

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The release log fetch (ANDROID-PLAN 0.1.31): one URL, no redirects, 1 MiB, JSON only, kept in memory. */
class ReleaseNotesClientTest {
    private lateinit var server: MockWebServer
    private lateinit var manifestUrl: String
    private lateinit var origin: String

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        manifestUrl = server.url("/.well-known/vettid/pcr-manifest.json").toString()
        origin = ReleaseLog.origin(manifestUrl)!!
    }

    @After
    fun stop() = server.close()

    private fun pcr(n: Long) = "%02x".format(n).repeat(48)

    private fun rel(n: Long) = ReleaseView(n, pcr(n), "active", null, "$origin/security/releases/$n/")

    private fun index(vararg n: Long) = n.joinToString(",", "{\"serial\": 3, \"releases\": [", "]}") {
        """{"release": $it, "pcr0": "${pcr(it)}", "summary": "Release $it", "changes": ["c$it"], "security": "none", "listed": true}"""
    }

    private fun json(body: String, code: Int = 200, type: String = "application/json") =
        MockResponse.Builder().code(code).setHeader("Content-Type", type).body(body).build()

    private fun client() = ReleaseNotesClient(OkHttpClient(), manifestUrl)

    @Test
    fun fetchesTheIndexAndKeepsItInMemory() = runBlocking {
        server.enqueue(json(index(6, 5, 4)))
        val c = client()
        val n = c.whatsNew(rel(6), listOf(rel(5))) as ReleaseNotes.Available
        assertEquals("localhost", n.host)
        assertEquals("Release 6", n.entry.summary)
        assertEquals(listOf(EarlierRelease(5, "Release 5")), n.earlier)
        val r = server.takeRequest()
        assertEquals("/security/releases/index.json", r.url.encodedPath)
        assertNull(r.headers["Cookie"])
        assertNull(r.headers["Authorization"])
        // Again: from memory, no second request.
        assertEquals(n, c.whatsNew(rel(6), listOf(rel(5))))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun otherNotesFetchNothing() = runBlocking {
        val gh = rel(6).copy(notes = "https://github.com/vettid/vettid-vault/releases/tag/staging-s6")
        assertEquals(ReleaseNotes.Unavailable, client().whatsNew(gh, emptyList()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectsAreRefused() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "$origin/elsewhere.json").build())
        server.enqueue(json(index(6)))
        assertEquals(ReleaseNotes.Unavailable, client().whatsNew(rel(6), emptyList()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failuresAreUnavailableAndRetried() = runBlocking {
        val c = client()
        server.enqueue(json("nope", code = 404))
        assertEquals(ReleaseNotes.Unavailable, c.whatsNew(rel(6), emptyList()))
        server.enqueue(json(index(6), type = "text/html"))
        assertEquals(ReleaseNotes.Unavailable, c.whatsNew(rel(6), emptyList()))
        server.enqueue(json(index(5)))
        assertEquals(ReleaseNotes.Unavailable, c.whatsNew(rel(6), emptyList()))
        server.enqueue(json("{not json"))
        assertEquals(ReleaseNotes.Unavailable, c.whatsNew(rel(6), emptyList()))
        // Opening again retries.
        server.enqueue(json(index(6)))
        assertTrue(c.whatsNew(rel(6), emptyList()) is ReleaseNotes.Available)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun atMostOneMebibyte() = runBlocking {
        val doc = index(6)
        val padded = doc.dropLast(1) + ", \"pad\": \"" + "x".repeat(ReleaseLog.MAX_BYTES) + "\"}"
        server.enqueue(json(padded))
        assertEquals(ReleaseNotes.Unavailable, client().whatsNew(rel(6), emptyList()))
        // Without a Content-Length (chunked): the read stops past the cap too.
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").chunkedBody(padded, 8192).build())
        assertEquals(ReleaseNotes.Unavailable, client().whatsNew(rel(6), emptyList()))
        // Just under it: fine.
        val fits = doc.dropLast(1) + ", \"pad\": \"" + "x".repeat(ReleaseLog.MAX_BYTES - doc.length - 20) + "\"}"
        server.enqueue(json(fits))
        assertTrue(client().whatsNew(rel(6), emptyList()) is ReleaseNotes.Available)
    }

    @Test
    fun aSlowLogTimesOut() = runBlocking {
        server.enqueue(json(index(6)).newBuilder().bodyDelay(ReleaseLog.TIMEOUT_S + 2, TimeUnit.SECONDS).build())
        val t0 = System.nanoTime()
        assertEquals(ReleaseNotes.Unavailable, client().whatsNew(rel(6), emptyList()))
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < ReleaseLog.TIMEOUT_S + 2)
    }
}
