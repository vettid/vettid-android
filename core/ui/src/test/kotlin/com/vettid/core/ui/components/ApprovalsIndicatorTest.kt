package com.vettid.core.ui.components

import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.vettid.core.ui.theme.DarkColors
import com.vettid.core.ui.theme.LightColors
import com.vettid.core.ui.theme.VettIdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

/** The approvals indicator (ANDROID-PLAN §4 "The approvals indicator" 2, 4, 6, 11). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp") // a phone's width: the pill shows from 360 dp
class ApprovalsIndicatorTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(indicator: ApprovalsIndicator?, detailActions: Boolean? = null, fontScale: Float? = null) = rule.setContent {
        VettIdTheme {
            val config = LocalConfiguration.current
            val scaled = fontScale?.let { f -> Configuration(config).apply { this.fontScale = f } } ?: config
            CompositionLocalProvider(LocalApprovalsIndicator provides indicator, LocalConfiguration provides scaled) {
                when (detailActions) {
                    null -> VettIdTopAppBar("Messages", {}, "Al", {}, bell = NotificationBell(0, false) {})
                    false -> VettIdBackTopBar(onBackClick = {})
                    true -> VettIdBackTopBar(onBackClick = {}) { IconButton({}) { Icon(Icons.Outlined.Edit, "Edit") } }
                }
            }
        }
    }

    @Test
    fun hiddenWithoutAnIndicator() {
        show(null)
        rule.onAllNodesWithTag("top_bar_approvals").assertCountEquals(0)
        rule.onAllNodesWithTag("approvals_pill").assertCountEquals(0)
        rule.onNodeWithTag("top_bar_bell").assertExists()
    }

    @Test
    fun theCountAndATapThatOpensTheList() {
        var taps = 0
        show(ApprovalsIndicator(count = 3) { taps++ })
        rule.onNodeWithTag("approvals_badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("top_bar_approvals")
            .assertContentDescriptionEquals("Approvals, 3 waiting")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aboveNinetyNine() {
        show(ApprovalsIndicator(count = 140) {})
        rule.onNodeWithText("99+", useUnmergedTree = true).assertExists()
    }

    @Test
    fun alsoOnADetailScreen() {
        show(ApprovalsIndicator(count = 1) {}, detailActions = false)
        rule.onNodeWithTag("top_bar_approvals").assertContentDescriptionEquals("Approvals, 1 waiting")
    }

    @Test
    fun theUrgentPillCountsDownToItsEnd() {
        val deadline = Instant.now().plusMillis(2_500)
        show(ApprovalsIndicator(count = 2, critical = true, urgent = true, deadline = deadline, label = "Shop") {})
        rule.onNodeWithTag("approvals_pill_urgent").assertExists()
        rule.onNodeWithText("0:03", useUnmergedTree = true).assertExists()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("0:00", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("approvals_pill_urgent").assertContentDescriptionEquals("Approvals, 2 waiting")
    }

    /** The pill is the one control: a 48 dp target whose tap opens the list. */
    @Test
    fun thePillIsA48dpTargetThatOpensTheList() {
        var taps = 0
        show(ApprovalsIndicator(count = 1, critical = true, urgent = true, deadline = Instant.now().plusSeconds(60)) { taps++ })
        rule.onNodeWithTag("approvals_pill_urgent").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aSignInIsNeutralAndDescribed() {
        val deadline = Instant.now().plusSeconds(80).plusMillis(500)
        show(ApprovalsIndicator(count = 1, critical = true, urgent = false, deadline = deadline, label = "Bank") {})
        rule.onNodeWithTag("approvals_pill").assertExists()
        rule.onAllNodesWithTag("approvals_pill_urgent").assertCountEquals(0)
        rule.onNodeWithText("1:21", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("approvals_pill").assertContentDescriptionEquals(
            "Approvals, 1 waiting. Bank expires in ${spokenDuration(81, Locale.US)}",
        )
    }

    @Test
    fun noTickWhileTheScreenIsBehind() {
        val behind = object : LifecycleOwner {
            override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
        }
        val indicator = ApprovalsIndicator(count = 1, critical = true, urgent = true, deadline = Instant.now().plusSeconds(60)) {}
        rule.setContent {
            VettIdTheme {
                CompositionLocalProvider(LocalApprovalsIndicator provides indicator, LocalLifecycleOwner provides behind) {
                    VettIdTopAppBar("Messages", {}, "Al", {}, bell = NotificationBell(0, false) {})
                }
            }
        }
        rule.onNodeWithTag("approvals_pill_urgent").assertExists()
        rule.onAllNodesWithTag("approvals_pill_time", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun noTickUnderTheDailyCheck() {
        val indicator = ApprovalsIndicator(count = 1, critical = true, urgent = true, deadline = Instant.now().plusSeconds(60)) {}
        rule.setContent {
            VettIdTheme {
                CompositionLocalProvider(LocalApprovalsIndicator provides indicator, LocalCheckCovered provides true) {
                    VettIdTopAppBar("Messages", {}, "Al", {}, bell = NotificationBell(0, false) {})
                }
            }
        }
        rule.onAllNodesWithTag("approvals_pill_time", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun noDeadlineKnownIsTheClockAlone() {
        show(ApprovalsIndicator(count = 1, critical = true, urgent = true) {})
        rule.onNodeWithTag("approvals_pill_urgent").assertExists()
        rule.onAllNodesWithTag("approvals_pill_time", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aDetailScreenWithActionsKeepsTheIconAndAClockBadge() {
        val indicator = ApprovalsIndicator(count = 1, critical = true, urgent = true, deadline = Instant.now().plusSeconds(60)) {}
        show(indicator, detailActions = true)
        rule.onAllNodesWithTag("approvals_pill_urgent").assertCountEquals(0)
        rule.onNodeWithTag("approvals_clock_badge_urgent", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("top_bar_approvals").assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun aNarrowScreenKeepsTheIconAndAClockBadge() {
        show(ApprovalsIndicator(count = 1, critical = true, urgent = false, deadline = Instant.now().plusSeconds(60)) {})
        rule.onAllNodesWithTag("approvals_pill").assertCountEquals(0)
        rule.onNodeWithTag("approvals_clock_badge", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun theIconCountsNothingDownUnlessTalkBackExplores() {
        val deadline = Instant.now().plusSeconds(80).plusMillis(500)
        show(ApprovalsIndicator(count = 1, critical = true, urgent = false, deadline = deadline, label = "Bank") {})
        // No pill on screen and no touch exploration: no tick, so no time in the description.
        rule.onNodeWithTag("top_bar_approvals").assertContentDescriptionEquals("Approvals, 1 waiting")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun withTalkBackTheIconSaysTheTimeLeft() {
        val deadline = Instant.now().plusSeconds(80).plusMillis(500)
        show(ApprovalsIndicator(count = 1, critical = true, urgent = false, deadline = deadline, label = "Bank") {})
        rule.onNodeWithTag("top_bar_approvals").assertContentDescriptionEquals("Approvals, 1 waiting")
        // TalkBack turned on while the icon is on screen: the listener starts the tick.
        Shadows.shadowOf(accessibility()).setTouchExplorationEnabled(true)
        rule.waitForIdle()
        rule.onNodeWithTag("top_bar_approvals").assertContentDescriptionEquals(
            "Approvals, 1 waiting. Bank expires in ${spokenDuration(81, Locale.US)}",
        )
    }

    private fun accessibility(): AccessibilityManager =
        ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(AccessibilityManager::class.java)

    @Test
    fun aLargeFontScaleKeepsTheIconAndAClockBadge() {
        val deadline = Instant.now().plusSeconds(60)
        show(ApprovalsIndicator(count = 1, critical = true, urgent = true, deadline = deadline, label = "Shop") {}, fontScale = 1.5f)
        rule.onAllNodesWithTag("approvals_pill_urgent").assertCountEquals(0)
        rule.onNodeWithTag("approvals_clock_badge_urgent", useUnmergedTree = true).assertExists()
    }

    @Test
    fun justBelowTheLargeFontScaleIsThePill() {
        show(ApprovalsIndicator(count = 1, critical = true, urgent = true, deadline = Instant.now().plusSeconds(60)) {}, fontScale = 1.3f)
        rule.onNodeWithTag("approvals_pill_urgent").assertExists()
    }

    @Test
    fun onlyAnUrgentEntryIsInTheUrgencyColours() {
        for (scheme in listOf(LightColors, DarkColors)) {
            val red = setOf(scheme.error, scheme.errorContainer, scheme.onError, scheme.onErrorContainer)
            assertEquals(scheme.surfaceVariant to scheme.onSurfaceVariant, timeColors(urgent = false, scheme))
            assertFalse(timeColors(urgent = false, scheme).first in red)
            assertEquals(scheme.errorContainer to scheme.onErrorContainer, timeColors(urgent = true, scheme))
        }
    }

    @Test
    fun spokenDurations() {
        assertEquals("45 seconds", spokenDuration(45, Locale.US))
        assertEquals("0 seconds", spokenDuration(0, Locale.US))
        assertEquals("1 minute, 20 seconds", spokenDuration(80, Locale.US))
        assertEquals("2 minutes", spokenDuration(120, Locale.US))
    }

    @Test
    fun secondsRoundUpAndStopAtZero() {
        assertEquals(81, secondsUntil(Instant.ofEpochMilli(80_400), nowMs = 0))
        assertEquals(80, secondsUntil(Instant.ofEpochMilli(80_000), nowMs = 0))
        assertEquals(0, secondsUntil(Instant.ofEpochMilli(0), nowMs = 5_000))
    }
}
