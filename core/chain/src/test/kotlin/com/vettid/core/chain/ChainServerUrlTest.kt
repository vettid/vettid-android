package com.vettid.core.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Use my own server" (ANDROID-PLAN §4 "The chain source"): `https` only, no credentials in the URL. */
class ChainServerUrlTest {
    private fun refused(text: String) = (ChainServerUrl.parse(text) as? UrlCheck.Refused)?.problem

    private fun ok(text: String) = (ChainServerUrl.parse(text) as UrlCheck.Ok).url

    @Test
    fun httpsIsAccepted() {
        assertEquals("https://mempool.space/signet/api", ok("https://mempool.space/signet/api").text)
        assertEquals("https://mempool.space/signet/api", ok("  https://mempool.space/signet/api/ ").text)
        assertEquals("https://node.example:3002", ok("https://node.example:3002/").text)
        assertEquals("node.example", ok("HTTPS://Node.Example/api").host)
    }

    @Test
    fun httpAndOtherSchemesAreRefused() {
        assertEquals(UrlProblem.NOT_HTTPS, refused("http://mempool.space/signet/api"))
        assertEquals(UrlProblem.NOT_HTTPS, refused("HTTP://127.0.0.1:3002"))
        assertEquals(UrlProblem.NOT_HTTPS, refused("ftp://node.example/api"))
        assertEquals(UrlProblem.NOT_HTTPS, refused("tcp://node.example:50001"))
    }

    @Test
    fun credentialsInTheUrlAreRefused() {
        assertEquals(UrlProblem.CREDENTIALS, refused("https://user:secret@node.example/api"))
        assertEquals(UrlProblem.CREDENTIALS, refused("https://user@node.example/api"))
        assertEquals(UrlProblem.CREDENTIALS, refused("https://@node.example/api"))
        assertEquals(UrlProblem.CREDENTIALS, refused("https://:secret@node.example/api"))
    }

    @Test
    fun aQueryOrFragmentIsRefused() {
        assertEquals(UrlProblem.QUERY_OR_FRAGMENT, refused("https://node.example/api?key=1"))
        assertEquals(UrlProblem.QUERY_OR_FRAGMENT, refused("https://node.example/api#x"))
    }

    @Test
    fun notAUrl() {
        for (t in listOf("", "   ", "mempool.space/signet/api", "https://", "https://exa mple/api", "https://" + "a".repeat(2050))) {
            assertEquals(t, UrlProblem.NOT_A_URL, refused(t))
        }
    }

    @Test
    fun toStringNamesTheHostOnly() {
        val u = ok("https://node.example/secret-path/api")
        assertFalse(u.toString().contains("secret-path"))
        assertTrue(u.toString().contains("node.example"))
    }

    @Test
    fun theDevelopmentRegtestServerIsLoopbackOnly() {
        assertEquals("http://127.0.0.1:18083", ChainServerUrl.loopbackOrNull("http://127.0.0.1:18083")?.text)
        assertEquals("http://localhost:3002/api", ChainServerUrl.loopbackOrNull("http://localhost:3002/api/")?.text)
        // Only what devStack's network security config allows: not ::1.
        val refused = listOf(
            null, "", "http://192.168.1.2:3002", "https://mempool.space/api", "http://u:p@127.0.0.1:3002", "nonsense", "http://[::1]:3002",
        )
        for (t in refused) {
            assertNull(t, ChainServerUrl.loopbackOrNull(t))
        }
    }
}
