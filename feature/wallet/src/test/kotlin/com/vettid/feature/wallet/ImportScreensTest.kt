// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The limits step, Import a wallet, the PIN for a capped phrase, and Receive held back by a pending limit, as the member sees them. */
@RunWith(RobolectricTestRunner::class)
class ImportScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val form = ImportUiState(
        networks = listOf(Network.SIGNET, Network.TESTNET4), network = Network.SIGNET, name = "Old wallet",
        typed = words, status = PhraseInput.analyse(words),
    )

    // The wallet list's floating button

    @Test
    fun theFloatingButtonOffersNewWalletAndImportAWallet() {
        var new = 0
        var import = 0
        rule.setContent { WalletsScreen(WalletsUiState(loading = false), chrome, WalletsActions(onNew = { new++ }, onImport = { import++ })) }
        rule.onNodeWithText("New wallet").assertDoesNotExist()
        rule.onNodeWithTag("wallet_new").performClick()
        rule.onNodeWithText("New wallet").assertIsDisplayed()
        rule.onNodeWithText("Import a wallet").assertIsDisplayed()
        rule.onNodeWithTag("wallet_import_item").performClick()
        assertEquals(1, import)
        assertEquals(0, new)
        rule.onNodeWithTag("wallet_new").performClick()
        rule.onNodeWithTag("wallet_new_item").performClick()
        assertEquals(1, new)
    }

    // The limits step

    @Test
    fun noLimitIsChosenAtFirstAndSetALimitShowsTheTwoFields() {
        var state by mutableStateOf(BackupUiState(BackupStep.LIMITS, name = "Savings", networks = listOf(Network.SIGNET), network = Network.SIGNET))
        rule.setContent {
            BackupScreen(
                state,
                BackupActions(
                    onChoice = { state = state.copy(limits = state.limits.copy(limit = it)) },
                    onPerTx = { state = state.copy(limits = state.limits.copy(perTx = LimitsInput.filter(it, false))) },
                    onPerPeriod = { state = state.copy(limits = state.limits.copy(perPeriod = LimitsInput.filter(it, false))) },
                    onToggleUnit = { state = state.copy(limits = state.limits.copy(btc = !state.limits.btc)) },
                ),
            )
        }
        rule.onNodeWithText("Set a spending limit?").assertIsDisplayed()
        rule.onNodeWithText("Your vault will refuse any payment above it. You can lower it any time; raising it needs your PIN and password.").assertIsDisplayed()
        rule.onNodeWithTag("limits_none").assertIsSelected()
        rule.onNodeWithTag("limit_per_tx").assertDoesNotExist()
        rule.onNodeWithTag("limits_set").performClick()
        rule.onNodeWithTag("limit_per_tx").assertIsDisplayed()
        rule.onNodeWithTag("limit_per_period").assertExists()
        rule.onNodeWithText("Most per payment").assertExists()
        rule.onNodeWithText("Most in any 24 hours").assertExists()
        // Sats, with a thousands separator drawn; a toggle shows BTC. Nothing is suggested.
        rule.onNodeWithTag("limit_per_tx").performTextInput("2000000")
        rule.onNodeWithText("sats").assertExists()
        rule.onNodeWithTag("limit_unit_toggle").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show in BTC").assertExists()
        assertEquals("2000000", state.limits.perTx)
        assertEquals("", state.limits.perPeriod)
    }

    @Test
    fun continueWaitsForLimitsTheVaultWouldTake() {
        var next = 0
        var state by mutableStateOf(BackupUiState(BackupStep.LIMITS, limits = LimitsForm(true, "", "")))
        rule.setContent { BackupScreen(state, BackupActions(onNext = { next++ })) }
        rule.onNodeWithText("Enter at least one limit, or choose No limit.").assertExists()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        state = state.copy(limits = LimitsForm(true, "3000000", "2000000"))
        rule.onNodeWithText("Most per payment can't be more than the most in any 24 hours.").assertExists()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        state = state.copy(limits = LimitsForm(true, "2000000", "2000000"))
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(1, next)
        state = state.copy(limits = LimitsForm(true, "0", ""))
        rule.onNodeWithText("Enter an amount from 1 sat to 2,100,000,000,000,000 sats.").assertExists()
    }

    @Test
    fun inBitcoinTheSatsItComesToAreShown() {
        rule.setContent { BackupScreen(BackupUiState(BackupStep.LIMITS, limits = LimitsForm(true, "0.005", "", btc = true)), BackupActions()) }
        rule.onNodeWithText("BTC").assertExists()
        rule.onNodeWithText("500,000 sats").assertExists()
        rule.onNodeWithText("Show in sats").assertExists()
    }

    @Test
    fun newWalletAsksForTheNameAndNetworkFirstAndThePasswordLast() {
        var state by mutableStateOf(BackupUiState(BackupStep.FORM, name = "Savings", networks = listOf(Network.SIGNET), network = Network.SIGNET))
        var created = 0
        rule.setContent { BackupScreen(state, BackupActions(onCreate = { created++ })) }
        rule.onNodeWithTag("wallet_name").assertIsDisplayed()
        rule.onNodeWithTag("wallet_password").assertDoesNotExist()
        rule.onNodeWithText("Continue").assertIsEnabled()
        state = state.copy(step = BackupStep.CREATE, password = "pw")
        rule.onNodeWithTag("wallet_password").assertIsDisplayed()
        rule.onNodeWithTag("wallet_name").assertDoesNotExist()
        rule.onNodeWithText("Create wallet").performClick()
        assertEquals(1, created)
    }

    // The phrase of a capped wallet

    @Test
    fun aCappedWalletsPhraseAsksForThePinToo() {
        var state by mutableStateOf(BackupUiState(BackupStep.PASSWORD, capped = true, password = "pw"))
        var shown = 0
        rule.setContent { BackupScreen(state, BackupActions(onReveal = { shown++ })) }
        rule.onNodeWithText("To protect your spending limit, enter your PIN too.").assertIsDisplayed()
        rule.onNodeWithTag("wallet_pin").assertExists()
        rule.onNodeWithTag("wallet_password").assertExists()
        // Not before the PIN is typed in full.
        rule.onNodeWithText("Show the recovery phrase").assertIsNotEnabled()
        state = state.copy(pin = "123456")
        rule.onNodeWithText("Show the recovery phrase").assertIsEnabled().performClick()
        assertEquals(1, shown)
        state = state.copy(error = FailureKind.BAD_PIN)
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aWalletWithoutALimitAsksForNoPin() {
        rule.setContent { BackupScreen(BackupUiState(BackupStep.PASSWORD, password = "pw"), BackupActions()) }
        rule.onNodeWithText("To protect your spending limit, enter your PIN too.").assertDoesNotExist()
        rule.onNodeWithTag("wallet_pin").assertDoesNotExist()
        rule.onNodeWithText("Show the recovery phrase").assertIsEnabled()
    }

    @Test
    fun aLimitThatCouldNotBeSetIsSaidAfterBackedUpWithTryAgain() {
        var again = 0
        rule.setContent {
            BackupScreen(BackupUiState(BackupStep.DONE, backedUp = true, capsFailed = true), BackupActions(onRetryCaps = { again++ }))
        }
        rule.onNodeWithText("Backed up").assertIsDisplayed()
        rule.onNodeWithText("Your spending limit isn't set yet. Receiving stays off until it is.").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, again)
    }

    // Import a wallet

    @Test
    fun theImportScreenIsSecureForTheWholeFlow() {
        var step by mutableStateOf(ImportStep.FORM)
        lateinit var activity: Activity
        rule.setContent {
            activity = LocalActivity.current!!
            ImportScreen(form.copy(step = step, importedId = "W", capsFailed = true), ImportActions())
        }
        fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        rule.waitForIdle()
        assertTrue(secure())
        for (s in ImportStep.entries) {
            step = s
            rule.waitForIdle()
            assertTrue("$s", secure())
        }
    }

    @Test
    fun theCatalogCopyIsNotSecure() {
        lateinit var activity: Activity
        rule.setContent {
            activity = LocalActivity.current!!
            ImportScreen(form, ImportActions(), secure = false)
        }
        rule.waitForIdle()
        assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun theFormTakesNameNetworkWordsAndHidesThePassphraseBehindAdvanced() {
        var advanced = 0
        var state by mutableStateOf(form)
        rule.setContent { ImportScreen(state, ImportActions(onAdvanced = { advanced++ })) }
        rule.onNodeWithText("Import a wallet").assertIsDisplayed()
        rule.onNodeWithTag("wallet_name").assertExists()
        rule.onNodeWithTag("wallet_network_signet").assertExists()
        rule.onNodeWithTag("import_words").assertExists()
        rule.onNodeWithText("12 words").assertExists()
        rule.onNodeWithTag("import_passphrase").assertDoesNotExist()
        rule.onNodeWithTag("import_advanced").performScrollTo().performClick()
        assertEquals(1, advanced)
        state = state.copy(advanced = true)
        rule.onNodeWithTag("import_passphrase").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Only if you set one when the wallet was made. Letters, digits and symbols from a standard keyboard only.").assertExists()
        state = state.copy(passphrase = "café")
        rule.onNodeWithText("Use only letters, digits and symbols from a standard keyboard, up to 256 characters.").assertExists()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun anUnknownWordIsNamedAndTheChecksumMessageIsThePlansText() {
        var state by mutableStateOf(form.copy(typed = "legal winnr", status = PhraseInput.analyse("legal winnr")))
        rule.setContent { ImportScreen(state, ImportActions()) }
        rule.onNodeWithText("Word 2 isn't a recovery word.").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        state = form.copy(invalidPhrase = true)
        rule.onNodeWithText("These words aren't a valid recovery phrase. Check each word and the order.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun typingInTheWordsFieldReachesTheViewModel() {
        val typed = mutableListOf<String>()
        rule.setContent { ImportScreen(form.copy(typed = "", status = PhraseStatus()), ImportActions(onWords = { typed += it })) }
        rule.onNodeWithTag("import_words").performTextInput("legal")
        assertEquals(listOf("legal"), typed)
    }

    @Test
    fun theImportPasswordStepAndItsRefusals() {
        var state by mutableStateOf(form.copy(step = ImportStep.PASSWORD, password = "pw"))
        var imported = 0
        rule.setContent { ImportScreen(state, ImportActions(onImport = { imported++ })) }
        rule.onNodeWithText("Enter your VettID password. It protects this wallet: you will need it for every payment.").assertIsDisplayed()
        rule.onNodeWithText("Import wallet").assertIsEnabled().performClick()
        assertEquals(1, imported)
        state = state.copy(problem = ImportProblem.EXISTS)
        rule.onNodeWithText("This wallet is already in your vault.").assertExists()
        state = state.copy(problem = ImportProblem.LIMIT)
        rule.onNodeWithText("Your vault holds 16 wallets, the most it can.").assertExists()
    }

    @Test
    fun anImportedWalletWhoseLimitIsNotSetOffersTryAgainAndOpen() {
        var again = 0
        var open = 0
        rule.setContent {
            ImportScreen(
                form.copy(step = ImportStep.DONE, importedId = "W", capsFailed = true),
                ImportActions(onRetryCaps = { again++ }, onOpen = { open++ }),
            )
        }
        rule.onNodeWithText("Your spending limit isn't set yet. Receiving stays off until it is.").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Open wallet").performClick()
        assertEquals(1, again)
        assertEquals(1, open)
    }

    // Receive held back

    @Test
    fun receiveIsOffNoteAndTryAgainWhileTheLimitIsPending() {
        var retried = 0
        rule.setContent {
            ReceiveScreen(
                ReceiveUiState(loading = false, network = Network.TESTNET4, problem = ReceiveProblem.CAPS_PENDING),
                ReceiveActions(onRetry = { retried++ }),
            )
        }
        rule.onNodeWithText("Receiving is off until your spending limit is set.").assertIsDisplayed()
        rule.onNodeWithTag("wallet_receive_qr").assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun theDetailOffersNoReceiveWhileTheLimitIsPending() {
        var retried = 0
        var received = 0
        var state by mutableStateOf(WalletDetailUiState(loaded = true, name = "Savings", network = Network.TESTNET4, capsPending = true))
        rule.setContent { WalletDetailScreen(state, WalletDetailActions(onRetryCaps = { retried++ }, onReceive = { received++ })) }
        rule.onNodeWithTag("wallet_caps_pending").assertIsDisplayed()
        rule.onNodeWithTag("wallet_receive").assertIsNotEnabled()
        rule.onNodeWithTag("wallet_caps_retry").performClick()
        assertEquals(1, retried)
        state = state.copy(capsPending = false)
        rule.onNodeWithTag("wallet_caps_pending").assertDoesNotExist()
        rule.onNodeWithTag("wallet_receive").assertIsEnabled().performClick()
        assertEquals(1, received)
    }
}
