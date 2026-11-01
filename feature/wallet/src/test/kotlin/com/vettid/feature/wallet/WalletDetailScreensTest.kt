// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.chain.UrlProblem
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.wallet.LookupResult
import com.vettid.core.wallet.WalletAddress
import com.vettid.core.wallet.WalletLookup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The wallet detail, Receive, Chain server and Details as the member sees them. */
@RunWith(RobolectricTestRunner::class)
class WalletDetailScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val lookup = WalletLookup(Instant.parse("2026-10-31T20:00:00Z"), "mempool.space", 123_000, 0, emptySet())
    private fun detail(
        last: WalletLookup? = lookup,
        result: LookupResult? = LookupResult.Done(lookup),
        backedUp: Boolean = true,
        noteNeeded: Boolean = false,
    ) = WalletDetailUiState(
        loaded = true, name = "Savings", network = Network.TESTNET4, backedUp = backedUp, last = last, lookup = result, noteNeeded = noteNeeded,
    )

    private fun show(state: WalletDetailUiState, actions: WalletDetailActions = WalletDetailActions()) =
        rule.setContent { WalletDetailScreen(state, actions) }

    @Test
    fun theBalanceIsInBitcoinWithSatsUnderneathAndTheLastLookup() {
        show(detail())
        rule.onNodeWithText("Savings").assertIsDisplayed()
        rule.onNodeWithText("0.00123 BTC").assertIsDisplayed()
        rule.onNodeWithText("123,000 sats").assertExists()
        rule.onNodeWithTag("wallet_last_lookup").assertExists()
        rule.onNodeWithTag("wallet_unconfirmed").assertDoesNotExist()
    }

    @Test
    fun anUnconfirmedAmountIsIncomingOrOutgoing() {
        show(detail(last = lookup.copy(unconfirmedSats = 100_000)))
        rule.onNodeWithText("+ 0.001 incoming").assertExists()
    }

    @Test
    fun aNegativeUnconfirmedAmountIsOutgoing() {
        show(detail(last = lookup.copy(unconfirmedSats = -100_000)))
        rule.onNodeWithText("- 0.001 outgoing").assertExists()
    }

    @Test
    fun receiveAndTheBackupBannerOpenTheirScreens() {
        var receive = 0
        var backUp = 0
        show(detail(backedUp = false), WalletDetailActions(onReceive = { receive++ }, onBackUp = { backUp++ }))
        rule.onNodeWithText("Not backed up. If your vault is lost, these coins are lost too.").assertExists()
        rule.onNodeWithTag("wallet_detail_back_up").performScrollTo().performClick()
        rule.onNodeWithTag("wallet_receive").performScrollTo().performClick()
        assertEquals(1, receive)
        assertEquals(1, backUp)
    }

    @Test
    fun thePrivacyNoteIsShownInsteadOfABalanceUntilContinue() {
        var acknowledged = 0
        show(detail(last = null, result = null, noteNeeded = true), WalletDetailActions(onAcknowledge = { acknowledged++ }))
        rule.onNodeWithText("To show your balance and send, VettID asks this server about your wallet's addresses.", substring = true).assertExists()
        rule.onNodeWithTag("wallet_balance_btc").assertDoesNotExist()
        rule.onNodeWithTag("wallet_privacy_continue").performScrollTo().performClick()
        assertEquals(1, acknowledged)
    }

    @Test
    fun whichTestNetworkOffersTestnet4AndTestnet3() {
        val chosen = mutableListOf<Network>()
        show(detail(last = null, result = LookupResult.NeedsNetwork), WalletDetailActions(onChooseNetwork = { chosen += it }))
        rule.onNodeWithText("Which test network is this wallet on?").assertExists()
        rule.onNodeWithTag("wallet_choose_testnet4").performScrollTo().performClick()
        rule.onNodeWithTag("wallet_choose_testnet3").performScrollTo().performClick()
        assertEquals(listOf(Network.TESTNET4, Network.TESTNET), chosen)
    }

    @Test
    fun testnet3SaysTheStagingAppDoesNotConnect() {
        show(detail(last = null, result = LookupResult.NetworkNotOffered).copy(network = Network.TESTNET))
        rule.onNodeWithText("VettID's staging app does not connect to testnet3").assertExists()
    }

    @Test
    fun eachLookupProblemSaysWhatItIsWithItsWayOut() {
        var chain = 0
        var again = 0
        var current by mutableStateOf(detail())
        rule.setContent { WalletDetailScreen(current, WalletDetailActions(onChainServer = { chain++ }, onRefresh = { again++ })) }
        val cases = listOf(
            Triple(LookupResult.NoServer(ChainServer.NotSet), "No chain server is set for this network.", "Chain server"),
            Triple(LookupResult.NoServer(ChainServer.Unreadable), "Your chain server setting couldn't be read.", "Chain server"),
            Triple(LookupResult.ServerWrongNetwork(Network.MAINNET), "That server isn't on Testnet4.", "Chain server"),
            Triple(LookupResult.ServerFailed(ChainError.Unreachable()), "That server didn't answer.", "Try again"),
            Triple(LookupResult.VaultFailed(VaultFailure(FailureKind.NETWORK, "x")), "", "Try again"),
            Triple(LookupResult.AddressMismatch, "This wallet's addresses don't match your vault's. Nothing was sent.", ""),
        )
        for ((result, text, way) in cases) {
            current = detail(result = result)
            rule.waitForIdle()
            if (text.isNotEmpty()) rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            if (way.isNotEmpty()) rule.onNodeWithText(way).performScrollTo().performClick()
        }
        assertEquals(3, chain)
        assertEquals(2, again)
    }

    @Test
    fun theMenuHasTheFourItemsAndNotTheW1dOnes() {
        val got = mutableListOf<String>()
        show(
            detail(),
            WalletDetailActions(
                onDetails = { got += "details" },
                onChainServer = { got += "chain" },
                onAskDelete = { got += "delete" },
                onAddressType = { got += "type:$it" },
            ),
        )
        rule.onNodeWithTag("wallet_menu").performClick()
        rule.onNodeWithText("Show recovery phrase").assertDoesNotExist()
        rule.onNodeWithText("Spending limits").assertDoesNotExist()
        rule.onNodeWithTag("wallet_menu_details").performClick()
        rule.onNodeWithTag("wallet_menu").performClick()
        rule.onNodeWithTag("wallet_menu_chain_server").performClick()
        rule.onNodeWithTag("wallet_menu").performClick()
        rule.onNodeWithTag("wallet_menu_delete").performClick()
        rule.onNodeWithTag("wallet_menu").performClick()
        rule.onNodeWithTag("wallet_menu_address_type").performClick()
        rule.onNodeWithText("Taproot (recommended)").assertIsDisplayed()
        rule.onNodeWithTag("wallet_type_p2wpkh").performClick()
        assertEquals(listOf("details", "chain", "delete", "type:p2wpkh"), got)
    }

    @Test
    fun deleteWarnsWithThePlansTextThenAsksThePassword() {
        var current by mutableStateOf(detail().copy(delete = DeleteStep.CONFIRM))
        var continued = 0
        var typed = ""
        rule.setContent {
            WalletDetailScreen(current, WalletDetailActions(onContinueDelete = { continued++ }, onPassword = { typed = it }))
        }
        rule.onNodeWithText("Delete Savings?").assertIsDisplayed()
        rule.onNodeWithText("Your vault forgets this wallet and its recovery phrase. Coins on it are lost unless you have the recovery phrase written down.").assertIsDisplayed()
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(1, continued)
        current = detail().copy(delete = DeleteStep.PASSWORD)
        rule.waitForIdle()
        rule.onNodeWithTag("wallet_delete_password").assertIsDisplayed()
        rule.onNodeWithTag("wallet_password").performTextInput("pw")
        assertEquals("pw", typed)
        current = detail().copy(delete = DeleteStep.PASSWORD, deleteError = FailureKind.BAD_PASSWORD)
        rule.waitForIdle()
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true).assertExists()
    }

    @Test
    fun receiveShowsTheAddressInGroupsOfFourTheQrAndTheLabels() {
        val address = WalletAddress("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", "p2wpkh", 0, false, false)
        var copied = 0
        var shared = 0
        var fresh = 0
        rule.setContent {
            ReceiveScreen(
                ReceiveUiState(loading = false, network = Network.TESTNET4, address = address, amountText = "0.5"),
                ReceiveActions(onCopy = { copied++ }, onShare = { shared++ }, onNewAddress = { fresh++ }),
            )
        }
        rule.onNodeWithText("tb1q w508 d6qe jxtd g4y5 r3za rvar y0c5 xw7k xpjz sx").assertExists()
        rule.onNodeWithTag("wallet_receive_qr").assertExists()
        rule.onNodeWithText("Native SegWit address").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Only send Testnet4 bitcoin to this address.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("wallet_receive_copy").performScrollTo().performClick()
        rule.onNodeWithTag("wallet_receive_share").performScrollTo().performClick()
        rule.onNodeWithTag("wallet_receive_new").performScrollTo().performClick()
        assertEquals(listOf(1, 1, 1), listOf(copied, shared, fresh))
    }

    @Test
    fun backDuringTheDeletePasswordCancelsThePasswordStepAndDoesNothingWhileDeleting() {
        var cancelled = 0
        var current by mutableStateOf(detail().copy(delete = DeleteStep.PASSWORD))
        lateinit var activity: androidx.activity.ComponentActivity
        rule.setContent {
            activity = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
            WalletDetailScreen(current, WalletDetailActions(onCancelDelete = { cancelled++ }))
        }
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, cancelled)
        current = current.copy(deleting = true)
        rule.waitForIdle()
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        // Taken by the screen, not passed on and not acted on.
        assertEquals(1, cancelled)
    }

    @Test
    fun receiveOfALegacyTestnetWalletAsksWhichNetworkFirst() {
        val chosen = mutableListOf<Network>()
        rule.setContent {
            ReceiveScreen(ReceiveUiState(loading = true, needsNetwork = true), ReceiveActions(onChooseNetwork = { chosen += it }))
        }
        rule.onNodeWithText("Which test network is this wallet on?").assertIsDisplayed()
        rule.onNodeWithTag("wallet_choose_testnet3").performClick()
        assertEquals(listOf(Network.TESTNET), chosen)
    }

    @Test
    fun receiveOfAWalletNotBackedUpShowsTheWayToTheCheckInstead() {
        var backUp = 0
        rule.setContent {
            ReceiveScreen(ReceiveUiState(loading = false, notBackedUp = true), ReceiveActions(onBackUp = { backUp++ }))
        }
        rule.onNodeWithText("Not backed up. If your vault is lost, these coins are lost too.").assertIsDisplayed()
        rule.onNodeWithTag("wallet_receive_qr").assertDoesNotExist()
        rule.onNodeWithText("Back up this wallet").performClick()
        assertEquals(1, backUp)
    }

    @Test
    fun theChainServerShowsItsSettingCheckingAndEachRefusal() {
        var current by mutableStateOf(ChainServerUiState(Network.TESTNET4, ChainServer.Default(ChainDefaults.TESTNET4)))
        rule.setContent { ChainServerScreen(current, ChainServerActions()) }
        rule.onNodeWithText("Default server: mempool.space").assertIsDisplayed()
        rule.onNodeWithTag("wallet_chain_server_note").performScrollTo().assertExists()
        current = current.copy(text = "https://x.example.org", checking = true)
        rule.waitForIdle()
        rule.onNodeWithTag("wallet_server_checking").performScrollTo().assertExists()
        val problems = listOf(
            ServerProblem.BadUrl(UrlProblem.NOT_HTTPS) to "Use an address that starts with https://.",
            ServerProblem.BadUrl(UrlProblem.NOT_A_URL) to "That isn't a web address.",
            ServerProblem.BadUrl(UrlProblem.CREDENTIALS) to "Leave the user name and password out of the address.",
            ServerProblem.BadUrl(UrlProblem.QUERY_OR_FRAGMENT) to "Leave out anything after ? or #: use the server's base address.",
            ServerProblem.Failed to "That server didn't answer.",
            ServerProblem.WrongNetwork to "That server isn't on Testnet4.",
            ServerProblem.NotWritten to "The server answered, but this phone couldn't save the setting. Try again.",
        )
        for ((p, text) in problems) {
            current = current.copy(checking = false, problem = p)
            rule.waitForIdle()
            rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theChainServerOffersResetOnlyForAnOwnServer() {
        var reset = 0
        var current by mutableStateOf(ChainServerUiState(Network.TESTNET4, ChainServer.Default(ChainDefaults.TESTNET4)))
        rule.setContent { ChainServerScreen(current, ChainServerActions(onReset = { reset++ })) }
        rule.onNodeWithTag("wallet_server_reset").assertDoesNotExist()
        current = current.copy(server = ChainServer.NotSet)
        rule.waitForIdle()
        rule.onNodeWithText("No chain server is set for this network.").assertExists()
        rule.onNodeWithTag("wallet_server_reset").performScrollTo().performClick()
        assertEquals(1, reset)
    }

    @Test
    fun theDetailsShowTheFingerprintAccountsAndWhichServerIsUsed() {
        var copied = ""
        var chain = 0
        val state = WalletDetailsUiState(
            loaded = true, fingerprint = "73c5da0a", network = Network.TESTNET4, host = "mempool.space", networkName = "testnet4",
            accounts = listOf(AccountDetails("p2tr", "m/86'/1'/0'", "tr(R/0/*)", "tr(R/1/*)")),
        )
        rule.setContent { WalletDetailsScreen(state, WalletDetailsActions(onCopy = { copied = it }, onChainServer = { chain++ })) }
        rule.onNodeWithText("73c5da0a").assertExists()
        rule.onNodeWithText("m/86'/1'/0'").performScrollTo().assertExists()
        rule.onNodeWithTag("wallet_change_descriptor_p2tr_copy").performScrollTo().performClick()
        assertEquals("tr(R/1/*)", copied)
        rule.onNodeWithText("Using mempool.space").performScrollTo().performClick()
        assertEquals(1, chain)
    }
}
