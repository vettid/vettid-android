package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * R33: on a Pixel Fold's outer screen (1080 x 2364 at 390 dpi, about 443 x 970 dp) the waiting card and the lock lines put the
 * PIN and password below the fold, under a disabled Check. The fields are scrolled into view when they appear.
 */
@RunWith(RobolectricTestRunner::class)
// Shorter than the Fold's outer screen, so the fields are below the fold here whatever Robolectric's fonts measure.
@Config(qualifiers = "w443dp-h560dp-xxhdpi")
class OwnerCheckFieldsInViewTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = object : OwnerCheckActions {
        override fun setPin(v: String) = Unit
        override fun setPassword(v: String) = Unit
        override fun setHoldOff(choice: HoldOffChoice) = Unit
        override fun submit() = Unit
        override fun lockVault() = Unit
    }

    @Test
    fun theFieldsAreScrolledIntoViewUnderALongWaitingCard() {
        val now = Instant.now()
        val view = OwnerCheckView(
            OwnerCheckState.HELD, now.minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null,
            waiting = WaitingCounts(messages = 12, requests = 3, calls = 2, other = 5),
            idleLockAt = now.plusSeconds(7_200),
        )
        rule.setContent { OwnerCheckContent(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view), actions, onCancel = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag("owner_check_pin").assertIsDisplayed()
        rule.onNodeWithTag("owner_check_password").assertIsDisplayed()
    }

    /** Holding off: the warning and its choices stay where the member reads them first; the fields are not scrolled to. */
    @Test
    fun holdingOffKeepsTheWarningInView() {
        val now = Instant.now()
        val view = OwnerCheckView(
            OwnerCheckState.HELD, now.minusSeconds(60), 86_400, 0, hold = true, holdOffUntil = null,
            waiting = WaitingCounts(messages = 12, requests = 3, calls = 2, other = 5),
            idleLockAt = now.plusSeconds(7_200),
        )
        rule.setContent { OwnerCheckContent(OwnerCheckUiState(mode = OwnerCheckMode.HOLD_OFF, view = view), actions, onCancel = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag("hold_off_warning").assertIsDisplayed()
    }

    /** A check whose answer was lost: the line saying it may have counted, under the fields, is brought into view. */
    @Test
    fun aMaybeCountedLineIsBroughtIntoView() {
        val now = Instant.now()
        val view = OwnerCheckView(
            OwnerCheckState.HELD, now.minusSeconds(60), 86_400, 1, hold = true, holdOffUntil = null,
            waiting = WaitingCounts(messages = 12, requests = 3, calls = 2, other = 5),
            idleLockAt = now.plusSeconds(7_200),
        )
        val state = OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view, message = OwnerCheckMessage.MaybeCounted(2))
        rule.setContent { OwnerCheckContent(state, actions, onCancel = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag("owner_check_maybe_counted").assertIsDisplayed()
    }

    /** With checks already failed, the "checks left" line under the fields is the group's last: it is brought into view too. */
    @Test
    fun theChecksLeftLineIsBroughtIntoViewWithTheFields() {
        val now = Instant.now()
        val view = OwnerCheckView(
            OwnerCheckState.HELD, now.minusSeconds(60), 86_400, 3, hold = true, holdOffUntil = null,
            waiting = WaitingCounts(messages = 12, requests = 3, calls = 2, other = 5),
            idleLockAt = now.plusSeconds(7_200),
        )
        rule.setContent { OwnerCheckContent(OwnerCheckUiState(mode = OwnerCheckMode.GATED, view = view), actions, onCancel = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag("owner_check_left").assertIsDisplayed()
        rule.onNodeWithTag("owner_check_password").assertIsDisplayed()
    }
}
