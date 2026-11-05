package com.vettid.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/** Day titles follow the region's date order (owner decision 2026-11-05): month first in the US, day first in the UK. */
@RunWith(RobolectricTestRunner::class)
class DayTitleTest {
    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun aUsPhoneShowsTheMonthFirst() {
        assertEquals("Mon, Nov 2", FeedSections.dayTitle(LocalDate.of(2026, 11, 2), today, Locale.US))
    }

    @Test
    fun aUkPhoneShowsTheDayFirst() {
        assertEquals("Mon 2 Nov", FeedSections.dayTitle(LocalDate.of(2026, 11, 2), today, Locale.UK))
    }

    @Test
    fun anEarlierYearIsNamed() {
        assertEquals("Sun, Nov 2, 2025", FeedSections.dayTitle(LocalDate.of(2025, 11, 2), today, Locale.US))
        assertEquals("Sun, 2 Nov 2025", FeedSections.dayTitle(LocalDate.of(2025, 11, 2), today, Locale.UK))
    }

    @Test
    fun aGermanPhoneGetsItsOwnOrder() {
        // Not a fixed English pattern: German puts the day before the month and starts with its own weekday.
        val t = FeedSections.dayTitle(LocalDate.of(2026, 11, 2), today, Locale.GERMANY)
        assertTrue(t, t.startsWith("Mo"))
        assertTrue(t, t.indexOf("2") in 0 until t.indexOf("Nov"))
    }

    @Test
    fun theYearAppearsAcrossTheYearBoundary() {
        // On 1 Jan 2027 a day of the year before is named with its year.
        assertEquals("Wed, Dec 30, 2026", FeedSections.dayTitle(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 1), Locale.US))
    }
}
