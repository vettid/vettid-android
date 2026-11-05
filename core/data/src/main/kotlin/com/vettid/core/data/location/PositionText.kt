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

    /** "50 m" (to the nearest 10) under a kilometre, else "1 km", "2.5 km", "12 km". Null without an accuracy. */
    fun accuracy(r: Resources, meters: Double?): String? = meters?.takeIf { it > 0.0 }?.let { distance(r, it) }

    /**
     * The same figure for a distance from this phone. Coarse (a position is never that exact): the nearest 10 m under a kilometre
     * (at least 10), 0.1 km under 10 km, whole kilometres above.
     */
    fun distance(r: Resources, meters: Double): String {
        if (meters < KM) {
            val n = ((meters / METRE_STEP).roundToInt() * METRE_STEP).coerceAtLeast(METRE_STEP)
            return r.getString(R.string.data_location_metres, n)
        }
        val km = meters / KM
        // The locale's own digits and separator; a whole number has no decimals.
        val format = java.text.NumberFormat.getNumberInstance(Locale.getDefault())
        format.maximumFractionDigits = if (km < KM_ONE_DECIMAL_BELOW) 1 else 0
        format.minimumFractionDigits = 0
        val text = format.format(km)
        return r.getString(R.string.data_location_kilometres, text)
    }

    private val COMPASS = listOf(
        R.string.data_location_dir_n, R.string.data_location_dir_ne, R.string.data_location_dir_e, R.string.data_location_dir_se,
        R.string.data_location_dir_s, R.string.data_location_dir_sw, R.string.data_location_dir_w, R.string.data_location_dir_nw,
    )

    /** The compass word for [bearing] (0 = north, clockwise). */
    fun direction(r: Resources, bearing: Double): String = r.getString(COMPASS[((bearing / SECTOR).roundToInt()) % SECTORS])

    /**
     * "About 3 km north-east of this phone"; "Near this phone" (no direction) when the distance is within the position's own accuracy,
     * or under 10 m: a direction from a circle one is inside would be made up.
     */
    fun offset(r: Resources, o: PositionOffset, accuracyM: Double? = null, precision: SharePrecision = SharePrecision.EXACT): String {
        // The position stands for a circle: its accuracy, or the share's own area when that is larger (Approximate ~1 km, City ~11 km).
        val radius = maxOf(accuracyM ?: 0.0, nominalRadius(precision))
        return if (o.meters < radius || o.meters < METRE_STEP) {
            r.getString(R.string.data_location_near_here)
        } else {
            r.getString(R.string.data_location_from_here, distance(r, o.meters), direction(r, o.bearingDeg))
        }
    }

    /** About how far from the real place a position of this precision may be (the vault's cells: 0.01 and 0.1 degrees). */
    fun nominalRadius(precision: SharePrecision): Double = when (precision) {
        SharePrecision.EXACT -> 0.0
        SharePrecision.APPROXIMATE -> APPROXIMATE_RADIUS_M
        SharePrecision.CITY -> CITY_RADIUS_M
    }
}
