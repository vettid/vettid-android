package com.vettid.feature.approvals

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
}
