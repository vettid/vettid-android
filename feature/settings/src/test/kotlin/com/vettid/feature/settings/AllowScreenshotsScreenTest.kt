package com.vettid.feature.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings → App → "Allow screenshots" (owner decision 2026-10-11, A6 audit S1): a switch that is off, asks before it
 * turns on and says plainly what that does, and says that it is on while it is.
 */
@RunWith(RobolectricTestRunner::class)
class AllowScreenshotsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val asked = mutableListOf<Boolean>()

    private fun show(state: SettingsUiState) = rule.setContent {
        SettingsContent(state, SettingsActions(setScreenshotsAllowed = { asked += it }))
    }

    @Test
    fun turningItOnAsksFirstAndSaysWhatItDoes() {
        show(SettingsUiState())
        rule.onNodeWithTag("allow_screenshots").performScrollTo().assertIsOff()
        rule.onNodeWithTag("allow_screenshots").assertTextContains("Allow screenshots")
        rule.onNodeWithTag("allow_screenshots")
            .assertTextContains("Off. Screenshots, screen recording and the app preview in Recents are blocked.")

        rule.onNodeWithTag("allow_screenshots").performClick()
        // Nothing is on yet: the dialog says what it does.
        assertEquals(emptyList<Boolean>(), asked)
        rule.onNodeWithText("Allow screenshots?").assertExists()
        rule.onNodeWithText("including messages and vault items", substring = true).assertExists()
        rule.onNodeWithText("turns itself off when the vault locks, and after 1 hour", substring = true).assertExists()
        rule.onNodeWithText("typing a recovery code stays protected", substring = true).assertExists()

        // Cancel leaves it off.
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<Boolean>(), asked)
        rule.onNodeWithTag("allow_screenshots_dialog").assertDoesNotExist()

        rule.onNodeWithTag("allow_screenshots").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        assertEquals(listOf(true), asked)
    }

    @Test
    fun whileOnTheRowSaysSoAndTurningItOffNeedsNoConfirmation() {
        show(SettingsUiState(screenshotsAllowed = true))
        rule.onNodeWithTag("allow_screenshots").performScrollTo().assertIsOn()
        rule.onNodeWithTag("allow_screenshots")
            .assertTextContains("On. Turns itself off when the vault locks, and after 1 hour.")
        rule.onNodeWithTag("allow_screenshots").performClick()
        assertEquals(listOf(false), asked)
        rule.onNodeWithTag("allow_screenshots_dialog").assertDoesNotExist()
    }

    @Test
    fun aDevelopmentBuildSaysThatItStaysOn() {
        show(SettingsUiState(screenshotsAllowed = true, screenshotsStayOn = true))
        rule.onNodeWithTag("allow_screenshots").performScrollTo()
            .assertTextContains("On. A development build: it stays on until you turn it off.")
    }
}
