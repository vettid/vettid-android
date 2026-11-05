package com.vettid.core.data.location

import android.content.res.Resources
import com.vettid.core.data.R
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The words for a received position (ANDROID-PLAN §4 "Location", L1b): its age ("5 min"), its accuracy ("1 km"), and the distance
 * and direction from this phone. Numbers only; the coordinates are never turned into text here.
 */
object PositionText {
    private const val SECONDS_MIN = 60L
    private const val MINUTES_HOUR = 60L
    private const val HOURS_DAY = 24L
    private const val KM = 1_000.0
    private const val KM_ONE_DECIMAL_BELOW = 10.0
    private const val METRE_STEP = 10
    private const val MILE = 1_609.344
    private const val FOOT = 0.3048
    private const val FEET_BELOW_MILES = 0.1
    private const val MILES_ONE_DECIMAL_BELOW = 10.0
    private const val FOOT_STEP = 10
    private const val APPROXIMATE_RADIUS_M = 1_000.0
    private const val CITY_RADIUS_M = 11_000.0
    private const val SECTOR = 45.0
    private const val SECTORS = 8

    /** "now" under a minute, then "5 min", "3 h", "2 d". A position dated ahead of [now] is "now". */
    fun age(r: Resources, at: Instant, now: Instant): String {
        val d = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.seconds < SECONDS_MIN -> r.getString(R.string.data_location_age_now)
            d.toMinutes() < MINUTES_HOUR -> r.getString(R.string.data_location_age_min, d.toMinutes().toInt())
            d.toHours() < HOURS_DAY -> r.getString(R.string.data_location_age_hours, d.toHours().toInt())
            else -> r.getString(R.string.data_location_age_days, d.toDays().toInt())
        }
    }

    /**
     * "50 m" (to the nearest 10) under a kilometer, else "1 km", "2.5 km", "12 km"; in [system] imperial "250 ft" under 0.1 mi, else
     * "0.5 mi", "2.5 mi", "12 mi". Null without an accuracy.
     */
    fun accuracy(r: Resources, meters: Double?, system: LengthSystem): String? =
        meters?.takeIf { it > 0.0 }?.let { distance(r, it, system) }

    /**
     * The same figure for a distance from this phone. Coarse (a position is never that exact). Metric: the nearest 10 m under a
     * kilometer (at least 10), 0.1 km under 10 km, whole kilometers above. Imperial: the nearest 10 ft (at least 10) under 0.1 mi,
     * 0.1 mi under 10 mi, whole miles above. The locale's own digits and separator.
     */
    fun distance(r: Resources, meters: Double, system: LengthSystem): String = when (system) {
        LengthSystem.METRIC -> metric(r, meters)
        LengthSystem.IMPERIAL -> imperial(r, meters)
    }

    private fun metric(r: Resources, meters: Double): String {
        // Rounded first: 995 m and more is 1 km, not "1000 m".
        val n = ((meters / METRE_STEP).roundToInt() * METRE_STEP).coerceAtLeast(METRE_STEP)
        if (n < KM) return r.getString(R.string.data_location_metres, decimal(r, n.toDouble(), 0.0))
        return r.getString(R.string.data_location_kilometres, decimal(r, meters / KM, KM_ONE_DECIMAL_BELOW))
    }

    private fun imperial(r: Resources, meters: Double): String {
        val miles = meters / MILE
        if (miles < FEET_BELOW_MILES) {
            val n = ((meters / FOOT / FOOT_STEP).roundToInt() * FOOT_STEP).coerceAtLeast(FOOT_STEP)
            return r.getString(R.string.data_location_feet, decimal(r, n.toDouble(), 0.0))
        }
        return r.getString(R.string.data_location_miles, decimal(r, miles, MILES_ONE_DECIMAL_BELOW))
    }

    /**
     * [value] in the digits of [r]'s configuration locale (the one the screen's strings and units come from, so they can't
     * disagree during a locale change): one decimal below [oneDecimalBelow], else whole; a whole number has no decimals.
     */
    private fun decimal(r: Resources, value: Double, oneDecimalBelow: Double): String {
        val format = java.text.NumberFormat.getNumberInstance(r.configuration.locales[0] ?: Locale.getDefault())
        format.maximumFractionDigits = if (value < oneDecimalBelow) 1 else 0
        format.minimumFractionDigits = 0
        return format.format(value)
    }

    private val COMPASS = listOf(
        R.string.data_location_dir_n, R.string.data_location_dir_ne, R.string.data_location_dir_e, R.string.data_location_dir_se,
        R.string.data_location_dir_s, R.string.data_location_dir_sw, R.string.data_location_dir_w, R.string.data_location_dir_nw,
    )

    /** The compass word for [bearing] (0 = north, clockwise). */
    fun direction(r: Resources, bearing: Double): String = r.getString(COMPASS[((bearing / SECTOR).roundToInt()) % SECTORS])

    /**
     * "About 3 km north-east of this phone" (or "About 1.9 mi …"); "Near this phone" (no direction) when the distance is within the
     * position's own accuracy,
     * or under 10 m: a direction from a circle one is inside would be made up.
     */
    fun offset(
        r: Resources,
        o: PositionOffset,
        accuracyM: Double?,
        precision: SharePrecision,
        system: LengthSystem,
    ): String {
        // The position stands for a circle: its accuracy, or the share's own area when that is larger (Approximate ~1 km, City ~11 km).
        val radius = maxOf(accuracyM ?: 0.0, nominalRadius(precision))
        return if (o.meters < radius || o.meters < METRE_STEP) {
            r.getString(R.string.data_location_near_here)
        } else {
            r.getString(R.string.data_location_from_here, distance(r, o.meters, system), direction(r, o.bearingDeg))
        }
    }

    /** About how far from the real place a position of this precision may be (the vault's cells: 0.01 and 0.1 degrees). */
    fun nominalRadius(precision: SharePrecision): Double = when (precision) {
        SharePrecision.EXACT -> 0.0
        SharePrecision.APPROXIMATE -> APPROXIMATE_RADIUS_M
        SharePrecision.CITY -> CITY_RADIUS_M
    }
}
