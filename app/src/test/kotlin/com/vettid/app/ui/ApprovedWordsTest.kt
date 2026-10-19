package com.vettid.app.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.SCALED_FAILURE_TEXTS
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.UiTimeScale
import com.vettid.core.ui.format.failureText
import com.vettid.feature.onboarding.presenceBody
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * (Owner decision 2026-10-19) The texts that say a scaled wait in approved words keep those words, verbatim, in real time
 * (production, and every vault at scale 1); only a vault with a time scale shows the `_scaled` text, with the durations
 * computed: the presence notice ("three days"), the early warning without a deadline ("within the hour") and the
 * lost-keys text ("one to three days").
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApprovedWordsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(scale: Long, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalTimeScale provides UiTimeScale(scale, stagingNote = scale > 1, scaledTexts = SCALED_FAILURE_TEXTS)) {
            content()
        }
    }

    @Test
    fun thePresenceNoticeSaysThreeDaysInRealTime() {
        show(1) { Text(presenceBody()) }
        rule.onNodeWithText(
            "If this stays for three days, your vault is no longer protected against a recovery started by someone else. " +
                "Try again later. If it does not go away, contact VettID.",
        ).assertExists()
    }

    @Test
    fun thePresenceNoticeComputesTheQuietPeriodOnAScaledVault() {
        show(24) { Text(presenceBody()) }
        rule.onNodeWithText("If this stays for 3 hours, your vault is no longer protected", substring = true).assertExists()
    }

    @Test
    fun theLostKeysTextSaysOneToThreeDaysInRealTime() {
        show(1) { Text(failureText(FailureKind.DEVICE_SECRET.messageRes())) }
        rule.onNodeWithText(
            "This phone no longer holds the keys of your vault. That happens when the app's data was cleared or the phone was " +
                "set up again from a backup. Nothing was disclosed. Whether you can get back into the vault depends on one of " +
                "its settings. If its credential backup was on (the usual case), you can recover it: start a recovery on the " +
                "account site. It takes one to three days, and you need your PIN and your password. If you had turned " +
                "credential backup off, this vault cannot be brought back; you can delete it on the account site and start a " +
                "new one. If you still have a phone on which VettID works, use 'Move to a new phone' there instead. The " +
                "account site tells you which of these applies.",
        ).assertExists()
    }

    @Test
    fun theLostKeysTextComputesTheRecoveryWaitsOnAScaledVault() {
        show(24) { Text(failureText(FailureKind.DEVICE_SECRET.messageRes())) }
        rule.onNodeWithText("It takes 1 hour to 3 hours, and you need", substring = true).assertExists()
    }

    /** Never reached from [OwnerCheckGateState.of] (the warning needs a deadline); kept, with the approved words. */
    private fun soonGate() = OwnerCheckGateState(warning = true, warningSeconds = OwnerCheckView.warningFor(1).seconds, deadline = null)

    @Test
    fun theWarningWithoutADeadlineSaysWithinTheHourInRealTime() {
        show(1) { OwnerCheckBanners(soonGate(), first = true, onCheckNow = {}, onHoldOn = {}, onDismissNotices = {}) }
        rule.onNodeWithText("Your daily check is due within the hour.", substring = true).assertExists()
    }

    @Test
    fun theWarningWithoutADeadlineComputesItsSpanOnAScaledVault() {
        val gate = soonGate().copy(warningSeconds = OwnerCheckView.warningFor(24).seconds)
        show(24) { OwnerCheckBanners(gate, first = true, onCheckNow = {}, onHoldOn = {}, onDismissNotices = {}) }
        rule.onNodeWithText("Your daily check is due within 150 seconds.", substring = true).assertExists()
    }

    /** The early warning is only raised with a deadline, so its banner always names the time: the soon text is unreachable. */
    @Test
    fun theWarningIsOnlyRaisedWithADeadline() {
        val now = Instant.parse("2026-10-19T12:00:00Z")
        val noDeadline = OwnerCheckView(OwnerCheckState.OK, null, 86_400, 0, hold = true, holdOffUntil = null)
        assertTrue(!OwnerCheckGateState.of(noDeadline, emptyList(), now).warning)
        val soon = noDeadline.copy(deadline = now.plusSeconds(600))
        val g = OwnerCheckGateState.of(soon, emptyList(), now)
        assertTrue(g.warning && g.deadline != null)
        show(1) { OwnerCheckBanners(g, first = true, onCheckNow = {}, onHoldOn = {}, onDismissNotices = {}) }
        rule.onNodeWithTag("owner_check_warning").assertExists()
        rule.onNodeWithText("within the hour", substring = true).assertDoesNotExist()
    }
}
