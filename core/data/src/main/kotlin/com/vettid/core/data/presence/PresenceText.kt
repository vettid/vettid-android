package com.vettid.core.data.presence

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.vettid.core.data.R
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/** The words of a presence line (ANDROID-PLAN §4 "Presence"), shared by the connection's page, the conversation and the settings. */
object PresenceText {
    @StringRes
    fun stateName(state: PresenceState): Int = when (state) {
        PresenceState.AVAILABLE -> R.string.data_presence_available
        PresenceState.BUSY -> R.string.data_presence_busy
        PresenceState.AWAY -> R.string.data_presence_away
        PresenceState.INVISIBLE -> R.string.data_presence_invisible
    }

    /** "active 10 min ago", "active just now" or "active today"; null when nothing is said. */
    fun age(res: Resources, age: PresenceAge?): String? = when (age) {
        null -> null
        PresenceAge.JustNow -> res.getString(R.string.data_presence_active_just_now)
        is PresenceAge.Minutes -> res.getString(R.string.data_presence_active_minutes, age.minutes)
        PresenceAge.Today -> res.getString(R.string.data_presence_active_today)
        is PresenceAge.Date -> res.getString(R.string.data_presence_active_date, dateText(res, age))
    }

    /**
     * "2 Nov" in the device's locale (day and month; with the year when it is not this year). A locale with a calendar
     * extension can yield pattern letters java.time refuses: then the locale's medium date, never a throw (this text is on the
     * connection page and in a conversation).
     */
    private fun dateText(res: Resources, age: PresenceAge.Date): String {
        val locale = res.configuration.locales[0] ?: java.util.Locale.getDefault()
        val skeleton = if (age.withYear) "dMMMyyyy" else "dMMM"
        return runCatching { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(age.date) }
            .getOrElse { DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale).format(age.date) }
    }

    /**
     * The line under a connection's name: "Checking…", "Status unknown", or the state with " · active {age}" from
     * `last_active` (nothing after the state without it).
     */
    fun line(res: Resources, reading: PresenceReading, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String = when (reading) {
        PresenceReading.Checking -> res.getString(R.string.data_presence_checking)
        PresenceReading.Unknown -> res.getString(R.string.data_presence_unknown)
        // A failure is said with its own text by the screen (`failureText`), and a closed vault shows no line.
        is PresenceReading.Failed, PresenceReading.Closed -> ""
        is PresenceReading.Known -> {
            val name = res.getString(stateName(reading.state))
            val ago = age(res, PresenceAge.of(reading.lastActive, now, zone))
            if (ago == null) name else res.getString(R.string.data_presence_with_age, name, ago)
        }
    }
}
