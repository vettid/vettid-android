package com.vettid.feature.notifications

import org.junit.Assert.assertEquals
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
        // Not a fixed English pattern: the locale's own words and punctuation.
        val t = FeedSections.dayTitle(LocalDate.of(2026, 11, 2), today, Locale.GERMANY)
        assertEquals(true, t.contains("Nov") && t.contains("2") && t.startsWith("Mo"))
    }
}
