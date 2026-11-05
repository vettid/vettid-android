// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.collectAsState
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
    // Robolectric's locale is en-US (Automatic would be miles): the older tests read kilometers.
    private val prefs = com.vettid.core.data.prefs.InMemoryPreferencesRepository(
        com.vettid.core.data.prefs.AppPreferences(distanceUnits = com.vettid.core.data.prefs.DistanceUnits.KILOMETERS),
    )
    private val vault = com.vettid.core.testing.FakeVault()
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
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), 1000.0, com.vettid.core.data.location.SharePrecision.APPROXIMATE), units = com.vettid.core.data.prefs.DistanceUnits.KILOMETERS)
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
    fun anUnknownAccuracyShowsNoWithinLineAndNeverWithinZero() {
        val c = FakeSocial.connection("c1", "Sam")
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), null, com.vettid.core.data.location.SharePrecision.APPROXIMATE))
        rule.setContent { ConnectionDetailScreen(state, DetailActions()) }
        rule.onNodeWithTag("detail_incoming_line").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("within", substring = true).fetchSemanticsNodes().size)
        assertEquals(null, com.vettid.core.data.location.PositionText.accuracy(org.robolectric.RuntimeEnvironment.getApplication().resources, 0.0, com.vettid.core.data.location.LengthSystem.METRIC))
    }

    @Test
    fun beforeTheFirstPositionTheCardSaysOnlyUntil() {
        val c = FakeSocial.connection("c1", "Sam")
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), null, null, com.vettid.core.data.location.SharePrecision.APPROXIMATE))
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
        vm.setAskNote("x".repeat(257)) // over the vault's bound: cut to it
        assertEquals("x".repeat(256), read(vm.uiState).locationAsk.note)
        vm.setAskNote("Where are you?")
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

    private fun positionVm() = PositionViewModel(SavedStateHandle(mapOf(PositionRoute.ARG to "c1")), location, social, vault, prefs)

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

    // --- review fixes ---

    @Test
    fun theOffsetIsWorkedOutAgainOnlyWhenThisConnectionsPositionChangesAndOnlyWhileShown() = runTest {
        location.incoming.value = listOf(share("in1", "c1"), share("in2", "c2"))
        val vm = positionVm()
        advanceUntilIdle()
        val first = location.offsetCalls
        assertEquals(1, first)
        // another connection's position changes: nothing is asked
        location.incoming.value = listOf(share("in1", "c1", last = location.incoming.value[0].last), share("in2", "c2", last = position(lat = 1.0)))
        advanceUntilIdle()
        assertEquals(first, location.offsetCalls)
        // this connection's position changes: asked again
        location.incoming.value = listOf(share("in1", "c1", last = position(lat = 52.6)), location.incoming.value[1])
        advanceUntilIdle()
        assertEquals(first + 1, location.offsetCalls)
        // not shown (screen stopped): not asked
        vm.setVisible(false)
        location.incoming.value = listOf(share("in1", "c1", last = position(lat = 52.7)), location.incoming.value[1])
        advanceUntilIdle()
        assertEquals(first + 1, location.offsetCalls)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(first + 2, location.offsetCalls)
    }

    @Test
    fun underAMinuteTheAgeSaysJustNowAndNearIsSaidInsideTheAccuracy() {
        val p = position(ago = 10)
        val s = PositionUiState(loaded = true, name = "Sam", share = share(last = p), offset = PositionOffset(500.0, 90.0))
        rule.setContent { PositionScreen(s, {}, {}, {}, {}) }
        rule.onNodeWithText("Updated just now").assertIsDisplayed()
        // 500 m from a position that is only known to 1 km: no direction is made up
        rule.onNodeWithText("Near this phone").assertIsDisplayed()
    }

    @Test
    fun coordinatesAreFormattedTheSameInEveryLocale() {
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            val s = PositionUiState(loaded = true, name = "Sam", share = share(last = position(52.52, 13.405)))
            rule.setContent { PositionScreen(s, {}, {}, {}, {}) }
            rule.onNodeWithText("52.52000, 13.40500").assertIsDisplayed()
        } finally {
            java.util.Locale.setDefault(java.util.Locale.US)
        }
    }

    @Test
    fun duringTheHoldTheScreenDoesNotSayTheyAreNotSharing() {
        val s = PositionUiState(loaded = true, name = "Sam", held = true)
        rule.setContent { PositionScreen(s, {}, {}, {}, {}) }
        assertEquals(0, rule.onAllNodesWithTag("position_none").fetchSemanticsNodes().size)
    }

    @Test
    fun theDistanceIsRoundedCoarselyAndNeverAboutOneMetre() {
        val r = org.robolectric.RuntimeEnvironment.getApplication().resources
        assertEquals("10 m", com.vettid.core.data.location.PositionText.distance(r, 3.0, com.vettid.core.data.location.LengthSystem.METRIC))
        assertEquals("1.2 km".replace(".", java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString()), com.vettid.core.data.location.PositionText.distance(r, 1_240.0, com.vettid.core.data.location.LengthSystem.METRIC))
        assertEquals("340 m", com.vettid.core.data.location.PositionText.distance(r, 343.0, com.vettid.core.data.location.LengthSystem.METRIC))
        assertEquals("14 km", com.vettid.core.data.location.PositionText.distance(r, 14_400.0, com.vettid.core.data.location.LengthSystem.METRIC))
        assertEquals("Near this phone", com.vettid.core.data.location.PositionText.offset(r, PositionOffset(4.0, 10.0), null, SharePrecision.EXACT, com.vettid.core.data.location.LengthSystem.METRIC))
        assertEquals("Near this phone", com.vettid.core.data.location.PositionText.offset(r, PositionOffset(800.0, 10.0), 1_000.0, SharePrecision.EXACT, com.vettid.core.data.location.LengthSystem.METRIC))
        assertTrue(com.vettid.core.data.location.PositionText.offset(r, PositionOffset(3_000.0, 0.0), 1_000.0, SharePrecision.EXACT, com.vettid.core.data.location.LengthSystem.METRIC).contains("north"))
    }

    @Test
    fun theIncomingCardAndTheAskDialogUseTheFirstName() = runTest {
        val c = com.vettid.core.data.social.ConnectionInfo("c1", "Dr Lee (work)", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")
        assertEquals(AccountNames.isolate("Sam"), firstOf(c, "x"))
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), 1000.0, com.vettid.core.data.location.SharePrecision.APPROXIMATE))
        rule.setContent { ConnectionDetailScreen(state, DetailActions()) }
        rule.onNodeWithText("${AccountNames.isolate("Sam")}'s location", substring = true).assertIsDisplayed()
    }

    @Test
    fun aPastedNoteIsCutAtACharacterAndTheDialogSaysSo() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.openAsk()
        val pasted = "\u00e9".repeat(200) + "\uD83D\uDE00" // 400 bytes, then an emoji
        vm.setAskNote(pasted)
        val ask = read(vm.uiState).locationAsk
        assertEquals(128, ask.note.length) // 128 two-byte letters = 256 bytes
        assertTrue(ask.trimmed)
        assertTrue(ask.note.toByteArray().size <= 256)
        // no half of a surrogate pair
        vm.setAskNote("a".repeat(255) + "\uD83D\uDE00")
        assertEquals(255, read(vm.uiState).locationAsk.note.length)
        vm.setAskNote("short")
        assertFalse(read(vm.uiState).locationAsk.trimmed)
    }

    @Test
    fun anAnswerForAClosedDialogDoesNotMarkAReopenedOneSent() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.openAsk()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        location.askGate = gate
        vm.sendAsk()
        vm.closeAsk()
        vm.openAsk()
        gate.complete(Unit)
        advanceUntilIdle()
        val ask = read(vm.uiState).locationAsk
        assertTrue(ask.open)
        assertFalse(ask.sent)
        assertFalse(ask.busy)
    }

    @Test
    fun aStopOnTheLocationScreenWaitsWhileItsStopIsOut() = runTest {
        location.incoming.value = listOf(share())
        val vm = LocationViewModel(location, social, social)
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        location.stopGate = gate
        vm.stopReceiving("in1")
        vm.stopReceiving("in1") // a second tap
        advanceUntilIdle()
        assertTrue(read(vm.uiState).incoming.single().busy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("in1"), location.stoppedReceiving)
    }

    @Test
    fun aShareThatEndedIsNotShownEvenIfTheRepositoryStillHoldsIt() = runTest {
        location.incoming.value = listOf(share().copy(expiresAt = now.minusSeconds(5)))
        assertNull(read(detail().uiState).incoming)
        val vm = LocationViewModel(location, social, social)
        assertTrue(read(vm.uiState).incoming.isEmpty())
        assertTrue(read(ConnectionsViewModel(social, social, vault, location = location).uiState).glances.isEmpty())
    }

    @Test
    fun theScreensClockLooksAtTheDailyCheckAgainWithoutLoopingInTheViewModel() = runTest {
        location.incoming.value = listOf(share())
        val vm = positionVm()
        advanceUntilIdle() // would never return if the view model ticked by itself
        vm.look()
        advanceUntilIdle()
        assertFalse(read(vm.uiState).held)
    }

    @Test
    fun theGlanceDescriptionIsOneSentenceWithoutTheAge() {
        val r = org.robolectric.RuntimeEnvironment.getApplication().resources
        assertEquals("Shares their location with you", r.getString(R.string.connections_location_glance))
    }

    // --- precision, L1-L4 ---

    @Test
    fun theOffsetThresholdIsTheLargerOfTheAccuracyAndTheSharesOwnArea() {
        val r = org.robolectric.RuntimeEnvironment.getApplication().resources
        val text = com.vettid.core.data.location.PositionText
        val approx = SharePrecision.APPROXIMATE
        val city = SharePrecision.CITY
        // City, accuracy unknown: 5 km from the centre of a ~11 km area is still "near"
        assertEquals("Near this phone", text.offset(r, PositionOffset(5_000.0, 10.0), null, city, com.vettid.core.data.location.LengthSystem.METRIC))
        assertTrue(text.offset(r, PositionOffset(15_000.0, 0.0), null, city, com.vettid.core.data.location.LengthSystem.METRIC).contains("north"))
        assertEquals("Near this phone", text.offset(r, PositionOffset(800.0, 10.0), 0.0, approx, com.vettid.core.data.location.LengthSystem.METRIC))
        assertTrue(text.offset(r, PositionOffset(3_000.0, 0.0), null, approx, com.vettid.core.data.location.LengthSystem.METRIC).contains("north"))
        // Exact with no accuracy: a direction is given from 800 m
        assertTrue(text.offset(r, PositionOffset(800.0, 0.0), null, SharePrecision.EXACT, com.vettid.core.data.location.LengthSystem.METRIC).contains("north"))
    }

    @Test
    fun theCardAndThePositionScreenSayThePrecisionAndMarkAnAreaWhenNotExact() {
        val c = FakeSocial.connection("c1", "Sam")
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), null, SharePrecision.CITY))
        rule.setContent { ConnectionDetailScreen(state, DetailActions()) }
        rule.onNodeWithTag("detail_incoming_precision").assertIsDisplayed()
        rule.onNodeWithText("City").assertIsDisplayed()
    }

    @Test
    fun thePositionScreenMarksAnApproximateAreaButNotAnExactSpot() {
        val city = share().copy(precision = SharePrecision.CITY)
        rule.setContent { PositionScreen(PositionUiState(loaded = true, name = "Sam", share = city), {}, {}, {}, {}) }
        rule.onNodeWithTag("position_area").assertIsDisplayed()
        rule.onNodeWithTag("position_precision").assertIsDisplayed()
    }

    @Test
    fun anExactShareHasNoAreaMark() {
        val exact = share().copy(precision = SharePrecision.EXACT)
        rule.setContent { PositionScreen(PositionUiState(loaded = true, name = "Sam", share = exact), {}, {}, {}, {}) }
        assertEquals(0, rule.onAllNodesWithTag("position_area").fetchSemanticsNodes().size)
    }

    @Test
    fun anOverdueCheckHidesThePositionEvenWhileTheStateIsStillOk() = runTest {
        location.incoming.value = listOf(share())
        vault.ownerCheck.value = com.vettid.core.data.vault.OwnerCheckView(
            com.vettid.core.data.vault.OwnerCheckState.OK, Instant.now().minusSeconds(60), 86_400, 0, false, null,
        )
        val vm = positionVm()
        val s = read(vm.uiState)
        assertTrue(s.held)
        assertNull(s.share)
        assertNull(s.offset)
    }

    @Test
    fun aStaleDistanceIsClearedWhenThePositionChangesOrGoes() = runTest {
        location.offset = PositionOffset(3_000.0, 0.0)
        location.incoming.value = listOf(share())
        val vm = positionVm()
        assertEquals(3_000.0, read(vm.uiState).offset!!.meters, 0.0)
        // a new position: the next recompute answers null (e.g. no fix), and the old distance does not stay
        location.offset = null
        location.incoming.value = listOf(share(last = position(lat = 52.9)))
        assertNull(read(vm.uiState).offset)
        location.offset = PositionOffset(10_000.0, 0.0)
        location.incoming.value = listOf(share(last = position(lat = 53.0)))
        assertEquals(10_000.0, read(vm.uiState).offset!!.meters, 0.0)
        // the position goes: the distance goes with it
        location.incoming.value = listOf(share(last = null))
        assertNull(read(vm.uiState).offset)
    }

    @Test
    fun theDistanceUsesTheLocalesOwnDigitsWithoutStringSurgery() {
        val r = org.robolectric.RuntimeEnvironment.getApplication().resources
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
        try {
            val t = com.vettid.core.data.location.PositionText.distance(r, 12_000.0, com.vettid.core.data.location.LengthSystem.METRIC)
            assertTrue(t, t.any { it in '\u0660'..'\u0669' })
            assertTrue(t, t.none { it in '0'..'9' })
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    // --- miles or kilometers (Settings → Units, owner decision 2026-11-05) ---

    private fun dec(s: String) = s.replace(".", java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString())

    @Test
    fun theIncomingCardsWithinFollowsTheUnits() {
        val c = FakeSocial.connection("c1", "Sam")
        val incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), 1000.0, com.vettid.core.data.location.SharePrecision.APPROXIMATE)
        val state = ConnectionDetailUiState("c1", c, null, loading = false, incoming = incoming, units = com.vettid.core.data.prefs.DistanceUnits.MILES)
        rule.setContent { ConnectionDetailScreen(state, DetailActions()) }
        rule.onNodeWithText(dec("within 0.6 mi"), substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("within 1 km", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDetailViewModelResolvesTheUnitsFromTheSetting() = runTest {
        val p = com.vettid.core.data.prefs.InMemoryPreferencesRepository()
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location, prefs = p)
        p.setDistanceUnits(com.vettid.core.data.prefs.DistanceUnits.KILOMETERS)
        assertEquals(com.vettid.core.data.prefs.DistanceUnits.KILOMETERS, read(vm.uiState).units)
        p.setDistanceUnits(com.vettid.core.data.prefs.DistanceUnits.MILES)
        assertEquals(com.vettid.core.data.prefs.DistanceUnits.MILES, read(vm.uiState).units)
    }

    @Test
    fun thePositionScreenWithinAndAboutFollowTheSettingAtOnce() = runTest {
        location.incoming.value = listOf(share(last = position(accuracy = 1000.0)))
        location.offset = PositionOffset(3_000.0, 0.0)
        val vm = positionVm()
        prefs.setDistanceUnits(com.vettid.core.data.prefs.DistanceUnits.KILOMETERS)
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.prefs.DistanceUnits.KILOMETERS, read(vm.uiState).units)
        rule.setContent { PositionScreen(vm.uiState.collectAsState().value, {}, {}, {}, {}) }
        rule.onNodeWithText("Within ${dec("1")} km").assertIsDisplayed()
        rule.onNodeWithText("About ${dec("3")} km north of this phone").assertIsDisplayed()
        prefs.setDistanceUnits(com.vettid.core.data.prefs.DistanceUnits.MILES)
        advanceUntilIdle()
        rule.waitForIdle()
        rule.onNodeWithText("Within ${dec("0.6")} mi").assertIsDisplayed()
        rule.onNodeWithText("About ${dec("1.9")} mi north of this phone").assertIsDisplayed()
    }

    private fun automaticCard() {
        val c = FakeSocial.connection("c1", "Sam")
        val incoming = DetailIncoming("in1", now.plusSeconds(3600), now.minusSeconds(300), 1000.0, com.vettid.core.data.location.SharePrecision.APPROXIMATE)
        rule.setContent { ConnectionDetailScreen(ConnectionDetailUiState("c1", c, null, loading = false, incoming = incoming), DetailActions()) }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "en-rUS-w411dp-h2400dp")
    fun automaticReadsTheConfigurationsRegionUs() {
        automaticCard()
        rule.onNodeWithText(dec("within 0.6 mi"), substring = true).assertIsDisplayed()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "en-rGB-w411dp-h2400dp")
    fun automaticReadsTheConfigurationsRegionGb() {
        automaticCard()
        rule.onNodeWithText("within 1 km", substring = true).assertIsDisplayed()
    }
}
