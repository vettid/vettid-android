package com.vettid.core.data.location

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * "until 15:30": a share's end as the member's phone writes a time (12 or 24 hours as set), with the day first when it is not
 * today ("2 Nov, 15:30"), since a share can last 7 days.
 */
object ShareTime {
    fun format(context: Context, at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val time = DateFormat.getTimeFormat(context).also { it.timeZone = java.util.TimeZone.getTimeZone(zone) }.format(Date.from(at))
        if (at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) return time
        val pattern = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd")
        val day = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .also { it.timeZone = java.util.TimeZone.getTimeZone(zone) }.format(Date.from(at))
        return "$day, $time"
    }
}
