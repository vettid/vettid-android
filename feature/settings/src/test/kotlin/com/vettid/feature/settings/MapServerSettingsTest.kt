package com.vettid.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import com.vettid.core.data.map.MapServer
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Privacy → Map server (Location L2): the form, its rules and its `holdsInput`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class MapServerSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val prefs = InMemoryPreferencesRepository()

    private fun vm() = MapServerViewModel(prefs)

    @Test
    fun startsOnTheDefaultAndHoldsNothing() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.loaded)
        assertEquals(MapServer.DEFAULT_STYLE, s.text)
        assertEquals("tiles.openfreemap.org", s.host)
        assertFalse("nothing edited, nothing held", s.holdsInput)
        assertFalse(s.canSave)
    }

    @Test
    fun anEditedAddressHoldsInputUntilItIsSavedOrRestored() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.setText("https://maps.example.org/s.json")
        assertTrue(vm.uiState.value.holdsInput)
        vm.setText(MapServer.DEFAULT_STYLE)
        assertFalse(vm.uiState.value.holdsInput)
        vm.setText("https://maps.example.org/s.json")
        vm.save()
        advanceUntilIdle()
        assertFalse("saved: not held any more", vm.uiState.value.holdsInput)
        assertEquals("https://maps.example.org/s.json", prefs.preferences.first().mapStyleUrl)
        assertEquals("maps.example.org", vm.uiState.value.host)
    }

    @Test
    fun refusedAddressesSaveNothingAndSayWhy() = runTest {
        val vm = vm()
        advanceUntilIdle()
        for ((text, problem) in listOf(
            "http://maps.example.org/s.json" to MapServer.Problem.NOT_HTTPS,
            "https://u:p@maps.example.org/s.json" to MapServer.Problem.CREDENTIALS,
            "https:///s.json" to MapServer.Problem.NO_HOST,
            "maps" to MapServer.Problem.NOT_A_URL,
        )) {
            vm.setText(text)
            vm.save()
            advanceUntilIdle()
            assertEquals(text, problem, vm.uiState.value.problem)
            assertNull(prefs.preferences.first().mapStyleUrl)
        }
        // Still being edited: the owner check waits.
        assertTrue(vm.uiState.value.holdsInput)
    }

    @Test
    fun useDefaultGoesBackAtOnceAndSavingTheDefaultStoresNoOwnServer() = runTest {
        prefs.setMapStyleUrl("https://maps.example.org/s.json")
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isDefault)
        vm.useDefault()
        advanceUntilIdle()
        assertNull(prefs.preferences.first().mapStyleUrl)
        assertEquals(MapServer.DEFAULT_STYLE, vm.uiState.value.text)
        vm.setText("https://maps.example.org/s.json")
        vm.save()
        advanceUntilIdle()
        vm.setText(MapServer.DEFAULT_STYLE)
        vm.save()
        advanceUntilIdle()
        assertNull(prefs.preferences.first().mapStyleUrl)
        assertTrue(vm.uiState.value.isDefault)
    }

    @Test
    fun theFormShowsTheHostTheProblemAndTheNote() {
        rule.setContent {
            MapServerContent(
                MapServerUiState(loaded = true, text = "http://x.example", storedText = MapServer.DEFAULT_STYLE, host = "tiles.openfreemap.org", problem = MapServer.Problem.NOT_HTTPS),
                {}, {}, {}, {},
            )
        }
        rule.onNodeWithTag("map_server_url").assertIsDisplayed()
        rule.onNodeWithTag("map_server_problem").assertIsDisplayed()
        rule.onNodeWithTag("map_server_default").assertIsDisplayed()
        rule.onNodeWithTag("map_server_note").assertIsDisplayed()
    }
}
