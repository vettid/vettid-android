package com.vettid.feature.approvals

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * A form's time (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"): Unix seconds from 0 to 253,402,300,799, shown as date
 * and time in the member's time zone with the zone named, then in UTC, with seconds whenever they are not zero. As
 * vettid-ios #300's `DocumentTime` (owner-approved format): the year always in full, the UTC line naming its zone "UTC".
 */
object DocumentTime {
    data class Shown(val local: String, val utc: String)

    /** [seconds] in [zone] (the member's) and in UTC, for [locale]. */
    fun shown(seconds: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): Shown {
        val instant = Instant.ofEpochSecond(seconds)
        // Seconds in each zone's own reading (a zone with an offset of seconds, as before 1972, differs from UTC's).
        val localSeconds = instant.atZone(zone).second != 0
        return Shown(
            local = format(instant, zone.id, locale, localSeconds, zoneName = true),
            utc = format(instant, "UTC", locale, seconds % SECONDS_PER_MINUTE != 0L, zoneName = false) + " UTC",
        )
    }

    private const val SECONDS_PER_MINUTE = 60L

    private fun format(at: Instant, zone: String, locale: Locale, withSeconds: Boolean, zoneName: Boolean): String {
        val skeleton = (if (withSeconds) "yyyyMMMdjmmss" else "yyyyMMMdjmm") + (if (zoneName) "z" else "")
        val f = DateFormat.getInstanceForSkeleton(skeleton, ULocale.forLocale(locale))
        f.timeZone = TimeZone.getTimeZone(zone)
        return f.format(Date.from(at))
    }
}
