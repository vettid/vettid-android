package com.vettid.core.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `FLAG_SECURE` is on the window only while a screen that asks for it is shown (owner decision 2026-10-11: the
 * typed recovery code's screen is the first), and is cleared when the last such screen leaves.
 */
@RunWith(RobolectricTestRunner::class)
class SecureWindowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theWindowIsSecureOnlyWhileTheScreenIsShown() {
        var first by mutableStateOf(false)
        var second by mutableStateOf(false)
        lateinit var activity: Activity
        rule.setContent {
            activity = LocalActivity.current!!
            if (first) SecureWindow()
            if (second) SecureWindow()
        }
        fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        rule.waitForIdle()
        assertFalse("not set by any other screen", secure())

        first = true
        rule.waitForIdle()
        assertTrue(secure())

        second = true
        rule.waitForIdle()
        first = false
        rule.waitForIdle()
        assertTrue("one screen still asks for it", secure())

        second = false
        rule.waitForIdle()
        assertFalse("cleared on leaving", secure())

        first = true
        rule.waitForIdle()
        assertTrue(secure())
    }
}
