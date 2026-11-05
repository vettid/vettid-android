package com.vettid.core.data.location

import android.icu.util.LocaleData
import android.icu.util.ULocale
import com.vettid.core.data.prefs.DistanceUnits

/** How distances are written: meters and kilometers, or feet and miles. */
enum class LengthSystem { METRIC, IMPERIAL }

/**
 * Which [LengthSystem] a distance is shown in (owner decision 2026-11-05): the member's choice, or for Automatic the phone's region.
 * Only the region is read, never a position.
 */
object LengthSystems {
    /** Regions that count in miles whatever the ICU data of this Android version says (owner decision 2026-11-05). */
    private val MILE_REGIONS = setOf("US", "LR", "MM")

    /**
     * Imperial where ICU says the region measures in the US system, and for the US, Liberia and Myanmar in any case; every other
     * region is metric, the UK included (its roads are in miles, but the app shows kilometers there by default). A blank or
     * unknown region is metric.
     */
    fun ofRegion(region: String?): LengthSystem {
        val code = region?.uppercase()?.takeIf { it.isNotBlank() } ?: return LengthSystem.METRIC
        val miles = code in MILE_REGIONS || runCatching {
            LocaleData.getMeasurementSystem(ULocale.Builder().setRegion(code).build()) == LocaleData.MeasurementSystem.US
        }.getOrDefault(false)
        return if (miles) LengthSystem.IMPERIAL else LengthSystem.METRIC
    }

    /** The region of the phone's default locale; a locale without one (plain "en") gets its likely region, as ICU does. */
    fun defaultRegion(): String? {
        val locale = java.util.Locale.getDefault()
        locale.country.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { ULocale.addLikelySubtags(ULocale.forLocale(locale)).country }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** The system for [units]; [region] is read only for Automatic (default: the phone's). */
    fun resolve(units: DistanceUnits, region: () -> String? = ::defaultRegion): LengthSystem = when (units) {
        DistanceUnits.MILES -> LengthSystem.IMPERIAL
        DistanceUnits.KILOMETERS -> LengthSystem.METRIC
        DistanceUnits.AUTOMATIC -> ofRegion(region())
    }
}
