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
import com.vettid.core.chain.AddressFamily
import com.vettid.core.wallet.CapReason
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
import com.vettid.feature.wallet.ImportActions
import com.vettid.feature.wallet.ImportProblem
import com.vettid.feature.wallet.ImportScreen
import com.vettid.feature.wallet.ImportStep
import com.vettid.feature.wallet.ImportUiState
import com.vettid.feature.wallet.LimitsForm
import com.vettid.feature.wallet.CapsEditForm
import com.vettid.feature.wallet.LimitsStep
import com.vettid.feature.wallet.PhraseInput
import com.vettid.feature.wallet.PhraseWords
import com.vettid.feature.wallet.ReceiveActions
import com.vettid.feature.wallet.ReceiveProblem
import com.vettid.feature.wallet.ReceiveScreen
import com.vettid.feature.wallet.ReceiveUiState
import com.vettid.feature.wallet.ServerProblem
import com.vettid.core.wallet.RecentSends
import com.vettid.feature.wallet.AddressIssue
import com.vettid.feature.wallet.BigFee
import com.vettid.feature.wallet.AmountUnit
import com.vettid.feature.wallet.BroadcastReason
import com.vettid.feature.wallet.CapText
import com.vettid.feature.wallet.ConfirmTime
import com.vettid.feature.wallet.FeeChoice
import com.vettid.feature.wallet.LimitLine
import com.vettid.feature.wallet.Review
import com.vettid.feature.wallet.SendActions
import com.vettid.feature.wallet.SendBlock
import com.vettid.feature.wallet.SendOutcome
import com.vettid.feature.wallet.SendProblem
import com.vettid.feature.wallet.SendScreen
import com.vettid.feature.wallet.SendStep
import com.vettid.feature.wallet.SendUiState
import com.vettid.feature.wallet.SpendingLimitsActions
import com.vettid.feature.wallet.SpendingLimitsScreen
import com.vettid.feature.wallet.SpendingLimitsUiState
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

    /** A public BIP39 test phrase (not a wallet of anyone). */
    private const val IMPORT_WORDS = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private val importForm = ImportUiState(
        networks = listOf(Network.SIGNET, Network.TESTNET4), network = Network.SIGNET, name = "Old wallet",
        typed = IMPORT_WORDS, status = PhraseInput.analyse(IMPORT_WORDS),
    )
    private val someLimits = LimitsForm(limit = true, perTx = "500000", perPeriod = "2000000")

    // Not secure here: the catalog is screenshotted.
    @Composable
    private fun Import(state: ImportUiState) = ImportScreen(state, ImportActions(), secure = false)

    private val credential = CredentialStatus(true, 7, "Jk4m2Qx9TzA1", "2026-10-04T14:12:00Z", null, true, 300, 3)

    private val lookup = WalletLookup(Instant.parse("2026-10-31T20:00:00Z"), "mempool.space", 123_000, 0, emptySet())
    private val detail = WalletDetailUiState(loaded = true, name = "Savings", network = Network.TESTNET4, last = lookup, lookup = LookupResult.Done(lookup))
    private val noLookup = detail.copy(last = null)

    @Composable
    private fun Detail(state: WalletDetailUiState) = WalletDetailScreen(state, WalletDetailActions())

    private val limits = SpendingLimitsUiState(
        loaded = true, name = "Savings", perTx = 500_000, perPeriod = 2_000_000, spent = 750_000,
    )

    @Composable
    private fun Limits(state: SpendingLimitsUiState) = SpendingLimitsScreen(state, SpendingLimitsActions())

    private val raiseSecrets = limits.copy(
        step = LimitsStep.CONFIRM_RAISE, raiseForm = true, form = CapsEditForm(perTx = "600000"), pin = "123456", password = "secret",
    )

    private val address = WalletAddress("tb1p5cyxnuxmeuwuvkwfem96lxx9wex2a5qe0l8e37j0sy6v6pxd0p6sxt3ukd", "p2tr", 0, false, false)
    private val receive = ReceiveUiState(loading = false, network = Network.TESTNET4, address = address)

    @Composable
    private fun Receive(state: ReceiveUiState) = ReceiveScreen(state, ReceiveActions())

    private val own = (ChainServerUrl.parse("https://esplora.example.org/api") as UrlCheck.Ok).url
    private val defaultServer = ChainServerUiState(Network.TESTNET4, ChainServer.Default(ChainDefaults.TESTNET4))

    @Composable
    private fun Server(state: ChainServerUiState) = ChainServerScreen(state, ChainServerActions())

    private fun refused(p: ServerProblem) = defaultServer.copy(text = "https://esplora.example.org/api", problem = p)

    private const val TEST_PAYEE = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"

    private val sendForm = SendUiState(
        loading = false, network = Network.TESTNET4, address = TEST_PAYEE, amountText = "40000", unit = AmountUnit.SATS,
        estimates = mapOf(2 to 12.0, 6 to 5.0, 144 to 1.0),
    )

    private val sendReview = Review(
        address = TEST_PAYEE, payeeSats = 40_000, changeSats = 58_800, feeSats = 1_200, vsize = 200, totalOutSats = 41_200,
        toFeeSats = 0, sendAll = false, leftBehind = 0, hasCap = true,
    )

    private fun sendReviewing(r: Review = sendReview, password: String = "") =
        sendForm.copy(step = SendStep.REVIEW, review = r, password = password)

    private fun sendDone(o: SendOutcome) = sendForm.copy(step = SendStep.DONE, outcome = o)

    // Not secure here: the catalog is screenshotted.
    @Composable
    private fun Send(state: SendUiState) = SendScreen(state, SendActions(), secure = false)

    private val sendPeriodCap = CapText(CapReason.PER_PERIOD, 2_000_000, 300_000, Instant.parse("2026-11-02T09:00:00Z"))

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "wallet" to { WalletsScreen(WalletsUiState(loading = false, rows = rows), chrome, WalletsActions()) },
        "wallet.empty" to { WalletsScreen(WalletsUiState(loading = false, testBuild = true), chrome, WalletsActions()) },
        "wallet.failed" to { WalletsScreen(WalletsUiState(loading = false, failed = FailureKind.NETWORK), chrome, WalletsActions()) },
        "wallet.new" to { Backup(form.copy(name = "Savings")) },
        "wallet.new_bad_name" to { Backup(form.copy(name = "x".repeat(129))) },
        "wallet.new_limits" to { Backup(form.copy(step = BackupStep.LIMITS, name = "Savings")) },
        "wallet.new_limits_set" to { Backup(form.copy(step = BackupStep.LIMITS, name = "Savings", limits = someLimits)) },
        "wallet.new_limits_btc" to {
            Backup(form.copy(step = BackupStep.LIMITS, name = "Savings", limits = LimitsForm(true, "0.005", "0.02", btc = true)))
        },
        "wallet.new_limits_bad" to {
            Backup(form.copy(step = BackupStep.LIMITS, name = "Savings", limits = LimitsForm(true, "3000000", "2000000")))
        },
        "wallet.new_password" to { Backup(form.copy(step = BackupStep.CREATE, name = "Savings", password = "secret")) },
        "wallet.new_limit" to { Backup(form.copy(step = BackupStep.CREATE, name = "Savings", problem = BackupProblem.LIMIT)) },
        "wallet.new_bad_password" to { Backup(form.copy(step = BackupStep.CREATE, name = "Savings", error = FailureKind.BAD_PASSWORD)) },
        "wallet.password" to { Backup(BackupUiState(BackupStep.PASSWORD, password = "secret")) },
        "wallet.password_pin_required" to { Backup(BackupUiState(BackupStep.PASSWORD, capped = true, pin = "123456", password = "secret")) },
        "wallet.password_bad_pin" to {
            Backup(BackupUiState(BackupStep.PASSWORD, capped = true, pin = "123456", password = "secret", error = FailureKind.BAD_PIN))
        },
        "wallet.phrase" to { Backup(BackupUiState(BackupStep.PHRASE, words = words())) },
        "wallet.phrase_passphrase" to { Backup(BackupUiState(BackupStep.PHRASE, words = words(passphrase = "Correct Horse"))) },
        "wallet.check" to { Backup(check()) },
        "wallet.check_wrong" to { Backup(check(wrong = asked[1])) },
        "wallet.done" to { Backup(BackupUiState(BackupStep.DONE, backedUp = true)) },
        "wallet.done_save_failed" to { Backup(BackupUiState(BackupStep.DONE, saveFailed = true)) },
        "wallet.done_reveal_failed" to { Backup(BackupUiState(BackupStep.DONE, revealFailed = true)) },
        "wallet.done_caps_failed" to { Backup(BackupUiState(BackupStep.DONE, backedUp = true, capsFailed = true)) },
        "wallet.import" to { Import(importForm.copy(typed = "", status = PhraseInput.analyse(""), name = "")) },
        "wallet.import_words" to { Import(importForm) },
        "wallet.import_unknown_word" to {
            Import(importForm.copy(typed = "legal winnr thank", status = PhraseInput.analyse("legal winnr thank")))
        },
        "wallet.import_invalid" to {
            val bad = "legal winner thank year wave sausage worth useful legal winner yellow thank"
            Import(importForm.copy(typed = bad, status = PhraseInput.analyse(bad), invalidPhrase = true))
        },
        "wallet.import_advanced" to { Import(importForm.copy(advanced = true, passphrase = "Correct Horse")) },
        "wallet.import_bad_passphrase" to { Import(importForm.copy(advanced = true, passphrase = "Caf\u00e9")) },
        "wallet.import_limits" to { Import(importForm.copy(step = ImportStep.LIMITS, limits = someLimits)) },
        "wallet.import_password" to { Import(importForm.copy(step = ImportStep.PASSWORD, password = "secret")) },
        "wallet.import_exists" to { Import(importForm.copy(step = ImportStep.PASSWORD, problem = ImportProblem.EXISTS)) },
        "wallet.import_done_caps_failed" to {
            Import(importForm.copy(step = ImportStep.DONE, typed = "", importedId = "01W", capsFailed = true))
        },
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
        "wallet.detail_caps_pending" to { Detail(detail.copy(capsPending = true)) },
        "wallet.detail_reset" to { Detail(detail.copy(capsPending = true, settingsUnreadable = true, capsSupported = true)) },
        "wallet.detail_reset_confirm" to {
            Detail(detail.copy(capsPending = true, settingsUnreadable = true, capsSupported = true, resetAsk = true))
        },
        "wallet.receive_reset" to {
            Receive(ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.SETTINGS_UNREADABLE))
        },
        "wallet.receive_limits_ask" to {
            Receive(ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.LIMITS_ASK))
        },
        "wallet.limits" to { Limits(limits) },
        "wallet.limits_none" to { Limits(limits.copy(perTx = null, perPeriod = null, spent = 0)) },
        "wallet.limits_one" to { Limits(limits.copy(perTx = null, spent = 0)) },
        "wallet.limits_unsupported" to { Limits(limits.copy(supported = false)) },
        "wallet.limits_lower" to {
            Limits(limits.copy(step = LimitsStep.LOWER, form = CapsEditForm(perTx = "400000")))
        },
        "wallet.limits_lower_bad" to {
            Limits(limits.copy(step = LimitsStep.LOWER, form = CapsEditForm(perTx = "600000")))
        },
        "wallet.limits_lower_confirm" to {
            Limits(limits.copy(step = LimitsStep.CONFIRM_SET, form = CapsEditForm(perTx = "400000")))
        },
        "wallet.limits_raise" to {
            Limits(limits.copy(step = LimitsStep.RAISE, raiseForm = true, form = CapsEditForm(perTx = "600000")))
        },
        "wallet.limits_raise_confirm" to { Limits(raiseSecrets) },
        "wallet.limits_remove" to {
            Limits(raiseSecrets.copy(form = CapsEditForm(removePeriod = true)))
        },
        "wallet.limits_bad_pin" to { Limits(raiseSecrets.copy(pin = "", password = "", error = FailureKind.BAD_PIN)) },
        "wallet.limits_backoff" to { Limits(raiseSecrets.copy(pin = "", password = "", error = FailureKind.BACKOFF)) },
        "wallet.limits_conflict" to { Limits(limits.copy(error = FailureKind.CONFLICT)) },
        "wallet.send" to { Send(sendForm.copy(address = "", amountText = "")) },
        "wallet.send_filled" to { Send(sendForm.copy(feeChoice = FeeChoice.FAST, includeUnconfirmed = true)) },
        "wallet.send_btc" to { Send(sendForm.copy(unit = AmountUnit.BTC, amountText = "0.0004")) },
        "wallet.send_all" to { Send(sendForm.copy(sendAll = true, amountText = "")) },
        "wallet.send_scan" to { Send(sendForm.copy(scanning = true)) },
        "wallet.send_scan_unavailable" to { Send(sendForm.copy(scanning = true, scanUnavailable = true)) },
        "wallet.send_wrong_network" to {
            Send(sendForm.copy(address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", addressIssue = AddressIssue.WrongNetwork(AddressFamily.MAINNET)))
        },
        "wallet.send_not_an_address" to { Send(sendForm.copy(address = "hello", addressIssue = AddressIssue.NotAnAddress)) },
        "wallet.send_fee_custom" to { Send(sendForm.copy(feeChoice = FeeChoice.CUSTOM, customFee = "40")) },
        "wallet.send_fee_custom_bad" to { Send(sendForm.copy(feeChoice = FeeChoice.CUSTOM, customFee = "1500")) },
        "wallet.send_fee_much_higher" to { Send(sendForm.copy(feeChoice = FeeChoice.CUSTOM, customFee = "40", bigFeeAsk = BigFee.MUCH_HIGHER)) },
        "wallet.send_fee_ten_percent" to { Send(sendForm.copy(bigFeeAsk = BigFee.TEN_PERCENT)) },
        "wallet.send_review_fee_ten_percent" to { Send(sendReviewing().copy(bigFeeAsk = BigFee.TEN_PERCENT)) },
        "wallet.send_link_refused" to { Send(sendForm.copy(linkRefused = true)) },
        "wallet.send_recent_waiting" to { Send(sendForm.copy(recentNotice = RecentSends.WAITING)) },
        "wallet.send_recent_gone" to { Send(sendForm.copy(recentNotice = RecentSends.MAY_HAVE_GONE)) },
        "wallet.send_recent_unreadable" to { Send(sendForm.copy(recentNotice = RecentSends.UNREADABLE)) },
        "wallet.send_no_estimates" to { Send(sendForm.copy(estimates = null, estimatesFailed = true)) },
        "wallet.send_not_enough" to { Send(sendForm.copy(problem = SendProblem.NotEnough(12_000, 41_200))) },
        "wallet.send_not_enough_confirmed" to { Send(sendForm.copy(problem = SendProblem.NotEnough(12_000, 41_200), includeUnconfirmed = true)) },
        "wallet.send_note_needed" to { Send(sendForm.copy(block = SendBlock.NOTE_NEEDED)) },
        "wallet.send_self_send" to { Send(sendForm.copy(problem = SendProblem.SelfSend)) },
        "wallet.send_different" to { Send(sendForm.copy(problem = SendProblem.Different)) },
        "wallet.send_server_failed" to { Send(sendForm.copy(problem = SendProblem.ServerFailed)) },
        "wallet.send_not_backed_up" to { Send(sendForm.copy(block = SendBlock.NOT_BACKED_UP)) },
        "wallet.send_caps_pending" to { Send(sendForm.copy(block = SendBlock.CAPS_PENDING)) },
        "wallet.send_caps_duplicate" to { Send(sendForm.copy(block = SendBlock.CAPS_DUPLICATE)) },
        "wallet.send_settings_unreadable" to { Send(sendForm.copy(block = SendBlock.SETTINGS_UNREADABLE)) },
        "wallet.send_sign_unsettled" to { Send(sendForm.copy(block = SendBlock.SIGN_UNSETTLED)) },
        "wallet.send_review" to { Send(sendReviewing()) },
        "wallet.send_review_password" to { Send(sendReviewing(password = "secret")) },
        "wallet.send_review_wrong_password" to { Send(sendReviewing().copy(error = FailureKind.BAD_PASSWORD)) },
        "wallet.send_review_limit" to { Send(sendReviewing(sendReview.limited(LimitLine(41_200, 1_208_800)))) },
        "wallet.send_review_dust" to { Send(sendReviewing(sendReview.dusty(200))) },
        "wallet.send_review_no_caps" to { Send(sendReviewing(sendReview.noCaps())) },
        "wallet.send_review_cap" to { Send(sendReviewing(sendReview.refused(CapText(CapReason.PER_TX, 30_000, null, null)))) },
        "wallet.send_sent" to { Send(sendDone(SendOutcome.Sent(ConfirmTime.HOUR))) },
        "wallet.send_broadcast_failed" to {
            Send(sendDone(SendOutcome.BroadcastFailed("mempool.space", BroadcastReason.FEE_TOO_LOW)))
        },
        "wallet.send_broadcast_checking" to {
            Send(sendDone(SendOutcome.BroadcastFailed("mempool.space", BroadcastReason.NO_ANSWER)).copy(discardLocked = true, checking = true))
        },
        "wallet.send_broadcast_unknown" to {
            Send(sendDone(SendOutcome.BroadcastFailed("mempool.space", BroadcastReason.NO_ANSWER)).copy(discardLocked = true))
        },
        "wallet.send_broadcast_not_there" to {
            Send(sendDone(SendOutcome.BroadcastFailed("mempool.space", BroadcastReason.NO_ANSWER)))
        },
        "wallet.send_cap_per_tx" to { Send(sendDone(SendOutcome.CapExceeded(CapText(CapReason.PER_TX, 500_000, null, null)))) },
        "wallet.send_cap_per_period" to { Send(sendDone(SendOutcome.CapExceeded(sendPeriodCap))) },
        "wallet.send_cap_per_period_none" to { Send(sendDone(SendOutcome.CapExceeded(sendPeriodCap.copy(remainingSats = 0, allowedAfter = null)))) },
        "wallet.send_invalid_fee_rate" to { Send(sendDone(SendOutcome.InvalidPsbt("fee_rate", false))) },
        "wallet.send_invalid_output_amount" to { Send(sendDone(SendOutcome.InvalidPsbt("output_amount", false))) },
        "wallet.send_invalid_technical" to { Send(sendDone(SendOutcome.InvalidPsbt("derivation_key", false))) },
        "wallet.send_invalid_wrong_seed" to { Send(sendDone(SendOutcome.InvalidPsbt("wrong_seed", true))) },
        "wallet.send_invalid_other" to { Send(sendDone(SendOutcome.InvalidPsbt("something_new", false))) },
        "wallet.send_signed_differs" to { Send(sendDone(SendOutcome.SignedDiffers)) },
        "wallet.send_already_signed" to { Send(sendDone(SendOutcome.AlreadySigned)) },
        "wallet.send_unreadable" to { Send(sendDone(SendOutcome.SignedUnreadable)) },
        "wallet.send_already_signed_locked" to { Send(sendDone(SendOutcome.AlreadySigned).copy(discardLocked = true)) },
        "wallet.send_unanswered" to { Send(sendDone(SendOutcome.Unanswered)) },
        "wallet.send_lapsed" to { Send(sendDone(SendOutcome.Lapsed)) },
        "wallet.send_superseded" to { Send(sendDone(SendOutcome.Superseded)) },
        "wallet.send_duplicate" to { Send(sendDone(SendOutcome.DuplicateWallet)) },
        "wallet.send_unsent" to { Send(sendDone(SendOutcome.Unsent)) },
        "wallet.send_discard" to { Send(sendDone(SendOutcome.AlreadySigned).copy(discardAsk = true)) },
        "wallet.address_type" to { AddressTypeDialog(AddressTypes.TAPROOT, onPick = {}, onDismiss = {}) },
        "wallet.delete_confirm" to { Detail(detail.copy(delete = DeleteStep.CONFIRM)) },
        "wallet.delete_password" to { Detail(detail.copy(delete = DeleteStep.PASSWORD, password = "secret")) },
        "wallet.delete_wrong_password" to { Detail(detail.copy(delete = DeleteStep.PASSWORD, deleteError = FailureKind.BAD_PASSWORD)) },
        "wallet.receive" to { Receive(receive) },
        "wallet.receive_amount" to { Receive(receive.copy(amountText = "0.00123")) },
        "wallet.receive_not_offered" to { Receive(ReceiveUiState(loading = false, network = Network.TESTNET, problem = ReceiveProblem.NOT_OFFERED)) },
        "wallet.receive_caps_pending" to { Receive(ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.CAPS_PENDING)) },
        "wallet.receive_mismatch" to { Receive(ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.MISMATCH)) },
        "wallet.detail_change_network" to { Detail(noLookup.copy(network = Network.TESTNET, lookup = LookupResult.NetworkNotOffered, askNetwork = true)) },
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

    private fun Review.rebuilt(capRefusal: CapText? = this.capRefusal, limit: LimitLine? = this.limit, toFee: Long = toFeeSats, cap: Boolean = hasCap) =
        Review(address, payeeSats, changeSats, feeSats, vsize, totalOutSats, toFee, sendAll, leftBehind, cap, capRefusal, limit)

    private fun Review.limited(l: LimitLine) = rebuilt(limit = l)

    private fun Review.dusty(sats: Long) = rebuilt(toFee = sats)

    private fun Review.noCaps() = rebuilt(cap = false)

    private fun Review.refused(c: CapText) = rebuilt(capRefusal = c)
}
