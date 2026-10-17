package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen for a phase that could not be read (A6 audit N4/N5, N6, P4): the cause in its title, "Try again", and
 * for the two dead ends a "Start over" that never runs without the confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UnreachableScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val asked = mutableListOf<String>()

    private fun show(kind: FailureKind, clearFailed: Boolean = false) = rule.setContent {
        UnreachableScreen(
            kind,
            busy = false,
            onRetry = { asked += "retry" },
            onStartOver = { asked += "startOver" },
            clearFailed = clearFailed,
        )
    }

    @Test
    fun aConnectionFailureOffersOnlyTryAgain() {
        show(FailureKind.NETWORK)
        rule.onNodeWithText("VettID cannot connect").assertIsDisplayed()
        rule.onNodeWithText("Start over on this phone").assertDoesNotExist()
        rule.onNodeWithText("Erase VettID from this phone").assertDoesNotExist()
        rule.onNodeWithTag("primary_button").performClick()
        assertEquals(listOf("retry"), asked)
    }

    @Test
    fun aWrongClockSaysSoAndOffersNoErase() {
        show(FailureKind.CLOCK)
        rule.onNodeWithText("Check this phone's date and time").assertIsDisplayed()
        rule.onNodeWithText("Turn on automatic date and time", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Start over on this phone").assertDoesNotExist()
        rule.onNodeWithText("Erase VettID from this phone").assertDoesNotExist()
    }

    @Test
    fun noVaultIsNeverActedOnWithoutTheMembersConfirmation() {
        show(FailureKind.NO_VAULT)
        rule.onNodeWithText("Your account has no vault").assertIsDisplayed()
        rule.onNodeWithText("do not start over", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Start over on this phone").performClick()
        assertEquals(emptyList<String>(), asked)
        rule.onNodeWithText("Start over on this phone?").assertIsDisplayed()
        rule.onNodeWithText("Only do this if you deleted your vault or closed your account.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), asked)
        rule.onNodeWithText("Start over on this phone").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(listOf("startOver"), asked)
    }

    @Test
    fun unreadableKeysAreADeadEndWithTheWayBackSpelledOut() {
        show(FailureKind.LOCAL_STATE)
        rule.onNodeWithText("VettID cannot read its keys on this phone").assertIsDisplayed()
        rule.onNodeWithText("Nothing was deleted or changed.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Erase VettID from this phone").performClick()
        assertEquals(emptyList<String>(), asked)
        rule.onNodeWithText("Erase VettID from this phone?").assertIsDisplayed()
        rule.onNodeWithText("you then need a recovery from the account site", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(listOf("startOver"), asked)
    }

    @Test
    fun aWipeThatDidNotFinishOffersTryAgainAndErasingTheDataAfterAConfirmation() {
        show(FailureKind.ERASE_FAILED)
        rule.onNodeWithText("VettID could not erase this phone").assertIsDisplayed()
        rule.onNodeWithText("Your vault itself is not affected", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("primary_button").performClick()
        assertEquals(listOf("retry"), asked)
        rule.onNodeWithText("Erase VettID's data on this phone").performClick()
        assertEquals(listOf("retry"), asked)
        rule.onNodeWithText("Erase VettID's data?").assertIsDisplayed()
        rule.onNodeWithText("This removes everything VettID keeps on this phone and closes VettID.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("retry"), asked)
        rule.onNodeWithText("Erase VettID's data on this phone").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(listOf("retry", "startOver"), asked)
        rule.onNodeWithTag("clear_data_failed").assertDoesNotExist()
    }

    @Test
    fun aRefusedClearSaysHowToDoItInSettings() {
        show(FailureKind.ERASE_FAILED, clearFailed = true)
        rule.onNodeWithText("Clear VettID's storage in Android Settings", substring = true).assertIsDisplayed()
    }

    @Test
    fun noOtherFailureOffersErasingTheData() {
        for (k in FailureKind.entries) {
            assertEquals(k.name, k == FailureKind.ERASE_FAILED, WayOut.of(k) == WayOut.CLEAR_DATA)
        }
    }
}
