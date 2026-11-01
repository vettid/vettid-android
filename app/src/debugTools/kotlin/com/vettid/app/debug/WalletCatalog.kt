// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainServerUrl
import com.vettid.core.chain.Network
import com.vettid.core.chain.UrlCheck
import com.vettid.core.chain.UrlProblem
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.wallet.WalletLabel
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.wallet.LookupResult
import com.vettid.core.wallet.WalletAddress
import com.vettid.core.wallet.WalletLookup
import com.vettid.feature.credential.CredentialActions
import com.vettid.feature.credential.CredentialContent
import com.vettid.feature.credential.CredentialUiState
import com.vettid.feature.wallet.AccountDetails
import com.vettid.feature.wallet.AddressTypeDialog
import com.vettid.feature.wallet.AddressTypes
import com.vettid.feature.wallet.BackupActions
import com.vettid.feature.wallet.BackupProblem
import com.vettid.feature.wallet.BackupScreen
import com.vettid.feature.wallet.BackupStep
import com.vettid.feature.wallet.BackupUiState
import com.vettid.feature.wallet.ChainServerActions
import com.vettid.feature.wallet.ChainServerScreen
import com.vettid.feature.wallet.ChainServerUiState
import com.vettid.feature.wallet.DeleteStep
import com.vettid.feature.wallet.PhraseWords
import com.vettid.feature.wallet.ReceiveActions
import com.vettid.feature.wallet.ReceiveScreen
import com.vettid.feature.wallet.ReceiveUiState
import com.vettid.feature.wallet.ServerProblem
import com.vettid.feature.wallet.WalletDetailActions
import com.vettid.feature.wallet.WalletDetailScreen
import com.vettid.feature.wallet.WalletDetailUiState
import com.vettid.feature.wallet.WalletDetailsActions
import com.vettid.feature.wallet.WalletDetailsScreen
import com.vettid.feature.wallet.WalletDetailsUiState
import com.vettid.feature.wallet.WalletRow
import com.vettid.feature.wallet.WalletsActions
import com.vettid.feature.wallet.WalletsScreen
import com.vettid.feature.wallet.WalletsUiState
import java.time.Instant

/**
 * The wallet (ANDROID-PLAN §4 "Wallet", W1c-2a): the list (with the test-network banner and a wallet not backed up), empty, the
 * failed read, new wallet, the password to show a phrase again, the phrase, the check (and a wrong word), done, and the Credential
 * screen's wallet rows; W1c-2b: the detail (loaded, incoming, each look-up state, the privacy note, not backed up), the address type
 * dialog, delete (the warning, the password), Receive (with and without an amount, not backed up), the Chain server (each setting,
 * checking, each refusal) and the details. The real phrase and check screens are `FLAG_SECURE` (`SecureWindow`); these samples are not. Made-up words.
 */
internal object WalletCatalog {
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})

    private val rows = listOf(
        WalletRow("01WALLETSAVINGS00000000000A", "Savings", Network.SIGNET, "signet", backedUp = true),
        WalletRow("01WALLETSPENDING0000000000B", "Spending", Network.TESTNET4, "testnet4", backedUp = false),
    )

    /** Twelve made-up words (not a phrase of any wallet: no checksum). */
    private fun words(passphrase: String? = null) = PhraseWords(
        listOf("orbit", "maple", "candle", "river", "fossil", "lantern", "velvet", "harbor", "pencil", "meadow", "copper", "thunder").map { it.toCharArray() },
        passphrase?.toCharArray(),
    )
    private val asked = listOf(2, 6, 9)

    private val form = BackupUiState(BackupStep.FORM, networks = listOf(Network.SIGNET, Network.TESTNET4), network = Network.SIGNET)

    private fun check(wrong: Int? = null) = BackupUiState(
        BackupStep.CHECK, words = words(), asked = asked, askIndex = 1, wrong = wrong,
        candidates = listOf(7, 5, 1, 10),
    )

    @Composable
    private fun Backup(state: BackupUiState) = BackupScreen(state, BackupActions())

    private val credential = CredentialStatus(true, 7, "Jk4m2Qx9TzA1", "2026-10-04T14:12:00Z", null, true, 300, 3)

    private val lookup = WalletLookup(Instant.parse("2026-10-31T20:00:00Z"), "mempool.space", 123_000, 0, emptySet())
    private val detail = WalletDetailUiState(loaded = true, name = "Savings", network = Network.TESTNET4, last = lookup, lookup = LookupResult.Done(lookup))
    private val noLookup = detail.copy(last = null)

    @Composable
    private fun Detail(state: WalletDetailUiState) = WalletDetailScreen(state, WalletDetailActions())

    private val address = WalletAddress("tb1p5cyxnuxmeuwuvkwfem96lxx9wex2a5qe0l8e37j0sy6v6pxd0p6sxt3ukd", "p2tr", 0, false, false)
    private val receive = ReceiveUiState(loading = false, network = Network.TESTNET4, address = address)

    @Composable
    private fun Receive(state: ReceiveUiState) = ReceiveScreen(state, ReceiveActions())

    private val own = (ChainServerUrl.parse("https://esplora.example.org/api") as UrlCheck.Ok).url
    private val defaultServer = ChainServerUiState(Network.TESTNET4, ChainServer.Default(ChainDefaults.TESTNET4))

    @Composable
    private fun Server(state: ChainServerUiState) = ChainServerScreen(state, ChainServerActions())

    private fun refused(p: ServerProblem) = defaultServer.copy(text = "https://esplora.example.org/api", problem = p)

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "wallet" to { WalletsScreen(WalletsUiState(loading = false, rows = rows), chrome, WalletsActions()) },
        "wallet.empty" to { WalletsScreen(WalletsUiState(loading = false, testBuild = true), chrome, WalletsActions()) },
        "wallet.failed" to { WalletsScreen(WalletsUiState(loading = false, failed = FailureKind.NETWORK), chrome, WalletsActions()) },
        "wallet.new" to { Backup(form.copy(name = "Savings", password = "secret")) },
        "wallet.new_bad_name" to { Backup(form.copy(name = "x".repeat(129), password = "secret")) },
        "wallet.new_limit" to { Backup(form.copy(name = "Savings", problem = BackupProblem.LIMIT)) },
        "wallet.new_bad_password" to { Backup(form.copy(name = "Savings", error = FailureKind.BAD_PASSWORD)) },
        "wallet.password" to { Backup(BackupUiState(BackupStep.PASSWORD, password = "secret")) },
        "wallet.password_pin_required" to { Backup(BackupUiState(BackupStep.PASSWORD, capped = true)) },
        "wallet.phrase" to { Backup(BackupUiState(BackupStep.PHRASE, words = words())) },
        "wallet.phrase_passphrase" to { Backup(BackupUiState(BackupStep.PHRASE, words = words(passphrase = "Correct Horse"))) },
        "wallet.check" to { Backup(check()) },
        "wallet.check_wrong" to { Backup(check(wrong = asked[1])) },
        "wallet.done" to { Backup(BackupUiState(BackupStep.DONE, backedUp = true)) },
        "wallet.done_save_failed" to { Backup(BackupUiState(BackupStep.DONE, saveFailed = true)) },
        "wallet.done_reveal_failed" to { Backup(BackupUiState(BackupStep.DONE, revealFailed = true)) },
        "wallet.detail" to { Detail(detail) },
        "wallet.detail_incoming" to { Detail(detail.copy(last = lookup.copy(unconfirmedSats = 100_000))) },
        "wallet.detail_needs_network" to { Detail(noLookup.copy(network = null, lookup = LookupResult.NeedsNetwork)) },
        "wallet.detail_testnet3" to { Detail(noLookup.copy(network = Network.TESTNET, lookup = LookupResult.NetworkNotOffered)) },
        "wallet.detail_no_server" to { Detail(noLookup.copy(lookup = LookupResult.NoServer(ChainServer.NotSet))) },
        "wallet.detail_server_unreadable" to { Detail(noLookup.copy(lookup = LookupResult.NoServer(ChainServer.Unreadable))) },
        "wallet.detail_wrong_network" to { Detail(noLookup.copy(lookup = LookupResult.ServerWrongNetwork(Network.MAINNET))) },
        "wallet.detail_server_failed" to { Detail(noLookup.copy(lookup = LookupResult.ServerFailed(ChainError.Unreachable()))) },
        "wallet.detail_mismatch" to { Detail(noLookup.copy(lookup = LookupResult.AddressMismatch)) },
        "wallet.detail_vault_failed" to { Detail(noLookup.copy(lookup = LookupResult.VaultFailed(VaultFailure(FailureKind.NETWORK, "x")))) },
        "wallet.detail_privacy_note" to { Detail(noLookup.copy(lookup = null, noteNeeded = true)) },
        "wallet.detail_not_backed_up" to { Detail(detail.copy(backedUp = false)) },
        "wallet.address_type" to { AddressTypeDialog(AddressTypes.TAPROOT, onPick = {}, onDismiss = {}) },
        "wallet.delete_confirm" to { Detail(detail.copy(delete = DeleteStep.CONFIRM)) },
        "wallet.delete_password" to { Detail(detail.copy(delete = DeleteStep.PASSWORD, password = "secret")) },
        "wallet.delete_wrong_password" to { Detail(detail.copy(delete = DeleteStep.PASSWORD, deleteError = FailureKind.BAD_PASSWORD)) },
        "wallet.receive" to { Receive(receive) },
        "wallet.receive_amount" to { Receive(receive.copy(amountText = "0.00123")) },
        "wallet.receive_not_backed_up" to { Receive(ReceiveUiState(loading = false, notBackedUp = true)) },
        "wallet.chain_server" to { Server(defaultServer) },
        "wallet.chain_server_own" to { Server(defaultServer.copy(server = ChainServer.Own(own))) },
        "wallet.chain_server_not_set" to { Server(defaultServer.copy(server = ChainServer.NotSet)) },
        "wallet.chain_server_unreadable" to { Server(defaultServer.copy(server = ChainServer.Unreadable)) },
        "wallet.chain_server_checking" to { Server(defaultServer.copy(text = "https://esplora.example.org/api", checking = true)) },
        "wallet.chain_server_saved" to { Server(defaultServer.copy(server = ChainServer.Own(own), saved = true)) },
        "wallet.chain_server_bad_url" to { Server(refused(ServerProblem.BadUrl(UrlProblem.NOT_A_URL))) },
        "wallet.chain_server_not_https" to { Server(refused(ServerProblem.BadUrl(UrlProblem.NOT_HTTPS))) },
        "wallet.chain_server_credentials" to { Server(refused(ServerProblem.BadUrl(UrlProblem.CREDENTIALS))) },
        "wallet.chain_server_query" to { Server(refused(ServerProblem.BadUrl(UrlProblem.QUERY_OR_FRAGMENT))) },
        "wallet.chain_server_failed" to { Server(refused(ServerProblem.Failed)) },
        "wallet.chain_server_wrong_network" to { Server(refused(ServerProblem.WrongNetwork)) },
        "wallet.chain_server_not_written" to { Server(refused(ServerProblem.NotWritten)) },
        "wallet.details" to {
            WalletDetailsScreen(
                WalletDetailsUiState(
                    loaded = true, fingerprint = "73c5da0a", network = Network.TESTNET4, host = "mempool.space", networkName = "testnet4",
                    accounts = listOf(
                        AccountDetails("p2tr", "m/86'/1'/0'", "tr([73c5da0a/86'/1'/0']tpubDDfvzhdVV4unso.../0/*)", "tr([73c5da0a/86'/1'/0']tpubDDfvzhdVV4unso.../1/*)"),
                        AccountDetails("p2wpkh", "m/84'/1'/0'", "wpkh([73c5da0a/84'/1'/0']tpubDC8msFGeGuwnKG.../0/*)", "wpkh([73c5da0a/84'/1'/0']tpubDC8msFGeGuwnKG.../1/*)"),
                    ),
                ),
                WalletDetailsActions(),
            )
        },
        "credential.wallets" to {
            CredentialContent(
                CredentialUiState(loading = false, status = credential, wallets = rows.map { WalletLabel(it.id, it.name) }),
                chrome,
                CredentialActions(),
            )
        },
    )
}
