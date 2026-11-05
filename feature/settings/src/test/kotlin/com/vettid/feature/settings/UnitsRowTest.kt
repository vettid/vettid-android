package com.vettid.feature.settings

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.DistanceUnits
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings → App → Units (owner decision 2026-11-05): Automatic, Miles or Kilometers, applied at once with nothing to confirm. */
@RunWith(RobolectricTestRunner::class)
class UnitsRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val picked = mutableListOf<DistanceUnits>()

    private fun show(units: DistanceUnits) = rule.setContent {
        SettingsContent(
            SettingsUiState(preferences = AppPreferences(distanceUnits = units)),
            SettingsActions(setDistanceUnits = { picked += it }),
        )
    }

    @Test
    fun theRowSaysTheChoiceAndOffersTheThree() {
        show(DistanceUnits.AUTOMATIC)
        rule.onNodeWithTag("units").performScrollTo().assertTextContains("Units")
        rule.onNodeWithTag("units").assertTextContains("Automatic")
        rule.onNodeWithTag("units").performClick()
        rule.onNodeWithText("Miles").assertExists()
        rule.onNodeWithText("Kilometers").assertExists()
    }

    @Test
    fun aPickIsAppliedAtOnceAndClosesTheDialog() {
        show(DistanceUnits.KILOMETERS)
        rule.onNodeWithTag("units").performScrollTo().assertTextContains("Kilometers")
        rule.onNodeWithTag("units").performClick()
        rule.onNodeWithText("Miles").performClick()
        assertEquals(listOf(DistanceUnits.MILES), picked)
        rule.onNodeWithText("Miles").assertDoesNotExist()
    }
}
