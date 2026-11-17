package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.R as DataR
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rule 3 (the release gate): what the open vault shows, and the gate while the release is checked: the check's screen,
 * its failure with "Try again" (a read at once), the ways to leave the vault, and the clone alarm above it. Every text is
 * an existing one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReleaseRequiredGateTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theGateWhileBehindTheCheckWhileReadAndTheShellOtherwise() {
        assertEquals(OpenAppScreen.RELEASE_GATE, OpenAppScreen.of(required = true, behind = true))
        assertEquals(OpenAppScreen.RELEASE_CHECK, OpenAppScreen.of(required = true, behind = false))
        assertEquals(OpenAppScreen.SHELL, OpenAppScreen.of(required = false, behind = false))
        // Fail closed: never the shell while the vault is behind, whatever else is read.
        assertEquals(OpenAppScreen.RELEASE_GATE, OpenAppScreen.of(required = false, behind = true))
    }

    @Test
    fun aFailedCheckSaysWhyInTheManifestsWords() {
        assertEquals(DataR.string.data_failure_manifest, checkFailureText(FailureKind.MANIFEST))
        assertEquals(DataR.string.data_failure_manifest_unavailable, checkFailureText(FailureKind.MANIFEST_UNAVAILABLE))
        assertEquals(DataR.string.data_failure_manifest_unavailable, checkFailureText(FailureKind.NETWORK))
    }

    private fun show(failure: FailureKind?, alarm: CredentialAlarm? = null, onTryAgain: () -> Unit = {}, onAccount: () -> Unit = {}) =
        rule.setContent {
            ReleaseRequiredGate(
                screen = OpenAppScreen.RELEASE_CHECK,
                failure = failure,
                onTryAgain = onTryAgain,
                onAccount = onAccount,
                alarm = alarm,
                relayRefused = false,
                deletionBanner = { false },
            )
        }

    @Test
    fun whileCheckedOnlyTheCheckAndTheWaysOut() {
        var account = 0
        show(failure = null, onAccount = { account++ })
        rule.onNodeWithTag("release_checking").assertExists()
        rule.onNodeWithContentDescription("Checking your vault's software").assertExists()
        rule.onNodeWithTag("release_check_failed").assertDoesNotExist()
        rule.onNodeWithTag("release_required_help").assertExists()
        rule.onNodeWithTag("release_required_delete").assertExists()
        rule.onNodeWithTag("release_required_account").performClick()
        assertEquals(1, account)
    }

    @Test
    fun aFailedCheckShowsWhyAndTryAgainReadsAtOnce() {
        var tries = 0
        show(failure = FailureKind.NETWORK, onTryAgain = { tries++ })
        rule.onNodeWithTag("release_check_failed").assertExists()
        rule.onNodeWithTag("release_check_retry").assertExists().performClick()
        assertEquals(1, tries)
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun theCloneAlarmStaysAboveTheGate() {
        show(failure = null, alarm = CredentialAlarm("a1", CredentialAlarm.STATE_FROZEN, null, null))
        rule.onNodeWithTag("alarm_banner").assertExists()
    }

    @Test
    fun helpOpensUnderTheGate() {
        show(failure = null)
        rule.onNodeWithTag("release_required_help").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("release_checking").assertDoesNotExist()
        rule.onNodeWithTag("release_required_help").assertDoesNotExist()
    }
}
