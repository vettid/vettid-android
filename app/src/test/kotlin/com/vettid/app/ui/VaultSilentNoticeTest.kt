package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.calls.CallHistory
import com.vettid.core.data.calls.CallPermissions
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (#19721 step 4) "Your vault isn't answering" (owner decision a, GC #19767; the texts approved there, word for word): its
 * three texts, an action that only opens the unlock screen, no dismissal, a polite announcement, and never during a call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VaultSilentNoticeTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun theNoticeShowsItsThreeApprovedTexts() {
        rule.setContent { VaultSilentNotice(first = true, onUnlockAgain = {}) }
        rule.onNodeWithText("Your vault isn't answering", substring = true).assertIsDisplayed()
        rule.onNodeWithText(
            "VettID can't reach your vault right now. If this goes on, unlock it again with your PIN. Anything still running " +
                "in your vault, such as a call, will end.",
            substring = true,
        ).assertIsDisplayed()
        rule.onNodeWithText("Unlock again").assertIsDisplayed()
    }

    @Test
    fun theActionIsUnlockAgain() {
        var asked = 0
        rule.setContent { VaultSilentNotice(first = false, onUnlockAgain = { asked++ }) }
        rule.onNodeWithText("Unlock again").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun theNoticeCannotBeDismissedAndIsAnnouncedPolitely() {
        rule.setContent { VaultSilentNotice(first = true, onUnlockAgain = {}) }
        rule.onNodeWithTag("banner_dismiss").assertDoesNotExist()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun neverDuringACall() {
        assertTrue(vaultSilentShown(true, CallAvailability.READY))
        assertFalse(vaultSilentShown(true, CallAvailability.IN_CALL))
        assertFalse(vaultSilentShown(false, CallAvailability.READY))
        // CLOSED only while the vault is not open, where vaultSilent is false already; nothing is hidden by it.
        assertTrue(vaultSilentShown(true, CallAvailability.CLOSED))
    }

    private class Calls : CallAccess {
        override val availability = MutableStateFlow(CallAvailability.READY)

        override fun call(connectionId: String) = Unit

        override suspend fun history(connectionId: String) = CallHistory()

        override val permissions = MutableStateFlow(CallPermissions())

        override suspend fun setAllowed(connectionId: String, allowed: Boolean) = Unit
    }

    @Test
    fun theViewModelShowsItOutsideACallAndUnlocksAgainOnlyWhileShown() {
        val vault = FakeVault(AppPhase.Unlocked)
        val calls = Calls()
        val vm = VaultSilentViewModel(vault, calls)
        vm.unlockAgain()
        assertFalse("not silent: nothing", vault.calls.contains("unlockAgain"))
        vault.vaultSilent.value = true
        assertTrue(vm.shown.value)
        calls.availability.value = CallAvailability.IN_CALL
        assertFalse(vm.shown.value)
        vm.unlockAgain()
        assertFalse("hidden during a call: nothing", vault.calls.contains("unlockAgain"))
        calls.availability.value = CallAvailability.READY
        vm.unlockAgain()
        assertEquals(listOf("unlockAgain"), vault.calls.filter { it == "unlockAgain" })
        vault.vaultSilent.value = false
        assertFalse(vm.shown.value)
    }
}
