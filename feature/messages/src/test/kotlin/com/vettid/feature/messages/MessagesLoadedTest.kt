package com.vettid.feature.messages

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVault
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.ShellChrome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

    private fun check(state: OwnerCheckState) = OwnerCheckView(state, null, 86_400, 0, hold = true, holdOffUntil = null)

    private fun TestScope.model(loaded: Boolean = false, vault: FakeVault = FakeVault()): Pair<FakeSocial, MessagesViewModel> {
        val social = FakeSocial()
        if (!loaded) social.notLoaded += "c1"
        social.seed(listOf(FakeSocial.connection("c1", "Sam")))
        val vm = MessagesViewModel(social, vault)
        advanceUntilIdle()
        return social to vm
    }

    @Test
    fun theViewModelCarriesTheQuietReloadFailureUntilAGoodReloadClearsIt() = runTest {
        val (social, vm) = model()
        assertEquals(null, vm.uiState.value.error)
        social.reloadFailure.value = VaultFailure(FailureKind.NETWORK)
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        social.reloadFailure.value = null
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun anErrorOfTheScreensOwnRefreshIsClearedByALaterGoodQuietReload() = runTest {
        val (social, _) = model().also { it.first.fail["refreshConversations"] = VaultFailure(FailureKind.NETWORK) }
        val vm = MessagesViewModel(social, FakeVault())
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        // The quiet reload (the manager's own call) succeeds.
        social.reloadFailure.value = null
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun retryReloadsAndClearsTheError() = runTest {
        val (social, vm) = model()
        social.fail["refreshConversations"] = VaultFailure(FailureKind.NETWORK)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun aFullyLoadedListNeverShowsALoadError() = runTest {
        val (social, vm) = model(loaded = true)
        social.reloadFailure.value = VaultFailure(FailureKind.NETWORK)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun aHeldReloadFailureIsHeldNotTheErrorCard() = runTest {
        val (social, vm) = model()
        social.reloadFailure.value = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MessagesScreen(state, chrome)
        }
        compose.onNodeWithText("Messages are on hold").assertIsDisplayed()
        compose.onNodeWithText("Could not load messages").assertDoesNotExist()
    }

    @Test
    fun theCheckPassingReadsAgainWhenTheHeldStateCameFromTheReload() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.HELD) }
        val (social, _) = model(vault = vault)
        social.reloadFailure.value = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        advanceUntilIdle()
        val before = social.calls.count { it == "refreshConversations" }
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertEquals(before + 1, social.calls.count { it == "refreshConversations" })
    }

    @Test
    fun aHeldReloadFailureOverFullyLoadedRowsShowsTheHeldNoticeAndReadsAgain() = runTest {
        val vault = FakeVault().apply { ownerCheck.value = check(OwnerCheckState.HELD) }
        val (social, vm) = model(loaded = true, vault = vault)
        social.reloadFailure.value = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED)
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MessagesScreen(state, chrome)
        }
        compose.onNodeWithText("Messages are on hold").assertIsDisplayed()
        val before = social.calls.count { it == "refreshConversations" }
        vault.ownerCheck.value = check(OwnerCheckState.OK)
        advanceUntilIdle()
        assertEquals(before + 1, social.calls.count { it == "refreshConversations" })
    }
}
