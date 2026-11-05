// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.map

import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.DataStorePreferencesRepository
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.prefs.PreferencesFiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The map server setting (Location L2): the URL rules, the default, and the privacy note answered per host. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapServerTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun problem(text: String) = (MapServer.parse(text) as MapServer.Check.Refused).problem

    @Test
    fun theDefaultIsOpenFreeMapLibertyOverHttps() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", MapServer.DEFAULT_STYLE)
        val ok = MapServer.parse(MapServer.DEFAULT_STYLE) as MapServer.Check.Ok
        assertEquals("tiles.openfreemap.org", ok.host)
        val setting = MapServer.of(AppPreferences())
        assertTrue(setting.isDefault)
        assertEquals("tiles.openfreemap.org", setting.host)
    }

    @Test
    fun onlyHttpsWithAHostAndNoCredentialsIsAccepted() {
        assertEquals(MapServer.Problem.NOT_HTTPS, problem("http://tiles.example.org/style.json"))
        assertEquals(MapServer.Problem.NOT_HTTPS, problem("HTTP://tiles.example.org/style.json"))
        assertEquals(MapServer.Problem.NOT_HTTPS, problem("ftp://tiles.example.org/style.json"))
        assertEquals(MapServer.Problem.CREDENTIALS, problem("https://user:pw@tiles.example.org/style.json"))
        assertEquals(MapServer.Problem.CREDENTIALS, problem("https://@tiles.example.org/style.json"))
        assertEquals(MapServer.Problem.NO_HOST, problem("https:///style.json"))
        assertEquals(MapServer.Problem.NO_HOST, problem("https://"))
        assertEquals(MapServer.Problem.NOT_A_URL, problem(""))
        assertEquals(MapServer.Problem.NOT_A_URL, problem("tiles.example.org/style.json"))
        assertEquals(MapServer.Problem.NOT_A_URL, problem("https://tiles example.org/x"))
        assertEquals(MapServer.Problem.NOT_A_URL, problem("https://tiles.example.org/" + "a".repeat(MapServer.MAX_LENGTH)))
        val ok = MapServer.parse("  https://Maps.Example.org:8443/styles/x.json  ") as MapServer.Check.Ok
        assertEquals("maps.example.org:8443", ok.host)
        assertEquals("https://Maps.Example.org:8443/styles/x.json", ok.url)
    }

    @Test
    fun theNoteIsNeededAgainForEveryNewHost() {
        val fresh = MapServer.of(AppPreferences())
        assertFalse("nothing is shown before the note is answered", fresh.noteAnswered)
        val answered = MapServer.of(AppPreferences(mapNoteHost = "tiles.openfreemap.org"))
        assertTrue(answered.noteAnswered)
        // A changed server: the answer was for another host, so the note comes again.
        val changed = MapServer.of(AppPreferences(mapStyleUrl = "https://maps.example.org/s.json", mapNoteHost = "tiles.openfreemap.org"))
        assertFalse(changed.noteAnswered)
        assertEquals("maps.example.org", changed.host)
        // The same host with another path needs no new answer; the case of the host does not matter.
        val samePlace = MapServer.of(AppPreferences(mapStyleUrl = "https://Maps.Example.org/other.json", mapNoteHost = "maps.example.org"))
        assertTrue(samePlace.noteAnswered)
        // Going back to the default is a changed host as well.
        assertFalse(MapServer.of(AppPreferences(mapNoteHost = "maps.example.org")).noteAnswered)
    }

    @Test
    fun anExplicitPortIsAnotherServerForTheNote() {
        val port = MapServer.parse("https://maps.example.org:8443/s.json") as MapServer.Check.Ok
        assertEquals("maps.example.org:8443", port.host)
        assertEquals("maps.example.org", (MapServer.parse("https://maps.example.org:443/s.json") as MapServer.Check.Ok).host)
        val answeredForPlain = AppPreferences(mapStyleUrl = "https://maps.example.org:8443/s.json", mapNoteHost = "maps.example.org")
        assertFalse(MapServer.of(answeredForPlain).noteAnswered)
        val answeredForPort = AppPreferences(mapStyleUrl = "https://maps.example.org:8443/s.json", mapNoteHost = "maps.example.org:8443")
        assertTrue(MapServer.of(answeredForPort).noteAnswered)
        assertFalse(MapServer.of(AppPreferences(mapStyleUrl = "https://maps.example.org/s.json", mapNoteHost = "maps.example.org:8443")).noteAnswered)
    }

    @Test
    fun aStoredAddressThatNoLongerPassesTheRulesFallsBackToTheDefault() {
        val s = MapServer.of(AppPreferences(mapStyleUrl = "http://plain.example.org/s.json", mapNoteHost = "plain.example.org"))
        assertTrue(s.isDefault)
        assertEquals("tiles.openfreemap.org", s.host)
        assertFalse(s.noteAnswered)
    }

    @Test
    fun theSettingsAreKeptInDataStoreAndNothingElse() = runTest {
        val f = File(dir.root, "map.preferences_pb")
        val repo = DataStorePreferencesRepository(PreferencesFiles.open(f, backgroundScope))
        assertNull(repo.preferences.first().mapStyleUrl)
        repo.setMapStyleUrl("https://maps.example.org/s.json")
        repo.setMapNoteHost("maps.example.org")
        val p = repo.preferences.first()
        assertEquals("https://maps.example.org/s.json", p.mapStyleUrl)
        assertEquals("maps.example.org", p.mapNoteHost)
        repo.setMapStyleUrl(null)
        assertNull(repo.preferences.first().mapStyleUrl)
        repo.clear()
        assertNull(repo.preferences.first().mapNoteHost)
    }

    @Test
    fun inMemoryRepositoryMirrorsIt() = runTest {
        val repo = InMemoryPreferencesRepository()
        repo.setMapStyleUrl("https://maps.example.org/s.json")
        repo.setMapNoteHost("maps.example.org")
        assertTrue(MapServer.of(repo.preferences.first()).noteAnswered)
    }
}
