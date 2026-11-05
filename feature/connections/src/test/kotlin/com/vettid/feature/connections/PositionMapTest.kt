// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.location.IncomingShare
import com.vettid.core.data.location.ReceivedPosition
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.map.MapServer
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.map.MapContent
import com.vettid.core.map.MapSurface
import com.vettid.core.testing.FakeLocation
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
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
import java.time.Instant

/** The embedded map on the position screen (Location L2), through a fake [MapSurface]: no native code, no tile request. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class PositionMapTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val location = FakeLocation()
    private val vault = FakeVault()
    private val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
    private val prefs = InMemoryPreferencesRepository()
    private val now = Instant.now()

    /** What the screen asked the map to draw. Empty means nothing was fetched: the real surface is what loads tiles. */
    private class RecordingMap : MapSurface {
        val shown = mutableListOf<MapContent>()

        @Composable
        override fun Show(content: MapContent, modifier: Modifier) {
            shown += content
            Box(modifier.testTag("fake_map"))
        }
    }

    private val map = RecordingMap()

    private fun position(lat: Double = 52.5, lon: Double = 13.4, accuracy: Double? = 30.0, ago: Long = 300) =
        ReceivedPosition(lat, lon, accuracy, null, null, null, now.minusSeconds(ago), now.minusSeconds(ago))

    private fun share(
        precision: SharePrecision = SharePrecision.APPROXIMATE,
        last: ReceivedPosition? = position(),
        trail: Boolean = false,
        points: List<ReceivedPosition> = emptyList(),
    ) = IncomingShare("in1", "c1", false, precision, 60, trail, now.minusSeconds(600), now.plusSeconds(3600), last = last, trailPoints = points)

    private fun vm() = PositionViewModel(SavedStateHandle(mapOf(PositionRoute.ARG to "c1")), location, social, vault, prefs)

    private fun show(vm: PositionViewModel) = rule.setContent {
        val s by vm.uiState.collectAsState()
        PositionScreen(s, {}, {}, {}, {}, vm::showMap, vm::mapNotNow, map)
    }

    private fun noMap() = assertEquals(0, rule.onAllNodesWithTag("fake_map").fetchSemanticsNodes().size)

    @Test
    fun beforeTheNoteIsAnsweredTheNoteIsShownAndNothingIsFetched() = runTest {
        location.incoming.value = listOf(share())
        val vm = vm()
        advanceUntilIdle()
        show(vm)
        rule.onNodeWithTag("position_map_note").assertIsDisplayed()
        rule.onNodeWithTag("position_map_show").assertIsDisplayed()
        rule.onNodeWithTag("position_map_not_now").assertIsDisplayed()
        val note = rule.activity()
        assertTrue(map.shown.isEmpty())
        noMap()
        assertEquals(
            "To show the map, VettID loads map images from this server. The server sees which area you look at, this phone's internet address and when you look. It doesn't learn whose location it is. VettID's own servers see none of it. Use your own server to keep this to yourself.",
            note.getString(R.string.connections_location_map_note),
        )
        assertEquals("This map's server is tiles.openfreemap.org.", note.getString(R.string.connections_location_map_server, "tiles.openfreemap.org"))
        // The text of the position stays on the screen.
        rule.onNodeWithTag("position_coordinates").assertIsDisplayed()
    }

    @Test
    fun theNoteGateAndTheRequestRuleAgreeOnTheServer() {
        for (u in listOf("https://tiles.openfreemap.org/styles/liberty", "https://Maps.Example.org:8443/s.json", "https://maps.example.org:443/s")) {
            val ok = MapServer.parse(u) as MapServer.Check.Ok
            assertEquals(ok.host, com.vettid.core.map.MapRequestPolicy.authorityOf(u))
        }
    }

    @Test
    fun showMapAnswersTheNoteForTheHostAndThenTheMapAppears() = runTest {
        location.incoming.value = listOf(share())
        val vm = vm()
        advanceUntilIdle()
        show(vm)
        rule.onNodeWithTag("position_map_show").performClick()
        advanceUntilIdle()
        rule.waitForIdle()
        assertEquals("tiles.openfreemap.org", prefs.preferences.first().mapNoteHost)
        rule.onNodeWithTag("fake_map").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("position_map_note").fetchSemanticsNodes().size)
        assertEquals(MapServer.DEFAULT_STYLE, map.shown.last().styleUrl)
        // The coordinates, precision, Within, Updated stay below the map.
        rule.onNodeWithTag("position_coordinates").assertIsDisplayed()
        rule.onNodeWithTag("position_accuracy").assertIsDisplayed()
        rule.onNodeWithTag("position_age").assertIsDisplayed()
        rule.onNodeWithTag("position_open_maps").assertIsDisplayed()
    }

    @Test
    fun aChangedServerNeedsTheNoteAgainBeforeAnythingIsFetched() = runTest {
        prefs.setMapNoteHost("tiles.openfreemap.org")
        location.incoming.value = listOf(share())
        val vm = vm()
        advanceUntilIdle()
        show(vm)
        rule.onNodeWithTag("fake_map").assertIsDisplayed()
        val before = map.shown.size
        prefs.setMapStyleUrl("https://maps.example.org/style.json")
        advanceUntilIdle()
        rule.waitForIdle()
        noMap()
        rule.onNodeWithTag("position_map_note").assertIsDisplayed()
        assertEquals("the new host is not asked until the note is answered", before, map.shown.size)
        assertTrue(map.shown.none { it.styleUrl.contains("maps.example.org") })
        rule.onNodeWithTag("position_map_show").performClick()
        advanceUntilIdle()
        rule.waitForIdle()
        assertEquals("maps.example.org", prefs.preferences.first().mapNoteHost)
        assertEquals("https://maps.example.org/style.json", map.shown.last().styleUrl)
    }

    @Test
    fun notNowFetchesNothingAndAnswersNothing() = runTest {
        location.incoming.value = listOf(share())
        val vm = vm()
        advanceUntilIdle()
        show(vm)
        rule.onNodeWithTag("position_map_not_now").performClick()
        advanceUntilIdle()
        rule.waitForIdle()
        noMap()
        assertEquals(0, rule.onAllNodesWithTag("position_map_note").fetchSemanticsNodes().size)
        assertNull(prefs.preferences.first().mapNoteHost)
        assertTrue(map.shown.isEmpty())
        rule.onNodeWithTag("position_coordinates").assertIsDisplayed()
    }

    @Test
    fun theCircleIsTheLargerOfAccuracyAndTheSharesArea() {
        val p = position(accuracy = 30.0)
        assertEquals(1_000.0, mapContentOf(share(SharePrecision.APPROXIMATE), p, "https://x.example/s").radiusM, 0.0)
        assertEquals(30.0, mapContentOf(share(SharePrecision.EXACT), p, "https://x.example/s").radiusM, 0.0)
        assertEquals(5_000.0, mapContentOf(share(SharePrecision.APPROXIMATE), position(accuracy = 5_000.0), "https://x.example/s").radiusM, 0.0)
        assertTrue(mapContentOf(share(SharePrecision.CITY), p, "https://x.example/s").radiusM >= 10_000.0)
        assertEquals(0.0, mapContentOf(share(SharePrecision.EXACT), position(accuracy = null), "https://x.example/s").radiusM, 0.0)
    }

    @Test
    fun theTrailIsDrawnOnlyWhenTheShareAllowsItAndItIsHeld() {
        val older = position(lat = 52.4, ago = 600)
        val last = position(lat = 52.5, ago = 60)
        assertEquals(2, mapContentOf(share(trail = true, last = last, points = listOf(older)), last, "https://x.example/s").trail.size)
        assertTrue("no history allowed", mapContentOf(share(trail = false, last = last, points = listOf(older)), last, "https://x.example/s").trail.isEmpty())
        assertTrue("no trail held", mapContentOf(share(trail = true, last = last), last, "https://x.example/s").trail.isEmpty())
        val sorted = mapContentOf(share(trail = true, last = last, points = listOf(last, older)), last, "https://x.example/s").trail
        assertEquals(listOf(52.4, 52.5), sorted.map { it.lat })
    }

    @Test
    fun theMapReceivesTheContentWithTheCircleAndTheTrail() = runTest {
        prefs.setMapNoteHost("tiles.openfreemap.org")
        val last = position(lat = 52.5, ago = 60)
        location.incoming.value = listOf(share(SharePrecision.APPROXIMATE, last, trail = true, points = listOf(position(lat = 52.4, ago = 600))))
        val vm = vm()
        advanceUntilIdle()
        show(vm)
        rule.onNodeWithTag("fake_map").assertIsDisplayed()
        val c = map.shown.last()
        assertEquals(1_000.0, c.radiusM, 0.0)
        assertEquals(2, c.trail.size)
        assertEquals(52.5, c.position.lat, 0.0)
        assertEquals("the camera re-frames for another share", "in1", c.shareKey)
        assertEquals("MapContent", c.toString())
    }

    @Test
    fun noMapAndNoNoteWhileTheCheckHoldsTheShareBack() = runTest {
        prefs.setMapNoteHost("tiles.openfreemap.org")
        location.incoming.value = listOf(share())
        vault.ownerCheck.value = com.vettid.core.data.vault.OwnerCheckView(
            com.vettid.core.data.vault.OwnerCheckState.OK, Instant.now().minusSeconds(60), 86_400, 0, false, null,
        )
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.held)
        assertNull(vm.uiState.value.share)
        show(vm)
        noMap()
        assertEquals(0, rule.onAllNodesWithTag("position_map_note").fetchSemanticsNodes().size)
        assertTrue(map.shown.isEmpty())
    }

    @Test
    fun noMapWhenTheShareEndsOrHasNoPositionYet() = runTest {
        prefs.setMapNoteHost("tiles.openfreemap.org")
        location.incoming.value = listOf(share(last = null))
        val vm = vm()
        advanceUntilIdle()
        show(vm)
        noMap()
        location.incoming.value = listOf(share())
        advanceUntilIdle()
        rule.waitForIdle()
        rule.onNodeWithTag("fake_map").assertIsDisplayed()
        // The share ends: the map goes with it.
        location.incoming.value = emptyList()
        advanceUntilIdle()
        rule.waitForIdle()
        noMap()
        assertFalse(vm.uiState.value.share != null)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.activity() = org.robolectric.RuntimeEnvironment.getApplication()
}
