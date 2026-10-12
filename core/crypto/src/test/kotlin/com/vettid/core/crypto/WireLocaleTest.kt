package com.vettid.core.crypto

import com.vettid.core.crypto.envelope.Timestamps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** A6 audit R2: the envelope timestamps (§5.3) are ASCII and parse strictly whatever the phone's language. */
class WireLocaleTest {
    private val before: Locale = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(before)

    @Test
    fun timestampsAreTheSameInEveryLocale() {
        val t = Instant.parse("2026-10-04T12:34:56.789Z")
        val locales = listOf("fa-IR", "ar-EG", "bn-BD", "my-MM", "ar-SA-u-nu-arab", "th-TH-u-nu-thai-ca-buddhist", "ja-JP-u-ca-japanese")
        locales.forEach { tag ->
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, "2026-10-04T12:34:56.789Z", Timestamps.formatMillis(t))
            assertEquals(tag, "2026-10-04T12:34:56Z", Timestamps.formatSeconds(t))
            assertEquals(tag, t, Timestamps.parseMillis("2026-10-04T12:34:56.789Z"))
            assertEquals(tag, Instant.parse("2026-10-04T12:34:56Z"), Timestamps.parseSeconds("2026-10-04T12:34:56Z"))
        }
    }
}
