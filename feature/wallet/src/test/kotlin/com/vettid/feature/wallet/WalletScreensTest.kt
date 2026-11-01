// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** The list and the backup steps as the member sees them, and FLAG_SECURE on the phrase and the check. */
@RunWith(RobolectricTestRunner::class)
class WalletScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private fun words() = PhraseWords(List(24) { "w$it".toCharArray() })

    @Test
    fun thePhraseAndTheCheckAreSecureAndTheFormIsNot() {
        var step by mutableStateOf(BackupStep.FORM)
        lateinit var activity: Activity
        lateinit var view: View
        rule.setContent {
            activity = LocalActivity.current!!
            view = LocalView.current
            BackupScreen(BackupUiState(step, words = words(), asked = listOf(3, 10, 19), candidates = listOf(3, 1, 2, 4)), BackupActions())
        }
        // FLAG_SECURE, and out of content capture and autofill (the view's flags; the activity's capture switch needs a device).
        fun secure(): Boolean {
            val flag = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            val hidden = view.importantForContentCapture == View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS &&
                (view.parent as View).importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            assertEquals(flag, hidden)
            return flag
        }
        rule.waitForIdle()
        assertFalse(secure())
        step = BackupStep.PHRASE
        rule.waitForIdle()
        assertTrue("phrase", secure())
        rule.onNodeWithTag("phrase_word_24").assertExists()
        step = BackupStep.CHECK
        rule.waitForIdle()
        assertTrue("check", secure())
        rule.onNodeWithText("Which is word 4?").assertIsDisplayed()
        step = BackupStep.DONE
        rule.waitForIdle()
        assertFalse("done", secure())
    }

    @Test
    fun aWrongWordEndsTheCheckWithOnlyTheWordsAgain() {
        var again = 0
        rule.setContent {
            BackupScreen(
                BackupUiState(BackupStep.CHECK, words = words(), asked = listOf(10, 12, 20), wrong = 10),
                BackupActions(onShowAgain = { again++ }),
            )
        }
        rule.onNodeWithText("That's not word 11. Look at your paper again.").assertIsDisplayed()
        rule.onNodeWithTag("check_typed").assertDoesNotExist()
        rule.onNodeWithTag("check_candidate_10").assertDoesNotExist()
        rule.onNodeWithTag("check_show_again").performClick()
        assertEquals(1, again)
    }

    @Test
    fun aPassphraseIsShownUnderTheWords() {
        rule.setContent {
            BackupScreen(BackupUiState(BackupStep.PHRASE, words = PhraseWords(List(12) { "w$it".toCharArray() }, "Correct Horse".toCharArray())), BackupActions())
        }
        rule.onNodeWithTag("phrase_passphrase").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Correct Horse").assertExists()
    }

    @Test
    fun theTestBannerShowsBeforeTheFirstWallet() {
        rule.setContent { WalletsScreen(WalletsUiState(loading = false, testBuild = true), chrome, WalletsActions()) }
        rule.onNodeWithText("Test network: these coins have no value.").assertIsDisplayed()
        rule.onNodeWithText("No wallets yet").assertIsDisplayed()
    }

    @Test
    fun anEmptyListSaysNoWalletsYet() {
        rule.setContent { WalletsScreen(WalletsUiState(loading = false), chrome, WalletsActions()) }
        rule.onNodeWithText("No wallets yet").assertIsDisplayed()
        rule.onNodeWithText("Keep bitcoin in your vault. Payments need your VettID password.").assertIsDisplayed()
    }

    @Test
    fun aWalletShowsItsChainTheBannerAndNotBackedUp() {
        val rows = listOf(WalletRow("W1", "Savings", Network.TESTNET4, "testnet4", backedUp = false), WalletRow("W2", "Spending", Network.SIGNET, "signet", backedUp = true))
        rule.setContent { WalletsScreen(WalletsUiState(loading = false, rows = rows), chrome, WalletsActions()) }
        rule.onNodeWithText("Test network: these coins have no value.").assertIsDisplayed()
        rule.onNodeWithText("Testnet4").assertIsDisplayed()
        rule.onNodeWithText("Signet").assertIsDisplayed()
        rule.onNodeWithTag("wallet_not_backed_up_W1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("wallet_not_backed_up_W2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aFailedReadOffersTryAgain() {
        var retried = 0
        rule.setContent { WalletsScreen(WalletsUiState(loading = false, failed = FailureKind.OTHER), chrome, WalletsActions(onRetry = { retried++ })) }
        rule.onNodeWithText("Your wallets couldn't be read.").assertIsDisplayed()
        rule.onNodeWithTag("wallet_retry").performClick()
        assertEquals(1, retried)
        rule.onNodeWithTag("wallet_new").assertDoesNotExist()
    }
}
