package com.vettid.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private fun show(kind: FailureKind, clearFailed: Boolean = false, longUnreachable: Boolean = false) = rule.setContent {
        UnreachableScreen(
            kind,
            busy = false,
            onRetry = { asked += "retry" },
            onStartOver = { asked += if (it == WayOut.ERASE_OR_RECOVER) "erase" else "startOver" },
            clearFailed = clearFailed,
            longUnreachable = longUnreachable,
            onRecover = { asked += "recover" },
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
            assertEquals(k.name, k == FailureKind.ERASE_FAILED, WayOut.of(k, longUnreachable = true) == WayOut.CLEAR_DATA)
        }
    }

    @Test
    fun eraseOrRecoverIsOfferedForAPhoneNotAcceptedAndAVaultUnreachableForLongOnly() {
        val own = mapOf(
            FailureKind.NO_VAULT to WayOut.START_OVER,
            FailureKind.LOCAL_STATE to WayOut.ERASE,
            FailureKind.ERASE_FAILED to WayOut.CLEAR_DATA,
            FailureKind.UNAUTHORIZED to WayOut.ERASE_OR_RECOVER,
        )
        for (k in FailureKind.entries) {
            assertEquals(k.name, own[k], WayOut.of(k))
            val long = own[k] ?: if (k == FailureKind.CLOCK) null else WayOut.ERASE_OR_RECOVER
            assertEquals("${k.name} long", long, WayOut.of(k, longUnreachable = true))
        }
    }

    @Test
    fun aPhoneTheAccountServiceNoLongerAcceptsOffersEraseAfterAConfirmationAndRecovery() {
        show(FailureKind.UNAUTHORIZED)
        // Recovery first: the primary action; the erase is the second one.
        rule.onNodeWithText("check on your account site that your vault can be recovered", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("primary_button").performClick()
        assertEquals(listOf("recover"), asked)
        rule.onNodeWithText("Erase VettID from this phone").performClick()
        assertEquals(listOf("recover"), asked)
        rule.onNodeWithText("Erase VettID from this phone?").assertIsDisplayed()
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(listOf("recover", "erase"), asked)
        rule.onNodeWithTag("long_unreachable").assertDoesNotExist()
    }

    @Test
    fun aVaultUnreachableForLongOffersEraseOrRecoverBesideTryAgain() {
        show(FailureKind.NETWORK, longUnreachable = true)
        rule.onNodeWithTag("long_unreachable").assertIsDisplayed()
        rule.onNodeWithTag("primary_button").performClick()
        assertEquals(listOf("recover"), asked)
        rule.onNodeWithTag("retry_button").performClick()
        assertEquals(listOf("recover", "retry"), asked)
        rule.onNodeWithText("Erase VettID from this phone").assertIsDisplayed()
    }

    @Test
    fun aShortOutageOrAWrongClockOffersNoEraseOrRecover() {
        show(FailureKind.NETWORK)
        rule.onNodeWithTag("retry_button").assertDoesNotExist()
        rule.onNodeWithText("Recover your vault").assertDoesNotExist()
        rule.onNodeWithText("Erase VettID from this phone").assertDoesNotExist()
    }

    @Test
    fun aWrongClockForLongStillOffersNoEraseOrRecover() {
        show(FailureKind.CLOCK, longUnreachable = true)
        rule.onNodeWithTag("retry_button").assertDoesNotExist()
        rule.onNodeWithText("Recover your vault").assertDoesNotExist()
        rule.onNodeWithText("Erase VettID from this phone").assertDoesNotExist()
    }

    @Test
    fun anOpenConfirmationClosesWhenTheFailureChanges() {
        var kind by mutableStateOf(FailureKind.NO_VAULT)
        rule.setContent { UnreachableScreen(kind, busy = false, onRetry = {}, onStartOver = { asked += "startOver" }) }
        rule.onNodeWithText("Start over on this phone").performClick()
        rule.onNodeWithText("Start over on this phone?").assertIsDisplayed()
        kind = FailureKind.LOCAL_STATE
        rule.waitForIdle()
        rule.onNodeWithText("Start over on this phone?").assertDoesNotExist()
        rule.onNodeWithText("Erase VettID from this phone?").assertDoesNotExist()
        assertEquals(emptyList<String>(), asked)
    }
}
