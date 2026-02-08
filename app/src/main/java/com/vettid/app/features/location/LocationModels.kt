package com.vettid.app.features.location

/**
 * Location precision level.
 * EXACT uses ACCESS_FINE_LOCATION (~11m), APPROXIMATE uses ACCESS_COARSE_LOCATION (~1.1km).
 */
enum class LocationPrecision(val decimalPlaces: Int, val displayName: String) {
    EXACT(4, "Exact (~11m)"),
    APPROXIMATE(2, "Approximate (~1.1km)")
}

/**
 * How often the WorkManager worker runs to capture location.
 * Minimum is 15 minutes per WorkManager constraints.
 */
enum class LocationUpdateFrequency(val minutes: Int, val displayName: String) {
    FIFTEEN_MINUTES(15, "Every 15 minutes"),
    THIRTY_MINUTES(30, "Every 30 minutes"),
    ONE_HOUR(60, "Every hour"),
    FOUR_HOURS(240, "Every 4 hours")
}

/**
 * Minimum displacement required before storing a new location point.
 */
enum class DisplacementThreshold(val meters: Int, val displayName: String) {
    ONE_HUNDRED(100, "100 meters"),
    FIVE_HUNDRED(500, "500 meters"),
    ONE_THOUSAND(1000, "1 kilometer")
}

/**
 * How long to keep location data in the vault.
 */
enum class LocationRetention(val days: Int, val displayName: String) {
    SEVEN_DAYS(7, "7 days"),
    THIRTY_DAYS(30, "30 days"),
    NINETY_DAYS(90, "90 days"),
    ONE_YEAR(365, "1 year")
}

/**
 * A location history entry retrieved from the vault.
 */
data class LocationHistoryEntry(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val speed: Float?,
    val timestamp: Long, // epoch seconds
    val source: String,
    val isSummary: Boolean = false
)
