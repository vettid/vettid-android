package com.vettid.core.ui.components

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A6 audit A4 and A1: after a wrong PIN the field shows "not correct" AND the wait that follows it (the error used to
 * replace the countdown, so the disabled field looked frozen), and the error is announced; the countdown is not a
 * live region, so it is not read out every second.
 */
@RunWith(RobolectricTestRunner::class)
class SecretFieldMessagesTest {
    @get:Rule
    val rule = createComposeRule()

    private fun liveRegion(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    @Test
    fun theErrorAndTheWaitAreBothShownAndOnlyTheErrorIsAnnounced() {
        rule.setContent {
            SecretField(
                "", {}, "Vault PIN",
                isPin = true, error = "That PIN is not correct.", supporting = "Try again in 0:30", enabled = false,
            )
        }
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true).assertTextEquals("That PIN is not correct.")
        rule.onNodeWithTag("secret_field_supporting", useUnmergedTree = true).assertTextEquals("Try again in 0:30")
        assertEquals(LiveRegionMode.Polite, liveRegion("secret_field_error"))
        assertNull(liveRegion("secret_field_supporting"))
    }

    @Test
    fun eachLineAloneIsShownAsBefore() {
        rule.setContent {
            SecretField("", {}, "Vault PIN", isPin = true, error = "That PIN is not correct.")
        }
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true).assertTextEquals("That PIN is not correct.")
        rule.onNodeWithTag("secret_field_supporting", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theWaitAloneIsShownWithoutAnError() {
        rule.setContent {
            SecretField("", {}, "Vault PIN", isPin = true, supporting = "Try again in 0:30")
        }
        rule.onNodeWithTag("secret_field_supporting", useUnmergedTree = true).assertTextEquals("Try again in 0:30")
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true).assertDoesNotExist()
    }
}
