package com.vettid.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.TimeScaleView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Settings → Security's daily check under the vault's time scale (STAGING-TIME-SCALE §3 rule 3, ANDROID-PLAN 0.1.44): the
 * setting in its real units and as it runs ("Every 24 hours (staging: 1 hour)"), the staging note in a staging build,
 * and nothing of either at real time.
 */
@RunWith(RobolectricTestRunner::class)
class StagingTimeScaleSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val view = OwnerCheckView(OwnerCheckState.OK, Instant.now().plusSeconds(1_800), 86_400, 0, hold = true, holdOffUntil = null)

    private fun show(t: TimeScaleView) = rule.setContent {
        Column { OwnerCheckSettingsRows(OwnerCheckSettingsUiState(view = view, timeScale = t), OwnerCheckSettingsActions()) }
    }

    @Test
    fun aStagingVaultShowsBothUnitsAndTheNote() {
        show(TimeScaleView(scale = 24, known = true, stagingBuild = true))
        rule.onNodeWithTag("owner_check_interval").assertTextContains("Every 24 hours (staging: 1 hour)", substring = true)
        rule.onNodeWithTag("staging_time_note").assertExists()
        rule.onNodeWithText("Staging: 1 day = 1 hour").assertExists()
    }

    @Test
    fun realTimeShowsTheSettingAsBefore() {
        show(TimeScaleView(scale = 1, known = true, stagingBuild = false))
        rule.onNodeWithTag("owner_check_interval").assertTextContains("Every 24 hours", substring = true)
        rule.onNodeWithText("staging", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("staging_time_note").assertDoesNotExist()
    }
}
