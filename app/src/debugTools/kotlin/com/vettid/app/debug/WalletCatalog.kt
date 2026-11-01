// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.wallet.WalletLabel
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.credential.CredentialActions
import com.vettid.feature.credential.CredentialContent
import com.vettid.feature.credential.CredentialUiState
import com.vettid.feature.wallet.BackupActions
import com.vettid.feature.wallet.BackupProblem
import com.vettid.feature.wallet.BackupScreen
import com.vettid.feature.wallet.BackupStep
import com.vettid.feature.wallet.BackupUiState
import com.vettid.feature.wallet.PhraseWords
import com.vettid.feature.wallet.WalletRow
import com.vettid.feature.wallet.WalletsActions
import com.vettid.feature.wallet.WalletsScreen
import com.vettid.feature.wallet.WalletsUiState

/**
 * The wallet (ANDROID-PLAN §4 "Wallet", W1c-2a): the list (with the test-network banner and a wallet not backed up), empty, the
 * failed read, new wallet, the password to show a phrase again, the phrase, the check (and a wrong word), done, and the Credential
 * screen's wallet rows. The real phrase and check screens are `FLAG_SECURE` (`SecureWindow`); these samples are not. Made-up words.
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
        "credential.wallets" to {
            CredentialContent(
                CredentialUiState(loading = false, status = credential, wallets = rows.map { WalletLabel(it.id, it.name) }),
                chrome,
                CredentialActions(),
            )
        },
    )
}
