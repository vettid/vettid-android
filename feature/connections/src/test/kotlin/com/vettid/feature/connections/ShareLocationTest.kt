// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.location.ShareInterval
import com.vettid.core.data.location.ShareLength
import com.vettid.core.data.location.SharePrecision
import com.vettid.core.data.location.ShareTiming
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeLocation
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.InputGuard
import com.vettid.core.ui.components.LocalInputGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

/** The share sheet and the connection page's location card (ANDROID-PLAN §4 "Location", L1a). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class ShareLocationTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val location = FakeLocation()
    private val sam = AccountNames.isolate("Sam")

    /** The view model's state flows run on the test's main dispatcher: let them catch up first. */
    private fun <T> TestScope.read(f: kotlinx.coroutines.flow.StateFlow<T>): T {
        advanceUntilIdle()
        return f.value
    }

    private fun vm() = ShareLocationViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), location)

    // --- the choices and the vault's bounds ---

    @Test
    fun theDefaultsAreForAWhileOneHourApproximateEveryMinuteWithoutTheTrail() = runTest {
        val vm = vm()
        vm.open()
        val s = read(vm.uiState)
        assertTrue(s.open && s.visible)
        assertFalse(s.once)
        assertEquals(ShareLength.HOUR_1, s.length)
        assertEquals(SharePrecision.APPROXIMATE, s.precision)
        assertEquals(ShareInterval.MINUTE_1, s.interval)
        assertFalse(s.trail)
    }

    @Test
    fun startSendsTheChosenShareWithinTheVaultsBounds() = runTest {
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        vm.setLength(ShareLength.HOURS_8)
        vm.setPrecision(SharePrecision.EXACT)
        vm.setInterval(ShareInterval.MINUTES_5)
        vm.setTrail(true)
        vm.start()
        advanceUntilIdle()
        val r = location.started.single()
        assertEquals("c1", r.connectionId)
        assertEquals(ShareTiming.ForAWhile(ShareLength.HOURS_8, ShareInterval.MINUTES_5), r.timing)
        assertEquals(28_800L, r.durationSeconds)
        assertEquals(300L, r.intervalSeconds)
        assertEquals(SharePrecision.EXACT, r.precision)
        assertTrue(r.trail)
        assertTrue(r.withinBounds())
    }

    @Test
    fun aOnceShareHasNoDurationAndNoIntervalAndNoTrail() = runTest {
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.APPROXIMATE)
        vm.setTrail(true)
        vm.setOnce(true)
        vm.start()
        advanceUntilIdle()
        val r = location.started.single()
        assertEquals(ShareTiming.Once, r.timing)
        assertNull(r.durationSeconds)
        assertNull(r.intervalSeconds)
        assertFalse("one position has no trail", r.trail)
        assertTrue(r.withinBounds())
    }

    // --- the permission ---

    @Test
    fun nothingStartsWithoutThePermissionAndRefusalSaysSo() = runTest {
        val vm = vm()
        vm.open()
        vm.start()
        advanceUntilIdle()
        assertTrue(location.started.isEmpty())
        vm.permissionResult(LocationPermission.NONE)
        assertTrue(read(vm.uiState).permissionDenied)
        advanceUntilIdle()
        assertTrue(location.started.isEmpty())
    }

    @Test
    fun grantingThePermissionStartsTheShare() = runTest {
        val vm = vm()
        vm.open()
        vm.permissionResult(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertEquals(1, location.started.size)
        assertFalse(read(vm.uiState).permissionDenied)
    }

    @Test
    fun withOnlyApproximateAccessExactIsNotOfferedAndApproximateAndCityWork() = runTest {
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.APPROXIMATE)
        assertFalse(read(vm.uiState).exactAvailable)
        vm.setPrecision(SharePrecision.EXACT)
        assertEquals("Exact cannot be chosen", SharePrecision.APPROXIMATE, read(vm.uiState).precision)
        vm.setPrecision(SharePrecision.CITY)
        assertEquals(SharePrecision.CITY, read(vm.uiState).precision)
        vm.start()
        advanceUntilIdle()
        assertEquals(SharePrecision.CITY, location.started.single().precision)
    }

    @Test
    fun exactChosenThenOnlyApproximateGrantedFallsBackAndWaitsForTheMember() = runTest {
        val vm = vm()
        vm.open()
        vm.setPrecision(SharePrecision.EXACT) // before the first ask Exact is offered
        assertEquals(SharePrecision.EXACT, read(vm.uiState).precision)
        vm.permissionResult(LocationPermission.APPROXIMATE)
        advanceUntilIdle()
        assertEquals(SharePrecision.APPROXIMATE, read(vm.uiState).precision)
        assertTrue("nothing started: the member sees why", location.started.isEmpty())
        assertFalse(read(vm.uiState).exactAvailable)
    }

    @Test
    fun exactTakenAwayLaterInAndroidSettingsIsNeverSent() = runTest {
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        vm.setPrecision(SharePrecision.EXACT)
        // The member allowed only approximate in Android's settings and came back.
        vm.setPermission(LocationPermission.APPROXIMATE)
        assertEquals(SharePrecision.APPROXIMATE, read(vm.uiState).precision)
    }

    // --- the sheet's life, and the daily check ---

    @Test
    fun theSheetHoldsInputWhileChoosingAndWhileTheStartIsOutButNotWhileFinding() = runTest {
        val vm = vm()
        assertFalse("closed: nothing is held", read(vm.uiState).holdsInput)
        vm.open()
        assertTrue(read(vm.uiState).holdsInput)
        vm.setPermission(LocationPermission.PRECISE)
        vm.start()
        advanceUntilIdle()
        val s = read(vm.uiState)
        assertTrue("the start went out: Finding your location…", s.finding && s.visible)
        assertFalse("nothing is waiting for the member", s.holdsInput)
        location.sent(s.startedId!!)
        advanceUntilIdle()
        assertFalse(read(vm.uiState).visible)
        assertFalse(read(vm.uiState).holdsInput)
    }

    @Test
    fun theStartInFlightHoldsInput() {
        assertTrue(ShareLocationUiState("c1", open = true, busy = true, startedId = null).holdsInput)
    }

    // --- vaults without location ---

    @Test
    fun theSheetReadsWhetherTheVaultHasLocationBeforeAnythingIsAsked() = runTest {
        location.availability.value = com.vettid.core.data.location.LocationAvailability.UNKNOWN
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertEquals(1, location.refreshes)
        assertTrue(read(vm.uiState).available)
    }

    @Test
    fun nothingStartsAndNoPermissionIsAskedWhileTheVaultIsNotKnownToHaveLocation() = runTest {
        location.availability.value = com.vettid.core.data.location.LocationAvailability.UNKNOWN
        location.fail = VaultFailure(FailureKind.NOT_SUPPORTED)
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertFalse(read(vm.uiState).available)
        assertEquals(FailureKind.NOT_SUPPORTED, read(vm.uiState).error)
        vm.start()
        advanceUntilIdle()
        assertTrue(location.started.isEmpty())
    }

    @Test
    fun theStartButtonWaitsForTheVaultToBeKnownToHaveLocation() {
        sheet(open.copy(availability = com.vettid.core.data.location.LocationAvailability.UNKNOWN))
        rule.onNodeWithTag("share_location_start").assertIsNotEnabled()
    }

    @Test
    fun theCardIsHiddenOnAVaultWithoutLocationAndShownWhileItIsNotKnown() = runTest {
        val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        location.availability.value = com.vettid.core.data.location.LocationAvailability.UNKNOWN
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location)
        assertTrue(read(vm.uiState).locationOffered)
        location.availability.value = com.vettid.core.data.location.LocationAvailability.UNSUPPORTED
        assertFalse(read(vm.uiState).locationOffered)
        val c = FakeSocial.connection("c1", "Sam")
        rule.setContent { ConnectionDetailScreen(ConnectionDetailUiState("c1", c, null, loading = false, locationOffered = false), DetailActions()) }
        assertEquals(0, rule.onAllNodesWithTag("detail_location").fetchSemanticsNodes().size)
    }

    @Test
    fun aDeliveredOnceShareIsNotShownAsSharing() = runTest {
        val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location)
        val share = location.start(com.vettid.core.data.location.ShareRequest("c1", ShareTiming.Once))
        advanceUntilIdle()
        assertEquals(share.shareId, read(vm.uiState).location?.shareId)
        location.sent(share.shareId)
        advanceUntilIdle()
        assertNull(read(vm.uiState).location)
    }

    @Test
    fun onceComesBeforeForAWhileOnTheSheet() {
        sheet(open)
        val once = rule.onNodeWithTag("share_location_once").getUnclippedBoundsInRoot().top
        val whileTop = rule.onNodeWithTag("share_location_while").getUnclippedBoundsInRoot().top
        assertTrue(once < whileTop)
    }

    @Test
    fun dismissingTheSheetLeavesTheShareGoing() = runTest {
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        vm.start()
        advanceUntilIdle()
        vm.dismiss()
        assertFalse(read(vm.uiState).holdsInput)
        assertEquals(1, location.outgoing.value.size)
        // Opened again, it is a fresh choice (the share runs; a new start replaces it).
        vm.open()
        assertNull(read(vm.uiState).startedId)
    }

    @Test
    fun aRefusedStartKeepsTheChoicesAndSaysWhy() = runTest {
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        vm.setLength(ShareLength.DAY_1)
        location.fail = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
        vm.start()
        advanceUntilIdle()
        val s = read(vm.uiState)
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, s.error)
        assertEquals(ShareLength.DAY_1, s.length)
        assertTrue(s.visible && !s.busy)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, location.started.size)
    }

    @Test
    fun theSheetReportsInputToTheShellWhileOpen() {
        val guard = InputGuard()
        var state = ShareLocationUiState("c1", open = true, permission = LocationPermission.PRECISE)
        rule.setContent {
            CompositionLocalProvider(LocalInputGuard provides guard) { ShareLocationSheet(state, sam, ShareLocationActions()) }
        }
        rule.waitForIdle()
        assertTrue(guard.active)
    }

    @Test
    fun aClosedSheetReportsNothing() {
        val guard = InputGuard()
        rule.setContent {
            CompositionLocalProvider(LocalInputGuard provides guard) { ShareLocationSheet(ShareLocationUiState("c1"), sam, ShareLocationActions()) }
        }
        rule.waitForIdle()
        assertFalse(guard.active)
    }

    // --- what the sheet shows ---

    private fun sheet(state: ShareLocationUiState, actions: ShareLocationActions = ShareLocationActions()) {
        rule.setContent { ShareLocationSheet(state, sam, actions) }
    }

    private val open = ShareLocationUiState(
        "c1", open = true, permission = LocationPermission.PRECISE, availability = com.vettid.core.data.location.LocationAvailability.AVAILABLE,
    )

    @Test
    fun theDefaultSheetSaysTheWordsOfThePlan() {
        sheet(open)
        rule.onNodeWithText("Share my location").assertIsDisplayed()
        rule.onNodeWithText("Once").assertIsDisplayed()
        rule.onNodeWithText("Sends one position now; ends after 15 minutes").assertIsDisplayed()
        rule.onNodeWithText("For a while").assertIsDisplayed()
        listOf("15 min", "1 hour", "8 hours", "1 day", "7 days", "Precision", "Approximate", "within about 1 km", "Exact", "within a few metres", "City", "within about 10 km", "Update every", "30 s", "1 min", "5 min", "Start sharing")
            .forEach { rule.requireText(it) }
        rule.onNodeWithText("Approximate and City show the centre of a fixed area. If you move across an area's edge, $sam may see that you were near it.").assertIsDisplayed()
        rule.onNodeWithText("Let $sam keep the trail").assertIsDisplayed()
        rule.onNodeWithText("Without this, $sam sees only your latest position.").assertIsDisplayed()
        rule.onNodeWithTag("share_location_trail").assertIsDisplayed()
        // The trail is off by default.
        rule.onNodeWithTag("share_location_start").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.requireText(text: String) {
        val n = onAllNodesWithText(text).fetchSemanticsNodes().size
        assertTrue("'$text' is on the sheet", n >= 1)
    }

    @Test
    fun aOnceShareHidesTheDurationTheIntervalAndTheTrail() {
        sheet(open.copy(once = true))
        rule.onNodeWithText("Sends one position now; ends after 15 minutes").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Update every").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("7 days").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Let $sam keep the trail").fetchSemanticsNodes().size)
    }

    @Test
    fun withApproximateOnlyExactIsDisabledWithItsNote() {
        sheet(open.copy(permission = LocationPermission.APPROXIMATE))
        rule.onNodeWithTag("share_location_EXACT").assertIsNotEnabled()
        rule.onNodeWithText("Exact needs precise location in Android settings.").assertIsDisplayed()
        rule.onNodeWithTag("share_location_APPROXIMATE").assertIsDisplayed()
        rule.onNodeWithTag("share_location_CITY").assertIsDisplayed()
    }

    @Test
    fun thePermissionTextIsShownUntilThePermissionIsGiven() {
        sheet(open.copy(permission = LocationPermission.NONE))
        rule.onNodeWithText("To share your location, VettID needs to know where this phone is. It asks only while you share, and sends nothing to anyone else.")
            .assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Exact needs precise location in Android settings.").fetchSemanticsNodes().size)
    }

    @Test
    fun aRefusedPermissionSaysSo() {
        sheet(open.copy(permission = LocationPermission.NONE, permissionDenied = true))
        rule.onNodeWithTag("share_location_permission_denied").assertIsDisplayed()
    }

    @Test
    fun startedShowsTheStartedTextAndFindingYourLocationUntilAPositionGoes() {
        val until = Instant.now().plusSeconds(3_600)
        val share = com.vettid.core.data.location.OutgoingShare("s1", "c1", false, SharePrecision.APPROXIMATE, 60, false, until, null, true)
        sheet(open.copy(startedId = "s1", started = share))
        rule.onNodeWithTag("share_location_started").assertIsDisplayed()
        rule.onNodeWithText("Finding your location…").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Start sharing").fetchSemanticsNodes().size)
    }

    @Test
    fun theChoicesAreSelectableAndEveryTargetIsBigEnough() {
        var picked: SharePrecision? = null
        var once: Boolean? = null
        sheet(open, ShareLocationActions(onPrecision = { picked = it }, onOnce = { once = it }))
        rule.onNodeWithTag("share_location_CITY").performClick()
        assertEquals(SharePrecision.CITY, picked)
        rule.onNodeWithTag("share_location_once").performClick()
        assertEquals(true, once)
        listOf("share_location_once", "share_location_while", "share_location_APPROXIMATE", "share_location_EXACT", "share_location_CITY", "share_location_trail", "share_location_start")
            .forEach { tag ->
                val h = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
                assertTrue("$tag is ${h}", h >= 48.dp)
            }
    }

    @Test
    fun startSharingCallsTheStartAction() {
        var started = 0
        sheet(open, ShareLocationActions(onStart = { started++ }))
        rule.onNodeWithTag("share_location_start").performClick()
        assertEquals(1, started)
    }

    // --- the connection page ---

    @Test
    fun theCardOffersShareMyLocationAndWhileSharingSaysUntilWhenWithStop() {
        val c = FakeSocial.connection("c1", "Sam")
        var shared = 0
        var stopped = 0
        val detail = androidx.compose.runtime.mutableStateOf(ConnectionDetailUiState(c.id, c, null, loading = false))
        rule.setContent { ConnectionDetailScreen(detail.value, DetailActions(onShareLocation = { shared++ }, onStopSharingLocation = { stopped++ })) }
        rule.onNodeWithTag("detail_location_share").assertIsDisplayed().performClick()
        assertEquals(1, shared)
        detail.value = detail.value.copy(location = DetailLocation("s1", Instant.now().plusSeconds(1_800)))
        rule.onNodeWithTag("detail_location_sharing").assertIsDisplayed()
        rule.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(1, stopped)
        val h = rule.onNodeWithTag("detail_location_stop").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(h >= 48.dp)
    }

    private fun card(loc: DetailLocation?, missed: Boolean = false, onStop: () -> Unit = {}) {
        val c = FakeSocial.connection("c1", "Sam")
        rule.setContent {
            ConnectionDetailScreen(
                ConnectionDetailUiState(c.id, c, null, loading = false, location = loc, locationMissed = missed),
                DetailActions(onStopSharingLocation = onStop),
            )
        }
    }

    @Test
    fun thePausedCardSaysSoAndKeepsStop() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), paused = true))
        rule.onNodeWithText("Paused until your daily check.").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_stop").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("detail_location_settings").fetchSemanticsNodes().size)
    }

    @Test
    fun aCardThatIsNotSendingSaysWhyWithStopAndWhereItCanBePutRight() {
        val until = Instant.now().plusSeconds(1_800)
        card(DetailLocation("s1", until, issue = com.vettid.core.data.location.SendIssue.NO_PERMISSION))
        rule.onNodeWithText("Not sending: location is off for VettID.").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_stop").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_settings").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Sharing your location until", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun locationOffOnThePhoneOffersTheLocationSettings() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = com.vettid.core.data.location.SendIssue.LOCATION_OFF))
        rule.onNodeWithText("Not sending: location is off on this phone.").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_settings").assertIsDisplayed()
        val h = rule.onNodeWithTag("detail_location_settings").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(h >= 48.dp)
    }

    @Test
    fun aStopToWaitForIsSaidWithoutASettingsButton() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = com.vettid.core.data.location.SendIssue.STOP_PENDING))
        rule.onNodeWithText("Not sending until your vault has stopped a share.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("detail_location_settings").fetchSemanticsNodes().size)
    }

    @Test
    fun aOnceShareThatFoundNoPositionIsSaidAboveTheButton() {
        card(null, missed = true)
        rule.onNodeWithText("No position was found, so nothing was sent.").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_share").assertIsDisplayed()
    }

    @Test
    fun theDetailViewModelCarriesThePausedStateTheIssueAndTheMissedOnce() = runTest {
        val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location)
        val share = location.start(com.vettid.core.data.location.ShareRequest("c1"))
        location.serviceState.value = com.vettid.core.data.location.ServiceState(
            listOf(com.vettid.core.data.location.ServiceState.Target("c1", share.expiresAt)),
            paused = true,
            issue = com.vettid.core.data.location.SendIssue.STOP_PENDING,
        )
        advanceUntilIdle()
        val shown = read(vm.uiState).location!!
        assertTrue(shown.paused)
        assertEquals(com.vettid.core.data.location.SendIssue.STOP_PENDING, shown.issue)
        location.stop(share.shareId)
        location.missedOnce.value = setOf("c1")
        advanceUntilIdle()
        assertTrue(read(vm.uiState).locationMissed)
        assertNull(read(vm.uiState).location)
    }

    @Test
    fun aStoppingShareIsSaidSoAndKeepsStop() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), stopping = true))
        rule.onNodeWithText("Stopping…").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_stop").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Sharing your location until", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDetailViewModelSaysStoppingWhileTheStopIsPending() = runTest {
        val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location)
        location.start(com.vettid.core.data.location.ShareRequest("c1"))
        advanceUntilIdle()
        assertFalse(read(vm.uiState).location!!.stopping)
        // The share's sending ended (it is no target any more) but its stop is not taken: still listed, "Stopping…".
        location.serviceState.value = com.vettid.core.data.location.ServiceState(stopping = setOf("c1"))
        advanceUntilIdle()
        assertTrue(read(vm.uiState).location!!.stopping)
    }

    @Test
    fun aStartRefusedBecauseNothingCouldBeSentSaysWhyWithSettings() = runTest {
        location.fail = VaultFailure(FailureKind.OTHER, com.vettid.core.data.location.SendIssue.LOCATION_OFF.code)
        val vm = vm()
        vm.open()
        vm.setPermission(LocationPermission.PRECISE)
        vm.start()
        advanceUntilIdle()
        assertEquals(com.vettid.core.data.location.SendIssue.LOCATION_OFF, read(vm.uiState).issue)
        sheet(read(vm.uiState))
        rule.onNodeWithText("Not sending: location is off on this phone.").assertIsDisplayed()
        rule.onNodeWithTag("share_location_settings").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("share_location_error").fetchSemanticsNodes().size)
        // Tried again, the reason goes.
        vm.start()
        advanceUntilIdle()
        assertNull(read(vm.uiState).issue)
    }

    @Test
    fun aServiceIsOfferedNoLocationSharing() {
        val svc = com.vettid.core.data.social.ConnectionInfo("s1", "Acme", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Acme", lastName = "Co-op", service = true)
        rule.setContent { ConnectionDetailScreen(ConnectionDetailUiState(svc.id, svc, null, loading = false), DetailActions()) }
        assertEquals(0, rule.onAllNodesWithTag("detail_location").fetchSemanticsNodes().size)
    }

    @Test
    fun theDetailViewModelShowsTheShareAndStopsIt() = runTest {
        val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location)
        advanceUntilIdle()
        assertNull(read(vm.uiState).location)
        val share = location.start(com.vettid.core.data.location.ShareRequest("c1"))
        advanceUntilIdle()
        assertEquals(DetailLocation(share.shareId, share.expiresAt), read(vm.uiState).location)
        vm.stopSharingLocation()
        advanceUntilIdle()
        assertEquals(listOf(share.shareId), location.stopped)
        assertNull(read(vm.uiState).location)
    }

    @Test
    fun theRequestIdIsUsedOnceAndThenForgotten() = runTest {
        val handle = SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1", ConnectionDetailRoute.ARG_REQUEST to "r7"))
        val vm = ShareLocationViewModel(handle, location)
        assertTrue("the sheet opens with the request", read(vm.uiState).open)
        vm.permissionResult(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertEquals("r7", location.started.single().requestId)
        assertNull(handle.get<String?>(ConnectionDetailRoute.ARG_REQUEST))
        // dismissed before starting: forgotten too, and a second start has none
        val handle2 = SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c2", ConnectionDetailRoute.ARG_REQUEST to "r8"))
        val vm2 = ShareLocationViewModel(handle2, location)
        vm2.dismiss()
        assertNull(handle2.get<String?>(ConnectionDetailRoute.ARG_REQUEST))
    }

    @Test
    fun answeringARequestWhileSharingWithThatConnectionStartsNothing() = runTest {
        location.start(com.vettid.core.data.location.ShareRequest("c1"))
        location.started.clear()
        val vm = ShareLocationViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1", ConnectionDetailRoute.ARG_REQUEST to "r1")), location)
        vm.permissionResult(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertTrue(location.started.isEmpty())
        assertTrue(read(vm.uiState).alreadySharing)
        // without a request, the sheet still replaces a share, as before
        val plain = ShareLocationViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), location)
        plain.open()
        plain.permissionResult(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertEquals(1, location.started.size)
    }
}
