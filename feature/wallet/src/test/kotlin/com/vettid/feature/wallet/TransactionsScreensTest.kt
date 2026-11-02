// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.wallet.TransactionsResult
import com.vettid.core.wallet.TxDirection
import com.vettid.core.wallet.TxStatus
import com.vettid.core.wallet.WalletPayee
import com.vettid.core.wallet.WalletTransaction
import com.vettid.core.wallet.WalletTransactions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The Transactions list and one transaction as the member sees them (W2-3). */
@RunWith(RobolectricTestRunner::class)
class TransactionsScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val payee = "tb1qpayee0000000000000000000000000000000x7k2"
    private val txid = "a1".repeat(32)
    private val sent = WalletTransaction(
        txid, TxDirection.SENT, 60_000, -61_000, 1_000, TxStatus.Confirmed(6), 100, Instant.parse("2026-10-31T19:00:00Z"),
        Instant.parse("2026-10-31T18:55:00Z"), listOf(WalletPayee(payee, 60_000)), null,
    )
    private val received = WalletTransaction("b2".repeat(32), TxDirection.RECEIVED, 50_000, 50_000, null, TxStatus.Waiting, null, null, null, emptyList(), null)
    private val self = WalletTransaction("c3".repeat(32), TxDirection.SELF, 19_500, -500, 500, TxStatus.Confirmed(1), 101, Instant.EPOCH, null, emptyList(), null)
    private val unsent = WalletTransaction(
        "d4".repeat(32), TxDirection.SENT, 5_000, -5_300, 300, TxStatus.NotBroadcast, null, null, Instant.parse("2026-10-29T08:00:00Z"),
        listOf(WalletPayee("tb1qother0000000000000000000000000000000z9z9", 5_000)), null,
    )
    private val list = TransactionsUiState(
        loading = false, name = "Savings", network = Network.TESTNET4,
        list = WalletTransactions(Instant.EPOCH, "mempool.space", null, listOf(sent, received, self, unsent)),
    )
    private val one = TransactionUiState(loading = false, name = "Savings", network = Network.TESTNET4, tx = sent, host = "mempool.space")

    @Test
    fun theRowsAreSentToReceivedAndTheirStatusesAndAmounts() {
        rule.setContent { TransactionsScreen(list, TransactionsActions()) }
        rule.onNodeWithText("Transactions").assertIsDisplayed()
        rule.onNodeWithText("Sent to tb1qpa…x7k2").assertExists()
        rule.onNodeWithText("Received").assertExists()
        rule.onNodeWithText("Moved within this wallet").assertExists()
        rule.onNodeWithText("− 0.0006 tBTC").assertExists()
        rule.onNodeWithText("+ 0.0005 tBTC").assertExists()
        assertEquals(4, rule.onAllNodesWithTag("wallet_tx_row").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Waiting for confirmation", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Not broadcast", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Confirmed", substring = true).assertCountEquals(2)
        rule.onNodeWithText("Sent to tb1qot…z9z9").assertExists()
    }

    @Test
    fun aRowOpensItsTransaction() {
        var opened: WalletTransaction? = null
        rule.setContent { TransactionsScreen(list, TransactionsActions(onOpen = { opened = it })) }
        rule.onNodeWithText("Received").performClick()
        assertEquals(received.txid, opened?.txid)
    }

    @Test
    fun emptyAndLoadingStates() {
        rule.setContent { TransactionsScreen(list.copy(list = list.list!!.copy(items = emptyList())), TransactionsActions()) }
        rule.onNodeWithText("No transactions yet.").assertIsDisplayed()
    }

    @Test
    fun loadingShowsAProgressIndicator() {
        rule.setContent { TransactionsScreen(list.copy(loading = true, list = null), TransactionsActions()) }
        rule.onNodeWithTag("wallet_transactions_loading").assertExists()
    }

    private var again = 0
    private var chain = 0

    private fun problem(p: TransactionsResult, network: Network? = Network.TESTNET4) {
        rule.setContent {
            TransactionsScreen(
                list.copy(list = null, network = network, problem = p),
                TransactionsActions(onRefresh = { again++ }, onChainServer = { chain++ }),
            )
        }
    }

    @Test
    fun aServerThatDidNotAnswerOffersTryAgainAndTheChainServer() {
        problem(TransactionsResult.ServerFailed(ChainError.Malformed()))
        rule.onNodeWithText("That server didn't answer.").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Chain server").performClick()
        assertEquals(1, again)
        assertEquals(1, chain)
    }

    @Test
    fun noServerAnUnreadableSettingAndAWrongNetworkOfferTheChainServer() {
        problem(TransactionsResult.NoServer(ChainServer.NotSet))
        rule.onNodeWithText("Chain server").performClick()
        assertEquals(1, chain)
        assertEquals(0, again)
    }

    @Test
    fun anUnreadableServerSettingIsSaidAsSuch() {
        problem(TransactionsResult.NoServer(ChainServer.Unreadable))
        rule.onNodeWithTag("wallet_transactions_problem").assertIsDisplayed()
        rule.onNodeWithText("Chain server").assertExists()
    }

    @Test
    fun aWrongNetworkServerNamesTheNetwork() {
        problem(TransactionsResult.ServerWrongNetwork(Network.MAINNET))
        rule.onNodeWithText("That server isn't on Testnet4.").assertIsDisplayed()
    }

    @Test
    fun aMismatchAndAVaultFailureAreSaid() {
        problem(TransactionsResult.AddressMismatch)
        rule.onNodeWithText("This wallet's addresses don't match your vault's. Nothing was sent.").assertIsDisplayed()
    }

    @Test
    fun aVaultFailureOffersTryAgain() {
        problem(TransactionsResult.VaultFailed(VaultFailure(FailureKind.NETWORK, "x")))
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, again)
    }

    @Test
    fun thePrivacyNoteNotYetShownSendsTheMemberBackToTheWallet() {
        problem(TransactionsResult.NoteNeeded)
        rule.onNodeWithText("Open the wallet first.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theTransactionShowsAmountsFeeConfirmationsBlockTimeAndTheIdWithCopy() {
        var copied: String? = null
        rule.setContent { TransactionScreen(one, TransactionActions(onCopy = { copied = it })) }
        rule.onNodeWithText("Sent to tb1qpa…x7k2").assertExists()
        rule.onNodeWithTag("wallet_transaction_payee").assertExists()
        rule.onNodeWithTag("wallet_transaction_amount").assertExists()
        rule.onNodeWithText("0.0006 tBTC").assertExists()
        rule.onNodeWithText("0.00001 tBTC").assertExists()
        rule.onNodeWithText("0.00061 tBTC").assertExists()
        rule.onNodeWithTag("wallet_transaction_confirmations").assertExists()
        rule.onNodeWithText("6").assertExists()
        rule.onNodeWithTag("wallet_transaction_block_time").assertExists()
        rule.onNodeWithText(txid).assertExists()
        rule.onNodeWithTag("wallet_transaction_copy").performScrollTo().performClick()
        assertEquals(txid, copied)
        // No site, no button.
        rule.onNodeWithTag("wallet_transaction_explorer").assertDoesNotExist()
    }

    @Test
    fun aReceivedPaymentHasNoFee() {
        rule.setContent { TransactionScreen(one.copy(tx = received), TransactionActions()) }
        rule.onNodeWithTag("wallet_transaction_fee").assertDoesNotExist()
        rule.onNodeWithTag("wallet_transaction_total").assertDoesNotExist()
        rule.onNodeWithTag("wallet_transaction_confirmations").assertDoesNotExist()
        rule.onNodeWithText("Waiting for confirmation").assertExists()
    }

    @Test
    fun aNotBroadcastPaymentSaysWhy() {
        rule.setContent { TransactionScreen(one.copy(tx = unsent), TransactionActions()) }
        rule.onNodeWithText("Not broadcast").assertExists()
        rule.onNodeWithTag("wallet_transaction_not_broadcast_note").assertExists()
    }

    @Test
    fun theExplorerButtonIsThereOnlyWithASiteAndAsksBeforeLeaving() {
        var asked = 0
        var confirmed = 0
        var cancelled = 0
        val actions = TransactionActions(onExplorer = { asked++ }, onConfirmExplorer = { confirmed++ }, onCancelExplorer = { cancelled++ })
        val withSite = one.copy(explorerUrl = "https://explorer.example/testnet4/tx/$txid")
        rule.setContent { TransactionScreen(withSite, actions) }
        rule.onNodeWithText("Open in a block explorer").performScrollTo().performClick()
        assertEquals(1, asked)
        assertEquals(0, confirmed)
    }

    @Test
    fun theConfirmNamesTheSiteAndSaysItLeavesVettId() {
        var confirmed = 0
        var cancelled = 0
        val ask = one.copy(explorerUrl = "https://explorer.example/testnet4/tx/$txid", explorerAsk = true)
        rule.setContent { TransactionScreen(ask, TransactionActions(onConfirmExplorer = { confirmed++ }, onCancelExplorer = { cancelled++ })) }
        rule.onNodeWithText("Leave VettID?").assertIsDisplayed()
        rule.onNodeWithText("This opens explorer.example in your browser.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, confirmed)
        rule.onNodeWithText("Open").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun notFoundLoadingAndAFailedReadAreShown() {
        rule.setContent { TransactionScreen(TransactionUiState(loading = false, notFound = true), TransactionActions()) }
        rule.onNodeWithTag("wallet_transaction_not_found").assertExists()
    }

    @Test
    fun aFailedReadKeepsTheTransactionShownAndOffersTryAgain() {
        var again = 0
        rule.setContent {
            TransactionScreen(
                one.copy(problem = TransactionsResult.VaultFailed(VaultFailure(FailureKind.NETWORK, "x"))),
                TransactionActions(onRefresh = { again++ }),
            )
        }
        rule.onNodeWithTag("wallet_transaction_amount").assertExists()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, again)
    }
}
