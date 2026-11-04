package com.vettid.feature.approvals

import com.vettid.core.ui.format.Times
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** "Access allowed until …": the time of day on the same day, the date and time otherwise, in the zone given (not the machine's). */
class DeviceAsksUntilTextTest {
    private val zone = ZoneId.of("Pacific/Pago_Pago") // UTC−11, where the old test failed at 23:02

    @Test
    fun anExpiryLaterTheSameDayShowsTheTimeOnly() {
        val now = Instant.parse("2026-11-04T10:00:00Z") // 23:00 on 3 Nov in Pago Pago
        val until = now.plusSeconds(30 * 60) // 23:30, still 3 Nov
        assertEquals(Times.time(until, zone), untilText(until, now, zone))
    }

    @Test
    fun anExpiryAnHourAheadThatIsTomorrowShowsTheDate() {
        val now = Instant.parse("2026-11-04T10:02:00Z") // 23:02 on 3 Nov in Pago Pago
        val until = now.plusSeconds(3_600) // 00:02 on 4 Nov
        assertEquals(Times.full(until, zone), untilText(until, now, zone))
    }
}
