// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import com.vettid.core.data.presence.PresencePolicy
import com.vettid.core.data.presence.PresenceShare
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakePresence
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Privacy → Presence (ANDROID-PLAN §4 "Presence", P1): the policy editor. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class PresenceSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val presence = FakePresence()
    private val prefs = InMemoryPreferencesRepository()
    private val social = FakeSocial().apply {
        seed(
            listOf(
                FakeSocial.connection("c1", "Ada"),
                FakeSocial.connection("c2", "Bo"),
                FakeSocial.connection("c3", "Cy", state = ConnectionState.PENDING),
                ConnectionInfo("s1", "Acme", ConnectionState.ACTIVE, firstName = "Acme", lastName = "Co-op", service = true),
            ),
        )
    }

    private fun vm() = PresenceSettingsViewModel(presence, social, prefs)

    @Test
    fun theConnectionListHasPeopleWhoAreConnectedAndNoServices() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2"), vm.uiState.value.people.map { it.id })
        assertTrue(vm.uiState.value.available)
    }

    @Test
    fun openingTheScreenReadsThePolicyOnlyWhenTheScreenAsks() = runTest {
        val vm = vm()
        advanceUntilIdle()
        // The screen's first resume is the one read (not a second one from the view model's start).
        assertEquals(0, presence.refreshes)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, presence.refreshes)
    }

    @Test
    fun theStatusIsOneOfFourAndSentAtOnce() = runTest {
        val vm = vm()
        advanceUntilIdle()
        for (s in PresenceState.entries) {
            vm.setState(s)
            advanceUntilIdle()
            assertEquals(s, presence.policy.value?.state)
        }
        assertEquals(PresenceState.INVISIBLE, vm.uiState.value.policy?.state)
    }

    @Test
    fun allMyConnectionsWithExceptionsAndNoOneWithOnly() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.setException("c2", true)
        advanceUntilIdle()
        vm.setException("c1", true)
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2"), presence.policy.value?.except)
        vm.setException("c2", false)
        advanceUntilIdle()
        assertEquals(listOf("c1"), presence.policy.value?.except)
        // No one: the exceptions start again (they would mean the opposite) and "Only…" is picked.
        vm.setShare(PresenceShare.NONE)
        advanceUntilIdle()
        assertEquals(PresenceShare.NONE, presence.policy.value?.share)
        assertEquals(emptyList<String>(), presence.policy.value?.except)
        vm.setException("c2", true)
        advanceUntilIdle()
        assertEquals(listOf("c2"), presence.policy.value?.except)
        // Choosing the same again changes nothing.
        val v = presence.policy.value?.version
        vm.setShare(PresenceShare.NONE)
        advanceUntilIdle()
        assertEquals(v, presence.policy.value?.version)
    }

    @Test
    fun atMost1024ExceptionsAndTheOnesPickedCanStillBeTakenOut() = runTest {
        presence.policy.value = PresencePolicy(3, except = (1..PresencePolicy.MAX_EXCEPT).map { "x$it" })
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.atLimit)
        vm.setException("c1", true)
        advanceUntilIdle()
        assertEquals(PresencePolicy.MAX_EXCEPT, presence.policy.value?.except?.size)
        assertFalse("c1" in presence.policy.value!!.except)
        vm.setException("x7", false)
        advanceUntilIdle()
        assertEquals(PresencePolicy.MAX_EXCEPT - 1, presence.policy.value?.except?.size)
    }

    @Test
    fun aChoiceTellsTheOneTimeCardItIsAnswered() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertFalse(prefs.current.value.presenceCardAnswered)
        vm.setState(PresenceState.BUSY)
        advanceUntilIdle()
        assertTrue(prefs.current.value.presenceCardAnswered)
    }

    @Test
    fun aRefusedChoiceShowsTheFailureAndChangesNothing() = runTest {
        val vm = vm()
        advanceUntilIdle()
        presence.fail = VaultFailure(FailureKind.CONFLICT)
        vm.setState(PresenceState.AWAY)
        advanceUntilIdle()
        assertEquals(FailureKind.CONFLICT, vm.uiState.value.error)
        assertEquals(PresenceState.AVAILABLE, presence.policy.value?.state)
        assertFalse(prefs.current.value.presenceCardAnswered)
        assertFalse(vm.uiState.value.busy)
        vm.dismissError()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun nothingIsHeldBecauseEveryChoiceIsSentAtOnce() = runTest {
        val vm = vm()
        advanceUntilIdle()
        // No unsaved edit (CLAUDE.md, the owner check): the screen reports no input in progress at any time.
        assertFalse(vm.uiState.value.holdsInput)
        vm.setState(PresenceState.BUSY)
        assertFalse(vm.uiState.value.holdsInput)
        assertNotNull(vm.uiState.value.policy)
    }

    // --- the screen ---

    private fun show(state: PresenceSettingsUiState, onState: (PresenceState) -> Unit = {}, onShare: (PresenceShare) -> Unit = {}, onPick: (String, Boolean) -> Unit = { _, _ -> }) =
        rule.setContent { PresenceSettingsContent(state, onState, onShare, onPick, {}, {}) }

    private val people = listOf(PresencePerson("c1", "Ada Doe"), PresencePerson("c2", "Bo Doe"))

    @Test
    fun theStatusWhoCanSeeItAndTheExceptionsUnderAllSayExcept() {
        val picked = mutableListOf<Pair<String, Boolean>>()
        show(PresenceSettingsUiState(available = true, policy = PresencePolicy(1, except = listOf("c2")), people = people, loading = false), onPick = { id, on -> picked += id to on })
        for (t in listOf("Presence", "Status", "Available", "Busy", "Away", "Invisible", "Who can see it", "All my connections", "No one", "Exceptions", "Except…")) {
            rule.onNodeWithText(t).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithTag("presence_state_available").assertIsSelected()
        rule.onNodeWithTag("presence_share_all").assertIsSelected()
        rule.onNodeWithTag("presence_person_c2").assertIsOn()
        rule.onNodeWithTag("presence_person_c1").performClick()
        assertEquals(listOf("c1" to true), picked)
        assertEquals(0, rule.onAllNodesWithTag("presence_invisible_note").fetchSemanticsNodes().size)
    }

    @Test
    fun underNoOneItSaysOnlyAndInvisibleHasItsNote() {
        show(PresenceSettingsUiState(available = true, policy = PresencePolicy(1, PresenceState.INVISIBLE, PresenceShare.NONE), people = people, loading = false))
        rule.onNodeWithText("Only…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Invisible: you appear offline to everyone.").performScrollTo().assertIsDisplayed()
        // The option's line is for choosing it: with Invisible chosen, only the note says it.
        assertEquals(0, rule.onAllNodesWithText("You appear offline to everyone").fetchSemanticsNodes().size)
        rule.onNodeWithTag("presence_share_none").assertIsSelected()
    }

    @Test
    fun theOptionLineIsShownWhileInvisibleIsNotChosenAndTheNoteIsNot() {
        show(PresenceSettingsUiState(available = true, policy = PresencePolicy(1), people = people, loading = false))
        rule.onNodeWithText("You appear offline to everyone").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("presence_invisible_note").fetchSemanticsNodes().size)
    }

    @Test
    fun untilThePolicyIsKnownAProgressIndicatorShowsAndNothingElse() {
        show(PresenceSettingsUiState(loading = true))
        assertEquals(0, rule.onAllNodesWithTag("presence_state_available").fetchSemanticsNodes().size)
        rule.onNodeWithText("Presence").assertIsDisplayed()
    }

    @Test
    fun tapsReachTheActions() {
        val states = mutableListOf<PresenceState>()
        val shares = mutableListOf<PresenceShare>()
        show(PresenceSettingsUiState(available = true, policy = PresencePolicy(1), people = people, loading = false), { states += it }, { shares += it })
        rule.onNodeWithTag("presence_state_busy").performScrollTo().performClick()
        rule.onNodeWithTag("presence_share_none").performScrollTo().performClick()
        assertEquals(listOf(PresenceState.BUSY), states)
        assertEquals(listOf(PresenceShare.NONE), shares)
    }

    @Test
    fun atTheLimitOnlyThePickedOnesCanBeChanged() {
        val full = PresencePolicy(1, except = listOf("c2") + (1 until PresencePolicy.MAX_EXCEPT).map { "x$it" })
        show(PresenceSettingsUiState(available = true, policy = full, people = people, loading = false))
        rule.onNodeWithTag("presence_person_c1").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("presence_person_c2").performScrollTo().assertIsEnabled()
    }

    // --- Settings → Privacy ---

    private fun settings(shown: Boolean, onOpen: () -> Unit = {}) = rule.setContent {
        SettingsContent(SettingsUiState(presenceShown = shown, presenceStateRes = com.vettid.core.data.presence.PresenceText.stateName(PresenceState.BUSY)), SettingsActions(presence = onOpen))
    }

    @Test
    fun theRowIsInPrivacyOnlyWhereTheVaultHasPresence() {
        var opened = 0
        settings(true) { opened++ }
        rule.onNodeWithTag("presence_row").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Busy").assertExists()
        rule.onNodeWithTag("presence_row").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noRowWithoutPresence() {
        settings(false)
        assertEquals(0, rule.onAllNodesWithTag("presence_row").fetchSemanticsNodes().size)
    }
}
