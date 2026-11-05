package com.vettid.core.data.prefs

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The units choice (owner decision 2026-11-05) in the real DataStore: kept, read back, and safe against a value it does not know. */
@OptIn(ExperimentalCoroutinesApi::class)
class DistanceUnitsPreferenceTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun theChoiceIsKeptInDataStoreAndClearGoesBackToAutomatic() = runTest {
        val repo = DataStorePreferencesRepository(PreferencesFiles.open(File(dir.root, "units.preferences_pb"), backgroundScope))
        assertEquals(DistanceUnits.AUTOMATIC, repo.preferences.first().distanceUnits)
        repo.setDistanceUnits(DistanceUnits.MILES)
        assertEquals(DistanceUnits.MILES, repo.preferences.first().distanceUnits)
        repo.setDistanceUnits(DistanceUnits.KILOMETERS)
        assertEquals(DistanceUnits.KILOMETERS, repo.preferences.first().distanceUnits)
        repo.clear()
        assertEquals(DistanceUnits.AUTOMATIC, repo.preferences.first().distanceUnits)
    }

    @Test
    fun aStoredValueThatIsNotAChoiceReadsAsAutomatic() = runTest {
        val store = PreferencesFiles.open(File(dir.root, "units2.preferences_pb"), backgroundScope)
        store.edit { it[stringPreferencesKey("distance_units")] = "FURLONGS" }
        assertEquals(DistanceUnits.AUTOMATIC, DataStorePreferencesRepository(store).preferences.first().distanceUnits)
    }
}
