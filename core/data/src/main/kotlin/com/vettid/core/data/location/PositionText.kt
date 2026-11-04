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
    fun accuracy(r: Resources, meters: Double?): String? = meters?.let { distance(r, it, coarse = true) }

    /** The same figure for a distance from this phone. */
    fun distance(r: Resources, meters: Double, coarse: Boolean = false): String {
        if (meters < KM) {
            val step = if (coarse) METRE_STEP else 1
            val n = ((meters / step).roundToInt() * step).coerceAtLeast(step)
            return r.getString(R.string.data_location_metres, n)
        }
        val km = meters / KM
        val text = if (km < KM_ONE_DECIMAL_BELOW) String.format(Locale.getDefault(), "%.1f", km).removeSuffix(".0").removeSuffix(",0")
        else km.roundToInt().toString()
        return r.getString(R.string.data_location_kilometres, text)
    }

    private val COMPASS = listOf(
        R.string.data_location_dir_n, R.string.data_location_dir_ne, R.string.data_location_dir_e, R.string.data_location_dir_se,
        R.string.data_location_dir_s, R.string.data_location_dir_sw, R.string.data_location_dir_w, R.string.data_location_dir_nw,
    )

    /** The compass word for [bearing] (0 = north, clockwise). */
    fun direction(r: Resources, bearing: Double): String = r.getString(COMPASS[((bearing / SECTOR).roundToInt()) % SECTORS])

    /** "about 3 km north-east of this phone". */
    fun offset(r: Resources, o: PositionOffset): String =
        r.getString(R.string.data_location_from_here, distance(r, o.meters), direction(r, o.bearingDeg))
}
