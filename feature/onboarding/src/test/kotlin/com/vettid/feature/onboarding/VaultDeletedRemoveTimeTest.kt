package com.vettid.feature.onboarding

import android.text.format.DateFormat
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.testing.FakeVault
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Date

/**
 * "Your vault was deleted" from the member API alone (owner decision 2026-11-15): before the bound the screen names the
 * time from which "Remove from this phone" is offered, and offers no removal it does not have; from the bound it offers
 * the removal.
 */
@RunWith(RobolectricTestRunner::class)
class VaultDeletedRemoveTimeTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = object : VaultDeletingActions {
        override fun checkAgain() = Unit

        override fun askRemove() = Unit

        override fun dismissRemove() = Unit

        override fun confirmRemove() = Unit
    }

    // 40 s past a whole minute: the text names the next minute, never the one before the removal.
    private val at = 1_794_700_020_000L + 40_000L

    private fun time(): String = DateFormat.getTimeFormat(RuntimeEnvironment.getApplication()).format(Date(roundUpToMinute(at)))

    @Test
    fun theTimeIsRoundedUpToTheMinute() {
        assertEquals(1_794_700_080_000L, roundUpToMinute(at))
        assertEquals(1_794_700_080_000L, roundUpToMinute(1_794_700_080_000L))
        assertEquals(1_794_700_140_000L, roundUpToMinute(1_794_700_080_001L))
    }

    @Test
    fun theRouteShowsTheTimeThroughItsViewModel() {
        val vm = VaultDeletingViewModel(FakeVault(AppPhase.Deleting(apiOnly = true, removeFromMs = at)))
        rule.setContent { VaultDeletingRoute(slow = false, onOpenAccountSite = {}, apiOnly = true, removeFromMs = at, viewModel = vm) }
        rule.onNodeWithText("If your vault doesn't confirm it by ${time()}, you can remove VettID from this phone here.", substring = true)
            .assertExists()
        rule.onNodeWithTag("vault_deleting_remove").assertDoesNotExist()
    }

    @Test
    fun beforeTheBoundTheTextSaysWhenRemovalIsOfferedAndNoRemoveIsShown() {
        rule.setContent { VaultDeletingContent(VaultDeletingUiState(apiOnly = true, removeFromMs = at), actions) {} }
        rule.onNodeWithText("If your vault doesn't confirm it by ${time()}, you can remove VettID from this phone here.", substring = true)
            .assertExists()
        rule.onNodeWithText("or you remove VettID from this phone", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("vault_deleting_remove").assertDoesNotExist()
    }

    @Test
    fun withoutTheTimeItNamesNone() {
        rule.setContent { VaultDeletingContent(VaultDeletingUiState(apiOnly = true), actions) {} }
        rule.onNodeWithText("If your vault doesn't confirm it, you will be able to remove VettID from this phone here.", substring = true)
            .assertExists()
        rule.onNodeWithTag("vault_deleting_remove").assertDoesNotExist()
    }

    @Test
    fun fromTheBoundRemoveIsOfferedAndTheTimeIsNoLongerNamed() {
        rule.setContent { VaultDeletingContent(VaultDeletingUiState(slow = true, apiOnly = true, removeFromMs = at), actions) {} }
        rule.onNodeWithTag("vault_deleting_remove").assertExists()
        rule.onNodeWithText(time(), substring = true).assertDoesNotExist()
    }
}
