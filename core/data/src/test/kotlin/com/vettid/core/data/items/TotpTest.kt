package com.vettid.core.data.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** One-time codes of `otp` fields (§10.7): the RFC 6238 appendix B vectors. */
class TotpTest {
    private val sha1Seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // "12345678901234567890"

    @Test
    fun rfc6238Sha1Vectors() {
        val t = Totp.parse("otpauth://totp/Example:alice?secret=$sha1Seed&digits=8&issuer=Example")!!
        assertEquals("94287082", t.code(59))
        assertEquals("07081804", t.code(1_111_111_109))
        assertEquals("14050471", t.code(1_111_111_111))
        assertEquals("89005924", t.code(1_234_567_890))
        assertEquals("69279037", t.code(2_000_000_000))
    }

    @Test
    fun rfc6238Sha256Vector() {
        // "12345678901234567890123456789012"
        val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
        val t = Totp.parse("otpauth://totp/x?secret=$seed&algorithm=SHA256&digits=8")!!
        assertEquals("46119246", t.code(59))
    }

    @Test
    fun aBareBase32SecretGivesSixDigitsEveryThirtySeconds() {
        val t = Totp.parse(sha1Seed.lowercase().chunked(4).joinToString(" "))!!
        assertEquals(6, t.digits)
        assertEquals(30, t.periodSeconds)
        assertEquals("287082", t.code(59))
        assertEquals(1, t.secondsLeft(59))
        assertEquals(30, t.secondsLeft(60))
    }

    @Test
    fun unreadableSeedsMakeNoCodes() {
        assertNull(Totp.parse("not a seed!"))
        assertNull(Totp.parse("otpauth://hotp/x?secret=$sha1Seed&counter=1"))
        assertNull(Totp.parse("otpauth://totp/x?digits=6"))
        assertNull(Totp.parse("otpauth://totp/x?secret=$sha1Seed&algorithm=MD5"))
        assertNull(Totp.parse("otpauth://totp/x?secret=$sha1Seed&digits=12"))
        assertNotNull(Totp.parse("otpauth://totp/x?secret=$sha1Seed&period=60"))
    }
}
