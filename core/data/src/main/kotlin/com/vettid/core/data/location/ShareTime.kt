package com.vettid.core.data.location

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * "until 15:30": a share's end as the member's phone writes a time (12 or 24 hours as set), with the day too when it is not
 * today, since a share can last 7 days. The date and the time are one pattern from the locale's own skeleton, so their order and
 * separators follow the region ("Nov 8, 3:30 PM" in the US, "8 Nov, 15:30" in the UK; owner decision 2026-11-05).
 */
object ShareTime {
    fun format(context: Context, at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val locale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val tz = java.util.TimeZone.getTimeZone(zone)
        if (at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) {
            return DateFormat.getTimeFormat(context).also { it.timeZone = tz }.format(Date.from(at))
        }
        val skeleton = if (DateFormat.is24HourFormat(context)) "MMMdHHmm" else "MMMdhmma"
        return java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .also { it.timeZone = tz }.format(Date.from(at))
    }
}
