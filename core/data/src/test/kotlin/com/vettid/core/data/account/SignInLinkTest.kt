package com.vettid.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInLinkTest {
    private val token = "test-sign-in-token-0000"

    @Test
    fun parsesTheEmailedLink() {
        val l = SignInLink.parse("https://account.vettid.org/auth/#t=$token&e=member%40example.org")!!
        assertEquals(token, l.token)
        assertEquals("member@example.org", l.email)
    }

    @Test
    fun acceptsABareToken() {
        assertEquals(SignInLink(token, null), SignInLink.parse("  $token \n"))
    }

    @Test
    fun refusesOtherHostsPathsAndSchemes() {
        assertNull(SignInLink.parse("https://evil.example/auth/#t=$token"))
        assertNull(SignInLink.parse("https://account.vettid.org/other/#t=$token"))
        assertNull(SignInLink.parse("http://account.vettid.org/auth/#t=$token"))
        // plain http is refused for every non-loopback host, staging included
        assertNull(SignInLink.parse("http://account.staging.vettid.org/auth/#t=$token", setOf("account.staging.vettid.org")))
        assertTrue(SignInLink.parse("https://account.staging.vettid.org/auth/#t=$token", setOf("account.staging.vettid.org")) != null)
        assertNull(SignInLink.parse("https://account.vettid.org/auth/?t=$token"))
        assertNull(SignInLink.parse("https://account.vettid.org/auth/#t=short"))
        assertNull(SignInLink.parse("not a link"))
    }

    @Test
    fun devStackHostOnlyWhenAllowed() {
        val dev = "http://127.0.0.1/auth/#t=$token"
        assertNull(SignInLink.parse(dev))
        assertEquals(token, SignInLink.parse(dev, setOf(SignInLink.HOST, "127.0.0.1"))!!.token)
    }

    @Test
    fun toStringHidesTheToken() {
        assertFalse(SignInLink(token, "a@b.org").toString().contains(token))
    }

    @Test
    fun emailFormat() {
        assertTrue(EmailFormat.isPlausible("member@example.org"))
        assertFalse(EmailFormat.isPlausible("member@example"))
        assertFalse(EmailFormat.isPlausible("me mber@example.org"))
    }
}
