package com.vettid.core.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The top bar's search (owner request 2026-10-08): hidden behind its icon, opens focused, stays open while it holds a
 * query, ✕ and back clear and close it, and the back gesture closes it before it leaves the screen.
 */
@RunWith(RobolectricTestRunner::class)
class TopBarSearchTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var query by mutableStateOf("")

    private fun screen() = rule.setContent {
        val search = rememberTopBarSearch(query, { query = it }, "Search your vault", "Search by name or tag")
        TopLevelScaffold("Vault", ShellChrome("Me", {}, {}), search = search) { Text("list") }
    }

    @Test
    fun hiddenByDefaultThenOpenAndFocused() {
        screen()
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search your vault").performClick()
        rule.onNodeWithTag("top_bar_search_field").assertIsFocused()
    }

    @Test
    fun closingClearsAndHides() {
        screen()
        rule.onNodeWithContentDescription("Search your vault").performClick()
        rule.onNodeWithTag("top_bar_search_field").performTextInput("pass")
        assertEquals("pass", query)
        rule.onNodeWithContentDescription("Clear and close search").performClick()
        assertEquals("", query)
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search your vault").assertExists()
    }

    @Test
    fun aQueryKeepsItOpen() {
        query = "pass"
        screen()
        rule.onNodeWithTag("top_bar_search_field").assertExists()
        rule.onNodeWithContentDescription("Close search").performClick()
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
    }

    @Test
    fun backClosesTheSearchFirst() {
        screen()
        rule.onNodeWithContentDescription("Search your vault").performClick()
        rule.onNodeWithTag("top_bar_search_field").performTextInput("x")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals("", query)
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        assertEquals(false, rule.activity.isFinishing)
    }
}
