package com.vettid.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lasting notice of a refused relay key (VAULT-MESSAGING 0.84.0 §3.4; owner decision a, 2026-11-05; the texts are
 * NEW, approved by the owner 2026-11-06, GC thread 01M6RZBVG54W7FXPVHKHF2GVGE, with vettid-ios #221): its three texts,
 * and an action that opens the existing key replacement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RelayRefusedNoticeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theNoticeShowsItsThreeTexts() {
        rule.setContent { RelayRefusedNotice(first = true, onReplaceKeys = {}) }
        rule.onNodeWithText("Your vault couldn't move to its new address", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Your vault may not receive messages until its keys are replaced.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Replace your vault's keys").assertIsDisplayed()
    }

    @Test
    fun theActionOpensTheKeyReplacement() {
        var opened = 0
        rule.setContent { RelayRefusedNotice(first = false, onReplaceKeys = { opened++ }) }
        rule.onNodeWithText("Replace your vault's keys").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theNoticeCannotBeDismissed() {
        rule.setContent { RelayRefusedNotice(first = true, onReplaceKeys = {}) }
        rule.onNodeWithTag("banner_dismiss").assertDoesNotExist()
    }
}
