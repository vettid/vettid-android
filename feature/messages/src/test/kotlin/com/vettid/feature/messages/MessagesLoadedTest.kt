package com.vettid.feature.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVault
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

/** A thread not read yet says nothing (never "No messages yet"); a quiet reload failure shows the error with its retry. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MessagesLoadedTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
    private val none = "No messages yet"

    private fun row(loaded: Boolean) = ConversationSummary(FakeSocial.connection("c1", "Sam"), null, 0, loaded)

    @Test
    fun aNotLoadedRowDoesNotSayNoMessages() {
        compose.setContent {
            MessagesScreen(MessagesUiState(loading = false, conversations = listOf(row(false)), noConnections = false), chrome)
        }
        compose.onNodeWithText(none).assertDoesNotExist()
    }

    @Test
    fun aLoadedEmptyThreadSaysNoMessages() {
        compose.setContent {
            MessagesScreen(MessagesUiState(loading = false, conversations = listOf(row(true)), noConnections = false), chrome)
        }
        compose.onNodeWithText(none).assertIsDisplayed()
    }

    @Test
    fun aQuietReloadFailureShowsTheErrorWithRetryOverTheRows() {
        compose.setContent {
            MessagesScreen(
                MessagesUiState(loading = false, conversations = listOf(row(false)), noConnections = false, error = FailureKind.NETWORK),
                chrome,
            )
        }
        compose.onNodeWithText("Could not load messages").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText(none).assertDoesNotExist()
    }

    @Test
    fun theViewModelCarriesTheQuietReloadFailureAndRetryClearsIt() = runTest {
        val social = FakeSocial()
        social.seed(listOf(FakeSocial.connection("c1", "Sam")))
        val vm = MessagesViewModel(social, FakeVault())
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
        social.reloadFailure.value = VaultFailure(FailureKind.NETWORK)
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        social.reloadFailure.value = null
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }
}
