package com.vettid.feature.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.ShellChrome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Connections' search behind the top bar's icon (owner request 2026-10-08): hidden, opens focused, filters, closes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionsSearchTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val ada = ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Ada", lastName = "Lovelace")
    private val bo = ConnectionInfo("c2", "Bobby", ConnectionState.ACTIVE, firstName = "Bo", lastName = "Diddley")

    @Test
    fun theSearchIsHiddenThenOpensFiltersAndCloses() {
        var query by mutableStateOf("")
        rule.setContent {
            val shown = listOf(ada, bo).filter { it.matches(query) }
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = shown, query = query, searchable = true),
                chrome,
                ConnectionsActions(onQuery = { query = it }),
            )
        }
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search connections").performClick()
        rule.onNodeWithTag("top_bar_search_field").assertIsFocused()
        rule.onNodeWithTag("top_bar_search_field").performTextInput("lovel")
        assertEquals("lovel", query)
        rule.onNodeWithTag("connection_c1").assertExists()
        rule.onNodeWithTag("connection_c2").assertDoesNotExist()
        rule.onNodeWithTag("top_bar_search_close").performClick()
        assertEquals("", query)
        rule.onNodeWithTag("top_bar_search_field").assertDoesNotExist()
        rule.onNodeWithTag("connection_c2").assertExists()
    }

    @Test
    fun aQueryKeepsTheSearchShownAndNoMatchSaysSo() {
        rule.setContent {
            ConnectionsScreen(ConnectionsUiState(loading = false, query = "zz", searchable = true), chrome, ConnectionsActions())
        }
        rule.onNodeWithTag("top_bar_search_field").assertExists()
        rule.onNodeWithTag("connections_no_match").assertExists()
    }

    @Test
    fun theViewModelFiltersByTitleAndDisplayName() = runTest {
        val social = FakeSocial().apply { seed(listOf(ada, bo)) }
        val vm = ConnectionsViewModel(social, social)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.connections.size)
        vm.setQuery("BOBBY")
        advanceUntilIdle()
        assertEquals(listOf("c2"), vm.uiState.value.connections.map { it.id })
        assertEquals(true, vm.uiState.value.searchable)
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.connections.size)
    }
}
