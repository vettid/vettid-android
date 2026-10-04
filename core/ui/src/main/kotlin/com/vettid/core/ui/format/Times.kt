package com.vettid.core.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Dates and times as lists and conversations show them, in the phone's zone and locale. */
object Times {
    private fun zone(): ZoneId = ZoneId.systemDefault()

    private val time get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val date get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    private val dateTime
        get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val weekday get() = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    /** A list row's date (Proton): the time today, the weekday this week, else the date. */
    fun short(at: Instant, now: Instant = Instant.now(), zone: ZoneId = zone()): String {
        val d = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            d == today -> time.format(at.atZone(zone))
            d.isAfter(today.minusDays(WEEK_DAYS)) && !d.isAfter(today) -> weekday.format(at.atZone(zone))
            else -> date.format(at.atZone(zone))
        }
    }

    /** The time of day only (a message's meta line). */
    fun time(at: Instant, zone: ZoneId = zone()): String = time.format(at.atZone(zone))

    /** A full date and time (details, expiries). */
    fun full(at: Instant, zone: ZoneId = zone()): String = dateTime.format(at.atZone(zone))

    /** The calendar day of [at] (conversation separators). */
    fun day(at: Instant, zone: ZoneId = zone()): LocalDate = at.atZone(zone).toLocalDate()

    fun dayLabel(day: LocalDate): String = date.format(day)

    /** Whole minutes from [now] to [until], at least 0. */
    fun minutesLeft(until: Instant, now: Instant = Instant.now()): Long = maxOf(0, ChronoUnit.MINUTES.between(now, until))

    private const val WEEK_DAYS = 6L
}
