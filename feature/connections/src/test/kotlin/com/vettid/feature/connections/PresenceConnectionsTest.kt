// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.connections

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.presence.PresencePolicy
import com.vettid.core.data.presence.PresenceReading
import com.vettid.core.data.presence.PresenceShare
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.ui.components.ShellChrome
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Presence on a connection's page, and the one-time card on Connections (ANDROID-PLAN §4 "Presence", P1; questions 22 and 23). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class PresenceConnectionsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val social = FakeSocial()
    private val presence = FakePresence()

    /** A first name in a sentence is bidi-isolated (VAULT-MESSAGING §10.8). */
    private val ada = com.vettid.core.data.account.AccountNames.isolate("Ada")

    private fun ada() = FakeSocial.connection("c1", "Ada", last = "Lovelace")

    private fun service() = ConnectionInfo("s1", "Acme", ConnectionState.ACTIVE, firstName = "Acme", lastName = "Co-op", service = true)

    private fun detailVm(id: String = "c1", p: FakePresence = presence) =
        ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to id)), social, FakeSharing(), presence = p)

    // --- the view model ---

    @Test
    fun aPersonIsAskedWhenItsPageOpensAndOnRefreshOnly() = runTest {
        social.seed(listOf(ada()))
        val vm = detailVm()
        advanceUntilIdle()
        assertEquals(listOf("c1"), presence.queries)
        val shown = vm.uiState.value.presence
        assertNotNull(shown)
        // Nothing asked yet answers "Checking…", and the switch follows the policy (all: on).
        assertEquals(PresenceReading.Checking, shown!!.reading)
        assertEquals(true, shown.shared)
        vm.refreshPresence()
        advanceUntilIdle()
        assertEquals(listOf("c1", "c1"), presence.queries)
        // The reading the repository holds is the line.
        presence.readings.value = mapOf("c1" to PresenceReading.Known(PresenceState.BUSY, null))
        advanceUntilIdle()
        assertEquals(PresenceReading.Known(PresenceState.BUSY, null), vm.uiState.value.presence?.reading)
        // Nothing else asks: an event or a new list does not.
        social.seed(listOf(ada(), FakeSocial.connection("c2", "Bo")))
        advanceUntilIdle()
        assertEquals(2, presence.queries.size)
    }

    @Test
    fun aServiceHasNoPresenceLineSwitchOrQuestion() = runTest {
        social.seed(listOf(service()))
        val vm = detailVm("s1")
        advanceUntilIdle()
        assertNull(vm.uiState.value.presence)
        vm.refreshPresence()
        advanceUntilIdle()
        assertTrue(presence.queries.isEmpty())
    }

    @Test
    fun aConnectionThatIsNotActiveHasNone() = runTest {
        social.seed(listOf(FakeSocial.connection("c1", "Ada", state = ConnectionState.PENDING)))
        val vm = detailVm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.presence)
        assertTrue(presence.queries.isEmpty())
    }

    @Test
    fun withoutPresenceInTheVaultNothingIsShownOrAsked() = runTest {
        social.seed(listOf(ada()))
        val none = FakePresence(available = false)
        val vm = detailVm(p = none)
        advanceUntilIdle()
        assertNull(vm.uiState.value.presence)
        assertTrue(none.queries.isEmpty())
        // The default of a screen built without presence is the same.
        val bare = ConnectionDetailViewModel(SavedStateHandle(mapOf(ConnectionDetailRoute.ARG to "c1")), social, FakeSharing())
        advanceUntilIdle()
        assertNull(bare.uiState.value.presence)
    }

    @Test
    fun theSwitchAddsAndRemovesTheConnectionInExcept() = runTest {
        social.seed(listOf(ada()))
        val vm = detailVm()
        advanceUntilIdle()
        vm.setPresenceShared(false)
        advanceUntilIdle()
        assertEquals(listOf("c1"), presence.policy.value?.except)
        assertEquals(false, vm.uiState.value.presence?.shared)
        vm.setPresenceShared(true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), presence.policy.value?.except)
        assertEquals(true, vm.uiState.value.presence?.shared)
        // Under "No one" the same switch adds the connection ("Only…").
        presence.policy.value = PresencePolicy(5, share = PresenceShare.NONE)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.presence?.shared)
        vm.setPresenceShared(true)
        advanceUntilIdle()
        assertEquals(listOf("c1"), presence.policy.value?.except)
    }

    // --- the screen ---

    private fun detail(reading: PresenceReading, shared: Boolean? = true, onRefresh: () -> Unit = {}, onShared: (Boolean) -> Unit = {}, service: Boolean = false) {
        val c = if (service) service() else ada()
        rule.setContent {
            ConnectionDetailScreen(
                ConnectionDetailUiState(c.id, c, null, loading = false, presence = if (service) null else DetailPresence(reading, shared)),
                DetailActions(onRefreshPresence = onRefresh, onSetPresenceShared = onShared),
            )
        }
    }

    @Test
    fun theLineUnderTheNameSaysTheStateWithHowLongAgo() {
        detail(PresenceReading.Known(PresenceState.AVAILABLE, Instant.now().minusSeconds(10 * 60)))
        rule.onNodeWithTag("detail_presence").assertIsDisplayed()
        rule.onNodeWithText("Available · active 10 min ago").assertIsDisplayed()
    }

    @Test
    fun anHourOrMoreAgoIsActiveTodayAndWithoutLastActiveNothingFollows() {
        // At most three hours back, so that it is still "today" whenever the test runs just after midnight too.
        detail(PresenceReading.Known(PresenceState.BUSY, Instant.now().minusSeconds(2 * 3600)))
        val today = rule.onAllNodesWithText("Busy · active today").fetchSemanticsNodes().size
        val sameDay = Instant.now().minusSeconds(2 * 3600).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
            java.time.LocalDate.now()
        assertEquals(if (sameDay) 1 else 0, today)
    }

    @Test
    fun awayWithoutLastActiveIsJustAway() {
        detail(PresenceReading.Known(PresenceState.AWAY, null))
        rule.onNodeWithText("Away").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("active", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun checkingThenUnknownWithWhy() {
        detail(PresenceReading.Checking)
        rule.onNodeWithText("Checking…").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("detail_presence_unknown").fetchSemanticsNodes().size)
    }

    @Test
    fun unknownSaysSoAndWhy() {
        detail(PresenceReading.Unknown)
        rule.onNodeWithText("Status unknown").assertIsDisplayed()
        rule.onNodeWithTag("detail_presence_unknown").assertIsDisplayed()
    }

    @Test
    fun theRefreshIconIsDescribedAndBigEnough() {
        var refreshed = 0
        detail(PresenceReading.Unknown, onRefresh = { refreshed++ })
        val icon = rule.onNodeWithContentDescription("Check again")
        icon.assertIsDisplayed()
        val b = icon.getUnclippedBoundsInRoot()
        assertTrue(b.right - b.left >= 48.dp && b.bottom - b.top >= 48.dp)
        icon.performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun theSwitchSaysWhatItDoesAndWhenOffWhatTheConnectionSees() {
        val seen = mutableListOf<Boolean>()
        detail(PresenceReading.Unknown, shared = false, onShared = { seen += it })
        rule.onNodeWithText("Share my status with $ada").assertIsDisplayed()
        rule.onNodeWithTag("detail_presence_share_off").assertIsDisplayed()
        rule.onNodeWithText("$ada sees 'Status unknown' for you, as if your vault were offline.").assertIsDisplayed()
        rule.onNodeWithTag("detail_presence_share").performClick()
        assertEquals(listOf(true), seen)
    }

    @Test
    fun noOffNoteWhileSharing() {
        detail(PresenceReading.Unknown, shared = true)
        assertEquals(0, rule.onAllNodesWithTag("detail_presence_share_off").fetchSemanticsNodes().size)
    }

    @Test
    fun aServiceShowsNoLineNoRefreshAndNoSwitch() {
        detail(PresenceReading.Unknown, service = true)
        assertEquals(0, rule.onAllNodesWithTag("detail_presence").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("detail_presence_share").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Check again").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Share my status", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun noPresenceInTheStateShowsNone() {
        val c = ada()
        rule.setContent { ConnectionDetailScreen(ConnectionDetailUiState(c.id, c, null, loading = false), DetailActions()) }
        assertEquals(0, rule.onAllNodesWithTag("detail_presence").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("detail_presence_share").fetchSemanticsNodes().size)
    }

    // --- the one-time card ---

    private fun listVm(p: FakePresence = presence, prefs: InMemoryPreferencesRepository = InMemoryPreferencesRepository()): ConnectionsViewModel {
        social.seed(listOf(ada()))
        return ConnectionsViewModel(social, social, FakeVault(), p, prefs)
    }

    @Test
    fun theCardIsShownOnceWhenTheVaultHasPresenceAndSharesWithAll() = runTest {
        val vm = listVm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.presenceCard)
    }

    @Test
    fun noCardWithoutPresenceOrOnceTheMemberChoseOrNoOneIsAlreadyChosen() = runTest {
        val a = listVm(FakePresence(available = false))
        advanceUntilIdle()
        assertFalse(a.uiState.value.presenceCard)
        val b = listVm(prefs = InMemoryPreferencesRepository(com.vettid.core.data.prefs.AppPreferences(presenceCardAnswered = true)))
        advanceUntilIdle()
        assertFalse(b.uiState.value.presenceCard)
        val c = listVm(FakePresence(initial = PresencePolicy(2, share = PresenceShare.NONE)))
        advanceUntilIdle()
        assertFalse(c.uiState.value.presenceCard)
    }

    @Test
    fun shareWithEveryoneJustMarksItAnsweredBecauseAllIsTheVaultsDefault() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = listVm(prefs = prefs)
        advanceUntilIdle()
        vm.shareWithEveryone()
        advanceUntilIdle()
        assertTrue(prefs.current.value.presenceCardAnswered)
        assertFalse(vm.uiState.value.presenceCard)
        assertEquals(PresenceShare.ALL, presence.policy.value?.share)
        assertEquals(1L, presence.policy.value?.version)
    }

    @Test
    fun notNowSetsNoOne() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = listVm(prefs = prefs)
        advanceUntilIdle()
        vm.notNow()
        advanceUntilIdle()
        assertEquals(PresenceShare.NONE, presence.policy.value?.share)
        assertTrue(prefs.current.value.presenceCardAnswered)
        assertFalse(vm.uiState.value.presenceCard)
    }

    @Test
    fun aRefusedNotNowKeepsTheCard() = runTest {
        val prefs = InMemoryPreferencesRepository()
        presence.fail = com.vettid.core.data.vault.VaultFailure(com.vettid.core.data.vault.FailureKind.NETWORK)
        val vm = listVm(prefs = prefs)
        advanceUntilIdle()
        vm.notNow()
        advanceUntilIdle()
        assertFalse(prefs.current.value.presenceCardAnswered)
        assertTrue(vm.uiState.value.presenceCard)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun theCardHasTheTextTheButtonsAndTheWayToSettings() {
        var shared = 0
        var notNow = 0
        rule.setContent {
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = listOf(ada()), presenceCard = true),
                ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {}),
                ConnectionsActions(onPresenceShare = { shared++ }, onPresenceNotNow = { notNow++ }),
            )
        }
        rule.onNodeWithText("Let your connections see when you're around?").assertIsDisplayed()
        rule.onNodeWithText("They see Available, Busy or Away and roughly when you last used VettID.").assertIsDisplayed()
        rule.onNodeWithText("Change this in Settings → Privacy → Presence").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Share with everyone").performClick()
        assertEquals(1, shared)
        assertEquals(1, notNow)
    }

    @Test
    fun noCardInTheStateMeansNoCard() {
        rule.setContent {
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = listOf(ada())),
                ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {}),
                ConnectionsActions(),
            )
        }
        rule.onAllNodesWithText("Share with everyone").assertCountEquals(0)
    }
}
