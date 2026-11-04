// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.AskResult
import com.vettid.core.data.location.IncomingShare
import com.vettid.core.data.location.PositionOffset
import com.vettid.core.data.location.ReceivedPosition
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.social.Approval
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeLocation
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.InputGuard
import com.vettid.core.ui.components.LocalInputGuard
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Receiving a connection's location on the screens (ANDROID-PLAN §4 "Location", L1b): the incoming card and Ask for location, the
 * Connections glance, the Location screen and the position as text. A service connection is never asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
@Suppress("TooManyFunctions", "LargeClass")
class ReceiveLocationTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val location = FakeLocation()
    private val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"), FakeSocial.connection("c2", "Kim"))) }
    private val now = Instant.now()

    private fun position(lat: Double = 52.5, lon: Double = 13.4, accuracy: Double? = 1000.0, ago: Long = 300) =
        ReceivedPosition(lat, lon, accuracy, null, null, null, now.minusSeconds(ago), now.minusSeconds(ago))

    private fun share(id: String = "in1", c: String = "c1", last: ReceivedPosition? = position()) =
        IncomingShare(id, c, false, SharePrecision.APPROXIMATE, 60, false, now.minusSeconds(600), now.plusSeconds(3600), last = last)

    private fun <T> TestScope.read(f: kotlinx.coroutines.flow.StateFlow<T>): T {
        advanceUntilIdle()
        return f.value
    }

    private fun detail(s: FakeSocial = social) = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), s, FakeSharing(), location = location)

    // --- the connection page ---

    @Test
    fun theIncomingCardCarriesTheLatestPositionsTimeAndAccuracyButNoCoordinates() = runTest {
        location.incoming.value = listOf(share())
        val vm = detail()
        val c = read(vm.uiState).incoming!!
        assertEquals("in1", c.shareId)
        assertEquals(1000.0, c.accuracyM!!, 0.0)
        assertNotNull(c.at)
        assertFalse(c.toString().contains("52.5"))
        // the page opens the share's latest position (location.get)
        advanceUntilIdle()
        assertEquals(listOf("c1"), location.opened)
    }

    @Test
    fun theCardStatesNoShareNoCardOnlyAskForLocation() = runTest {
        val vm = detail()
        assertNull(read(vm.uiState).incoming)
        val c = FakeSocial.connection("c1", "Sam")
        rule.setContent { ConnectionDetailScreen(ConnectionDetailUiState("c1", c, null, loading = false), DetailActions()) }
        assertEquals(0, rule.onAllNodesWithTag("detail_incoming_location").fetchSemanticsNodes().size)
        rule.onNodeWithTag("detail_ask_location_button").assertIsDisplayed()
    }

    @Test
    fun theCardSaysAgeAccuracyAndUntilAndOffersStopReceiving() {
        val c = FakeSocial.connection("c1", "Sam")
        var stopped = false
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), 1000.0))
        rule.setContent { ConnectionDetailScreen(state, DetailActions(onStopReceiving = { stopped = true })) }
        rule.onNodeWithTag("detail_incoming_line").assertIsDisplayed()
        rule.onNodeWithText("5 min", substring = true).assertIsDisplayed()
        rule.onNodeWithText("within 1 km", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("detail_incoming_until").assertIsDisplayed()
        rule.onNodeWithTag("detail_incoming_stop").performClick()
        assertTrue(stopped)
        // no Ask for location while the connection shares
        assertEquals(0, rule.onAllNodesWithTag("detail_ask_location").fetchSemanticsNodes().size)
    }

    @Test
    fun beforeTheFirstPositionTheCardSaysOnlyUntil() {
        val c = FakeSocial.connection("c1", "Sam")
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), null, null))
        rule.setContent { ConnectionDetailScreen(state, DetailActions()) }
        assertEquals(0, rule.onAllNodesWithTag("detail_incoming_line").fetchSemanticsNodes().size)
        rule.onNodeWithTag("detail_incoming_until").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("detail_incoming_open").fetchSemanticsNodes().size)
    }

    @Test
    fun stopReceivingStopsTheShareAndAFailureIsShown() = runTest {
        location.incoming.value = listOf(share())
        val vm = detail()
        advanceUntilIdle()
        location.fail = VaultFailure(FailureKind.NETWORK)
        vm.stopReceiving()
        assertEquals(FailureKind.NETWORK, read(vm.uiState).error)
        assertTrue(location.stoppedReceiving.isEmpty())
        vm.stopReceiving()
        advanceUntilIdle()
        assertEquals(listOf("in1"), location.stoppedReceiving)
        assertNull(read(vm.uiState).incoming)
    }

    @Test
    fun askForLocationSendsTheNoteKeptTo256Bytes() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.openAsk()
        assertTrue(read(vm.uiState).holdsInput)
        vm.setAskNote("Where are you?")
        vm.setAskNote("x".repeat(257)) // over the vault's bound: not taken
        assertEquals("Where are you?", read(vm.uiState).locationAsk.note)
        vm.setAskNote("é".repeat(128)) // 256 bytes exactly
        assertEquals(128, read(vm.uiState).locationAsk.note.length)
        vm.setAskNote("é".repeat(129))
        assertEquals(128, read(vm.uiState).locationAsk.note.length)
        vm.sendAsk()
        advanceUntilIdle()
        assertEquals("c1" to "é".repeat(128), location.asks.single())
        assertTrue(read(vm.uiState).locationAsk.sent)
        vm.closeAsk()
        assertFalse(read(vm.uiState).holdsInput)
    }

    @Test
    fun aSecondAskWithinTenMinutesShowsTheWaitAndAFailureIsShown() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.openAsk()
        location.askResult = AskResult.Limited(7)
        vm.sendAsk()
        assertEquals(7, read(vm.uiState).locationAsk.limitedMinutes)
        assertFalse(read(vm.uiState).locationAsk.sent)
        location.fail = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        vm.sendAsk()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, read(vm.uiState).locationAsk.error)
    }

    @Test
    fun theAskDialogReportsHeldInputAndSaysItWasSent() {
        val guard = InputGuard()
        val ask = LocationAsk(open = true, sent = true)
        rule.setContent {
            CompositionLocalProvider(LocalInputGuard provides guard) { AskLocationDialog(ask, "Sam", {}, {}, {}) }
        }
        rule.onNodeWithTag("ask_location_sent").assertIsDisplayed()
        assertTrue(guard.active)
    }

    @Test
    fun theLimitTextIsThePlansWithAWorkingPlural() {
        val r = org.robolectric.RuntimeEnvironment.getApplication().resources
        assertEquals(
            "You asked Sam a few minutes ago. You can ask again in 7 minutes.",
            r.getQuantityString(R.plurals.connections_location_ask_limit, 7, "Sam", 7),
        )
        assertEquals(
            "You asked Sam a few minutes ago. You can ask again in 1 minute.",
            r.getQuantityString(R.plurals.connections_location_ask_limit, 1, "Sam", 1),
        )
    }

    @Test
    fun aServiceIsNeverAskedAndGetsNoCard() = runTest {
        val svc = com.vettid.core.data.social.ConnectionInfo("s1", "Acme", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Acme", lastName = "Co-op", service = true)
        val s = FakeSocial().also { it.seed(listOf(svc)) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "s1")), s, FakeSharing(), location = location)
        advanceUntilIdle()
        vm.openAsk()
        assertFalse(read(vm.uiState).locationAsk.open)
        rule.setContent { ConnectionDetailScreen(ConnectionDetailUiState("s1", svc, null, loading = false), DetailActions()) }
        assertEquals(0, rule.onAllNodesWithTag("detail_ask_location").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("detail_incoming_location").fetchSemanticsNodes().size)
    }

    // --- the Connections list ---

    @Test
    fun theGlanceMarksTheConnectionsThatShareWithTheMember() = runTest {
        location.incoming.value = listOf(share("in1", "c1"), share("in2", "c2", last = null))
        val vm = ConnectionsViewModel(social, social, com.vettid.core.testing.FakeVault(), location = location)
        val g = read(vm.uiState).glances
        assertEquals(setOf("c1", "c2"), g.keys)
        assertNotNull(g["c1"]!!.at)
        assertNull(g["c2"]!!.at)
    }

    @Test
    fun theGlanceLineShowsTheIconAndTheAge() {
        rule.setContent { LocationGlanceLine(LocationGlance(Instant.now().minusSeconds(300))) }
        rule.onNodeWithTag("connection_glance").assertIsDisplayed()
        rule.onNodeWithText("5 min").assertIsDisplayed()
    }

    // --- the Location screen ---

    @Test
    fun theLocationScreenListsOutgoingIncomingAndRequests() = runTest {
        location.start(com.vettid.core.data.location.ShareRequest("c2"))
        location.incoming.value = listOf(share("in1", "c1"))
        social.approvals.value = listOf(Approval.LocationRequest("r1", "c1", "hi", now, now.plusSeconds(3600)))
        val vm = LocationViewModel(location, social, social)
        val s = read(vm.uiState)
        assertEquals(listOf("Kim"), s.outgoing.map { it.name })
        assertEquals(listOf("Sam"), s.incoming.map { it.name })
        assertEquals(listOf("location:r1"), s.requests.map { it.key })
        var stopped: String? = null
        rule.setContent {
            LocationScreen(s, com.vettid.core.ui.components.ShellChrome("Me", {}, {}), LocationActions(onStopSharing = { stopped = it }))
        }
        rule.onNodeWithTag("location_out_s1").assertIsDisplayed()
        rule.onNodeWithTag("location_in_in1").assertIsDisplayed()
        rule.onNodeWithTag("location_request_location:r1").assertIsDisplayed()
        rule.onNodeWithText("${AccountNames.isolate("Sam")} asks where you are").assertIsDisplayed()
        rule.onNodeWithTag("location_out_stop_s1").performClick()
        assertEquals("s1", stopped)
    }

    @Test
    fun theLocationScreenIsEmptyWithNothingToShow() {
        rule.setContent { LocationScreen(LocationUiState(), com.vettid.core.ui.components.ShellChrome("Me", {}, {}), LocationActions()) }
        rule.onNodeWithTag("location_empty").assertIsDisplayed()
    }

    @Test
    fun stoppingFromTheLocationScreenGoesThroughTheRepository() = runTest {
        location.incoming.value = listOf(share())
        val vm = LocationViewModel(location, social, social)
        advanceUntilIdle()
        vm.stopReceiving("in1")
        advanceUntilIdle()
        assertEquals(listOf("in1"), location.stoppedReceiving)
        location.fail = VaultFailure(FailureKind.NETWORK)
        vm.stopSharing("x")
        assertEquals(FailureKind.NETWORK, read(vm.uiState).error)
    }

    // --- the position ---

    private fun positionVm() = PositionViewModel(SavedStateHandle(mapOf(PositionRoute.ARG to "c1")), location, social)

    @Test
    fun thePositionScreenShowsCoordinatesAccuracyAgeAndTheOffsetOnlyWhenKnown() = runTest {
        location.incoming.value = listOf(share(last = position(accuracy = 1000.0)))
        val vm = positionVm()
        assertNull(read(vm.uiState).offset) // location not allowed: nothing is shown, nothing asked
        assertEquals(listOf("c1"), location.opened)
        rule.setContent { PositionScreen(read(vm.uiState), {}, {}, {}, {}) }
        rule.onNodeWithTag("position_coordinates").assertIsDisplayed()
        rule.onNodeWithTag("position_accuracy").assertIsDisplayed()
        rule.onNodeWithTag("position_age").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("position_offset").fetchSemanticsNodes().size)
    }

    @Test
    fun theOffsetIsShownWhenThePhoneKnowsWhereItIs() = runTest {
        location.offset = PositionOffset(3200.0, 45.0)
        location.incoming.value = listOf(share())
        val vm = positionVm()
        advanceUntilIdle()
        rule.setContent { PositionScreen(read(vm.uiState), {}, {}, {}, {}) }
        rule.onNodeWithText("About 3.2 km north-east of this phone").assertIsDisplayed()
    }

    @Test
    fun openInMapsAsksFirstAndReportsHeldInput() {
        val guard = InputGuard()
        var asked = false
        val s = PositionUiState(loaded = true, name = "Sam", share = share(), confirmMaps = true)
        assertTrue(s.holdsInput)
        rule.setContent {
            CompositionLocalProvider(LocalInputGuard provides guard) { PositionScreen(s, {}, {}, { asked = true }, {}) }
        }
        rule.onNodeWithText("This hands the position to another app.").assertIsDisplayed()
        assertTrue(guard.active)
        assertFalse(PositionUiState(loaded = true, share = share()).holdsInput)
        assertFalse(asked)
    }

    @Test
    fun noShareAndWaitingForTheFirstPositionAreTold() {
        rule.setContent { PositionScreen(PositionUiState(loaded = true, name = "Sam"), {}, {}, {}, {}) }
        rule.onNodeWithTag("position_none").assertIsDisplayed()
    }

    @Test
    fun theGeoLinkCarriesThePositionWithAPin() {
        val uri = geoUri(52.5, -13.25)
        assertEquals("geo", uri.scheme)
        assertEquals("geo:52.500000,-13.250000?q=52.500000,-13.250000", uri.toString())
    }

    @Test
    fun theUiStatesPrintNoPosition() {
        val s = PositionUiState(loaded = true, name = "Sam", share = share(last = position(52.5123, 13.4567)))
        assertFalse("$s".contains("52.5123"))
        assertEquals("PositionUiState", "$s")
        assertFalse(share(last = position(52.5123, 13.4567)).toString().contains("52.5123"))
    }
}
