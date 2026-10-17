package com.vettid.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.RecoveryStatusView
import com.vettid.core.data.vault.ReleaseInfoView
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
import org.robolectric.RuntimeEnvironment

/**
 * Release R in Settings (ANDROID-PLAN "Owner check and status", "Change PIN", "Other screens"): the day-long
 * counter-unavailable notice, the used attempts with the PIN and password changes, the PIN screen's warning when it was
 * opened from there, and New phone secret.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReleaseRSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private fun overview(r: RecoveryStatusView) = VaultOverview(
        "3f9c2a7be41d4c0e9b8a6f5d2c1e0a9b", "unlocked", ReleaseInfoView(15, "active", null, 15, null), 15, null, false, 1, 4,
        recovery = r,
    )

    @Test
    fun aDayWithoutTheCounterIsUrgent() {
        val r = RecoveryStatusView("unknown", null, true, unknownForADay = true)
        rule.setContent { VaultStatusContent(LoadState(false, overview(r)), {}, {}) }
        rule.onNodeWithTag("recovery_unknown_day").assertIsDisplayed()
        rule.onNodeWithText(res.getString(R.string.settings_recovery_unknown_day)).assertExists()
        rule.onNodeWithTag("recovery_attempts_used").assertDoesNotExist()
    }

    @Test
    fun lessThanADayIsOnlyTheLine() {
        rule.setContent { VaultStatusContent(LoadState(false, overview(RecoveryStatusView("unknown", null, true))), {}, {}) }
        rule.onNodeWithTag("recovery_unknown_day").assertDoesNotExist()
    }

    @Test
    fun usedAttemptsOfferThePasswordAndThePin() {
        var pin = 0
        var password = 0
        rule.setContent {
            VaultStatusContent(
                LoadState(false, overview(RecoveryStatusView("armed", 3, false))), {}, {},
                onChangePin = { pin++ }, onChangePassword = { password++ },
            )
        }
        rule.onNodeWithTag("attempts_change_pin").performClick()
        rule.onNodeWithTag("attempts_change_password").performClick()
        assertEquals(1, pin)
        assertEquals(1, password)
    }

    @Test
    fun thePinScreenFromAnAttemptsNoticeWarnsFirst() {
        rule.setContent { ChangePinContent(ChangePinUiState(withPassword = true, fromAttempts = true), {}, {}, {}, {}, {}) }
        rule.onNodeWithTag("pin_password_too").assertIsDisplayed()
        rule.onNodeWithText(res.getString(R.string.settings_pin_password_too)).assertExists()
    }

    @Test
    fun thePinScreenOtherwiseDoesNot() {
        rule.setContent { ChangePinContent(ChangePinUiState(withPassword = true), {}, {}, {}, {}, {}) }
        rule.onNodeWithTag("pin_password_too").assertDoesNotExist()
    }

    @Test
    fun newPhoneSecret() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unlocked).apply { phoneSecret = true }
        val vm = NewPhoneSecretViewModel(vault)
        vm.setPin("975310")
        assertFalse(vm.uiState.value.canSubmit)
        vm.setPassword("the password")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("975310" to "the password", vault.lastRenew)
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun newPhoneSecretSaysANewSecretAloneIsNotEnoughBeforeThePin() {
        rule.setContent { NewPhoneSecretContent(NewPhoneSecretUiState(), {}, {}, {}, {}) }
        rule.onNodeWithText(res.getString(R.string.settings_new_secret_body)).assertExists()
        rule.onNodeWithText(
            "If something ran as VettID on this phone, a new secret alone is not enough: move your vault to another " +
                "phone, or recover it.",
        ).assertExists()
        val notice = rule.onNodeWithTag("new_secret_not_enough").fetchSemanticsNode().boundsInRoot
        val pin = rule.onNodeWithTag("new_secret_pin").fetchSemanticsNode().boundsInRoot
        assertTrue(notice.bottom <= pin.top)
    }

    @Test
    fun newPhoneSecretDoneDropsTheWarning() {
        rule.setContent { NewPhoneSecretContent(NewPhoneSecretUiState(done = true), {}, {}, {}, {}) }
        rule.onNodeWithTag("new_secret_not_enough").assertDoesNotExist()
    }

    @Test
    fun newPhoneSecretRefusedKeepsNothing() = runTest(dispatcher) {
        val vault = FakeVault(AppPhase.Unlocked).apply {
            phoneSecret = true
            fail["renewPhoneSecret"] = FakeVault.failure(FailureKind.BAD_PASSWORD, "bad_password")
        }
        val vm = NewPhoneSecretViewModel(vault)
        vm.setPin("975310")
        vm.setPassword("wrong")
        vm.submit()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.password)
        assertFalse(vm.uiState.value.done)
    }
}
