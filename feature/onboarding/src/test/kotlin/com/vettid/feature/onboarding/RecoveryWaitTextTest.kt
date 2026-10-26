package com.vettid.feature.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.ui.format.LocalTimeScale
import com.vettid.core.ui.format.UiTimeScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * (Owner decision 2026-10-19; STAGING-TIME-SCALE §5.2) The second attempt's warning: in real time the text as approved,
 * word for word; on a vault with a time scale the wait as a staging vault runs it, with the staging note.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryWaitTextTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(t: UiTimeScale) = rule.setContent {
        CompositionLocalProvider(LocalTimeScale provides t) { Text(openWarningText(OpenWarning.SECOND, used = 1)) }
    }

    @Test
    fun inRealTimeTheSecondAttemptsWarningIsAsApproved() {
        show(UiTimeScale())
        rule.onNodeWithText(
            "This is your second attempt. If it is wrong too, you can try again in 24 hours, and the wait gets longer after " +
                "every further wrong attempt.",
        ).assertExists()
    }

    @Test
    fun onAScaledVaultTheWaitIsTheStagingOne() {
        show(UiTimeScale(24, stagingNote = true))
        rule.onNodeWithText("If it is wrong too, you can try again in 10 minutes, and the wait gets longer", substring = true)
            .assertExists()
        rule.onNodeWithText("Staging: 1 day = 1 hour", substring = true).assertExists()
        rule.onNodeWithText("24 hours", substring = true).assertDoesNotExist()
    }

    /** A staging build on a vault at scale 1 (staging may run in real time): the approved text, without the note. */
    @Test
    fun aStagingBuildAtScaleOneKeepsTheApprovedText() {
        show(UiTimeScale(1, stagingNote = false))
        rule.onNodeWithText("you can try again in 24 hours", substring = true).assertExists()
    }
}
