// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertIsDisplayed
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
import com.vettid.core.testing.FakeLocation
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
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

/** The connection page's location card and its view model (ANDROID-PLAN §4 "Location", L1a; the stall line, A6 N21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class LocationCardTest {
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
    fun aStalledCardSaysHowLongNothingHasReachedThePersonAndOffersNoFixWithoutAKnownCause() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), stalledMinutes = 25))
        rule.onNodeWithTag("detail_location_stalled").assertIsDisplayed()
        rule.onNodeWithText("Your location hasn't reached $sam for 25 minutes.").assertIsDisplayed()
        rule.onNodeWithTag("detail_location_stop").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("detail_location_settings").fetchSemanticsNodes().size)
    }

    @Test
    fun aStalledCardWithAKnownCauseGivesTheIssueLineAndItsFixOnce() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), issue = com.vettid.core.data.location.SendIssue.LOCATION_OFF, stalledMinutes = 16))
        rule.onNodeWithText("Your location hasn't reached $sam for 16 minutes.").assertIsDisplayed()
        rule.onNodeWithText("Not sending: location is off on this phone.").assertIsDisplayed()
        // The issue's own action, shown once: not a second one for the stall.
        assertEquals(1, rule.onAllNodesWithTag("detail_location_settings").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Open settings").fetchSemanticsNodes().size)
    }

    @Test
    fun aStalledCardKeepsTheSingularForOneMinute() {
        card(DetailLocation("s1", Instant.now().plusSeconds(1_800), stalledMinutes = 1))
        rule.onNodeWithText("Your location hasn't reached $sam for 1 minute.").assertIsDisplayed()
    }

    @Test
    fun theDetailViewModelCarriesTheStallOnlyWhileSending() = runTest {
        val social = FakeSocial().also { it.seed(listOf(FakeSocial.connection("c1", "Sam"))) }
        val vm = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing(), location = location)
        val share = location.start(com.vettid.core.data.location.ShareRequest("c1"))
        val target = com.vettid.core.data.location.ServiceState.Target("c1", share.expiresAt)
        advanceUntilIdle()
        assertNull(read(vm.uiState).location!!.stalledMinutes)
        location.serviceState.value = com.vettid.core.data.location.ServiceState(listOf(target), stalled = mapOf("c1" to 17))
        assertEquals(17, read(vm.uiState).location!!.stalledMinutes)
        location.serviceState.value = com.vettid.core.data.location.ServiceState(listOf(target), stalled = mapOf("c1" to 18))
        assertEquals(18, read(vm.uiState).location!!.stalledMinutes)
        // Paused (or stopping): not a stall.
        location.serviceState.value = com.vettid.core.data.location.ServiceState(listOf(target), paused = true, stalled = mapOf("c1" to 18))
        assertNull(read(vm.uiState).location!!.stalledMinutes)
        location.serviceState.value = com.vettid.core.data.location.ServiceState(listOf(target))
        assertNull(read(vm.uiState).location!!.stalledMinutes)
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
}
