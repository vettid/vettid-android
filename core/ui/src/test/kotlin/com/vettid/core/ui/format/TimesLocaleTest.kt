package com.vettid.core.ui.format

import android.content.res.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The formats follow the app's configuration locale, not `Locale.getDefault()` (the lead's #295 follow-up). */
@RunWith(RobolectricTestRunner::class)
class TimesLocaleTest {
    private val default = Locale.getDefault()

    @After
    fun restore() {
        Times.installConfiguration(null)
        Locale.setDefault(default)
    }

    private fun config(tag: String) = Configuration().apply { setLocale(Locale.forLanguageTag(tag)) }

    @Test
    fun theConfigurationsLocaleWinsOverTheDefault() {
        Locale.setDefault(Locale.US)
        Times.installConfiguration { config("en-GB") }
        assertEquals("5 Nov 2026", Times.dayLabel(LocalDate.of(2026, 11, 5)))
    }

    @Test
    fun aChangedConfigurationIsReadAtTheNextCall() {
        var c = config("en-US")
        Times.installConfiguration { c }
        val at = Instant.parse("2026-11-05T15:30:00Z")
        assertEquals("Nov 5, 2026", Times.dayLabel(LocalDate.of(2026, 11, 5)))
        c = config("de-DE")
        assertEquals("15:30", Times.time(at, ZoneOffset.UTC))
    }

    @Test
    fun installReadsTheApplicationsConfigurationAtEachCall() {
        Locale.setDefault(Locale.US)
        RuntimeEnvironment.setQualifiers("en-rGB")
        Times.install(RuntimeEnvironment.getApplication())
        Locale.setDefault(Locale.US)
        assertEquals("5 Nov 2026", Times.dayLabel(LocalDate.of(2026, 11, 5)))
        RuntimeEnvironment.setQualifiers("de-rDE")
        Locale.setDefault(Locale.US)
        assertEquals("15:30", Times.time(Instant.parse("2026-11-05T15:30:00Z"), ZoneOffset.UTC))
    }

    @Test
    fun withoutAConfigurationTheDefaultLocaleIsUsed() {
        Locale.setDefault(Locale.UK)
        assertEquals("5 Nov 2026", Times.dayLabel(LocalDate.of(2026, 11, 5)))
    }
}
