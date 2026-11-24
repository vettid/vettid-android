package com.vettid.feature.approvals

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vettid.core.ui.components.LocalCheckCovered
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A rotation or a fold change (the activity recreated) is not leaving a result: it is not taken as read (R33). */
@RunWith(RobolectricTestRunner::class)
class ShownOnceRotationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aRecreatedActivityDoesNotFinishTheResult() {
        var finished = 0
        rule.setContent { FinishWhenLeft { finished++ } }
        rule.waitForIdle()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(0, finished)
    }

    @Test
    fun theDailyChecksCoverComingUpFinishesTheResult() {
        var finished = 0
        var covered by mutableStateOf(false)
        rule.setContent { CompositionLocalProvider(LocalCheckCovered provides covered) { FinishWhenLeft { finished++ } } }
        rule.waitForIdle()
        assertEquals(0, finished)
        covered = true
        rule.waitForIdle()
        assertEquals(1, finished)
    }

    /** A result composed under the cover (it arrived while the check was up) was never seen: it waits, then shows. */
    @Test
    fun aResultFirstComposedUnderTheCoverIsNotFinished() {
        var finished = 0
        var covered by mutableStateOf(true)
        rule.setContent { CompositionLocalProvider(LocalCheckCovered provides covered) { FinishWhenLeft { finished++ } } }
        rule.waitForIdle()
        assertEquals(0, finished)
        covered = false
        rule.waitForIdle()
        assertEquals(0, finished)
        // Seen now: the next cover counts as leaving.
        covered = true
        rule.waitForIdle()
        assertEquals(1, finished)
    }
}
