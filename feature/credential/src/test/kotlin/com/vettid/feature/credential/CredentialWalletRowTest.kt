package com.vettid.feature.credential

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.wallet.WalletAccess
import com.vettid.core.data.wallet.WalletLabel
import com.vettid.core.testing.FakeVault
import com.vettid.core.ui.components.ShellChrome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ANDROID-PLAN §4 "The wallet's item elsewhere": the Credential screen lists each wallet ("Bitcoin wallet: <name>"); a tap opens the
 * wallets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CredentialWalletRowTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private val status = CredentialStatus(
        exists = true, version = 2, keyFingerprint = "ab12", updatedAt = null, alarm = null, backup = true,
        unlockTtlSeconds = 300, criticalItems = 2,
    )

    @Test
    fun eachWalletIsARowThatOpensTheWallets() {
        var opened = 0
        val wallets = listOf(WalletLabel("w1", "Savings"), WalletLabel("w2", "Spending"))
        compose.setContent {
            CredentialContent(
                CredentialUiState(loading = false, status = status, wallets = wallets),
                ShellChrome("Sam", {}, {}),
                CredentialActions(openWallets = { opened++ }),
            )
        }
        compose.onNodeWithText("Bitcoin wallet: Savings").assertExists()
        compose.onNodeWithTag("credential_wallet_w2").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withoutTheWalletThereIsNoRow() {
        compose.setContent {
            CredentialContent(CredentialUiState(loading = false, status = status), ShellChrome("Sam", {}, {}), CredentialActions())
        }
        compose.onNodeWithText("Bitcoin wallet:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theViewModelFollowsTheVaultsWallets() = runTest {
        val labels = MutableStateFlow(emptyList<WalletLabel>())
        val access = object : WalletAccess {
            override val shown: StateFlow<Boolean> = MutableStateFlow(true)
            override val wallets: StateFlow<List<WalletLabel>> = labels
        }
        val vm = CredentialViewModel(FakeVault(AppPhase.Unlocked), access)
        advanceUntilIdle()
        assertEquals(emptyList<WalletLabel>(), vm.uiState.value.wallets)
        labels.value = listOf(WalletLabel("w1", "Savings"))
        advanceUntilIdle()
        assertEquals(listOf("w1"), vm.uiState.value.wallets.map { it.id })
    }
}
