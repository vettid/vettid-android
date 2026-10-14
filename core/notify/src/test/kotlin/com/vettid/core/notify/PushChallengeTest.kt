package com.vettid.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A registration's challenge as a push message (PUSH-GATEWAY 0.5.0 §6; VAULT-MESSAGING 0.30.0 §14.1). */
class PushChallengeTest {
    @Test
    fun aCodeIsOneToSixtyFourBase64urlCharacters() {
        assertTrue("the gateway's 16 random bytes", PushChallenge.valid("AAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(PushChallenge.valid("a"))
        assertTrue(PushChallenge.valid("Az09-_"))
        assertTrue(PushChallenge.valid("x".repeat(64)))
        assertFalse(PushChallenge.valid(""))
        assertFalse(PushChallenge.valid("x".repeat(65)))
        assertFalse("padding", PushChallenge.valid("abc="))
        assertFalse("base64, not base64url", PushChallenge.valid("ab+/"))
        assertFalse(PushChallenge.valid("a b"))
        assertFalse(PushChallenge.valid("é"))
    }

    @Test
    fun aChallengeHasCAndNoW() {
        val c = PushChallenge.classify(mapOf("c" to "the-code"))
        assertTrue(c is PushMessage.Challenge)
        assertEquals("the-code", (c as PushMessage.Challenge).code)
        assertEquals(PushMessage.Wake, PushChallenge.classify(mapOf("w" to "1")))
        assertEquals(PushMessage.Wake, PushChallenge.classify(mapOf("c" to "the-code", "w" to "1")))
        assertEquals("anything else, as before 0.5.0", PushMessage.Wake, PushChallenge.classify(mapOf("x" to "y")))
        assertEquals(PushMessage.Wake, PushChallenge.classify(emptyMap()))
        assertEquals(PushMessage.Malformed, PushChallenge.classify(mapOf("c" to "not a code")))
    }

    @Test
    fun aChallengesTextNeverShowsTheCode() {
        assertFalse("the-code" in PushMessage.Challenge("the-code").toString())
    }
}
