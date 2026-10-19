package com.vettid.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.TimeScaleView
import com.vettid.core.data.vault.messageRes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The check screen's texts under the vault's time scale (STAGING-TIME-SCALE §5.2, ANDROID-PLAN 0.1.44): the interval as
 * it runs, computed from seconds ("1 hour" at 24 for the 24-hour setting), and the staging note in a staging build.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerCheckTimeScaleTest {
    @get:Rule
    val rule = createComposeRule()

    private val view = OwnerCheckView(OwnerCheckState.OK, Instant.now().plusSeconds(1_800), 86_400, 0, hold = true, holdOffUntil = null)

    private val actions = object : OwnerCheckActions {
        override fun setPin(v: String) = Unit
        override fun setPassword(v: String) = Unit
        override fun setHoldOff(choice: HoldOffChoice) = Unit
        override fun submit() = Unit
        override fun lockVault() = Unit
    }

    private fun show(t: TimeScaleView) = rule.setContent {
        OwnerCheckContent(OwnerCheckUiState(mode = OwnerCheckMode.VOLUNTARY, view = view, timeScale = t), actions, onCancel = {})
    }

    @Test
    fun atTwentyFourTheNextCheckIsAnHourAway() {
        show(TimeScaleView(scale = 24, known = true, stagingBuild = true))
        rule.onNodeWithText("Your next check is then due 1 hour from now.", substring = true).assertExists()
        rule.onNodeWithTag("staging_time_note").assertExists()
    }

    /** The lost-keys text names the recovery's waits from seconds: "24 hours to 3 days", "1 hour to 3 hours" at 24. */
    @Test
    fun theLostKeysTextComputesTheRecoveryWaits() {
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.vettid.core.ui.format.LocalTimeScale provides com.vettid.core.ui.format.UiTimeScale(24, stagingNote = true),
            ) {
                androidx.compose.material3.Text(
                    com.vettid.core.ui.format.failureText(com.vettid.core.data.vault.FailureKind.DEVICE_SECRET.messageRes()) +
                        com.vettid.core.ui.format.stagingNoteSuffix(),
                )
            }
        }
        rule.onNodeWithText("It takes 1 hour to 3 hours, and you need", substring = true).assertExists()
        rule.onNodeWithText("Staging: 1 day = 1 hour", substring = true).assertExists()
    }

    @Test
    fun atRealTimeItIsTwentyFourHours() {
        show(TimeScaleView())
        rule.onNodeWithText("Your next check is then due 24 hours from now.", substring = true).assertExists()
        rule.onNodeWithTag("staging_time_note").assertDoesNotExist()
    }
}
