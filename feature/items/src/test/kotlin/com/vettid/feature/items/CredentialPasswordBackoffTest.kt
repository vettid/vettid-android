package com.vettid.feature.items

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** A6 audit A28: the wait counts down in the supporting line; the live error says it once, without the count. */
@RunWith(RobolectricTestRunner::class)
class CredentialPasswordBackoffTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theLiveErrorHasNoCountdownAndTheSupportingLineCounts() {
        // The clock is held: the countdown loop does not run, so the test never waits.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CredentialPasswordContent(
                PasswordPrompt(PasswordPurpose.OPEN, error = FailureKind.BACKOFF, retryUntil = Instant.now().plusSeconds(60)),
                "Bank PIN", onPassword = {}, onSubmit = {}, onCancel = {},
            )
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("secret_field_error", useUnmergedTree = true)
            .assertTextEquals("Too many wrong passwords. Wait a moment and try again")
        rule.onNodeWithTag("secret_field_supporting", useUnmergedTree = true).assertTextContains("Try again in", substring = true)
        val live = { tag: String ->
            rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        }
        assertEquals(LiveRegionMode.Polite, live("secret_field_error"))
        assertNull(live("secret_field_supporting"))
    }
}
