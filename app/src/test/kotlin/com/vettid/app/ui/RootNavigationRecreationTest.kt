package com.vettid.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.SetupStage
import com.vettid.core.testing.FakeVault
import com.vettid.feature.onboarding.TransferInStep
import com.vettid.feature.onboarding.TransferInViewModel
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@Serializable
internal data object StartingTestDest

@Serializable
internal data object OnboardingTestDest

@Serializable
internal data object MainTestDest

@Serializable
internal data object HomeTestRoute

@Serializable
internal data object DetailTestRoute

/** A screen of the open app with something typed: memory only, as every form of the app. */
class DraftTestViewModel : ViewModel() {
    var draft by mutableStateOf("")
}

/**
 * A6 audit R1: the root navigation follows the phase without rebuilding the destination that already shows. The
 * activity is recreated (as by a rotation, a fold or a theme change) and the app lock closes and opens, over the
 * root as [VettIdApp] builds it: [RootDestinationEffect], [AppLockGate], one destination per phase, the open app with
 * a navigation of its own. The transfer is the real [TransferInViewModel] waiting for the old phone's approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RootNavigationRecreationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val vault = FakeVault(AppPhase.Setup(SetupStage.VAULT_ELSEWHERE))
    private var target: Any by mutableStateOf(StartingTestDest)
    private var locked by mutableStateOf(false)
    private val transfers = mutableListOf<TransferInViewModel>()
    private val drafts = mutableListOf<DraftTestViewModel>()

    @Composable
    private fun Root() {
        val nav = rememberNavController()
        RootDestinationEffect(nav, target, locked)
        AppLockGate(locked = locked, onAutoPrompt = {}, lockScreen = { Text("locked", Modifier.testTag("lock_screen")) }) {
            NavHost(nav, startDestination = StartingTestDest) {
                composable<StartingTestDest> { Text("starting", Modifier.testTag("starting")) }
                composable<OnboardingTestDest> {
                    val vm = viewModel { TransferInViewModel(vault, vault) }
                    if (transfers.lastOrNull() !== vm) transfers += vm
                    val s by vm.uiState.collectAsState()
                    Column {
                        Text(s.step.name, Modifier.testTag("transfer_step"))
                        Text(s.sas.orEmpty(), Modifier.testTag("transfer_sas"))
                    }
                }
                composable<MainTestDest> {
                    val inner = rememberNavController()
                    NavHost(inner, startDestination = HomeTestRoute) {
                        composable<HomeTestRoute> {
                            Button({ inner.navigate(DetailTestRoute) }, Modifier.testTag("open_detail")) { Text("open") }
                        }
                        composable<DetailTestRoute> {
                            val vm = viewModel { DraftTestViewModel() }
                            if (drafts.lastOrNull() !== vm) drafts += vm
                            TextField(vm.draft, { vm.draft = it }, Modifier.testTag("draft"))
                        }
                    }
                }
            }
        }
    }

    private fun show() {
        rule.activityRule.scenario.onActivity { it.setContent { Root() } }
        rule.waitForIdle()
    }

    /** A new activity instance for a new configuration; the ViewModel store and the saved state are carried over. */
    private fun rotate() {
        RuntimeEnvironment.setQualifiers("+land")
        rule.activityRule.scenario.recreate()
        show()
    }

    private fun code() = String(
        InviteQr(
            InviteKind.APP,
            "https://relay.vettid.test",
            "abcdefghijklmnopqrstuvwxyz",
            ByteArray(32) { 1 },
            ByteArray(32) { 2 },
            Instant.now().plusSeconds(600).epochSecond,
        ).marshal(),
    )

    private fun startTransfer(): TransferInViewModel {
        target = OnboardingTestDest
        show()
        val vm = transfers.single()
        vm.scan()
        vm.scanned(code())
        rule.waitForIdle()
        rule.onNodeWithTag("transfer_step").assertTextEquals(TransferInStep.COMPARE.name)
        rule.onNodeWithTag("transfer_sas").assertTextEquals("042817")
        return vm
    }

    private fun openDetailAndType(): DraftTestViewModel {
        target = MainTestDest
        show()
        rule.onNodeWithTag("open_detail").performClick()
        rule.onNodeWithTag("draft").performTextInput("half a sentence")
        rule.waitForIdle()
        return drafts.single()
    }

    @Test
    fun aRotationDuringTheTransferWaitKeepsTheWaitAndTheSafetyCode() {
        val vm = startTransfer()
        rotate()
        // The same ViewModel, still waiting for the old phone: nothing was cancelled, nothing asked for twice.
        assertSame(vm, transfers.single())
        assertTrue(vm.viewModelScope.isActive)
        rule.onNodeWithTag("transfer_step").assertTextEquals(TransferInStep.COMPARE.name)
        rule.onNodeWithTag("transfer_sas").assertTextEquals("042817")
        assertEquals(listOf("transferIn", "awaitTransferIn"), vault.calls)
        // The old phone approves after the rotation: the wait that was running completes.
        vault.transferApproval.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag("transfer_step").assertTextEquals(TransferInStep.DONE.name)
        assertFalse("abandonTransferIn" in vault.calls)
    }

    @Test
    fun aRotationOnAScreenOfTheOpenAppKeepsTheScreenAndWhatWasTyped() {
        val vm = openDetailAndType()
        rotate()
        assertSame(vm, drafts.single())
        rule.onNodeWithTag("draft").assertTextEquals("half a sentence")
        rule.onNodeWithTag("open_detail").assertDoesNotExist()
    }

    @Test
    fun theAppLockClosingAndOpeningKeepsTheScreenAndWhatWasTyped() {
        val draft = openDetailAndType()
        locked = true
        rule.waitForIdle()
        rule.onNodeWithTag("draft").assertDoesNotExist()
        locked = false
        rule.waitForIdle()
        assertSame(draft, drafts.single())
        rule.onNodeWithTag("draft").assertTextEquals("half a sentence")
    }

    @Test
    fun theAppLockOverTheTransferWaitKeepsIt() {
        val vm = startTransfer()
        locked = true
        rule.waitForIdle()
        rule.onNodeWithTag("lock_screen").assertExists()
        // Rotated while locked, then unlocked.
        rotate()
        locked = false
        rule.waitForIdle()
        assertSame(vm, transfers.single())
        assertTrue(vm.viewModelScope.isActive)
        rule.onNodeWithTag("transfer_sas").assertTextEquals("042817")
        assertEquals(listOf("transferIn", "awaitTransferIn"), vault.calls)
    }

    @Test
    fun aPhaseChangeStillReplacesTheDestinationAndForgetsItsViewModels() {
        val vm = startTransfer()
        target = MainTestDest
        rule.waitForIdle()
        rule.onNodeWithTag("transfer_step").assertDoesNotExist()
        rule.onNodeWithTag("open_detail").assertExists()
        // Leaving a phase drops what it held (CLAUDE.md: "leaving a phase drops its secrets").
        assertFalse(vm.viewModelScope.isActive)
        // Coming back to the phase is a new destination, not the old one restored.
        target = OnboardingTestDest
        rule.waitForIdle()
        assertEquals(2, transfers.size)
        assertNotSame(vm, transfers.last())
        rule.onNodeWithTag("transfer_step").assertTextEquals(TransferInStep.INTRO.name)
    }

    @Test
    fun aPhaseThatChangedUnderTheAppLockIsFollowedOnUnlock() {
        val draft = openDetailAndType()
        locked = true
        rule.waitForIdle()
        target = OnboardingTestDest // the vault locked meanwhile, say
        rule.waitForIdle()
        locked = false
        rule.waitForIdle()
        rule.onNodeWithTag("draft").assertDoesNotExist()
        rule.onNodeWithTag("transfer_step").assertExists()
        assertFalse(draft.viewModelScope.isActive)
    }
}
