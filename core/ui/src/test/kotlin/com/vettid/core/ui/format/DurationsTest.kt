package com.vettid.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** Durations in words from seconds (STAGING-TIME-SCALE §5.2): real and scaled waits. */
class DurationsTest {
    @Test
    fun wholeDaysAndHoursKeepTheirUnitAndTheRestIsInMinutes() {
        assertEquals(Durations.Unit.DAYS to 7L, Durations.parts(604_800))
        assertEquals(Durations.Unit.DAYS to 3L, Durations.parts(259_200))
        assertEquals(Durations.Unit.HOURS to 24L, Durations.parts(86_400)) // a day is "24 hours", as the texts said
        assertEquals(Durations.Unit.DAYS to 2L, Durations.parts(172_800))
        assertEquals(Durations.Unit.HOURS to 36L, Durations.parts(36 * 3_600))
        assertEquals(Durations.Unit.HOURS to 7L, Durations.parts(25_200)) // 7 days at 24
        assertEquals(Durations.Unit.HOURS to 3L, Durations.parts(10_800)) // the quiet period at 24
        assertEquals(Durations.Unit.HOURS to 1L, Durations.parts(3_600)) // 24 h at 24
        assertEquals(Durations.Unit.MINUTES to 3L, Durations.parts(150)) // 1 h at 24: 2.5 minutes, rounded up
        assertEquals(Durations.Unit.MINUTES to 90L, Durations.parts(5_400))
        assertEquals(Durations.Unit.SECONDS to 24L, Durations.parts(24))
        assertEquals(Durations.Unit.SECONDS to 0L, Durations.parts(-5))
    }

    @Test
    fun aScaledWaitRunsDividedAndRoundedUp() {
        assertEquals(86_400L, UiTimeScale().runs(86_400))
        assertEquals(3_600L, UiTimeScale(24).runs(86_400))
        assertEquals(150L, UiTimeScale(24).runs(3_600))
        assertEquals(10_800L, UiTimeScale(24).runs(259_200))
        assertEquals(86_400L, UiTimeScale(0).runs(86_400))
    }
}
