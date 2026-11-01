// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.Network
import com.vettid.core.wallet.WalletFlags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "Reset wallet settings on this phone": offered only while the flags file cannot be read, and never a limit quietly dropped. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WalletResetTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val rule = createComposeRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private val id = "01WALLET0000000000000000AA"
    private val unreadable = """{"version":2,"backed_up":["$id"]}"""

    private fun TestScope.mgr() = manager(backgroundScope, ops, WalletFlags(flagsFile))

    // The screens

    private fun detail(unreadable: Boolean, ask: Boolean = false) = WalletDetailUiState(
        loaded = true, name = "Savings", network = Network.TESTNET4, capsPending = unreadable, settingsUnreadable = unreadable,
        capsSupported = true, resetAsk = ask,
    )

    @Test
    fun theDetailHasNoResetWhileTheSettingsRead() {
        rule.setContent { WalletDetailScreen(detail(unreadable = false), WalletDetailActions()) }
        rule.onNodeWithTag("wallet_reset_settings").assertDoesNotExist()
    }

    @Test
    fun theDetailOffersTheResetWhileTheSettingsAreUnreadable() {
        var asked = 0
        rule.setContent { WalletDetailScreen(detail(unreadable = true), WalletDetailActions(onAskReset = { asked++ })) }
        rule.onNodeWithText("Reset wallet settings on this phone").assertIsDisplayed().performClick()
        assertEquals(1, asked)
    }

    @Test
    fun theConfirmSaysWhatIsForgottenAndThatTheVaultIsNotChanged() {
        var reset = 0
        rule.setContent { WalletDetailScreen(detail(unreadable = true, ask = true), WalletDetailActions(onReset = { reset++ })) }
        rule.onNodeWithText("Reset wallet settings?").assertIsDisplayed()
        rule.onNodeWithText("Nothing in your vault changes.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("backed up", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Reset").performClick()
        assertEquals(1, reset)
    }

    @Test
    fun spendingLimitsIsInTheMenuOnlyForAVaultThatEnforcesThem() {
        rule.setContent { WalletDetailScreen(detail(unreadable = false).copy(capsSupported = true), WalletDetailActions()) }
        rule.onNodeWithTag("wallet_menu").performClick()
        rule.onNodeWithTag("wallet_menu_limits").assertIsDisplayed()
    }

    @Test
    fun noSpendingLimitsInTheMenuForAVaultWithoutCaps() {
        rule.setContent { WalletDetailScreen(detail(unreadable = false).copy(capsSupported = false), WalletDetailActions()) }
        rule.onNodeWithTag("wallet_menu").performClick()
        rule.onNodeWithTag("wallet_menu_limits").assertDoesNotExist()
    }

    @Test
    fun receiveOffersTheResetOnTheUnreadableNoticeOnly() {
        rule.setContent {
            ReceiveScreen(
                ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.SETTINGS_UNREADABLE),
                ReceiveActions(),
            )
        }
        rule.onNodeWithText("Reset wallet settings on this phone").assertIsDisplayed()
    }

    @Test
    fun receiveAsksTheLimitQuestionWithNoLimitAChoice() {
        var go = 0
        rule.setContent {
            ReceiveScreen(
                ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.LIMITS_ASK),
                ReceiveActions(onLimitsContinue = { go++ }),
            )
        }
        rule.onNodeWithText("Set a spending limit?").assertIsDisplayed()
        rule.onNodeWithTag("limits_none").assertIsDisplayed()
        rule.onNodeWithTag("limits_set").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        assertEquals(1, go)
    }

    // The view models

    @Test
    fun theDetailResetIsRefusedForAReadableFileAndSendsNothingForAnUnreadableOne() = runTest {
        ops.wallet(id)
        flagsFile.bytes = null
        val readable = mgr().also { it.open() }
        val d1 = WalletDetailViewModel(SavedStateHandle(mapOf("walletId" to id)), readable, FakeServerSettings())
        eventually { d1.state.value.loaded }
        d1.askReset()
        assertFalse(d1.state.value.resetAsk)

        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = mgr().also { it.open() }
        val d = WalletDetailViewModel(SavedStateHandle(mapOf("walletId" to id)), m, FakeServerSettings())
        eventually { d.state.value.loaded && d.state.value.settingsUnreadable }
        ops.calls.clear()
        d.askReset()
        assertTrue(d.state.value.resetAsk)
        // Cancel does nothing.
        d.cancelReset()
        assertEquals(unreadable, flagsFile.bytes!!.decodeToString())
        d.askReset()
        d.resetSettings()
        eventually { !d.state.value.settingsUnreadable }
        assertTrue(ops.calls.none { it.startsWith("caps.") || it == "new" || it.startsWith("create") })
        assertFalse(WalletFlags(flagsFile).backedUp(id))
        // Nothing was marked backed up: the wallet shows Not backed up.
        eventually { !d.state.value.backedUp }
    }

    @Test
    fun anUncappedWalletAsksTheLimitBeforeReceiveAfterTheReset() = runTest {
        ops.wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = mgr().also { it.open() }
        m.resetSettings()
        WalletFlags(flagsFile).setBackedUp(id)
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.problem == ReceiveProblem.LIMITS_ASK }
        assertTrue(ops.calls.none { it == "new" })
        // No limit: Receive goes on with an address, nothing set.
        vm.answerLimits()
        eventually { vm.state.value.address != null }
        assertTrue(ops.calls.none { it.startsWith("caps.") })
    }

    @Test
    fun aLimitChosenOnTheQuestionIsSentThroughThePendingPath() = runTest {
        ops.wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = mgr().also { it.open() }
        m.resetSettings()
        WalletFlags(flagsFile).setBackedUp(id)
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.problem == ReceiveProblem.LIMITS_ASK }
        vm.setLimitChoice(true)
        vm.setPerTx("100000")
        vm.answerLimits()
        eventually { vm.state.value.address != null }
        assertEquals(listOf("caps.set:100000:null"), ops.calls.filter { it.startsWith("caps.") })
        assertTrue(ops.wallets.getValue(id).capped)
    }

    @Test
    fun aCappedWalletGoesStraightOnAfterTheReset() = runTest {
        val w = ops.wallet(id)
        ops.wallets[id] = w.copy(capped = true, perTxSats = 1000, perPeriodSats = 5000)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = mgr().also { it.open() }
        m.resetSettings()
        WalletFlags(flagsFile).setBackedUp(id)
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.address != null }
        assertEquals(null, vm.state.value.problem)
    }

    @Test
    fun theReceiveResetStartsReceiveOver() = runTest {
        val w = ops.wallet(id)
        ops.wallets[id] = w.copy(capped = true, perTxSats = 1000, perPeriodSats = 5000)
        WalletFlags(flagsFile).setBackedUp(id)
        val m = mgr().also { it.open() }
        val vm = ReceiveViewModel(SavedStateHandle(mapOf("walletId" to id)), m)
        eventually { vm.state.value.address != null }
        // Not offered while the file reads.
        vm.askReset()
        assertFalse(vm.state.value.resetAsk)
    }
}
