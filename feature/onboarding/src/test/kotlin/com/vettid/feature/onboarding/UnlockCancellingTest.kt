package com.vettid.feature.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.ReleaseView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * "Cancel the recovery and unlock" in progress: the screen has no primary button while a recovery is pending, so the
 * cancel (and the owner check that follows it with a phone secret) shows its progress in the recovery card.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockCancellingTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = Proxy.newProxyInstance(UnlockActions::class.java.classLoader, arrayOf(UnlockActions::class.java)) { _, _, _ ->
        null
    } as UnlockActions

    private val release = ReleaseView(3, "aa".repeat(48), "active", null, "")
    private val pending = UnlockUiState(
        loading = false, preflight = PreflightInfo(release, 3, false, false, null), recoveryPending = true, phoneSecret = true,
        pin = "123456",
    )

    @Test
    fun theCancelInProgressShowsItsProgress() {
        rule.mainClock.autoAdvance = false // the indicator's animation never ends
        rule.setContent { UnlockContent(pending.copy(busy = true), actions) }
        rule.onNodeWithTag("unlock_recovery_cancelling").assertExists()
        rule.onNodeWithText("Cancelling the recovery…").assertExists()
        rule.onNodeWithText("Cancel the recovery and unlock").assertIsNotEnabled()
    }

    @Test
    fun nothingInProgressBeforeTheCancel() {
        rule.setContent { UnlockContent(pending.copy(password = "password one"), actions) }
        rule.onNodeWithTag("recovery_pending").assertExists()
        rule.onNodeWithTag("unlock_recovery_cancelling").assertDoesNotExist()
    }
}
