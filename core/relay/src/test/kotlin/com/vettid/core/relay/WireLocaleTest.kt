package com.vettid.core.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * A6 audit R2: what the relay client signs and sends is ASCII whatever the phone's language. With a default locale
 * whose digits are not Latin (Persian, Arabic as used in Egypt, Bengali, Burmese...), `"%03d".format(...)` wrote the
 * fraction of the request timestamp in that language's digits, so the signed header was not ASCII and every relay
 * request of such a phone failed.
 */
class WireLocaleTest {
    private val before: Locale = Locale.getDefault()
    private val locales =
        listOf("fa-IR", "ar-EG", "bn-BD", "my-MM", "ar-SA-u-nu-arab", "th-TH-u-nu-thai-ca-buddhist", "hi-IN-u-nu-deva")
    private val key = VectorsTest.key(VectorsTest.RECIPIENT_SEED)

    @After
    fun restore() = Locale.setDefault(before)

    private fun everyLocale(check: (String) -> Unit) = locales.forEach { tag ->
        Locale.setDefault(Locale.forLanguageTag(tag))
        check(tag)
    }

    private fun assertAscii(what: String, s: String) = assertTrue("$what: $s", s.all { it.code in 0x20..0x7E })

    @Test
    fun theRequestTimestampIsAsciiInEveryLocale() = everyLocale { tag ->
        assertEquals(tag, "2026-10-04T12:34:56.789Z", RelayAuth.timestamp(Instant.parse("2026-10-04T12:34:56.789Z")))
        assertEquals(tag, "2026-10-04T12:34:56.07Z", RelayAuth.timestamp(Instant.parse("2026-10-04T12:34:56.070Z")))
        assertEquals(tag, "2026-10-04T12:34:56.005Z", RelayAuth.timestamp(Instant.parse("2026-10-04T12:34:56.005123Z")))
        assertEquals(tag, "2026-10-04T12:34:56Z", RelayAuth.timestamp(Instant.parse("2026-10-04T12:34:56Z")))
    }

    @Test
    fun theSignedHeadersAreTheSameInEveryLocale() {
        val t = Instant.parse("2026-10-04T12:34:56.789Z")
        val hash = ByteArray(32) { 7 }
        Locale.setDefault(Locale.ROOT)
        val expected = RelayAuth.headers(key, "POST", "/v1/mailboxes/abc/messages", t, hash)
        everyLocale { tag ->
            val h = RelayAuth.headers(key, "POST", "/v1/mailboxes/abc/messages", t, hash)
            assertEquals(tag, expected, h)
            h.forEach { (k, v) -> assertAscii("$tag $k", v) }
        }
    }

    @Test
    fun aDepositTokensTimesAreTheSameInEveryLocale() {
        val now = Instant.parse("2026-10-04T12:34:56.789Z")
        Locale.setDefault(Locale.ROOT)
        fun mint() = DepositTokens.mintStanding(
            key, VectorsTest.SENDER_PUB, VectorsTest.AUD, now, Duration.ofDays(30), jti = "01JXAMPLE0000000000000001",
        )
        val expected = mint()
        everyLocale { tag ->
            val t = mint()
            assertEquals(tag, expected, t)
            assertEquals(tag, Instant.parse("2026-10-04T12:33:56Z"), DepositTokens.verify(t, key.publicKey).iat)
        }
    }
}
