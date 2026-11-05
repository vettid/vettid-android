package com.vettid.core.data.location

import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.DistanceUnits
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** Miles and kilometers (owner decision 2026-11-05): the region default, the override and the text at the boundaries. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class PositionUnitsTest {
    private val r get() = RuntimeEnvironment.getApplication().resources
    private val mi = LengthSystem.IMPERIAL
    private val km = LengthSystem.METRIC

    private fun dec(s: String) = s.replace(".", java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString())

    // --- the region default ---

    @Test
    fun theUsLiberiaAndMyanmarUseMilesEveryOtherRegionKilometers() {
        listOf("US", "LR", "MM").forEach { assertEquals(it, mi, LengthSystems.ofRegion(it)) }
        listOf("GB", "DE", "IN", "FR", "CA", "AU", "JP").forEach { assertEquals(it, km, LengthSystems.ofRegion(it)) }
        assertEquals(km, LengthSystems.ofRegion(null))
        assertEquals(km, LengthSystems.ofRegion(""))
        assertEquals(km, LengthSystems.ofRegion("ZZ"))
    }

    @Test
    fun automaticFollowsTheRegionAndTheChoicesOverrideItBothWays() {
        assertEquals(mi, LengthSystems.resolve(DistanceUnits.AUTOMATIC) { "US" })
        assertEquals(km, LengthSystems.resolve(DistanceUnits.AUTOMATIC) { "GB" })
        assertEquals(km, LengthSystems.resolve(DistanceUnits.KILOMETERS) { "US" })
        assertEquals(mi, LengthSystems.resolve(DistanceUnits.MILES) { "DE" })
        // The region is not even read for an explicit choice.
        assertEquals(mi, LengthSystems.resolve(DistanceUnits.MILES) { error("not read") })
    }

    @Test
    fun theDefaultRegionComesFromTheDefaultLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en-GB"))
            assertEquals("GB", LengthSystems.defaultRegion())
            assertEquals(km, LengthSystems.resolve(DistanceUnits.AUTOMATIC))
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            assertEquals(mi, LengthSystems.resolve(DistanceUnits.AUTOMATIC))
            Locale.setDefault(Locale.forLanguageTag("de-DE"))
            assertEquals(km, LengthSystems.resolve(DistanceUnits.AUTOMATIC))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun theChoiceIsStoredAsAPreferenceAndDefaultsToAutomatic() = runTest {
        val repo = InMemoryPreferencesRepository(AppPreferences())
        assertEquals(DistanceUnits.AUTOMATIC, repo.preferences.first().distanceUnits)
        repo.setDistanceUnits(DistanceUnits.MILES)
        assertEquals(DistanceUnits.MILES, repo.preferences.first().distanceUnits)
    }

    // --- kilometers: as before ---

    @Test
    fun kilometersAreAsTheyWere() {
        assertEquals("10 m", PositionText.distance(r, 3.0, km))
        assertEquals("340 m", PositionText.distance(r, 343.0, km))
        assertEquals(dec("1.2 km"), PositionText.distance(r, 1_240.0, km))
        assertEquals("14 km", PositionText.distance(r, 14_400.0, km))
        assertEquals("50 m", PositionText.accuracy(r, 50.0, km))
        assertEquals(null, PositionText.accuracy(r, 0.0, mi))
    }

    // --- miles ---

    @Test
    fun feetBelowAboutATenthOfAMile() {
        assertEquals("10 ft", PositionText.distance(r, 1.0, mi))
        assertEquals("330 ft", PositionText.distance(r, 100.0, mi))
        // 0.0999 mi = 160.8 m = 527.6 ft
        assertEquals("530 ft", PositionText.distance(r, 0.0999 * 1_609.344, mi))
    }

    @Test
    fun milesWithOneDecimalBelowTenAndWholeAbove() {
        assertEquals(dec("0.1 mi"), PositionText.distance(r, 0.1001 * 1_609.344, mi))
        assertEquals(dec("0.1 mi"), PositionText.distance(r, 0.1 * 1_609.344, mi))
        assertEquals(dec("1.9 mi"), PositionText.distance(r, 3_000.0, mi))
        assertEquals(dec("9.9 mi"), PositionText.distance(r, 9.94 * 1_609.344, mi))
        assertEquals("10 mi", PositionText.distance(r, 10.0 * 1_609.344, mi))
        assertEquals("10 mi", PositionText.distance(r, 9.97 * 1_609.344, mi))
        assertEquals("12 mi", PositionText.distance(r, 12.4 * 1_609.344, mi))
    }

    @Test
    fun milesUseTheLocalesOwnDigits() {
        val old = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        try {
            val t = PositionText.distance(r, 20_000.0, mi)
            assertTrue(t, t.any { it in '٠'..'٩' })
            assertTrue(t, t.none { it in '0'..'9' })
            val f = PositionText.distance(r, 50.0, mi)
            assertTrue(f, f.any { it in '٠'..'٩' })
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun theOffsetTextFollowsTheSystemWithTheSameThresholds() {
        val o = PositionOffset(3_000.0, 0.0)
        val k = PositionText.offset(r, o, 1_000.0, SharePrecision.EXACT, km)
        val m = PositionText.offset(r, o, 1_000.0, SharePrecision.EXACT, mi)
        assertEquals("About ${dec("3")} km north of this phone", k)
        assertEquals("About ${dec("1.9")} mi north of this phone", m)
        // The threshold is meters in both: 800 m inside 1,000 m is near, either way.
        assertEquals("Near this phone", PositionText.offset(r, PositionOffset(800.0, 0.0), 1_000.0, SharePrecision.EXACT, mi))
        assertEquals("Near this phone", PositionText.offset(r, PositionOffset(5.0, 0.0), null, SharePrecision.EXACT, mi))
    }
}
