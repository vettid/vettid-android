package com.vettid.core.data.location

import com.vettid.core.data.prefs.DistanceUnits

/** How distances are written: meters and kilometers, or feet and miles. */
enum class LengthSystem { METRIC, IMPERIAL }

/**
 * Which [LengthSystem] a distance is shown in (owner decision 2026-11-05): the member's choice, or for Automatic the phone's region.
 * Only the region is read, never a position.
 */
object LengthSystems {
    /** The regions that count in miles (the owner's list); every other region, the UK included, is kilometers. */
    private val MILE_REGIONS = setOf("US", "LR", "MM")

    /** Imperial for the US, Liberia and Myanmar; metric for every other region and for none (a locale such as plain "en"). */
    fun ofRegion(region: String?): LengthSystem =
        if (region?.uppercase() in MILE_REGIONS) LengthSystem.IMPERIAL else LengthSystem.METRIC

    /** The region of the phone's default locale; null when it has none. No region is guessed. */
    fun defaultRegion(): String? = java.util.Locale.getDefault().country.takeIf { it.isNotEmpty() }

    /** The system for [units]; [region] is read only for Automatic (default: the phone's). */
    fun resolve(units: DistanceUnits, region: () -> String? = ::defaultRegion): LengthSystem = when (units) {
        DistanceUnits.MILES -> LengthSystem.IMPERIAL
        DistanceUnits.KILOMETERS -> LengthSystem.METRIC
        DistanceUnits.AUTOMATIC -> ofRegion(region())
    }
}
